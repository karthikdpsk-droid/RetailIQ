"""Compare two feature windows using configured, explainable drift thresholds."""
import argparse
import json
from pathlib import Path
import sys

import pandas as pd
import yaml

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from src.monitoring.drift import decide_retraining


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("reference_csv")
    parser.add_argument("current_csv")
    parser.add_argument("--features", nargs="*")
    args = parser.parse_args()
    reference, current = pd.read_csv(args.reference_csv), pd.read_csv(args.current_csv)
    if args.features:
        reference, current = reference[args.features], current[args.features]
    config = yaml.safe_load((ROOT / "configs" / "monitoring.yaml").read_text(encoding="utf-8"))
    decision = decide_retraining(reference=reference, current=current, config=config)
    result = {"status": decision.status, "reasons": decision.reasons, "metrics": decision.metrics,
              "note": "A retraining flag is advisory and never launches training or changes the serving model."}
    print(json.dumps(result, indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
