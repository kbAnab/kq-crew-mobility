import os
from pathlib import Path

import firebase_admin
from firebase_admin import credentials, firestore

_KEY_FILE = Path(__file__).resolve().parent.parent / "serviceAccountKey.json"
_db = None


def _credential():
    env_path = os.environ.get("GOOGLE_APPLICATION_CREDENTIALS")
    if env_path and Path(env_path).is_file():
        return credentials.Certificate(env_path)
    if _KEY_FILE.is_file():
        return credentials.Certificate(str(_KEY_FILE))
    return credentials.ApplicationDefault()


def get_db():
    global _db
    if _db is None:
        if not firebase_admin._apps:
            firebase_admin.initialize_app(_credential())
        _db = firestore.client()
    return _db
