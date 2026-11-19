
import json
import logging
from typing import List, Optional

from fastapi import Depends, FastAPI, Header, HTTPException
from firebase_admin import auth
from pydantic import BaseModel, Field

from config import QUALIFICATIONS
from recommender import CrewRecommender
from services.crew_service import get_candidates, get_flight
from services.eligibility_service import candidates_to_frame, flight_type_of
from services.firebase_service import get_db

import pandas as pd

log = logging.getLogger("crew-api")

app = FastAPI(
    title="AI Crew Task Allocation API",
    description="Firebase-connected crew recommendation service",
    version="2.1.0",
)

recommender = CrewRecommender()

ALLOWED_ROLES = {"CREW_CONTROLLER", "MANAGER"}


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


# ---------------------------------------
# AUTHENTICATION AND AUTHORIZATION
# ---------------------------------------

def verify_controller(authorization: str = Header(...)) -> str:
    """Accept only an active CREW_CONTROLLER or MANAGER with a valid Firebase ID token."""
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
    if profile.get("role") not in ALLOWED_ROLES:
        raise HTTPException(status_code=403, detail="Access denied")
    return uid


# ---------------------------------------
# BASIC ENDPOINTS
# ---------------------------------------

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


# ---------------------------------------
# RECOMMENDATIONS
# ---------------------------------------

def _to_records(results: pd.DataFrame) -> list:
    # going through JSON turns numpy numbers into plain Python numbers
    return json.loads(results.to_json(orient="records"))


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
    """Load the flight and its candidate crew from Firestore, then rank them."""
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
