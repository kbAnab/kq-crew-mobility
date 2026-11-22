"""Reads flights and candidate crew from Firestore."""
from typing import Optional

from google.cloud.firestore_v1.base_query import FieldFilter


def get_flight(db, flight_id: str) -> Optional[dict]:
    """flights/{flightId}: flightNo, route, qualification, slots, filled, international."""
    doc = db.collection("flights").document(flight_id).get()
    if not doc.exists:
        return None
    flight = doc.to_dict() or {}
    flight["id"] = doc.id
    return flight


def get_candidates(db, qualification: str) -> list[dict]:
    """
    crew/{crewId}: name, qualification, crewType, experienceYears, flightHours,
    dutyHours, restHours, available, active.
    Only active crew with the required qualification are returned.
    """
    pending_ids = set()
    for request in db.collection("assignments").where(
        filter=FieldFilter("status", "==", "pending")
    ).stream():
        crew_id = (request.to_dict() or {}).get("crewId")
        if crew_id:
            pending_ids.add(str(crew_id))

    query = db.collection("crew").where(filter=FieldFilter("qualification", "==", qualification))
    candidates = []
    for doc in query.stream():
        data = doc.to_dict() or {}
        if data.get("active", True) is False:
            continue
        data["crew_id"] = doc.id
        # A crew member already awaiting a scheduler decision is reserved and
        # cannot receive another simultaneous recommendation.
        if doc.id in pending_ids:
            continue
        candidates.append(data)
    return candidates
