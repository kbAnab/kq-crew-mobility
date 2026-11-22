import unittest
from datetime import datetime, timezone

from api import _eat_date, _policy_for_flight
from services.config import RULES


class Snapshot:
    exists = True

    def __init__(self, data):
        self.data = data

    def to_dict(self):
        return self.data


class Document:
    def __init__(self, data):
        self.data = data

    def get(self, transaction=None):
        return Snapshot(self.data)


class Collection:
    def __init__(self, data):
        self.data = data

    def document(self, _document_id):
        return Document(self.data)


class FakeFirestore:
    def __init__(self, data):
        self.data = data

    def collection(self, _collection_name):
        return Collection(self.data)


class PolicyRuleTests(unittest.TestCase):
    def test_saved_policy_cannot_loosen_model_baseline(self):
        db = FakeFirestore({"policies": {
            "domestic": {"maxDutyHours": 24, "minRestHours": 0, "minExperienceYears": 0},
        }})

        policy = _policy_for_flight(db, {"international": False})
        self.assertEqual(policy, RULES["Domestic"])

    def test_saved_policy_can_tighten_baseline(self):
        db = FakeFirestore({"policies": {
            "international": {"maxDutyHours": 9, "minRestHours": 14, "minExperienceYears": 5},
        }})

        policy = _policy_for_flight(db, {"international": True})
        self.assertEqual(policy, {
            "max_duty_hours": 9,
            "min_rest_hours": 14,
            "min_experience_years": 5,
        })

    def test_flight_day_is_compared_in_nairobi_time(self):
        timestamp = datetime(2026, 11, 22, 21, 30, tzinfo=timezone.utc)
        self.assertEqual(str(_eat_date(timestamp)), "2026-11-23")
        self.assertIsNone(_eat_date(None))


if __name__ == "__main__":
    unittest.main()
