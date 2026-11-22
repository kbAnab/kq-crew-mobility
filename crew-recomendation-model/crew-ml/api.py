import json
import logging
import hashlib
from datetime import date, datetime, timezone
from typing import List, Optional, Literal
from zoneinfo import ZoneInfo

from fastapi import Depends, FastAPI, Header, HTTPException
from firebase_admin import auth, messaging
from pydantic import BaseModel, Field
from google.cloud import firestore
from google.cloud.firestore_v1.base_query import FieldFilter

from services.config import QUALIFICATIONS, RULES
from recommender import CrewRecommender
from services.crew_service import get_candidates, get_flight
from services.eligibility_service import candidates_to_frame, flight_type_of
from services.config import eligible_mask
from services.firebase_service import get_db

import pandas as pd

log = logging.getLogger("crew-api")

app = FastAPI(
    title="AI Crew Task Allocation API",
    description="Firebase-connected crew recommendation service",
    version="2.1.0",
)

recommender = CrewRecommender()

ALLOWED_ROLES = {"CREW_CONTROLLER", "MANAGER", "ADMIN"}


# ---------------------------------------
# REQUEST MODELS
# ---------------------------------------

class CrewMember(BaseModel):
    crew_id: str
    qualification: str
    crew_type: str
    experience_years: int = Field(ge=0)
    flight_hours: int = Field(ge=0)
    duty_hours: float = Field(ge=0)
    rest_hours: float = Field(ge=0)
    availability: int = Field(ge=0, le=1)


class RecommendationRequest(BaseModel):
    flight_id: Optional[str] = None
    flight_type: str
    required_qualification: str
    top_n: Optional[int] = Field(default=None, ge=1)
    crew: List[CrewMember] = Field(min_length=1)


class FlightRecommendationRequest(BaseModel):
    flight_id: str
    top_n: Optional[int] = Field(default=None, ge=1)


class AssignmentRequest(BaseModel):
    flight_id: str = Field(min_length=1)
    crew_id: str = Field(min_length=1)
    source: str = Field(default="AI recommender", max_length=80)


class AssignmentDecision(BaseModel):
    action: Literal["approve", "reject"]


class InstallationRequest(BaseModel):
    installation_id: str = Field(min_length=10, max_length=256)


# ---------------------------------------
# AUTHENTICATION AND AUTHORIZATION
# ---------------------------------------

def verify_active_user(authorization: str = Header(...)) -> str:
    if not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Missing Firebase authentication token")

    token = authorization.split(" ", 1)[1]
    try:
        uid = auth.verify_id_token(token)["uid"]
    except Exception:
        raise HTTPException(status_code=401, detail="Invalid or expired Firebase token")

    try:
        profile_doc = get_db().collection("users").document(uid).get()
    except Exception:
        log.exception("Could not read the user profile")
        raise HTTPException(status_code=503, detail="User directory unavailable")

    if not profile_doc.exists:
        raise HTTPException(status_code=403, detail="User profile not found")
    profile = profile_doc.to_dict() or {}
    if profile.get("active") is not True:
        raise HTTPException(status_code=403, detail="Account is inactive")
    return uid


def verify_controller(authorization: str = Header(...)) -> str:
    uid = verify_active_user(authorization)
    try:
        profile = get_db().collection("users").document(uid).get().to_dict() or {}
    except Exception:
        log.exception("Could not authorize the user profile")
        raise HTTPException(status_code=503, detail="User directory unavailable")
    if profile.get("role") not in ALLOWED_ROLES:
        raise HTTPException(status_code=403, detail="Access denied")
    return uid


def verify_scheduler(authorization: str = Header(...)) -> str:
    uid = verify_controller(authorization)
    try:
        profile = get_db().collection("users").document(uid).get().to_dict() or {}
    except Exception:
        log.exception("Could not verify scheduler role")
        raise HTTPException(status_code=503, detail="User directory unavailable")
    if profile.get("role") not in {"CREW_CONTROLLER", "ADMIN"}:
        raise HTTPException(status_code=403, detail="Scheduler approval permission required")
    return uid


def verify_manager(authorization: str = Header(...)) -> str:
    uid = verify_controller(authorization)
    try:
        profile = get_db().collection("users").document(uid).get().to_dict() or {}
    except Exception:
        log.exception("Could not verify manager role")
        raise HTTPException(status_code=503, detail="User directory unavailable")
    if profile.get("role") not in {"MANAGER", "ADMIN"}:
        raise HTTPException(status_code=403, detail="Management access required")
    return uid



@app.get("/")
def home():
    return {"message": "AI Crew Task Allocation API is running", "status": "online"}


@app.get("/health")
def health():
    firebase_connected = True
    try:
        get_db().collection("flights").limit(1).get()
    except Exception:
        firebase_connected = False
    return {"status": "healthy", "model_loaded": True, "firebase_connected": firebase_connected}



def _to_records(results: pd.DataFrame) -> list:
    return json.loads(results.to_json(orient="records"))


def _policy_for_flight(db, flight: dict, transaction=None) -> dict:
    flight_type = flight_type_of(flight)
    defaults = RULES[flight_type]
    reference = db.collection("settings").document("policies")
    snapshot = reference.get(transaction=transaction) if transaction else reference.get()
    policies = (snapshot.to_dict() or {}).get("policies", {}) if snapshot.exists else {}
    key = "international" if flight_type == "International" else "domestic"
    stored = policies.get(key, {}) if isinstance(policies, dict) else {}
    values = {}
    for field, default in defaults.items():
        value = stored.get({
            "max_duty_hours": "maxDutyHours",
            "min_rest_hours": "minRestHours",
            "min_experience_years": "minExperienceYears",
        }[field]) if isinstance(stored, dict) else None
        if not isinstance(value, (int, float)) or value < 0:
            value = default
        # Admin-configured policies can tighten the model's baseline, never loosen it.
        if field == "max_duty_hours":
            values[field] = min(value, default)
        elif field in {"min_rest_hours", "min_experience_years"}:
            values[field] = max(value, default)
    return values


@app.post("/recommend")
def recommend_crew(request: RecommendationRequest, uid: str = Depends(verify_controller)):
    """Rank crew supplied in the request."""
    try:
        crew_data = pd.DataFrame([member.model_dump() for member in request.crew])
        results = recommender.recommend(
            crew_data=crew_data,
            flight_type=request.flight_type,
            required_qualification=request.required_qualification,
            top_n=request.top_n,
            policy_override=_policy_for_flight(get_db(), {"international": request.flight_type == "International"}),
        )
        recommendations = _to_records(results)
        return {
            "success": True,
            "flight_id": request.flight_id,
            "total_crew_submitted": len(request.crew),
            "total_recommended": len(recommendations),
            "recommendations": recommendations,
        }
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception:
        log.exception("Recommendation failed")
        raise HTTPException(status_code=500, detail="Recommendation failed")


@app.post("/recommend/flight")
def recommend_for_flight(request: FlightRecommendationRequest, uid: str = Depends(verify_controller)):
    try:
        db = get_db()
        flight = get_flight(db, request.flight_id)
        if flight is None:
            raise HTTPException(status_code=404, detail="Flight not found")

        qualification = flight.get("qualification")
        if qualification not in QUALIFICATIONS:
            raise HTTPException(status_code=422, detail="Flight has no valid qualification requirement")

        docs = get_candidates(db, qualification)
        recommendations = []
        if docs:
            frame = candidates_to_frame(docs)
            results = recommender.recommend(
                crew_data=frame,
                flight_type=flight_type_of(flight),
                required_qualification=qualification,
                top_n=request.top_n,
                policy_override=_policy_for_flight(db, flight),
            )
            names = dict(zip(frame["crew_id"], frame["name"]))
            recommendations = _to_records(results)
            for record in recommendations:
                record["name"] = names.get(record["crew_id"], record["crew_id"])

        return {
            "success": True,
            "flight_id": request.flight_id,
            "total_crew_considered": len(docs),
            "total_recommended": len(recommendations),
            "recommendations": recommendations,
        }
    except HTTPException:
        raise
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception:
        log.exception("Recommendation failed")
        raise HTTPException(status_code=500, detail="Recommendation failed")


def _flight_payload(doc) -> dict:
    flight = doc.to_dict() or {}
    departure = flight.get("departure")
    if isinstance(departure, datetime):
        departure = departure.isoformat()
    return {
        "id": doc.id,
        "flightNo": flight.get("flightNo", doc.id),
        "route": flight.get("route", ""),
        "departure": departure.isoformat() if hasattr(departure, "isoformat") else str(departure or ""),
        "qualification": flight.get("qualification", ""),
        "slots": int(flight.get("slots", 0)),
        "filled": int(flight.get("filled", 0)),
        "international": bool(flight.get("international", False)),
    }


def _assert_eligible(crew_id: str, crew: dict, flight: dict, db, transaction=None) -> None:
    qualification = flight.get("qualification")
    if qualification not in QUALIFICATIONS:
        raise HTTPException(status_code=422, detail="Flight has no valid qualification requirement")
    if crew.get("active", True) is False:
        raise HTTPException(status_code=409, detail="Crew member is inactive")
    frame = candidates_to_frame([dict(crew, crew_id=crew_id)])
    mask = eligible_mask(frame, flight_type_of(flight), qualification, _policy_for_flight(db, flight, transaction))
    if frame.empty or not bool(mask.iloc[0]):
        raise HTTPException(status_code=409, detail="Crew member fails availability, qualification, experience, duty, or rest requirements")


@app.get("/flights/open")
def list_open_flights(uid: str = Depends(verify_controller)):
    try:
        docs = get_db().collection("flights").stream()
        flights = [_flight_payload(doc) for doc in docs]
        return {"flights": [flight for flight in flights if flight["slots"] > flight["filled"]]}
    except Exception:
        log.exception("Could not load flights")
        raise HTTPException(status_code=503, detail="Flight schedule unavailable")


@app.get("/flights")
def list_flights(uid: str = Depends(verify_controller)):
    try:
        docs = get_db().collection("flights").stream()
        flights = [_flight_payload(doc) for doc in docs]
        return {"flights": flights}
    except Exception:
        log.exception("Could not load flights")
        raise HTTPException(status_code=503, detail="Flight schedule unavailable")


@app.get("/flights/by-number/{flight_no}/crew")
def list_flight_crew(flight_no: str, departure_day: date, uid: str = Depends(verify_active_user)):
    db = get_db()
    try:
        user_profile = db.collection("users").document(uid).get().to_dict() or {}
        role = user_profile.get("role")
        if role == "FLIGHT_CREW":
            duties = db.collection("users").document(uid).collection("duties").stream()
            assigned_to_user = any(
                str((duty.to_dict() or {}).get("flightInfo", "")).split("/", 1)[0].strip().upper() == flight_no.upper()
                and _eat_date((duty.to_dict() or {}).get("start")) == departure_day
                for duty in duties
            )
            if not assigned_to_user:
                raise HTTPException(status_code=403, detail="You are not assigned to this flight")
        elif role not in ALLOWED_ROLES:
            raise HTTPException(status_code=403, detail="Access denied")

        flight_docs = db.collection("flights").where(
            filter=FieldFilter("flightNo", "==", flight_no)
        ).stream()
        flight_ids = {
            doc.id for doc in flight_docs
            if _eat_date((doc.to_dict() or {}).get("departure")) == departure_day
        }
        crew_rows = []
        for assignment in db.collection("assignments").where(
            filter=FieldFilter("status", "==", "approved")
        ).stream():
            data = assignment.to_dict() or {}
            if data.get("flightId") in flight_ids:
                crew_rows.append({
                    "crew_id": str(data.get("crewId", "")),
                    "name": str(data.get("crewName", data.get("crewId", ""))),
                    "qualification": str((get_flight(db, str(data.get("flightId", ""))) or {}).get("qualification", "")),
                })
        return {"flight_no": flight_no, "crew": crew_rows}
    except HTTPException:
        raise
    except Exception:
        log.exception("Could not load assigned flight crew")
        raise HTTPException(status_code=503, detail="Flight crew information unavailable")


def _eat_date(value) -> date | None:
    if not isinstance(value, datetime):
        return None
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return value.astimezone(ZoneInfo("Africa/Nairobi")).date()


@app.get("/assignments/pending")
def list_pending_assignments(uid: str = Depends(verify_controller)):
    try:
        db = get_db()
        pending = db.collection("assignments").where(
            filter=FieldFilter("status", "==", "pending")
        ).stream()
        output = []
        for doc in pending:
            data = doc.to_dict() or {}
            flight = get_flight(db, str(data.get("flightId", ""))) or {}
            output.append({
                "id": doc.id,
                "crewId": str(data.get("crewId", "")),
                "crewName": str(data.get("crewName", data.get("crewId", ""))),
                "flightId": str(data.get("flightId", "")),
                "flightNo": str(flight.get("flightNo", data.get("flightId", ""))),
                "route": str(flight.get("route", "")),
                "qualification": str(flight.get("qualification", "")),
                "source": str(data.get("source", "")),
            })
        return {"assignments": output}
    except Exception:
        log.exception("Could not load pending assignments")
        raise HTTPException(status_code=503, detail="Pending assignments unavailable")


@app.post("/assignments")
def request_assignment(request: AssignmentRequest, uid: str = Depends(verify_scheduler)):
    db = get_db()
    flight_ref = db.collection("flights").document(request.flight_id)
    crew_ref = db.collection("crew").document(request.crew_id)
    stable_id = hashlib.sha256(f"{request.flight_id}:{request.crew_id}".encode()).hexdigest()
    assignment_ref = db.collection("assignments").document(stable_id)
    transaction = db.transaction()

    @firestore.transactional
    def write_request(tx):
        flight_snap = flight_ref.get(transaction=tx)
        crew_snap = crew_ref.get(transaction=tx)
        assignment_snap = assignment_ref.get(transaction=tx)
        if not flight_snap.exists:
            raise HTTPException(status_code=404, detail="Flight not found")
        if not crew_snap.exists:
            raise HTTPException(status_code=404, detail="Crew member not found")
        if assignment_snap.exists:
            raise HTTPException(status_code=409, detail="An assignment request already exists for this crew and flight")
        pending_query = db.collection("assignments").where(
            filter=FieldFilter("status", "==", "pending")
        )
        has_other_pending = any(
            (pending.to_dict() or {}).get("crewId") == request.crew_id
            for pending in pending_query.stream(transaction=tx)
        )
        if has_other_pending:
            raise HTTPException(status_code=409, detail="Crew member already has an assignment awaiting review")
        flight = flight_snap.to_dict() or {}
        crew = crew_snap.to_dict() or {}
        if int(flight.get("filled", 0)) >= int(flight.get("slots", 0)):
            raise HTTPException(status_code=409, detail="Flight is already fully staffed")
        _assert_eligible(request.crew_id, crew, flight, db, tx)
        tx.create(assignment_ref, {
            "crewId": request.crew_id,
            "crewName": str(crew.get("name", request.crew_id)),
            "crewUid": str(crew.get("uid", "")),
            "flightId": request.flight_id,
            "status": "pending",
            "source": request.source,
            "requestedBy": uid,
            "requestedAt": firestore.SERVER_TIMESTAMP,
        })
        return {"id": assignment_ref.id, "status": "pending"}

    try:
        return write_request(transaction)
    except HTTPException:
        raise
    except Exception:
        log.exception("Could not create assignment request")
        raise HTTPException(status_code=503, detail="Assignment request could not be saved")


@app.post("/assignments/{assignment_id}/decision")
def decide_assignment(assignment_id: str, decision: AssignmentDecision, uid: str = Depends(verify_scheduler)):
    db = get_db()
    assignment_ref = db.collection("assignments").document(assignment_id)
    transaction = db.transaction()

    @firestore.transactional
    def apply_decision(tx):
        assignment_snap = assignment_ref.get(transaction=tx)
        if not assignment_snap.exists:
            raise HTTPException(status_code=404, detail="Assignment request not found")
        assignment = assignment_snap.to_dict() or {}
        if assignment.get("status") != "pending":
            raise HTTPException(status_code=409, detail="Assignment request has already been reviewed")
        flight_ref = db.collection("flights").document(str(assignment.get("flightId", "")))
        crew_ref = db.collection("crew").document(str(assignment.get("crewId", "")))
        flight_snap = flight_ref.get(transaction=tx)
        crew_snap = crew_ref.get(transaction=tx)
        if not flight_snap.exists or not crew_snap.exists:
            raise HTTPException(status_code=409, detail="Flight or crew record is no longer available")
        flight = flight_snap.to_dict() or {}
        crew = crew_snap.to_dict() or {}

        if decision.action == "approve":
            if int(flight.get("filled", 0)) >= int(flight.get("slots", 0)):
                raise HTTPException(status_code=409, detail="Flight is already fully staffed")
            _assert_eligible(str(assignment.get("crewId", "")), crew, flight, db, tx)
            filled = int(flight.get("filled", 0)) + 1
            tx.update(flight_ref, {
                "filled": filled,
                "status": "filled" if filled >= int(flight.get("slots", 0)) else "open",
            })
            tx.update(crew_ref, {
                "available": False,
                "assignedFlightId": str(assignment.get("flightId", "")),
                "updatedAt": firestore.SERVER_TIMESTAMP,
            })

        tx.update(assignment_ref, {
            "status": "approved" if decision.action == "approve" else "rejected",
            "reviewedBy": uid,
            "reviewedAt": firestore.SERVER_TIMESTAMP,
        })
        crew_uid = assignment.get("crewUid") or crew.get("uid")
        push_notice = None
        if crew_uid:
            notification_ref = db.collection("notifications").document()
            approved = decision.action == "approve"
            title = "Assignment approved" if approved else "Assignment declined"
            message_text = f"Your request for {flight.get('flightNo', assignment.get('flightId', 'a flight'))} was {'approved' if approved else 'declined'}."
            tx.create(notification_ref, {
                "userId": str(crew_uid),
                "title": title,
                "message": message_text,
                "read": False,
                "createdAt": firestore.SERVER_TIMESTAMP,
            })
            push_notice = {
                "uid": str(crew_uid),
                "notification_id": notification_ref.id,
                "title": title,
                "body": message_text,
            }
        return {
            "id": assignment_id,
            "status": "approved" if decision.action == "approve" else "rejected",
            "push": push_notice,
        }

    try:
        result = apply_decision(transaction)
        _send_push_notification(result.pop("push", None))
        return result
    except HTTPException:
        raise
    except Exception:
        log.exception("Could not review assignment")
        raise HTTPException(status_code=503, detail="Assignment decision could not be saved")


def _time_text(value) -> str:
    return value.isoformat() if hasattr(value, "isoformat") else str(value or "")


def _send_push_notification(notice: dict | None) -> None:
    """Send best-effort FCM after the assignment transaction commits."""
    if not notice:
        return
    try:
        user_ref = get_db().collection("users").document(notice["uid"])
        profile = user_ref.get().to_dict() or {}
        tokens = profile.get("fcmInstallationIds", [])
        if not isinstance(tokens, list):
            return
        for token in set(str(value) for value in tokens if value):
            try:
                messaging.send(messaging.Message(
                    data={
                        "notification_id": str(notice["notification_id"]),
                        "title": str(notice["title"]),
                        "body": str(notice["body"]),
                    },
                    fid=token,
                    android=messaging.AndroidConfig(priority="high"),
                ))
            except messaging.UnregisteredError:
                user_ref.update({"fcmInstallationIds": firestore.ArrayRemove([token])})
            except Exception:
                log.exception("Could not send assignment push notification")
    except Exception:
        # The Firestore assignment and in-app notification have already committed.
        log.exception("Could not load notification device tokens")


@app.get("/alerts")
def list_alerts(uid: str = Depends(verify_controller)):
    try:
        alerts = []
        for doc in get_db().collection("alerts").stream():
            data = doc.to_dict() or {}
            alerts.append({
                "id": doc.id,
                "title": str(data.get("title", "Alert")),
                "detail": str(data.get("detail", "")),
                "severity": str(data.get("severity", "LOW")),
                "acknowledged": bool(data.get("acknowledged", False)),
                "time": _time_text(data.get("createdAt")),
            })
        return {"alerts": alerts}
    except Exception:
        log.exception("Could not load alerts")
        raise HTTPException(status_code=503, detail="Alerts unavailable")


@app.post("/alerts/{alert_id}/acknowledge")
def acknowledge_alert(alert_id: str, uid: str = Depends(verify_scheduler)):
    try:
        ref = get_db().collection("alerts").document(alert_id)
        if not ref.get().exists:
            raise HTTPException(status_code=404, detail="Alert not found")
        ref.update({"acknowledged": True, "acknowledgedBy": uid, "acknowledgedAt": firestore.SERVER_TIMESTAMP})
        return {"id": alert_id, "acknowledged": True}
    except HTTPException:
        raise
    except Exception:
        log.exception("Could not acknowledge alert")
        raise HTTPException(status_code=503, detail="Alert update failed")


@app.get("/compliance")
def list_compliance(uid: str = Depends(verify_manager)):
    try:
        records = []
        for doc in get_db().collection("compliance").stream():
            data = doc.to_dict() or {}
            records.append({
                "id": doc.id,
                "crewId": str(data.get("crewId", "")),
                "crew": str(data.get("crew", data.get("crewId", ""))),
                "check": str(data.get("check", "")),
                "detail": str(data.get("detail", "")),
                "status": str(data.get("status", "")),
            })
        return {"records": records}
    except Exception:
        log.exception("Could not load compliance records")
        raise HTTPException(status_code=503, detail="Compliance data unavailable")


@app.get("/notifications")
def list_notifications(uid: str = Depends(verify_active_user)):
    try:
        output = []
        for doc in get_db().collection("notifications").stream():
            data = doc.to_dict() or {}
            owner = data.get("userId")
            if owner not in ("all", uid):
                continue
            read_by = data.get("readBy", [])
            if not isinstance(read_by, list):
                read_by = []
            output.append({
                "id": doc.id,
                "title": str(data.get("title", "Notification")),
                "message": str(data.get("message", "")),
                "time": _time_text(data.get("createdAt")),
                "read": bool(data.get("read", False)) or uid in read_by,
            })
        return {"notifications": output}
    except Exception:
        log.exception("Could not load notifications")
        raise HTTPException(status_code=503, detail="Notifications unavailable")


@app.post("/devices/token")
def register_device_token(request: InstallationRequest, uid: str = Depends(verify_active_user)):
    try:
        get_db().collection("users").document(uid).update({
            "fcmInstallationIds": firestore.ArrayUnion([request.installation_id]),
        })
        return {"registered": True}
    except Exception:
        log.exception("Could not register push token")
        raise HTTPException(status_code=503, detail="Push token registration failed")


@app.delete("/devices/token")
def unregister_device_token(request: InstallationRequest, uid: str = Depends(verify_active_user)):
    try:
        get_db().collection("users").document(uid).update({
            "fcmInstallationIds": firestore.ArrayRemove([request.installation_id]),
        })
        return {"registered": False}
    except Exception:
        log.exception("Could not unregister push token")
        raise HTTPException(status_code=503, detail="Push token removal failed")


@app.post("/notifications/{notification_id}/read")
def mark_notification_read(notification_id: str, uid: str = Depends(verify_active_user)):
    try:
        ref = get_db().collection("notifications").document(notification_id)
        snapshot = ref.get()
        if not snapshot.exists:
            raise HTTPException(status_code=404, detail="Notification not found")
        owner = (snapshot.to_dict() or {}).get("userId")
        if owner not in ("all", uid):
            raise HTTPException(status_code=403, detail="Notification access denied")
        ref.update({"readBy": firestore.ArrayUnion([uid])})
        return {"id": notification_id, "read": True}
    except HTTPException:
        raise
    except Exception:
        log.exception("Could not mark notification read")
        raise HTTPException(status_code=503, detail="Notification update failed")


@app.get("/reports/{report_kind}")
def get_report(report_kind: str, uid: str = Depends(verify_manager)):
    db = get_db()
    try:
        if report_kind == "utilisation":
            rows = [{
                "crew_id": doc.id,
                "name": (doc.to_dict() or {}).get("name", doc.id),
                "qualification": (doc.to_dict() or {}).get("qualification", ""),
                "duty_hours": (doc.to_dict() or {}).get("dutyHours", ""),
                "rest_hours": (doc.to_dict() or {}).get("restHours", ""),
                "available": (doc.to_dict() or {}).get("available", False),
            } for doc in db.collection("crew").stream()]
        elif report_kind == "compliance":
            rows = [{"crew_id": (d.to_dict() or {}).get("crewId", ""),
                     "crew": (d.to_dict() or {}).get("crew", ""),
                     "check": (d.to_dict() or {}).get("check", ""),
                     "detail": (d.to_dict() or {}).get("detail", ""),
                     "status": (d.to_dict() or {}).get("status", "")}
                    for d in db.collection("compliance").stream()]
        elif report_kind == "assignments":
            rows = [{"crew_id": (d.to_dict() or {}).get("crewId", ""),
                     "crew_name": (d.to_dict() or {}).get("crewName", ""),
                     "flight_id": (d.to_dict() or {}).get("flightId", ""),
                     "status": (d.to_dict() or {}).get("status", ""),
                     "source": (d.to_dict() or {}).get("source", ""),
                     "requested_at": _time_text((d.to_dict() or {}).get("requestedAt"))}
                    for d in db.collection("assignments").stream()]
        elif report_kind == "alerts":
            rows = [{"title": (d.to_dict() or {}).get("title", ""),
                     "detail": (d.to_dict() or {}).get("detail", ""),
                     "severity": (d.to_dict() or {}).get("severity", ""),
                     "acknowledged": (d.to_dict() or {}).get("acknowledged", False),
                     "created_at": _time_text((d.to_dict() or {}).get("createdAt"))}
                    for d in db.collection("alerts").stream()]
        else:
            raise HTTPException(status_code=404, detail="Unknown report type")
        return {"rows": rows}
    except HTTPException:
        raise
    except Exception:
        log.exception("Could not build report")
        raise HTTPException(status_code=503, detail="Report data unavailable")
