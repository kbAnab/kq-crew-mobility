"""Evaluate the saved crew model on held-out flights and plot binary metrics.

Run from any working directory with: python evaluate_model.py
The evaluation uses the same deterministic, flight-grouped split as train_model.py.
"""

import json
import os
from pathlib import Path

PROJECT_DIR = Path(__file__).resolve().parent.parent
REPORT_DIR = PROJECT_DIR / "reports"
REPORT_DIR.mkdir(parents=True, exist_ok=True)
os.environ.setdefault("MPLCONFIGDIR", str(REPORT_DIR / ".matplotlib"))

import joblib
import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import pandas as pd
from sklearn.metrics import (
    accuracy_score,
    confusion_matrix,
    f1_score,
    precision_score,
    recall_score,
    roc_auc_score,
)
from sklearn.model_selection import GroupShuffleSplit


DATA_PATH = PROJECT_DIR / "data" / "crew_assignments.csv"
MODEL_PATH = PROJECT_DIR / "models" / "crew_recommender.pkl"
THRESHOLD = 0.5

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


def main() -> None:
    if not DATA_PATH.is_file():
        raise FileNotFoundError(f"Evaluation dataset not found: {DATA_PATH}")
    if not MODEL_PATH.is_file():
        raise FileNotFoundError(f"Trained model not found: {MODEL_PATH}")

    data = pd.read_csv(DATA_PATH)
    required = set(FEATURES + ["eligible", "assigned", "flight_id"])
    missing = required - set(data.columns)
    if missing:
        raise ValueError(f"Dataset is missing required columns: {sorted(missing)}")

    eligible = data[data["eligible"] == 1].reset_index(drop=True)
    if eligible.empty or eligible["assigned"].nunique() < 2:
        raise ValueError("Eligible evaluation data must contain both assigned and unassigned rows.")

    splitter = GroupShuffleSplit(n_splits=1, test_size=0.2, random_state=42)
    _, test_indices = next(splitter.split(eligible, groups=eligible["flight_id"]))
    test = eligible.iloc[test_indices]
    if test["assigned"].nunique() < 2:
        raise ValueError("The held-out flight split must contain both target classes.")

    model = joblib.load(MODEL_PATH)
    # Keep inference reliable on constrained hosts; this does not alter predictions.
    if hasattr(model, "set_params"):
        model.set_params(model__n_jobs=1)

    probabilities = model.predict_proba(test[FEATURES])[:, 1]
    predictions = (probabilities >= THRESHOLD).astype(int)
    actual = test["assigned"].astype(int)
    matrix = confusion_matrix(actual, predictions, labels=[0, 1])

    metrics = {
        "accuracy": float(accuracy_score(actual, predictions)),
        "precision": float(precision_score(actual, predictions, zero_division=0)),
        "recall": float(recall_score(actual, predictions, zero_division=0)),
        "f1_score": float(f1_score(actual, predictions, zero_division=0)),
        "roc_auc": float(roc_auc_score(actual, probabilities)),
        "decision_threshold": THRESHOLD,
        "test_rows": int(len(test)),
        "test_flights": int(test["flight_id"].nunique()),
        "positive_rows": int(actual.sum()),
        "negative_rows": int(len(actual) - actual.sum()),
        "confusion_matrix": {
            "labels": ["Not assigned", "Assigned"],
            "rows_actual_columns_predicted": matrix.tolist(),
            "true_negative": int(matrix[0, 0]),
            "false_positive": int(matrix[0, 1]),
            "false_negative": int(matrix[1, 0]),
            "true_positive": int(matrix[1, 1]),
        },
        "dataset": str(DATA_PATH.relative_to(PROJECT_DIR)),
        "model": str(MODEL_PATH.relative_to(PROJECT_DIR)),
        "evaluation_note": "Metrics use the synthetic labelled dataset and a held-out flight-group split; they are not operational validation.",
    }

    def value_counts(series: pd.Series) -> dict[str, int]:
        return {str(key): int(value) for key, value in series.value_counts(dropna=False).items()}

    numeric_features = ["experience_years", "flight_hours", "duty_hours", "rest_hours"]
    dataset_distribution = {
        "total_rows": int(len(data)),
        "total_flights": int(data["flight_id"].nunique()),
        "eligibility": value_counts(data["eligible"].map({0: "Ineligible", 1: "Eligible"}).fillna("Unknown")),
        "assignment_label_among_eligible": value_counts(
            eligible["assigned"].map({0: "Not assigned", 1: "Assigned"}).fillna("Unknown")
        ),
        "flight_type_among_eligible": value_counts(eligible["flight_type"]),
        "crew_qualification_among_eligible": value_counts(eligible["qualification"]),
        "required_qualification_among_eligible": value_counts(eligible["required_qualification"]),
        "eligible_feature_summary": {
            feature: {
                "mean": float(eligible[feature].mean()),
                "median": float(eligible[feature].median()),
                "min": float(eligible[feature].min()),
                "max": float(eligible[feature].max()),
            }
            for feature in numeric_features
        },
        "note": "This is the project's synthetic dataset. Eligibility counts include all rows; other distributions and numeric summaries use eligible rows.",
    }

    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    metrics_path = REPORT_DIR / "model_metrics.json"
    chart_path = REPORT_DIR / "model_metrics.png"
    confusion_path = REPORT_DIR / "confusion_matrix.png"
    distribution_path = REPORT_DIR / "dataset_distribution.png"
    distribution_json_path = REPORT_DIR / "dataset_distribution.json"
    metrics["dataset_distribution"] = dataset_distribution
    metrics_path.write_text(json.dumps(metrics, indent=2) + "\n", encoding="utf-8")
    distribution_json_path.write_text(
        json.dumps(dataset_distribution, indent=2) + "\n", encoding="utf-8"
    )

    names = ["Accuracy", "Precision", "Recall", "F1 score"]
    values = [metrics["accuracy"], metrics["precision"], metrics["recall"], metrics["f1_score"]]
    colors = ["#006B54", "#0B8068", "#30A48A", "#72C4AC"]

    figure, axis = plt.subplots(figsize=(8.5, 5.2))
    bars = axis.bar(names, values, color=colors, width=0.62)
    axis.set_ylim(0, 1.08)
    axis.set_ylabel("Score")
    axis.set_title("Crew Recommendation Model — Held-out Metrics")
    axis.set_axisbelow(True)
    axis.grid(axis="y", linestyle="--", alpha=0.3)
    axis.spines["top"].set_visible(False)
    axis.spines["right"].set_visible(False)
    for bar, value in zip(bars, values):
        axis.text(bar.get_x() + bar.get_width() / 2, value + 0.025, f"{value:.1%}", ha="center", va="bottom")
    figure.text(
        0.5,
        0.015,
        f"Threshold {THRESHOLD:.2f} · {metrics['test_rows']:,} eligible rows across {metrics['test_flights']:,} held-out flights · synthetic data",
        ha="center",
        fontsize=8,
        color="#555555",
    )
    figure.tight_layout(rect=(0, 0.06, 1, 1))
    figure.savefig(chart_path, dpi=180, bbox_inches="tight")
    plt.close(figure)

    figure, axis = plt.subplots(figsize=(6.2, 5.4))
    image = axis.imshow(matrix, cmap="Greens")
    axis.set_xticks([0, 1], labels=["Not assigned", "Assigned"])
    axis.set_yticks([0, 1], labels=["Not assigned", "Assigned"])
    axis.set_xlabel("Predicted label")
    axis.set_ylabel("Actual label")
    axis.set_title("Crew Recommendation Model — Confusion Matrix")
    threshold_for_text = matrix.max() / 2 if matrix.max() else 0
    for row in range(2):
        for column in range(2):
            axis.text(
                column,
                row,
                f"{matrix[row, column]:,}",
                ha="center",
                va="center",
                color="white" if matrix[row, column] > threshold_for_text else "#16352D",
                fontsize=15,
                fontweight="bold",
            )
    figure.colorbar(image, ax=axis, label="Crew records")
    figure.text(
        0.5,
        0.015,
        f"Held-out set · {metrics['test_rows']:,} eligible rows across {metrics['test_flights']:,} flights · synthetic data",
        ha="center",
        fontsize=8,
        color="#555555",
    )
    figure.tight_layout(rect=(0, 0.06, 1, 1))
    figure.savefig(confusion_path, dpi=180, bbox_inches="tight")
    plt.close(figure)

    figure, axes = plt.subplots(4, 2, figsize=(12, 15))
    categorical_plots = [
        (axes[0, 0], data["eligible"].map({0: "Ineligible", 1: "Eligible"}).fillna("Unknown"), "Eligibility — all rows"),
        (axes[0, 1], eligible["assigned"].map({0: "Not assigned", 1: "Assigned"}).fillna("Unknown"), "Assignment label — eligible rows"),
        (axes[1, 0], eligible["flight_type"], "Flight type — eligible rows"),
        (axes[1, 1], eligible["qualification"], "Crew qualification — eligible rows"),
        (axes[2, 0], eligible["required_qualification"], "Required qualification — eligible rows"),
    ]
    for axis, series, title in categorical_plots:
        counts = series.value_counts().sort_index()
        bars = axis.bar(counts.index.astype(str), counts.values, color="#16856E")
        axis.set_title(title)
        axis.set_ylabel("Rows")
        axis.tick_params(axis="x", rotation=20)
        axis.grid(axis="y", linestyle="--", alpha=0.25)
        axis.bar_label(bars, fmt="%d", padding=3, fontsize=8)

    numeric_plots = [
        (axes[2, 1], "experience_years", "Experience years — eligible rows", "Experience (years)"),
        (axes[3, 0], "duty_hours", "Duty hours — eligible rows", "Duty hours"),
        (axes[3, 1], "rest_hours", "Rest hours — eligible rows", "Rest hours"),
    ]
    for axis, column, title, label in numeric_plots:
        axis.hist(eligible[column], bins=20, color="#16856E", edgecolor="white")
        axis.set_title(title)
        axis.set_xlabel(label)
        axis.set_ylabel("Rows")
        axis.grid(axis="y", linestyle="--", alpha=0.25)

    figure.suptitle("Crew Allocation Dataset Distribution", fontsize=16, y=0.995)
    figure.text(
        0.5,
        0.006,
        f"{len(data):,} total rows across {data['flight_id'].nunique():,} flights · feature distributions use eligible rows · synthetic data",
        ha="center",
        fontsize=9,
        color="#555555",
    )
    figure.tight_layout(rect=(0, 0.02, 1, 0.98), h_pad=2.0, w_pad=1.5)
    figure.savefig(distribution_path, dpi=180, bbox_inches="tight")
    plt.close(figure)

    print("Held-out model metrics (positive class: assigned crew)")
    for name in ("accuracy", "precision", "recall", "f1_score", "roc_auc"):
        print(f"{name.replace('_', ' ').title():<10}: {metrics[name]:.4f}")
    print(f"Test rows: {metrics['test_rows']:,} across {metrics['test_flights']:,} flights")
    print(f"Chart: {chart_path}")
    print(f"Confusion matrix: {confusion_path}")
    print(f"Dataset distribution: {distribution_path}")
    print(f"Distribution data: {distribution_json_path}")
    print(f"Metrics: {metrics_path}")
    print("Note: these results evaluate synthetic data, not real airline operations.")


if __name__ == "__main__":
    main()
