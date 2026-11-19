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
    query = db.collection("crew").where(filter=FieldFilter("qualification", "==", qualification))
    candidates = []
    for doc in query.stream():
        data = doc.to_dict() or {}
        if data.get("active", True) is False:
            continue
        data["crew_id"] = doc.id
        candidates.append(data)
    return candidates
