import joblib
import numpy as np
import pandas as pd
import pytest
from sklearn.dummy import DummyRegressor
from sklearn.pipeline import Pipeline

from src.forecasting import (
    CATEGORICAL, FEATURES, chronological_split, generate_forecasts,
    make_preprocessor,
)
from src.features.feature_engineering import _sales_features


def sample():
    dates = pd.date_range("2016-01-01", periods=400)
    rows = []
    for d in dates:
        rows.append({"date": d, "store_nbr": 1, "family": "GROCERY", "sales": 5.,
                     "onpromotion": 0, "city": "Quito", "state": "Pichincha", "type": "A",
                     "cluster": "C", "dcoilwtico": np.nan, "is_holiday_event": 0,
                     "year": d.year, "month": d.month, "day": d.day, "day_of_week": d.dayofweek,
                     "week_of_year": int(d.isocalendar().week), "quarter": d.quarter,
                     "is_weekend": int(d.dayofweek >= 5), "has_promotion": 0,
                     "sales_lag_1": np.nan, "sales_lag_7": 5., "sales_lag_14": 5.,
                     "sales_lag_28": 5., "sales_rolling_mean_7": 5.,
                     "sales_rolling_mean_14": 5., "sales_rolling_mean_28": 5.,
                     "sales_rolling_std_7": np.nan})
    return pd.DataFrame(rows)


def test_chronological_split_disjoint_and_ordered():
    df = sample()
    train, valid, test = chronological_split(df)
    assert train.date.max() < valid.date.min() < test.date.min()
    assert train.date.max() < pd.Timestamp("2016-07-01")


def test_preprocessor_handles_missing_and_unseen_category():
    df = sample()
    prep = make_preprocessor()
    prep.fit(df[FEATURES].iloc[:200])
    changed = df[FEATURES].iloc[[201]].copy()
    changed.loc[:, "family"] = "UNSEEN"
    assert prep.transform(changed).shape[0] == 1


def test_reload_prediction_shape_nonnegative_and_next_day_output(tmp_path):
    df = sample()
    pipe = Pipeline([("preprocess", make_preprocessor()), ("model", DummyRegressor(strategy="constant", constant=-1))])
    pipe.fit(df[FEATURES], df.sales)
    path = tmp_path / "forecast.joblib"
    joblib.dump({"pipeline": pipe, "features": FEATURES, "version": "test"}, path)
    loaded = joblib.load(path)
    assert loaded["pipeline"].predict(df[FEATURES].head(3)).shape == (3,)
    forecast_row = df[FEATURES].tail(1).copy()
    forecast_row["date"] = df.date.iloc[-1]
    result = generate_forecasts(path, forecast_row)
    assert result.shape[0] == 1
    assert result.predicted_demand.iloc[0] == 0
    assert result.forecast_date.iloc[0] == df.date.iloc[-1]
    assert result.lower_bound.isna().all() and result.upper_bound.isna().all()


def test_prediction_cutoff_features_exclude_target_and_transactions():
    assert "sales" not in FEATURES
    assert "transactions" not in " ".join(FEATURES)
    assert not any("dbscan" in c.lower() for c in FEATURES)
    assert len(FEATURES) == 25


def test_fixed_seed_training_sample_is_reproducible():
    df = sample()
    first = df.sample(n=50, random_state=42).index.tolist()
    second = df.sample(n=50, random_state=42).index.tolist()
    assert first == second


def test_sales_lags_require_exact_calendar_date_and_rolling_excludes_target():
    frame = pd.DataFrame({
        "date": pd.to_datetime(["2016-01-01", "2016-01-02", "2016-01-04"]),
        "store_nbr": [1, 1, 1], "family": ["GROCERY"] * 3,
        "sales": [10., 20., 999.],
    })
    values, mismatches = _sales_features(frame)
    assert mismatches and not any(mismatches.values())
    assert np.isnan(values["sales_lag_1"][2])  # Jan 3 is absent; do not substitute Jan 2
    assert values["sales_rolling_mean_7"][2] == 15.  # only Jan 1-3; target's 999 is excluded
