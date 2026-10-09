"""Create labeled monitoring metrics from the saved final historical holdout replay."""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from src.monitoring.error_monitor import monitor_historical_replay


if __name__ == "__main__":
    _, report = monitor_historical_replay(
        ROOT / "logs" / "forecasting" / "forecast_validation.csv",
        ROOT / "logs" / "monitoring" / "historical_holdout_replay.json",
    )
    print(report)
