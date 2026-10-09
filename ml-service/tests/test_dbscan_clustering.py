import unittest

import numpy as np
import pandas as pd

from src.clustering.dbscan import (
    FEATURE_COLUMNS,
    build_behavior_profiles,
    evaluate_clusters,
    fit_dbscan,
    prepare_feature_matrix,
)


def synthetic_daily(days=60):
    dates = pd.date_range("2020-01-01", periods=days, freq="D")
    rows = []
    for store, family, scale in [(1, "GROCERY", 20.0), (2, "GROCERY", 200.0), (3, "BOOKS", 0.5)]:
        for i, day in enumerate(dates):
            rows.append({
                "date": day,
                "store_nbr": store,
                "family": family,
                "sales": 0.0 if i % 7 == 0 else scale + i % 5,
                "onpromotion": int(i % 2 == 0),
            })
    return pd.DataFrame(rows)


class DBSCANFeatureTests(unittest.TestCase):
    def test_one_profile_per_store_family_and_behavior_fields(self):
        profiles = build_behavior_profiles(synthetic_daily())
        self.assertEqual(len(profiles), 3)
        self.assertFalse(profiles.duplicated(["store_nbr", "family"]).any())
        self.assertEqual(set(profiles["store_nbr"]), {1, 2, 3})
        self.assertIn("weekday_0_sales_index", profiles)
        self.assertIn("month_01_sales_index", profiles)
        self.assertTrue((profiles["zero_sales_fraction"] > 0).all())

    def test_promotion_missingness_is_explicit(self):
        daily = synthetic_daily()
        daily["onpromotion"] = 0
        profiles = build_behavior_profiles(daily, minimum_promotion_rows=5)
        self.assertTrue(profiles["promotion_log_lift"].isna().all())
        self.assertTrue((profiles["promotion_log_lift_missing"] == 1).all())
        scaled, _, medians = prepare_feature_matrix(profiles)
        self.assertTrue(np.isfinite(scaled.to_numpy()).all())
        self.assertEqual(medians["promotion_log_lift"], 0.0)

    def test_scaling_and_identifier_exclusion(self):
        profiles = build_behavior_profiles(synthetic_daily())
        scaled, scaler, _ = prepare_feature_matrix(profiles)
        self.assertNotIn("store_nbr", FEATURE_COLUMNS)
        self.assertNotIn("family", FEATURE_COLUMNS)
        self.assertNotIn("id", FEATURE_COLUMNS)
        self.assertEqual(scaler.n_features_in_, len(FEATURE_COLUMNS))
        self.assertTrue(np.allclose(scaled.mean(axis=0).to_numpy(), 0.0, atol=1e-10))
        self.assertTrue(np.isfinite(scaled.to_numpy()).all())

    def test_dbscan_labels_and_diagnostics(self):
        matrix = np.array([
            [0.00, 0.00], [0.02, 0.00], [0.00, 0.02],
            [3.00, 3.00], [3.02, 3.00], [3.00, 3.02], [10.0, 10.0],
        ])
        model, labels = fit_dbscan(matrix, eps=0.1, min_samples=2)
        self.assertEqual(len(labels), len(matrix))
        self.assertEqual(set(labels), {-1, 0, 1})
        result = evaluate_clusters(matrix, labels)
        self.assertEqual(result["n_clusters_excluding_noise"], 2)
        self.assertEqual(result["n_noise"], 1)
        self.assertIsNotNone(result["silhouette_score"])
        self.assertEqual(len(model.labels_), len(matrix))


if __name__ == "__main__":
    unittest.main()
