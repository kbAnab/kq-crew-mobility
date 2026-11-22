import os
import joblib
import numpy as np
import pandas as pd
from sklearn.compose import ColumnTransformer
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import roc_auc_score
from sklearn.model_selection import GroupShuffleSplit
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler


df = pd.read_csv("data/crew_assignments.csv")

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


df = df[df.eligible == 1].reset_index(drop=True)


splitter = GroupShuffleSplit(n_splits=1, test_size=0.2, random_state=42)
train_idx, test_idx = next(splitter.split(df, groups=df.flight_id))
train, test = df.iloc[train_idx], df.iloc[test_idx].copy()

pipeline = Pipeline([
    ("prep", ColumnTransformer([
        ("cat", OneHotEncoder(handle_unknown="ignore"), CATEGORICAL),
        ("num", StandardScaler(), NUMERIC),
    ])),
    ("model", RandomForestClassifier(n_estimators=300, max_depth=8, min_samples_leaf=5,
                                     random_state=42, n_jobs=-1)),
])
pipeline.fit(train[FEATURES], train.assigned)

test["p"] = pipeline.predict_proba(test[FEATURES])[:, 1]
print("ROC-AUC:", round(roc_auc_score(test.assigned, test.p), 3))


hits, baseline = [], []
for _, g in test.groupby("flight_id"):
    k = int(g.slots.iloc[0])
    if len(g) <= k:           
        continue
    hits.append(g.nlargest(k, "p").assigned.mean())
    baseline.append(k / len(g))
print(f"Top-k agreement with dispatcher: {np.mean(hits):.1%}  (random picking: {np.mean(baseline):.1%})")

os.makedirs("models", exist_ok=True)
joblib.dump(pipeline, "models/crew_recommender.pkl")
print("Model saved.")
