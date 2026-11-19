
from pathlib import Path
import joblib
import numpy as np
import pandas as pd

from config import (
    QUALIFICATIONS,
    SLOTS,
    RULES,
    eligible_mask
)

# Must match train_model.py exactly
CATEGORICAL = [
    "qualification",
    "crew_type",
    "flight_type",
    "required_qualification",
]

NUMERIC = [
    "experience_years",
    "flight_hours",
    "duty_hours",
    "rest_hours",
    "availability",
]

FEATURES = CATEGORICAL + NUMERIC

REQUIRED_COLUMNS = [
    "crew_id",
    "availability",
    "qualification",
    "crew_type",
    "experience_years",
    "flight_hours",
    "duty_hours",
    "rest_hours",
]

OUTPUT_COLUMNS = [
    "rank",
    "crew_id",
    "qualification",
    "experience_years",
    "duty_hours",
    "rest_hours",
    "suitability_score",
    "recommendation",
    "reason",
]


class CrewRecommender:

    def __init__(self, model_path=None):

        if model_path:
            path = Path(model_path)
        else:
            base_dir = (
                Path(__file__).parent.parent
                if "__file__" in globals()
                else Path(".")
            )
            path = base_dir / "models" / "crew_recommender.pkl"

        if not path.exists():
            raise FileNotFoundError(
                f"Trained model not found: {path}. "
                "Run train_model.py first."
            )

        self.model = joblib.load(path)

    def recommend(
        self,
        crew_data: pd.DataFrame,
        flight_type: str,
        required_qualification: str,
        top_n: int | None = None
    ) -> pd.DataFrame:

        # Validate inputs
        if flight_type not in RULES:
            raise ValueError(
                f"flight_type must be one of {list(RULES)}"
            )

        if required_qualification not in QUALIFICATIONS:
            raise ValueError(
                f"required_qualification must be one of "
                f"{list(QUALIFICATIONS)}"
            )

        missing = set(REQUIRED_COLUMNS) - set(crew_data.columns)

        if missing:
            raise ValueError(
                f"crew_data is missing columns: {sorted(missing)}"
            )

        if top_n is not None and top_n < 1:
            raise ValueError("top_n must be at least 1")

        # Apply hard eligibility rules
        eligible = crew_data[
            eligible_mask(
                crew_data,
                flight_type,
                required_qualification
            )
        ].copy()

        if eligible.empty:
            return pd.DataFrame(columns=OUTPUT_COLUMNS)

        # Add flight context
        eligible["flight_type"] = flight_type
        eligible["required_qualification"] = required_qualification

        # Verify model features
        missing_features = set(FEATURES) - set(eligible.columns)

        if missing_features:
            raise ValueError(
                f"Missing model features: {sorted(missing_features)}"
            )

        # Predict suitability scores
        raw = self.model.predict_proba(
            eligible[FEATURES]
        )[:, 1]

        eligible["raw"] = raw

        # Rank crew members
        eligible = eligible.sort_values(
            "raw",
            ascending=False
        ).copy()

        n = (
            top_n
            if top_n is not None
            else SLOTS[required_qualification]
        )

        eligible["rank"] = np.arange(
            1,
            len(eligible) + 1
        )

        eligible["suitability_score"] = (
            eligible["raw"] * 100
        ).round(1)

        eligible["recommendation"] = np.where(
            eligible["rank"] <= n,
            "Recommended",
            "Alternate"
        )

        # Explain recommendations
        eligible["reason"] = eligible.apply(
            lambda r: (
                f"{r.experience_years} years experience, "
                f"{r.rest_hours:.1f} hours rest, "
                f"{r.duty_hours:.1f} hours duty"
            ),
            axis=1
        )

        return eligible[
            OUTPUT_COLUMNS
        ].reset_index(drop=True)


if __name__ == "__main__":

    roster = pd.read_csv(
        "data/crew_assignments.csv"
    ).drop_duplicates("crew_id").head(80)

    roster = roster.assign(
        availability=1,
        duty_hours=3.0,
        rest_hours=14.0
    )

    recommender = CrewRecommender()

    recommendations = recommender.recommend(
        roster,
        "International",
        "Captain"
    )

    print("\nCREW RECOMMENDATIONS")
    print(recommendations.to_string(index=False))
