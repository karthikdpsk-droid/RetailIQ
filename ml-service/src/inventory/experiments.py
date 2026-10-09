"""Reproducible inventory examples, sensitivity analysis, and historical simulation."""
from __future__ import annotations

import random
from collections import defaultdict
from datetime import date, timedelta, datetime, timezone
from pathlib import Path

import pandas as pd

from .optimization import (
    DEFAULT_CONFIG_PATH, estimate_demand_variability, load_config,
    recommend_inventory, stock_parameters,
)

ROOT = Path(__file__).resolve().parents[2]
LOG = ROOT / "logs" / "inventory"
FORECAST = ROOT / "logs" / "forecasting" / "forecast_validation.csv"
DATA = ROOT / "data" / "processed" / "retailiq_leakage_safe_corrected.csv"
MODEL_VERSION = "1.0.2"


def _history_rows(group: pd.DataFrame, cutoff: date, window: int) -> list[dict]:
    start = cutoff - timedelta(days=window - 1)
    rows = group[(group.date.dt.date >= start) & (group.date.dt.date <= cutoff)]
    return [{"date": d.date().isoformat(), "sales": float(v)} for d, v in zip(rows.date, rows.sales)]


def build_examples(config):
    cutoff = date(2025, 1, 28)
    hist = [{"date": (cutoff - timedelta(days=i)).isoformat(), "sales": float(80 + (i % 5) * 5)} for i in range(28, 0, -1)]
    base = {"store_nbr": 1, "family": "GROCERY", "prediction_cutoff": cutoff.isoformat(),
            "forecast_date": (cutoff + timedelta(days=1)).isoformat(),
            "historical_demand": hist, "lead_time_days": 7, "model_version": MODEL_VERSION}
    cases = [
        ("1 high forecast + low stock", {"forecast_demand": 120, "current_inventory": 10}),
        ("2 low forecast + high stock", {"forecast_demand": 5, "current_inventory": 500}),
        ("3 zero current stock", {"forecast_demand": 50, "current_inventory": 0}),
        ("4 inventory above target", {"forecast_demand": 20, "current_inventory": 1000}),
        ("5 no inventory supplied", {"forecast_demand": 50}),
    ]
    records = []
    fixed_time = datetime(2025, 1, 29, tzinfo=timezone.utc)
    for name, values in cases:
        result = recommend_inventory({**base, **values}, config, calculation_time=fixed_time)
        records.append({"scenario": name, **result})
    pd.DataFrame(records).to_csv(LOG / "example_recommendations.csv", index=False)
    return records


def run_sensitivity(config):
    cfg = load_config(config)
    history = [float(40 + ((i * 17) % 23)) for i in range(28)]
    base_sigma = estimate_demand_variability(history, window_days=28)
    rows = []
    for service in (0.90, 0.95, 0.99):
        for lead in (3, 7, 14):
            for variability_scale in (0.5, 1.0, 1.5):
                sigma = base_sigma * variability_scale
                p = stock_parameters(50, sigma, lead, service, cfg["review_period_days"])
                position = 25.0
                rows.append({"forecast_daily": 50, "service_level": service, "lead_time_days": lead,
                             "variability_scale": variability_scale, "demand_variability": sigma,
                             **p, "inventory_position": position,
                             "recommended_order_quantity": max(0.0, p["target_stock"] - position)})
    result = pd.DataFrame(rows)
    dest = LOG / "sensitivity"
    dest.mkdir(parents=True, exist_ok=True)
    result.to_csv(dest / "inventory_sensitivity.csv", index=False)
    return result


def simulate_backtest(config, series_count=24, seed=42):
    """Replay test forecasts with only prior observed sales driving decisions.

    A deterministic 24-series sample limits computation. Initial stock is
    synthetically set to the policy target at the first test date; this is a
    simulation condition, never a claim about actual inventory.
    """
    cfg = load_config(config)
    forecasts = pd.read_csv(FORECAST, parse_dates=["forecast_date"])
    history = pd.read_csv(DATA, usecols=["date", "store_nbr", "family", "sales"], parse_dates=["date"])
    forecasts = forecasts.sort_values(["store_nbr", "family", "forecast_date"])
    keys = sorted(set(zip(forecasts.store_nbr, forecasts.family)))
    rng = random.Random(seed)
    chosen = sorted(rng.sample(keys, min(series_count, len(keys))))
    history_groups = {key: group.sort_values("date") for key, group in history.groupby(["store_nbr", "family"], observed=True)}
    forecast_groups = {(s, f): g.sort_values("forecast_date") for (s, f), g in forecasts.groupby(["store_nbr", "family"], observed=True)}
    daily = []
    for key in chosen:
        fg = forecast_groups[key]
        hg = history_groups[key]
        first = fg.iloc[0]
        cutoff = first.forecast_date.date() - timedelta(days=1)
        hist = _history_rows(hg, cutoff, int(cfg["variability_window_days"]))
        start_rec = recommend_inventory({"store_nbr": key[0], "family": key[1],
            "forecast_demand": float(first.predicted_demand), "historical_demand": hist,
            "prediction_cutoff": cutoff.isoformat(), "lead_time_days": cfg["default_lead_time_days"]}, cfg)
        on_hand = float(start_rec["target_stock"] or 0.0)
        arrivals = defaultdict(float)
        for row in fg.itertuples(index=False):
            forecast_date = row.forecast_date.date()
            on_hand += arrivals.pop(forecast_date, 0.0)
            pipeline = sum(arrivals.values())
            cutoff = forecast_date - timedelta(days=1)
            hist = _history_rows(hg, cutoff, int(cfg["variability_window_days"]))
            rec = recommend_inventory({"store_nbr": key[0], "family": key[1],
                "forecast_demand": float(row.predicted_demand), "historical_demand": hist,
                "prediction_cutoff": cutoff.isoformat(), "lead_time_days": cfg["default_lead_time_days"],
                "current_inventory": on_hand, "on_order_inventory": pipeline,
                "backorder_quantity": 0.0}, cfg)
            order = rec["recommended_order_quantity"] or 0.0
            lead = int(rec["lead_time"])
            if order > 0:
                if lead == 0:
                    on_hand += order
                else:
                    arrivals[forecast_date + timedelta(days=lead)] += order
            actual = float(row.actual)
            available = on_hand
            fulfilled = min(available, actual)
            stockout = actual > available
            on_hand = max(0.0, available - fulfilled)
            next_review_demand = float(row.predicted_demand) * float(cfg["review_period_days"])
            daily.append({"store_nbr": key[0], "family": key[1], "date": forecast_date,
                "actual_demand": actual, "forecast_demand": float(row.predicted_demand),
                "fulfilled_demand": fulfilled, "stockout": stockout,
                "ending_on_hand": on_hand, "excess_inventory_proxy": max(0.0, on_hand - next_review_demand),
                "order_quantity": order, "reorder_event": order > 0})
    detail = pd.DataFrame(daily)
    detail.to_csv(LOG / "inventory_backtest_daily_sample.csv", index=False)
    total_demand = detail.actual_demand.sum()
    summary = {
        "simulation_scope": "synthetic policy replay on 24 deterministic store/family series from the 2017 held-out forecast period",
        "series_count": int(detail[["store_nbr", "family"]].drop_duplicates().shape[0]),
        "series_days": int(len(detail)),
        "synthetic_initial_stock": "policy target level at first simulated date; not observed inventory",
        "stockout_day_frequency": float(detail.stockout.mean()),
        "average_ending_inventory": float(detail.ending_on_hand.mean()),
        "fill_rate_proxy": float(detail.fulfilled_demand.sum() / total_demand) if total_demand > 0 else None,
        "average_excess_inventory_proxy": float(detail.excess_inventory_proxy.mean()),
        "reorder_events": int(detail.reorder_event.sum()),
        "total_recommended_order_quantity": float(detail.order_quantity.sum()),
        "default_lead_time_days": cfg["default_lead_time_days"],
        "service_level_setting": cfg["service_level"],
    }
    pd.DataFrame([summary]).to_csv(LOG / "inventory_backtest_summary.csv", index=False)
    return summary


def write_reports(config, examples, sensitivity, backtest):
    cfg = load_config(config)
    LOG.mkdir(parents=True, exist_ok=True)
    (LOG / "inventory_design.md").write_text(f"""# Inventory policy design\n\n## Inputs and units\n\nDemand forecast is next-day `sales` from the finalized RetailIQ model. In the source dataset, `sales` is a monetary sales value rather than item units. Until unit-level demand and matching stock units are available, calculations are in **sales-equivalent demand units**. Callers must supply inventory in matching units; this is not a physical SKU quantity recommendation. Demand forecast, variability, lead time, service level, on-hand, on-order, backorders, inventory position and order quantity remain distinct fields. DBSCAN is not imported or required.\n\n## Demand variability and safety stock\n\nVariability is sample standard deviation of observed daily demand over the trailing {cfg['variability_window_days']} calendar days ending at the supplied prediction cutoff. Dated observations after cutoff are excluded; missing dates are skipped, explicit zeros retained. Fewer than {cfg['minimum_history_observations']} observations means variability and downstream policy levels are unavailable. Intermittent demand is represented by the observed zero/nonzero values; with short history this estimate is unstable.\n\nFor daily forecast μ, historical daily sample standard deviation σ, lead time L, review period R, and normal quantile z for service level p: safety stock = zσ√L; reorder point = μL + safety stock; target = μ(L+R) + zσ√(L+R). Units are sales-equivalent demand over days. Assumptions: approximately independent daily forecast errors, stable daily forecast over the protection period, and a normal approximation. These are planning estimates, not exact optimal quantities.\n\n## Inventory and ordering\n\nInventory position = on-hand + on-order - backorders. Missing on-order/backorder components remain null in output and contribute zero only to arithmetic when on-hand is supplied; reports disclose this assumption. Without on-hand, status is `UNKNOWN / INVENTORY_DATA_REQUIRED` and order quantity is unavailable. With on-hand, order quantity is max(0, target - position), then configured MOQ and maximum-stock constraints apply. Conflicting constraints or max stock below reorder point fail closed.\n\nStatuses: `CRITICAL` below safety stock; `REORDER` at/below reorder point when an order is advised; `ADEQUATE` through target; `ABOVE_TARGET` above target; explicit `UNKNOWN / ...` values for missing inputs/conflicts.\n\n## Defaults\n\n{cfg}\n\nReview is daily by default. Lead times above the configured bound are rejected. Lead time zero yields zero lead-time safety stock and reorder demand, but target still covers review-period demand.\n""", encoding="utf-8")
    pd.DataFrame(examples).to_csv(LOG / "example_recommendations.csv", index=False)
    (LOG / "inventory_examples.md").write_text("# Inventory examples\n\nThe complete reproducible calculations and output fields are in `example_recommendations.csv`. They use a fixed 2025-01-29 timestamp, a 28-observation dated history, and the configured defaults. Scenarios cover high forecast/low stock, low forecast/high stock, zero stock, above-target stock, and no inventory supplied. They are examples, not live stock records.\n", encoding="utf-8")
    (LOG / "inventory_sensitivity_report.md").write_text(f"# Inventory sensitivity\n\nA deterministic 28-day demand history, forecast of 50 sales-equivalent units/day, and inventory position of 25 were used. The grid varies service levels 0.90/0.95/0.99, lead times 3/7/14 days, and demand SD multipliers 0.5/1.0/1.5. Full calculations are in `sensitivity/inventory_sensitivity.csv`. In this grid, safety stock ranges from 7.78 to 91.50, reorder point from 157.78 to 791.50, target stock from 208.98 to 844.71, and order quantity from 183.98 to 819.71 sales-equivalent units. Higher service level, lead time, and variability increase these buffers/targets. Lead time zero is separately covered by tests. These are synthetic sensitivity checks, not commercial outcomes.\n", encoding="utf-8")
    (LOG / "inventory_backtest_report.md").write_text(f"# Inventory policy simulation\n\n{backtest}\n\nThis is a historical policy replay on a deterministic sample from the 2017 held-out forecast period, not production performance. For each forecast date, recommendations use only historical sales dated no later than the prior day; that date's actual demand is read only after the decision to simulate fulfillment and then becomes available to later decisions. Initial stock was synthetically set to the calculated target for each series because true inventory is absent. Lost sales are used (no backorders), and scheduled orders arrive after configured lead time. Fill rate is fulfilled demand / actual demand. Stockout-day frequency is days with unmet demand / simulated series-days. Average excess inventory proxy is ending stock above forecast demand for the next review period. Detailed daily replay and aggregate CSVs are included alongside this report.\n", encoding="utf-8")
    backtest_md = "\n".join([
        f"- Scope: {backtest['simulation_scope']}.",
        f"- Series/days: {backtest['series_count']} series; {backtest['series_days']:,} series-days.",
        f"- Initial stock: {backtest['synthetic_initial_stock']}.",
        f"- Stockout-day frequency: {backtest['stockout_day_frequency']:.2%}.",
        f"- Average ending inventory: {backtest['average_ending_inventory']:.2f} sales-equivalent units.",
        f"- Fill-rate proxy: {backtest['fill_rate_proxy']:.2%} of realized demand.",
        f"- Average excess-inventory proxy: {backtest['average_excess_inventory_proxy']:.2f} sales-equivalent units.",
        f"- Reorder events: {backtest['reorder_events']:,}.",
        f"- Total recommended order quantity: {backtest['total_recommended_order_quantity']:,.2f} sales-equivalent units.",
        f"- Policy settings: {backtest['default_lead_time_days']} day lead; {backtest['service_level_setting']:.0%} service level.",
    ])
    (LOG / "inventory_backtest_report.md").write_text(
        "# Inventory policy simulation\n\n" + backtest_md +
        "\n\nThis is a historical policy replay on a deterministic sample from the 2017 held-out forecast period, not production performance. For each forecast date, recommendations use only sales dated no later than the prior day; that date's actual demand is read only after the decision, to simulate fulfillment, and is then available to later decisions. Initial stock was synthetically set to target because true inventory is absent. Lost sales are used (no backorders), and scheduled orders arrive after configured lead time. Fill rate is fulfilled demand / actual demand. Stockout-day frequency is days with unmet demand / simulated series-days. Excess inventory is a proxy: ending stock above forecast demand for the next review period. These simulation figures are not production performance. Detailed rows and the aggregate CSV are included alongside this report.\n",
        encoding="utf-8",
    )
    (LOG / "inventory_validation_report.md").write_text(f"# Inventory validation\n\n- Forecast source: `{FORECAST}` from demand model version {MODEL_VERSION}.\n- Corrected historical dataset is read-only input; no inventory source is present.\n- No current inventory was inferred. Missing on-hand returns `UNKNOWN / INVENTORY_DATA_REQUIRED`; order quantity remains unavailable.\n- Configuration: `{DEFAULT_CONFIG_PATH}`.\n- Defaults: lead {cfg['default_lead_time_days']} days; service {cfg['service_level']}; review {cfg['review_period_days']} day.\n- Output schema fields: {', '.join(__import__('src.inventory.optimization', fromlist=['RESULT_FIELDS']).RESULT_FIELDS)}.\n- Sensitivity rows: {len(sensitivity)}; examples: {len(examples)}; backtest series-days: {backtest['series_days']}.\n- Input validation rejects negative/non-finite demand or inventory, invalid service levels, and unsupported lead times.\n\nSee automated inventory tests for calculation, edge-case, and reproducibility checks.\n", encoding="utf-8")


def run():
    LOG.mkdir(parents=True, exist_ok=True)
    config = load_config(DEFAULT_CONFIG_PATH)
    examples = build_examples(config)
    sensitivity = run_sensitivity(config)
    backtest = simulate_backtest(config)
    write_reports(config, examples, sensitivity, backtest)
    print(f"Inventory reports and simulation written to {LOG}")


if __name__ == "__main__":
    run()
