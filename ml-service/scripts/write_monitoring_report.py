"""Write a production monitoring snapshot without inventing actual demand."""
import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from src.monitoring.error_monitor import monitor_actuals
from src.monitoring.prediction_logger import DEFAULT_LOG_PATH
REPORT = ROOT / "logs" / "monitoring" / "production_monitoring_report.json"
ACTUALS = ROOT / "data" / "production" / "actuals.csv"
PREDICTIONS = Path(os.getenv("RETAILIQ_PREDICTION_LOG", str(DEFAULT_LOG_PATH)))


def main():
    if not ACTUALS.exists():
        records = []
        if PREDICTIONS.exists():
            from src.monitoring.prediction_logger import load_prediction_records
            records = [r for r in load_prediction_records(PREDICTIONS) if r.get("endpoint") == "/forecast" and r.get("status") == "success"]
        report = {"report_type": "production_forecast_monitoring", "status": "INSUFFICIENT_DATA",
                  "n_predictions": len(records), "n_with_actual": 0, "actual_coverage": 0.0 if records else None,
                  "MAE": None, "RMSE": None, "WAPE_pct": None, "bias_mean_error": None,
                  "note": "No production actuals file is configured. No production accuracy is claimed."}
        REPORT.parent.mkdir(parents=True, exist_ok=True)
        REPORT.write_text(json.dumps(report, indent=2), encoding="utf-8")
        print(json.dumps(report, indent=2))
        return
    import pandas as pd
    _, report = monitor_actuals(PREDICTIONS, pd.read_csv(ACTUALS, parse_dates=["date"]), REPORT)
    report["status"] = "ACTIVE" if report["n_with_actual"] else "INSUFFICIENT_DATA"
    REPORT.write_text(json.dumps(report, indent=2, allow_nan=False), encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
