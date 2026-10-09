from __future__ import annotations

import csv
import json
import os
from bisect import bisect_right
from collections import Counter
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pandas as pd


LAGS = (1, 7, 14, 28)
WINDOWS = (7, 14, 28)
OIL_BUFFER_DAYS = 2
MODEL_FEATURES = [
    "store_nbr", "family", "onpromotion", "city", "state", "type", "cluster",
    "dcoilwtico", "is_holiday_event", "year", "month", "day", "day_of_week",
    "week_of_year", "quarter", "is_weekend", "has_promotion", "sales_lag_1",
    "sales_lag_7", "sales_lag_14", "sales_lag_28", "sales_rolling_mean_7",
    "sales_rolling_mean_14", "sales_rolling_mean_28", "sales_rolling_std_7",
]


def _reader(path: Path):
    return csv.DictReader(path.open(newline="", encoding="utf-8"))


def _missing_before(path: Path) -> tuple[int, dict[str, int]]:
    cols = [
        "sales", "transactions", "dcoilwtico", "sales_lag_1", "sales_lag_7",
        "sales_lag_14", "sales_lag_28", "sales_rolling_mean_7",
        "sales_rolling_mean_14", "sales_rolling_mean_28", "sales_rolling_std_7",
        "transactions_lag_1", "transactions_lag_7",
    ]
    rows, missing = 0, Counter()
    for part in pd.read_csv(path, usecols=cols, chunksize=200_000):
        rows += len(part)
        missing.update(part.isna().sum().to_dict())
    return rows, dict(missing)


def _holiday_map(root: Path, stores: pd.DataFrame) -> dict[tuple[str, int], str]:
    by_date: dict[str, list[dict[str, str]]] = {}
    for event in _reader(root / "data/raw/holidays_events.csv"):
        by_date.setdefault(event["date"], []).append(event)
    store_info = {
        int(row.store_nbr): (str(row.city), str(row.state))
        for row in stores.itertuples(index=False)
    }
    result = {}
    for day, events in by_date.items():
        for store, (city, state) in store_info.items():
            selected = []
            for event in events:
                if event["transferred"].strip().lower() == "true":
                    continue
                locale, name = event["locale"].strip(), event["locale_name"].strip()
                applies = (
                    locale == "National"
                    or (locale == "Regional" and name == state)
                    or (locale == "Local" and name == city)
                )
                if applies:
                    selected.append({
                        field: event[field]
                        for field in (
                            "date", "type", "locale", "locale_name", "description",
                            "transferred",
                        )
                    })
            if selected:
                selected.sort(key=lambda e: (e["locale"], e["locale_name"], e["type"], e["description"]))
                result[(day, store)] = json.dumps(
                    selected, ensure_ascii=False, separators=(",", ":")
                )
    return result


def _oil_asof(root: Path, target_dates: set[date]):
    source_values = {}
    for row in _reader(root / "data/raw/oil.csv"):
        if row["dcoilwtico"].strip():
            source_values[date.fromisoformat(row["date"])] = float(row["dcoilwtico"])
    source_dates = sorted(source_values)
    asof = {}
    for target in target_dates:
        cutoff = target - timedelta(days=OIL_BUFFER_DAYS)
        pos = bisect_right(source_dates, cutoff) - 1
        if pos >= 0:
            src_date = source_dates[pos]
            asof[target] = (source_values[src_date], src_date)
    return asof, source_values


def _sales_features(frame: pd.DataFrame):
    size = len(frame)
    names = (
        [f"sales_lag_{n}" for n in LAGS]
        + [f"sales_rolling_mean_{n}" for n in WINDOWS]
        + ["sales_rolling_std_7"]
    )
    output = {name: np.full(size, np.nan, dtype="float64") for name in names}
    mismatches = Counter()
    for _, group in frame.groupby(["store_nbr", "family"], sort=False, observed=True):
        group = group.sort_values("date")
        dates = pd.DatetimeIndex(group["date"])
        sales = pd.Series(group["sales"].to_numpy(dtype="float64"), index=dates)
        daily = sales.asfreq("D")
        pos = group.index.to_numpy()
        sales_by_date = sales.to_dict()
        grid = daily.to_numpy(dtype="float64")
        valid = np.isfinite(grid)
        clean = np.where(valid, grid, 0.0)
        prefix = np.concatenate(([0.0], np.cumsum(clean)))
        prefix_count = np.concatenate(([0], np.cumsum(valid.astype("int64"))))
        prefix_square = np.concatenate(([0.0], np.cumsum(clean * clean)))
        day_pos = (dates - daily.index[0]).days.to_numpy()

        for n in LAGS:
            name = f"sales_lag_{n}"
            vals = daily.shift(n).reindex(dates).to_numpy(dtype="float64")
            output[name][pos] = vals
            lookup_dates = dates - pd.Timedelta(days=n)
            independently_looked_up = np.array(
                [sales_by_date.get(day, np.nan) for day in lookup_dates], dtype="float64"
            )
            mismatches[name] += int((~np.isclose(
                vals, independently_looked_up, rtol=0, atol=1e-10, equal_nan=True
            )).sum())

        prior = daily.shift(1)
        for n in WINDOWS:
            name = f"sales_rolling_mean_{n}"
            vals = prior.rolling(n, min_periods=1).mean().reindex(dates).to_numpy(dtype="float64")
            output[name][pos] = vals
            starts = np.maximum(0, day_pos - n)
            sums = prefix[day_pos] - prefix[starts]
            counts = prefix_count[day_pos] - prefix_count[starts]
            reference = np.divide(
                sums, counts, out=np.full(len(counts), np.nan), where=counts > 0
            )
            mismatches[name] += int((~np.isclose(
                vals, reference, rtol=1e-10, atol=1e-10, equal_nan=True
            )).sum())

        vals = prior.rolling(7, min_periods=2).std(ddof=1).reindex(dates).to_numpy(dtype="float64")
        output["sales_rolling_std_7"][pos] = vals
        starts = np.maximum(0, day_pos - 7)
        sums = prefix[day_pos] - prefix[starts]
        counts = prefix_count[day_pos] - prefix_count[starts]
        sumsquares = prefix_square[day_pos] - prefix_square[starts]
        numerator = np.maximum(0.0, sumsquares - np.divide(
            sums * sums, counts, out=np.zeros(len(counts)), where=counts > 0
        ))
        variance = np.divide(
            numerator, counts - 1, out=np.full(len(counts), np.nan), where=counts > 1
        )
        reference = np.sqrt(variance)
        mismatches["sales_rolling_std_7"] += int((~np.isclose(
            vals, reference, rtol=1e-8, atol=1e-4, equal_nan=True
        )).sum())
    return output, dict(mismatches)


def build_corrected_dataset(root: Path | None = None):
    root = (root or Path(__file__).resolve().parents[2]).resolve()
    old_path = root / "data/processed/retailiq_leakage_safe.csv"
    output = root / "data/processed/retailiq_leakage_safe_corrected.csv"
    report_path = root / "logs/leakage_correction_validation_report.md"
    if output.exists() or report_path.exists():
        raise FileExistsError("Refusing to overwrite an existing corrected dataset or report")

    before_rows, before_missing = _missing_before(old_path)
    frame = pd.read_csv(root / "data/raw/train.csv", parse_dates=["date"], dtype={"family": "category"})
    source_rows = len(frame)
    frame["store_nbr"] = frame["store_nbr"].astype("int16")
    stores = pd.read_csv(root / "data/raw/stores.csv")
    stores["store_nbr"] = stores["store_nbr"].astype("int16")
    frame = frame.merge(stores, on="store_nbr", how="left", validate="many_to_one", sort=False)
    frame = frame.reset_index(drop=True)

    frame["year"] = frame.date.dt.year.astype("int16")
    frame["month"] = frame.date.dt.month.astype("int8")
    frame["day"] = frame.date.dt.day.astype("int8")
    frame["day_of_week"] = frame.date.dt.dayofweek.astype("int8")
    frame["week_of_year"] = frame.date.dt.isocalendar().week.astype("int8")
    frame["quarter"] = frame.date.dt.quarter.astype("int8")
    frame["is_weekend"] = (frame.day_of_week >= 5).astype("int8")
    frame["has_promotion"] = (frame.onpromotion > 0).astype("int8")

    holidays = _holiday_map(root, stores)
    keys = zip(frame.date.dt.strftime("%Y-%m-%d"), frame.store_nbr.astype(int))
    details = [holidays.get(key, "[]") for key in keys]
    frame["holiday_event_details"] = details
    frame["is_holiday_event"] = np.fromiter(
        (value != "[]" for value in details), dtype="int8", count=len(details)
    )

    oil_asof, oil_source = _oil_asof(root, {d.date() for d in frame.date.drop_duplicates()})
    mapped = frame.date.dt.date.map(oil_asof)
    frame["dcoilwtico"] = mapped.map(
        lambda pair: pair[0] if isinstance(pair, tuple) else np.nan
    ).astype("float64")
    frame["oil_source_date"] = mapped.map(
        lambda pair: pair[1].isoformat() if isinstance(pair, tuple) else None
    )

    features, feature_mismatches = _sales_features(frame)
    for name, vals in features.items():
        frame[name] = vals

    # Same-day and unaudited lagged transactions are not model features.
    frame = frame.drop(columns=["transactions", "transactions_lag_1", "transactions_lag_7"], errors="ignore")
    columns = [
        "id", "date", "store_nbr", "family", "sales", "onpromotion", "city", "state",
        "type", "cluster", "dcoilwtico", "oil_source_date", "is_holiday_event",
        "holiday_event_details", "year", "month", "day", "day_of_week", "week_of_year",
        "quarter", "is_weekend", "has_promotion", "sales_lag_1", "sales_lag_7",
        "sales_lag_14", "sales_lag_28", "sales_rolling_mean_7", "sales_rolling_mean_14",
        "sales_rolling_mean_28", "sales_rolling_std_7",
    ]
    frame = frame[columns]
    if len(frame) != before_rows or len(frame) != source_rows:
        raise AssertionError(f"Row count changed: old={before_rows}, source={source_rows}, new={len(frame)}")
    duplicate_keys = int(frame.duplicated(["date", "store_nbr", "family"]).sum())
    if duplicate_keys:
        raise AssertionError(f"Found {duplicate_keys} duplicate prediction keys")
    if any("transactions" in col for col in frame.columns):
        raise AssertionError("A transaction-derived feature remains")
    if any(feature_mismatches.values()):
        raise AssertionError(f"Calendar feature mismatches: {feature_mismatches}")

    oil_cutoff_failures = oil_value_failures = 0
    oil_rows = frame[["date", "dcoilwtico", "oil_source_date"]].drop_duplicates()
    for row in oil_rows.itertuples(index=False):
        if pd.isna(row.oil_source_date):
            oil_value_failures += int(not pd.isna(row.dcoilwtico))
        else:
            src = date.fromisoformat(row.oil_source_date)
            oil_cutoff_failures += int(src > row.date.date() - timedelta(days=OIL_BUFFER_DAYS))
            oil_value_failures += int(row.dcoilwtico != oil_source[src])
    if oil_cutoff_failures or oil_value_failures:
        raise AssertionError(f"Oil checks failed: cutoff={oil_cutoff_failures}, values={oil_value_failures}")

    holiday_failures = sum(
        int(int(flag) != int(bool(json.loads(value))))
        for flag, value in zip(frame.is_holiday_event, frame.holiday_event_details)
    )
    if holiday_failures:
        raise AssertionError(f"Holiday flag/detail mismatches: {holiday_failures}")

    temp = output.with_suffix(output.suffix + ".tmp")
    if temp.exists():
        raise FileExistsError(f"Refusing to overwrite {temp}")
    try:
        frame.to_csv(temp, index=False, date_format="%Y-%m-%d")
        os.rename(temp, output)
    except Exception:
        if temp.exists():
            temp.unlink()
        raise

    # Validate the serialized artifact in chunks, including the cutoff provenance.
    header = pd.read_csv(output, nrows=0).columns.tolist()
    if any("transactions" in col for col in header):
        raise AssertionError("Persisted CSV contains a transaction feature")
    persisted_rows = persisted_oil_failures = persisted_holiday_failures = 0
    for chunk in pd.read_csv(
        output,
        usecols=["date", "dcoilwtico", "oil_source_date", "is_holiday_event", "holiday_event_details"],
        parse_dates=["date"], chunksize=200_000,
    ):
        persisted_rows += len(chunk)
        source_dates = pd.to_datetime(chunk["oil_source_date"], errors="coerce")
        future = source_dates.notna() & source_dates.gt(chunk["date"] - pd.Timedelta(days=OIL_BUFFER_DAYS))
        expected_oil = source_dates.dt.date.map(oil_source).to_numpy(dtype="float64")
        oil_values = chunk["dcoilwtico"].to_numpy(dtype="float64")
        oil_equal = np.isclose(oil_values, expected_oil, rtol=0, atol=1e-10, equal_nan=True)
        persisted_oil_failures += int(future.sum()) + int((~oil_equal).sum())
        persisted_holiday_failures += int((
            chunk.is_holiday_event.astype(int) != chunk.holiday_event_details.ne("[]").astype(int)
        ).sum())
    if persisted_rows != len(frame) or persisted_oil_failures or persisted_holiday_failures:
        raise AssertionError(
            f"Persisted checks failed: rows={persisted_rows}, oil={persisted_oil_failures}, holidays={persisted_holiday_failures}"
        )

    after_missing = {k: int(v) for k, v in frame.isna().sum().items() if v}
    report = [
        "# Corrected feature dataset: validation and leakage report", "",
        "## Changes made", "",
        "- Sales lag N uses the exact date target_date minus N calendar days. If that date is absent, including the four omitted Christmas dates, the lag is null; no older row is substituted.",
        "- Rolling features cover the prior N calendar dates and exclude the target date. Missing Christmas sales are skipped; means use available days (minimum one), and sample standard deviation needs at least two observations.",
        "- Oil comes from raw oil.csv. For target date D, use the latest nonmissing source date no later than D minus two calendar days; otherwise null. No future fill or interpolation is used.",
        "- Holiday applicability is National, matching Regional state, or matching Local city. Transferred-away rows are excluded from the flag. holiday_event_details preserves applicable source event fields as JSON; raw holidays_events.csv remains available.",
        "- Same-day transactions and unaudited transaction lags are omitted.", "",
        "## Row counts and missing values", "",
        f"- Existing processed before: {before_rows:,} rows.",
        f"- Raw train source: {source_rows:,} rows.",
        f"- Corrected in memory: {len(frame):,} rows; persisted and re-read: {persisted_rows:,} rows.",
        "- Row count is unchanged.", "",
        "Missing values in the previous processed data (selected columns):", "",
    ]
    report.extend(f"- {key}: {value:,}" for key, value in sorted(before_missing.items()))
    report += ["", "Missing values in the corrected data (nonzero columns):", ""]
    report.extend(f"- {key}: {value:,}" for key, value in sorted(after_missing.items()))
    report += [
        "", "## Validation results", "",
        f"- Unique date/store/family key: passed; duplicate count {duplicate_keys}.",
        f"- Calendar lag and rolling recomputation: passed; mismatch count {sum(feature_mismatches.values())}.",
        "- Lag sources are exact prior calendar dates; rolling windows exclude current and future sales.",
        f"- Oil values and cutoff: passed; source date is at most target date minus {OIL_BUFFER_DAYS} days; persisted row failures {persisted_oil_failures}.",
        f"- Holiday flag matches preserved applicable event details: passed; in-memory failures {holiday_failures}, persisted failures {persisted_holiday_failures}.",
        "- Same-day transactions, transaction lag columns, and all columns containing transactions are absent.",
        "- Persisted row count and output schema checks: passed.", "",
        "## Prediction cutoff assumption", "",
        "Daily one-day-ahead sales forecast per date + store_nbr + family. Cutoff is the start of target date D. Sales history must be from dates before D. Calendar values, store attributes, known holiday schedules, and planned promotions are assumed available. Since oil release timestamps are missing, oil source dates are restricted to D minus two days or earlier. Target-day sales and transactions are unavailable.",
        "", "## Final model feature list", "",
    ]
    report.extend(f"- {name}" for name in MODEL_FEATURES)
    report += [
        "", "sales is the label. id and date are retained as row/time keys. oil_source_date and holiday_event_details are audit/provenance columns, not model inputs.",
        "", "## Remaining issues", "",
        "- The four Christmas target dates are absent from the source training data; exact-date lags are null where the requested source date is missing.",
        "- Oil release timestamps are unavailable; the two-day buffer is conservative but actual publication time cannot be proven from these files.",
        "- Transaction features remain excluded until prior-day availability is established.",
        "- Holiday matching uses the source locale names and store city/state strings; no separate jurisdiction table is provided.", "",
    ]
    report_path.write_text("\n".join(report), encoding="utf-8")
    return output, report_path


if __name__ == "__main__":
    dataset, report = build_corrected_dataset()
    print(f"Corrected dataset: {dataset}")
    print(f"Validation report: {report}")
