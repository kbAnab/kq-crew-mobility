# qualification -> crew type
QUALIFICATIONS = {"Captain": "Pilot", "First Officer": "Pilot", "Cabin Crew": "Cabin Crew"}

# how many people of each qualification a flight needs
SLOTS = {"Captain": 1, "First Officer": 1, "Cabin Crew": 4}

RULES = {
    "Domestic":      {"max_duty_hours": 12, "min_rest_hours": 10, "min_experience_years": 1},
    "International": {"max_duty_hours": 11, "min_rest_hours": 12, "min_experience_years": 3},
}

# columns the model sees (availability is a hard rule, so it is not a feature)
FEATURES = ["crew_type", "qualification", "experience_years",
            "flight_hours", "duty_hours", "rest_hours", "flight_type"]
CATEGORICAL = ["crew_type", "qualification", "flight_type"]
NUMERIC = ["experience_years", "flight_hours", "duty_hours", "rest_hours"]


def eligible_mask(crew, flight_type, qualification):
    """Hard operational constraints. Rules decide WHO MAY fly; the model ranks who SHOULD."""
    r = RULES[flight_type]
    return (
        (crew["availability"] == 1)
        & (crew["qualification"] == qualification)
        & (crew["duty_hours"] < r["max_duty_hours"])
        & (crew["rest_hours"] >= r["min_rest_hours"])
        & (crew["experience_years"] >= r["min_experience_years"])
    )
