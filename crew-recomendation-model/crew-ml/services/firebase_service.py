import os
from pathlib import Path

import firebase_admin
from firebase_admin import credentials, firestore

_KEY_FILE = Path(__file__).resolve().parent.parent / "serviceAccountKey.json"
_db = None


def _credential():
    env_path = os.environ.get("GOOGLE_APPLICATION_CREDENTIALS")
    if env_path:
        if not Path(env_path).is_file():
            raise FileNotFoundError("GOOGLE_APPLICATION_CREDENTIALS does not point to a readable credential file")
        return credentials.Certificate(env_path)
    if _KEY_FILE.is_file():
        return credentials.Certificate(str(_KEY_FILE))
    return credentials.ApplicationDefault()


def get_db():
    global _db
    if _db is None:
        if not firebase_admin._apps:
            project_id = os.environ.get("FIREBASE_PROJECT_ID") or os.environ.get("GOOGLE_CLOUD_PROJECT")
            options = {"projectId": project_id} if project_id else None
            firebase_admin.initialize_app(_credential(), options=options)
        _db = firestore.client()
    return _db
