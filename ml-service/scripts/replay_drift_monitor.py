"""Compare two historical feature windows as a drift simulation (not production)."""
import json
import sys
from pathlib import Path

import pandas as pd
import yaml

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from src.forecasting import FEATURES
from src.monitoring.drift import decide_retraining

DATASET = ROOT / "data" / "processed" / "retailiq_leakage_safe_corrected.csv"
REPORT = ROOT / "logs" / "monitoring" / "historical_drift_simulation.json"
REFERENCE_START, REFERENCE_END = "2016-01-01", "2016-07-01"
CURRENT_START, CURRENT_END = "2016-07-01", "2017-01-01"
CHUNK_SIZE, SAMPLE_PER_CHUNK, WINDOW_CAP = 200_000, 5_000, 50_000


def main():
    reference_parts, current_parts = [], []
    columns = ["date", *FEATURES]
    for chunk_index, chunk in enumerate(
        pd.read_csv(DATASET, usecols=columns, parse_dates=["date"], chunksize=CHUNK_SIZE)
    ):
        for start, end, parts in ((REFERENCE_START, REFERENCE_END, reference_parts),
                                  (CURRENT_START, CURRENT_END, current_parts)):
            window = chunk[(chunk.date >= start) & (chunk.date < end)][FEATURES]
            if len(window):
                parts.append(window.sample(n=min(SAMPLE_PER_CHUNK, len(window)), random_state=42 + chunk_index))
    reference = pd.concat(reference_parts, ignore_index=True).sample(
        n=min(WINDOW_CAP, sum(map(len, reference_parts))), random_state=42)
    current = pd.concat(current_parts, ignore_index=True).sample(
        n=min(WINDOW_CAP, sum(map(len, current_parts))), random_state=42)
    config = yaml.safe_load((ROOT / "configs" / "monitoring.yaml").read_text(encoding="utf-8"))
    decision = decide_retraining(reference=reference, current=current, config=config)
    report = {"report_type": "historical_drift_simulation", "status": decision.status,
              "reference_period": f"{REFERENCE_START}..{REFERENCE_END} (end exclusive)",
              "current_period": f"{CURRENT_START}..{CURRENT_END} (end exclusive)",
              "reference_sample_rows": len(reference), "current_sample_rows": len(current),
              "reasons": decision.reasons, "metrics": decision.metrics,
              "note": "Controlled historical feature-window comparison; not production drift and does not trigger retraining."}
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text(json.dumps(report, indent=2, allow_nan=False), encoding="utf-8")
    print(json.dumps(report, indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
