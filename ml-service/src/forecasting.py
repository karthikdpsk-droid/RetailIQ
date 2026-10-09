"""Leakage-safe one-day-ahead demand forecasting pipeline."""
from __future__ import annotations

import json
import time
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from sklearn.compose import ColumnTransformer
from sklearn.ensemble import AdaBoostRegressor, RandomForestRegressor
from sklearn.impute import SimpleImputer
from sklearn.linear_model import LinearRegression
from sklearn.metrics import mean_absolute_error, mean_squared_error, r2_score
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder
from sklearn.tree import DecisionTreeRegressor

FEATURES = ["store_nbr", "family", "onpromotion", "city", "state", "type", "cluster", "dcoilwtico", "is_holiday_event", "year", "month", "day", "day_of_week", "week_of_year", "quarter", "is_weekend", "has_promotion", "sales_lag_1", "sales_lag_7", "sales_lag_14", "sales_lag_28", "sales_rolling_mean_7", "sales_rolling_mean_14", "sales_rolling_mean_28", "sales_rolling_std_7"]
CATEGORICAL = ["family", "city", "state", "type", "cluster"]
TARGET = "sales"
KEYS = ["date", "store_nbr", "family"]
MAX_FIT_ROWS = 60_000


def load_data(path):
    df = pd.read_csv(path, usecols=list(dict.fromkeys(FEATURES + [TARGET] + KEYS)), parse_dates=["date"])
    missing = set(FEATURES + [TARGET] + KEYS) - set(df.columns)
    if missing:
        raise ValueError(f"Missing required columns: {sorted(missing)}")
    if df.duplicated(KEYS).any():
        raise ValueError("Duplicate date/store/family keys")
    return df.sort_values(KEYS, kind="stable").reset_index(drop=True)


def chronological_split(df):
    """Train through 2016-06-30; validate Jul-Dec 2016; test calendar 2017."""
    train = df[df.date < "2016-07-01"].copy()
    valid = df[(df.date >= "2016-07-01") & (df.date < "2017-01-01")].copy()
    test = df[df.date >= "2017-01-01"].copy()
    if min(map(len, (train, valid, test))) == 0:
        raise ValueError("Dataset does not cover the configured chronological periods")
    return train, valid, test


def make_preprocessor():
    numeric = [c for c in FEATURES if c not in CATEGORICAL]
    return ColumnTransformer([
        ("numeric", SimpleImputer(strategy="median", add_indicator=True, keep_empty_features=True), numeric),
        ("categorical", Pipeline([("impute", SimpleImputer(strategy="most_frequent")), ("encode", OneHotEncoder(handle_unknown="ignore"))]), CATEGORICAL),
    ])


def metrics(y, pred):
    return {"MAE": float(mean_absolute_error(y, pred)), "RMSE": float(mean_squared_error(y, pred) ** .5), "R2": float(r2_score(y, pred))}


def generate_forecasts(model_path, future_features):
    """Forecast supplied next-day feature rows; bounds are unavailable (not calibrated)."""
    artifact = joblib.load(model_path)
    frame = future_features.copy()
    required = set(artifact["features"] + ["date", "store_nbr", "family"])
    if required - set(frame.columns):
        raise ValueError(f"Missing forecast columns: {sorted(required - set(frame.columns))}")
    frame["date"] = pd.to_datetime(frame["date"])
    predictions = np.maximum(0, artifact["pipeline"].predict(frame[artifact["features"]]))
    result = frame[["date", "store_nbr", "family"]].copy()
    result["forecast_date"] = result.pop("date")
    result["predicted_demand"] = predictions
    result["lower_bound"] = np.nan
    result["upper_bound"] = np.nan
    return result


def baseline_predictions(train, frame):
    # Use exact lag-1 where present. If the prior calendar date is absent,
    # search backward for the last observed demand strictly before the target.
    last = frame["sales_lag_1"].to_numpy(dtype=float).copy()
    missing_rows = np.flatnonzero(~np.isfinite(last))
    if len(missing_rows):
        histories = {key: group.sort_values("date") for key, group in train.groupby(["store_nbr", "family"], observed=True)}
        for pos in missing_rows:
            row = frame.iloc[pos]
            group = histories.get((row.store_nbr, row.family))
            if group is None:
                continue
            dates = group.date.to_numpy(dtype="datetime64[ns]")
            prior = np.searchsorted(dates, row.date.to_datetime64(), side="left") - 1
            if prior >= 0:
                last[pos] = float(group.sales.iloc[prior])
    return {"last_available": last,
            "seasonal_lag_7": frame["sales_lag_7"].to_numpy(dtype=float)}


def run(root=None):
    root = Path(root or Path(__file__).resolve().parents[1])
    data_path = root / "data/processed/retailiq_leakage_safe_corrected.csv"
    out = root / "logs/forecasting"
    out.mkdir(parents=True, exist_ok=True)
    df = load_data(data_path)
    train, valid, test = chronological_split(df)
    # Common subset so baselines and learned candidates are directly comparable.
    valid_compare = valid[valid["sales_lag_1"].notna() & valid["sales_lag_7"].notna()].copy()
    # Keep the full chronological windows for evaluation, but use a fixed-seed
    # representative training sample to keep sparse one-hot models tractable.
    fit_train = train.sample(n=min(MAX_FIT_ROWS, len(train)), random_state=42).sort_index()
    # Missing demand history indicates a warm-up or omitted calendar date. Keep it
    # missing and impute using training medians; never reinterpret it as zero.
    results = []
    for name, pred in baseline_predictions(df, valid_compare).items():
        ok = np.isfinite(pred)
        row = {"model": name, **metrics(valid_compare.loc[ok, TARGET], pred[ok]), "features": "historical sales only", "parameters": "naive", "training_time_seconds": 0.0, "decision": "baseline"}
        results.append(row)
    estimators = {
        "Linear Regression": LinearRegression(n_jobs=-1),
        "Random Forest": RandomForestRegressor(n_estimators=40, max_depth=20, min_samples_leaf=3, n_jobs=-1, random_state=42),
        "AdaBoost": AdaBoostRegressor(estimator=DecisionTreeRegressor(max_depth=6), n_estimators=20, learning_rate=.05, random_state=42),
    }
    try:
        from xgboost import XGBRegressor
        estimators["XGBoost"] = XGBRegressor(n_estimators=80, max_depth=7, learning_rate=.05, subsample=.8, colsample_bytree=.8, objective="reg:squarederror", n_jobs=-1, random_state=42)
    except ImportError:
        estimators["XGBoost"] = None
    valid_preds = {}
    for name, estimator in estimators.items():
        if estimator is None:
            results.append({"model": name, "MAE": np.nan, "RMSE": np.nan, "R2": np.nan, "features": ";".join(FEATURES), "parameters": "unavailable: install xgboost", "training_time_seconds": np.nan, "decision": "not run"})
            continue
        pipe = Pipeline([("preprocess", make_preprocessor()), ("model", estimator)])
        start = time.perf_counter()
        pipe.fit(fit_train[FEATURES], fit_train[TARGET])
        elapsed = time.perf_counter() - start
        pred = pipe.predict(valid_compare[FEATURES])
        valid_preds[name] = pred
        results.append({"model": name, **metrics(valid_compare[TARGET], pred), "features": ";".join(FEATURES), "parameters": json.dumps(estimator.get_params(), default=str), "training_time_seconds": elapsed, "decision": "candidate"})
    table = pd.DataFrame(results).sort_values("MAE", na_position="last")
    table.to_csv(out / "model_comparison.csv", index=False)
    supported = table[table.model.isin(valid_preds)].sort_values(["MAE", "RMSE"])
    if supported.empty:
        raise RuntimeError("No supported model completed validation")
    winner = str(supported.iloc[0].model)
    # No search on test: choose a small deterministic parameter comparison for RF,
    # the only high-capacity in-repository candidate, on the same validation slice.
    tuning = []
    if winner == "Random Forest":
        for trees, leaf in [(40, 3), (60, 4)]:
            model = RandomForestRegressor(n_estimators=trees, max_depth=20, min_samples_leaf=leaf, n_jobs=-1, random_state=42)
            pipe = Pipeline([("preprocess", make_preprocessor()), ("model", model)])
            pipe.fit(fit_train[FEATURES], fit_train[TARGET]); pred = pipe.predict(valid_compare[FEATURES])
            tuning.append({"n_estimators": trees, "min_samples_leaf": leaf, **metrics(valid_compare[TARGET], pred)})
        winner = min(tuning, key=lambda r: r["MAE"])["n_estimators"]
        leaf = min(tuning, key=lambda r: r["MAE"])["min_samples_leaf"]
        selected_name = "Random Forest"
        final_estimator = RandomForestRegressor(n_estimators=winner, max_depth=20, min_samples_leaf=leaf, n_jobs=-1, random_state=42)
        selected_metrics = next(item for item in tuning if item["n_estimators"] == winner and item["min_samples_leaf"] == leaf)
        results.append({"model": "Random Forest (tuned)", **{k: selected_metrics[k] for k in ("MAE", "RMSE", "R2")}, "features": ";".join(FEATURES), "parameters": json.dumps(final_estimator.get_params(), default=str), "training_time_seconds": np.nan, "decision": "selected after tuning"})
        table = pd.DataFrame(results).sort_values("MAE", na_position="last")
    else:
        selected_name = winner
        final_estimator = estimators[winner]
        tuning = [{"note": "No tuning performed; validation winner retained to limit search."}]
    # Refit after selection using train + validation; test remains untouched.
    history = pd.concat([train, valid], ignore_index=True)
    fit_history = history.sample(n=min(MAX_FIT_ROWS, len(history)), random_state=42).sort_index()
    final_pipe = Pipeline([("preprocess", make_preprocessor()), ("model", final_estimator)])
    final_pipe.fit(fit_history[FEATURES], fit_history[TARGET])
    test_pred = np.maximum(0, final_pipe.predict(test[FEATURES]))
    test_result = metrics(test[TARGET], test_pred)
    models = root / "models"; models.mkdir(exist_ok=True)
    artifact = models / "demand_forecast_v1.0.2.joblib"
    if artifact.exists():
        raise FileExistsError(f"Refusing to overwrite {artifact}")
    joblib.dump({"pipeline": final_pipe, "features": FEATURES, "categorical": CATEGORICAL, "version": "1.0.2", "model": selected_name, "train_end": str(history.date.max().date())}, artifact)
    reloaded = joblib.load(artifact)
    if reloaded["pipeline"].predict(test[FEATURES].head(5)).shape != (min(5, len(test)),):
        raise AssertionError("Reloaded model returned an invalid prediction shape")
    test_forecast = generate_forecasts(artifact, test.loc[:, list(dict.fromkeys(FEATURES + ["date"]))])
    test_forecast = test_forecast.merge(test[KEYS + [TARGET]].rename(columns={TARGET: "actual"}), left_on=["forecast_date", "store_nbr", "family"], right_on=KEYS, validate="one_to_one").drop(columns=["date"])
    test_forecast.to_csv(out / "forecast_validation.csv", index=False)
    test_forecast["error"] = test_forecast.predicted_demand - test_forecast.actual
    test_forecast.to_csv(out / "forecast_error_analysis.csv", index=False)
    test_detail = test.merge(test_forecast[["forecast_date", "store_nbr", "family", "predicted_demand"]], left_on=KEYS, right_on=["forecast_date", "store_nbr", "family"])
    test_detail["absolute_error"] = (test_detail[TARGET] - test_detail.predicted_demand).abs()
    test_detail["squared_error"] = (test_detail[TARGET] - test_detail.predicted_demand) ** 2
    q1, q2 = test_detail[TARGET].quantile([1 / 3, 2 / 3])
    test_detail["demand_tier"] = pd.cut(test_detail[TARGET], [-np.inf, q1, q2, np.inf], labels=["low", "medium", "high"], include_lowest=True)
    test_detail["promotion_group"] = np.where(test_detail.onpromotion > 0, "promoted", "not promoted")
    test_detail["holiday_group"] = np.where(test_detail.is_holiday_event > 0, "holiday", "non-holiday")
    breakdowns = []
    for column in ["store_nbr", "family", "demand_tier", "promotion_group", "holiday_group"]:
        groups = test_detail.groupby(column, observed=True)
        for label, group in groups:
            breakdowns.append({"dimension": column, "group": str(label), "rows": len(group), "MAE": group.absolute_error.mean(), "RMSE": np.sqrt(group.squared_error.mean())})
    pd.DataFrame(breakdowns).to_csv(out / "forecast_error_breakdowns.csv", index=False)
    pd.DataFrame(results).sort_values("MAE", na_position="last").to_csv(out / "model_comparison.csv", index=False)
    report = f"""# Model evaluation report\n\nPrediction: at the cutoff after date D, predict sales for D+1 per store and family using data available by the cutoff. The corrected data's lag construction is exact calendar based and excludes target-day sales.\n\nTrain: {train.date.min().date()} through {train.date.max().date()} ({len(train):,} rows; {len(fit_train):,} sampled rows fit); validation: {valid.date.min().date()} through {valid.date.max().date()} ({len(valid):,}; common comparison rows with both naive lags: {len(valid_compare):,}); final test: {test.date.min().date()} through {test.date.max().date()} ({len(test):,}). Final fit uses {len(fit_history):,} rows sampled from train plus validation, after selection; the test remained untouched until final evaluation.\n\nRows: {len(df):,}; date range: {df.date.min().date()} to {df.date.max().date()}; stores: {df.store_nbr.nunique()}; families: {df.family.nunique()}; duplicate keys: 0. Features: {len(FEATURES)}. Training samples are fixed-seed samples drawn only within the chronological training period; validation and test are full chronological windows. All validation model and baseline metrics use the same rows where lag-1 and lag-7 baselines are available.\n\n## Validation comparison\n\n```\n{table.to_string(index=False)}\n```\n\n## Test result\n\nSelected model: {selected_name}. Untouched test metrics: MAE {test_result['MAE']:.5f}; RMSE {test_result['RMSE']:.5f}; R2 {test_result['R2']:.5f}.\n\nMissing lag/rolling inputs remain null at feature creation and are median-imputed from training data, with missingness indicators. No target value or same-day transaction is an input. Holiday dates/store metadata and promotions are assumed known at the cutoff. No prediction intervals are emitted because calibrated bounds were not established.\n"""
    (out / "model_evaluation_report.md").write_text(report, encoding="utf-8")
    (out / "forecasting_setup_report.md").write_text(f"# Forecasting setup\n\nAt cutoff after D, forecast D+1 for each store_nbr + family; target sales. Input: `{data_path}`. Total rows {len(df):,}, range {df.date.min().date()} to {df.date.max().date()}, stores {df.store_nbr.nunique()}, families {df.family.nunique()}, unique keys confirmed. Exact chronological split and row counts are recorded in model_evaluation_report.md. Missing historical values are imputed from train medians with indicators; no zero fill. The existing store cluster attribute is retained; DBSCAN-derived assignments are excluded.\n", encoding="utf-8")
    (out / "final_model_report.md").write_text(f"# Final model\n\nSelected: {selected_name}, chosen by validation MAE and practical fit time. Test MAE {test_result['MAE']:.5f}; RMSE {test_result['RMSE']:.5f}; R2 {test_result['R2']:.5f}. Artifact: `{artifact}`. Initial validation metrics and the tuned comparison are in model_comparison.csv; tested tuning candidates: `{json.dumps(tuning, default=str)}`. Training used fixed-seed samples of at most {MAX_FIT_ROWS:,} rows; validation and test scoring used full eligible chronological rows.\n", encoding="utf-8")
    (out / "forecast_error_analysis.md").write_text("# Forecast error analysis\n\nPer-store, family, demand-tier based on actual test-set quantiles, promotion and holiday aggregates are in `forecast_error_breakdowns.csv`. Demand tiers are descriptive evaluation bins, not business labels. Predictions are clipped at zero; interval bounds remain unavailable because no calibration procedure was established.\n", encoding="utf-8")
    return table, test_result, artifact


if __name__ == "__main__":
    run()
