import os
import numpy as np
import pandas as pd
from services.config import QUALIFICATIONS, SLOTS, eligible_mask

rng = np.random.default_rng(42)
N_CREW, N_FLIGHTS, POOL_SIZE = 300, 1000, 30


quals = rng.choice(list(SLOTS), size=N_CREW, p=[0.15, 0.20, 0.65])
experience = np.array([
    rng.integers(8, 31) if q == "Captain" else
    rng.integers(1, 13) if q == "First Officer" else
    rng.integers(1, 26)
    for q in quals
])
roster = pd.DataFrame({
    "crew_id": [f"C{i:04d}" for i in range(1, N_CREW + 1)],
    "qualification": quals,
    "crew_type": [QUALIFICATIONS[q] for q in quals],
    "experience_years": experience,
    # flight hours grow with experience (~350-650 h per year)
    "flight_hours": (experience * rng.uniform(350, 650, N_CREW)).astype(int),
})

# ---- 2. Flights: sample candidates, add per-flight state, simulate the dispatcher ----
frames = []
for flight_id in range(1, N_FLIGHTS + 1):
    flight_type = rng.choice(["Domestic", "International"], p=[0.6, 0.4])
    required_q = rng.choice(list(SLOTS), p=[0.25, 0.25, 0.50])

    pool = roster[roster.qualification == required_q].sample(
        POOL_SIZE, random_state=int(rng.integers(1_000_000_000))).copy()

    duty = np.round(rng.uniform(0, 13, POOL_SIZE), 2)
    pool["duty_hours"] = duty
    pool["rest_hours"] = np.round(np.clip(rng.normal(14 - 0.4 * duty, 3), 4, 24), 2)
    pool["availability"] = rng.choice([0, 1], size=POOL_SIZE, p=[0.15, 0.85])
    pool["flight_id"] = flight_id
    pool["flight_type"] = flight_type
    pool["required_qualification"] = required_q
    pool["slots"] = SLOTS[required_q]

    w_exp = 0.6 if flight_type == "International" else 0.4
    score = (w_exp * pool.experience_years + 0.3 * pool.rest_hours
             - 0.5 * pool.duty_hours + rng.normal(0, 1.5, POOL_SIZE))

    pool["eligible"] = eligible_mask(pool, flight_type, required_q).astype(int)
    pool["assigned"] = 0
    chosen = score[pool.eligible == 1].sort_values(ascending=False).head(SLOTS[required_q]).index
    pool.loc[chosen, "assigned"] = 1
    frames.append(pool)

df = pd.concat(frames, ignore_index=True)
os.makedirs("data", exist_ok=True)
df.to_csv("data/crew_assignments.csv", index=False)

el = df[df.eligible == 1]
print("Rows:", len(df), "| eligible rows:", len(el))
print("Assigned rate among eligible:", round(el.assigned.mean(), 3))
print("Flights with fewer eligible crew than slots:",
      int((df.groupby("flight_id").apply(lambda g: g.eligible.sum() < g.slots.iloc[0], include_groups=False)).sum()))
