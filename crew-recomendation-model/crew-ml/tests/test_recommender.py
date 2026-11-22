import unittest

import pandas as pd

from recommender import CrewRecommender, OUTPUT_COLUMNS


class CrewRecommenderTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.model = CrewRecommender()

    @staticmethod
    def crew_row(crew_id, **updates):
        row = {
            "crew_id": crew_id,
            "availability": 1,
            "qualification": "Captain",
            "crew_type": "Pilot",
            "experience_years": 8,
            "flight_hours": 3840,
            "duty_hours": 3.0,
            "rest_hours": 14.0,
        }
        row.update(updates)
        return row

    def test_model_returns_expected_recommendation_contract(self):
        result = self.model.recommend(
            pd.DataFrame([self.crew_row("eligible-crew")]),
            "Domestic",
            "Captain",
        )

        self.assertEqual(list(result.columns), OUTPUT_COLUMNS)
        self.assertEqual(result["crew_id"].tolist(), ["eligible-crew"])
        self.assertEqual(result.iloc[0]["recommendation"], "Recommended")
        self.assertTrue(0 <= result.iloc[0]["suitability_score"] <= 100)
        self.assertIn("experience", result.iloc[0]["reason"])

    def test_hard_constraints_remove_unavailable_or_ineligible_crew(self):
        crew = pd.DataFrame([
            self.crew_row("eligible"),
            self.crew_row("unavailable", availability=0),
            self.crew_row("wrong-role", qualification="First Officer"),
            self.crew_row("duty-breach", duty_hours=12.0),
            self.crew_row("rest-breach", rest_hours=9.0),
            self.crew_row("experience-breach", experience_years=0),
        ])

        result = self.model.recommend(crew, "Domestic", "Captain")
        self.assertEqual(result["crew_id"].tolist(), ["eligible"])

    def test_model_honors_stricter_policy_override(self):
        crew = pd.DataFrame([
            self.crew_row("valid-under-baseline", duty_hours=4.0, rest_hours=14.0),
            self.crew_row("fails-stricter-policy", duty_hours=5.0, rest_hours=14.0),
        ])

        result = self.model.recommend(
            crew,
            "Domestic",
            "Captain",
            policy_override={
                "max_duty_hours": 4.5,
                "min_rest_hours": 12,
                "min_experience_years": 2,
            },
        )
        self.assertEqual(result["crew_id"].tolist(), ["valid-under-baseline"])


if __name__ == "__main__":
    unittest.main()
