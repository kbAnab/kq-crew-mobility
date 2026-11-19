import firebase_admin
from firebase_admin import credentials, firestore
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent

if not firebase_admin._apps:
    credential_path = BASE_DIR / "serviceAccountKey.json"

    cred = credentials.Certificate(str(credential_path))

    firebase_admin.initialize_app(cred)

db = firestore.client()