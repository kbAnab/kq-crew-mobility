"""Turns Firestore documents into the table the recommender expects."""
import pandas as pd

from config import QUALIFICATIONS


def flight_type_of(flight: dict) -> str:
    return "International" if flight.get("international") else "Domestic"


def candidates_to_frame(docs: list[dict]) -> pd.DataFrame:
    """
    One row per crew member. Missing numbers default to values that fail the hard rules
    (no rest, long duty), so incomplete records are never recommended by accident.
    """
    rows = []
    for d in docs:
        qualification = d.get("qualification", "")
        rows.append({
            "crew_id": d["crew_id"],
            "name": d.get("name", d["crew_id"]),
            "qualification": qualification,
            "crew_type": d.get("crewType") or QUALIFICATIONS.get(qualification, ""),
            "experience_years": int(d.get("experienceYears", 0)),
            "flight_hours": int(d.get("flightHours", 0)),
            "duty_hours": float(d.get("dutyHours", 24.0)),
            "rest_hours": float(d.get("restHours", 0.0)),
            "availability": 1 if d.get("available", False) else 0,
        })
    return pd.DataFrame(rows)
