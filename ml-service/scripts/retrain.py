"""CLI for controlled batch candidate training; never overwrites the champion."""
import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from src.retraining.pipeline import run_retraining


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset")
    parser.add_argument("--champion")
    parser.add_argument("--promote", action="store_true", help="request registry promotion if all validation gates pass")
    args = parser.parse_args()
    options = {key: value for key, value in {"dataset_path": args.dataset,
                                             "champion_path": args.champion}.items() if value}
    print(json.dumps(run_retraining(**options, promote=args.promote), indent=2))


if __name__ == "__main__":
    main()
