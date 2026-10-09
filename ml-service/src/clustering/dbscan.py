"""Reproducible DBSCAN clustering of historical store-family demand profiles."""

from __future__ import annotations

import json
import math
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from sklearn.cluster import DBSCAN
from sklearn.decomposition import PCA
from sklearn.metrics import silhouette_score
from sklearn.neighbors import NearestNeighbors
from sklearn.preprocessing import StandardScaler


FEATURE_COLUMNS = [
    "mean_sales_log1p", "std_sales_log1p", "cv_sales_log1p", "zero_sales_fraction",
    "recent_mean_sales_log1p", "recent_vs_overall_log_ratio",
    *[f"weekday_{day}_sales_index" for day in range(7)],
    *[f"month_{month:02d}_sales_index" for month in range(1, 13)],
    "promotion_rate", "promotion_log_lift", "promotion_log_lift_missing",
]
DEFAULT_INPUT = Path(__file__).resolve().parents[2] / "data/processed/retailiq_leakage_safe_corrected.csv"
DEFAULT_OUTPUT = Path(__file__).resolve().parents[2] / "logs/dbscan"
DEFAULT_MODELS = Path(__file__).resolve().parents[2] / "models"


def build_behavior_profiles(
    source: str | Path | pd.DataFrame,
    recent_days: int = 365,
    minimum_promotion_rows: int = 20,
) -> pd.DataFrame:
    """Return exactly one historical behavior profile per store-family pair."""
    needed = ["date", "store_nbr", "family", "sales", "onpromotion"]
    if isinstance(source, pd.DataFrame):
        daily = source[needed].copy()
    else:
        daily = pd.read_csv(source, usecols=needed)
    daily["date"] = pd.to_datetime(daily["date"], errors="raise")
    daily["sales"] = pd.to_numeric(daily["sales"], errors="raise")
    daily["onpromotion"] = pd.to_numeric(daily["onpromotion"], errors="raise").fillna(0)
    if daily[needed].isna().any().any():
        raise ValueError("Required profile inputs contain missing values")
    if daily.duplicated(["date", "store_nbr", "family"]).any():
        raise ValueError("Input contains duplicate date/store/family rows")

    cutoff = daily["date"].max()
    recent_start = cutoff - pd.Timedelta(days=recent_days - 1)
    records = []
    group_columns = ["store_nbr", "family"]
    for (store, family), group in daily.groupby(group_columns, sort=True, observed=True):
        group = group.sort_values("date")
        sales = group["sales"].astype(float)
        mean_sales = float(sales.mean())
        std_sales = float(sales.std(ddof=1)) if len(sales) > 1 else 0.0
        zero_fraction = float((sales == 0).mean())
        recent = sales[group["date"] >= recent_start]
        recent_mean = float(recent.mean()) if len(recent) else float("nan")
        safe_mean = max(mean_sales, 0.0)
        cv = std_sales / mean_sales if mean_sales > 0 else 0.0

        row = {
            "store_nbr": int(store),
            "family": str(family),
            "observation_count": int(len(group)),
            "profile_start_date": group["date"].min().date().isoformat(),
            "profile_end_date": group["date"].max().date().isoformat(),
            "mean_sales": mean_sales,
            "std_sales": std_sales,
            "coefficient_of_variation": cv,
            "zero_sales_fraction": zero_fraction,
            "recent_mean_sales": recent_mean,
            "recent_vs_overall_log_ratio": math.log1p(max(recent_mean, 0.0)) - math.log1p(safe_mean)
            if np.isfinite(recent_mean) else float("nan"),
            "mean_sales_log1p": math.log1p(safe_mean),
            "std_sales_log1p": math.log1p(max(std_sales, 0.0)),
            "cv_sales_log1p": math.log1p(max(cv, 0.0)),
            "recent_mean_sales_log1p": math.log1p(max(recent_mean, 0.0))
            if np.isfinite(recent_mean) else float("nan"),
        }

        overall_reference = mean_sales if mean_sales > 0 else 0.0
        weekday_means = group.groupby(group["date"].dt.dayofweek, observed=True)["sales"].mean()
        month_means = group.groupby(group["date"].dt.month, observed=True)["sales"].mean()
        for weekday in range(7):
            value = weekday_means.get(weekday, np.nan)
            row[f"weekday_{weekday}_sales_index"] = (
                float(value / overall_reference) if np.isfinite(value) and overall_reference > 0 else 0.0
            )
        for month in range(1, 13):
            value = month_means.get(month, np.nan)
            row[f"month_{month:02d}_sales_index"] = (
                float(value / overall_reference) if np.isfinite(value) and overall_reference > 0 else 0.0
            )

        promoted = group["onpromotion"] > 0
        yes, no = group.loc[promoted, "sales"], group.loc[~promoted, "sales"]
        row["promotion_rate"] = float(promoted.mean())
        safe_promotion = len(yes) >= minimum_promotion_rows and len(no) >= minimum_promotion_rows
        row["promotion_log_lift_missing"] = int(not safe_promotion)
        row["promotion_log_lift"] = (
            math.log1p(max(float(yes.mean()), 0.0)) - math.log1p(max(float(no.mean()), 0.0))
            if safe_promotion else float("nan")
        )
        records.append(row)

    profiles = pd.DataFrame.from_records(records)
    if profiles.duplicated(group_columns).any():
        raise AssertionError("Profile generation did not produce unique store-family rows")
    return profiles


def prepare_feature_matrix(profiles: pd.DataFrame):
    """Median-impute profile features, then StandardScale only behavior columns."""
    absent = sorted(set(FEATURE_COLUMNS) - set(profiles.columns))
    if absent:
        raise ValueError(f"Missing clustering feature columns: {absent}")
    raw = profiles[FEATURE_COLUMNS].replace([np.inf, -np.inf], np.nan).copy()
    medians = raw.median().fillna(0.0)
    imputed = raw.fillna(medians)
    if imputed.isna().any().any() or not np.isfinite(imputed.to_numpy(dtype=float)).all():
        raise ValueError("Feature imputation did not produce a finite matrix")
    scaler = StandardScaler()
    matrix = scaler.fit_transform(imputed.to_numpy(dtype=float))
    scaled = pd.DataFrame(matrix, columns=FEATURE_COLUMNS, index=profiles.index)
    return scaled, scaler, medians


def select_dbscan_parameters(matrix: np.ndarray, min_samples: int | None = None):
    """Select min_samples by dimensionality and eps at the normalized k-distance knee."""
    matrix = np.asarray(matrix, dtype=float)
    n_rows, n_features = matrix.shape
    if n_rows < 3:
        raise ValueError("DBSCAN parameter selection requires at least three profiles")
    if min_samples is None:
        # Common density rule: neighborhood includes the point and at least D other
        # dimensions' worth of support, so min_samples = D + 1.
        min_samples = max(5, n_features + 1)
    if min_samples >= n_rows:
        raise ValueError("min_samples must be smaller than the profile count")
    neighbors = NearestNeighbors(n_neighbors=min_samples, metric="euclidean")
    neighbors.fit(matrix)
    distances, _ = neighbors.kneighbors(matrix)
    k_distances = np.sort(distances[:, -1])
    x = np.linspace(0.0, 1.0, n_rows)
    span = float(k_distances[-1] - k_distances[0])
    if span <= 0:
        raise ValueError("All k-distances are equal; a k-distance knee cannot be selected")
    y = (k_distances - k_distances[0]) / span
    # Maximum normalized distance above the chord from the first to last point.
    knee_index = int(np.argmax(y - x))
    if knee_index <= 0 or knee_index >= n_rows - 1:
        raise ValueError("K-distance curve has no interior knee; refusing arbitrary eps")
    eps = float(k_distances[knee_index])
    if not np.isfinite(eps) or eps <= 0:
        raise ValueError("K-distance knee produced an invalid eps")
    metadata = {
        "min_samples_rule": "max(5, n_features + 1); dimension-based neighborhood support",
        "k_distance_neighbor_rank": min_samples,
        "k_distance_knee_index_zero_based": knee_index,
        "k_distance_knee_percentile": 100.0 * knee_index / (n_rows - 1),
        "k_distance_knee_distance": eps,
        "knee_method": "maximum normalized vertical distance above the chord joining sorted k-distance endpoints",
    }
    return eps, min_samples, k_distances, knee_index, metadata


def fit_dbscan(matrix: np.ndarray, eps: float, min_samples: int):
    model = DBSCAN(eps=float(eps), min_samples=int(min_samples), metric="euclidean")
    labels = model.fit_predict(np.asarray(matrix, dtype=float))
    return model, labels


def profile_clusters(profiles: pd.DataFrame, labels: np.ndarray) -> pd.DataFrame:
    if len(profiles) != len(labels):
        raise ValueError("Profile and label lengths differ")
    data = profiles[["mean_sales", "std_sales", "coefficient_of_variation",
                     "zero_sales_fraction", "recent_mean_sales", "promotion_rate",
                     "promotion_log_lift"]].copy()
    data["cluster"] = np.asarray(labels, dtype=int)
    rows = []
    for cluster, group in data.groupby("cluster", sort=True):
        rows.append({
            "cluster": int(cluster),
            "records": int(len(group)),
            "share": float(len(group) / len(data)),
            "mean_demand": float(group.mean_sales.mean()),
            "median_demand": float(group.mean_sales.median()),
            "mean_demand_std": float(group.std_sales.mean()),
            "median_cv": float(group.coefficient_of_variation.median()),
            "mean_zero_sales_fraction": float(group.zero_sales_fraction.mean()),
            "mean_recent_demand": float(group.recent_mean_sales.mean()),
            "mean_promotion_rate": float(group.promotion_rate.mean()),
            "mean_promotion_log_lift": float(group.promotion_log_lift.mean()),
        })
    return pd.DataFrame(rows).sort_values("cluster").reset_index(drop=True)


def evaluate_clusters(matrix: np.ndarray, labels: np.ndarray):
    labels = np.asarray(labels, dtype=int)
    total = len(labels)
    noise = int(np.count_nonzero(labels == -1))
    cluster_ids = sorted(set(labels) - {-1})
    sizes = {str(int(c)): int(np.count_nonzero(labels == c)) for c in cluster_ids}
    score, reason = None, None
    in_cluster = labels != -1
    if len(cluster_ids) >= 2 and int(in_cluster.sum()) > len(cluster_ids):
        score = float(silhouette_score(np.asarray(matrix)[in_cluster], labels[in_cluster], metric="euclidean"))
        reason = "computed on non-noise points because DBSCAN noise is not a coherent cluster"
    else:
        reason = "undefined: silhouette requires at least two non-noise clusters and more points than clusters"
    return {
        "n_profiles": int(total),
        "n_clusters_excluding_noise": int(len(cluster_ids)),
        "n_noise": noise,
        "noise_fraction": float(noise / total) if total else 0.0,
        "noise_percentage": float(100.0 * noise / total) if total else 0.0,
        "cluster_sizes_excluding_noise": sizes,
        "silhouette_score": score,
        "silhouette_note": reason,
    }


def _svg_plot(path: Path, title: str, body: list[str], width=1000, height=600):
    header = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
        '<rect width="100%" height="100%" fill="white"/>',
        '<style>text{font-family:Arial,sans-serif;fill:#243447}.title{font-size:20px;font-weight:bold}.small{font-size:11px}</style>',
        f'<text x="{width/2:.0f}" y="28" class="title" text-anchor="middle">{title}</text>',
    ]
    path.write_text("\n".join(header + body + ["</svg>"]), encoding="utf-8")


def _plot_k_distance(path: Path, distances: np.ndarray, knee: int, eps: float):
    width, height, left, right, top, bottom = 1000, 520, 70, 970, 55, 450
    vals = np.asarray(distances)
    lo, hi = float(vals.min()), float(vals.max())
    if hi == lo: hi = lo + 1
    def px(i): return left + (right-left) * i / max(1, len(vals)-1)
    def py(v): return bottom - (v-lo)/(hi-lo)*(bottom-top)
    pts = " ".join(f"{px(i):.1f},{py(v):.1f}" for i,v in enumerate(vals))
    body = [f'<line x1="{left}" y1="{bottom}" x2="{right}" y2="{bottom}" stroke="#657786"/>',
            f'<line x1="{left}" y1="{py(eps):.1f}" x2="{right}" y2="{py(eps):.1f}" stroke="#c44" stroke-dasharray="7,5"/>',
            f'<polyline points="{pts}" fill="none" stroke="#147d92" stroke-width="2"/>',
            f'<circle cx="{px(knee):.1f}" cy="{py(eps):.1f}" r="5" fill="#c44"/>',
            f'<text x="{right-5}" y="{py(eps)-8:.1f}" text-anchor="end" class="small">selected eps = {eps:.4f}</text>',
            f'<text x="{(left+right)/2}" y="495" text-anchor="middle" class="small">Sorted profile rank</text>',
            f'<text x="22" y="250" transform="rotate(-90 22 250)" class="small">Distance to min_samples-th neighbor</text>']
    _svg_plot(path,"DBSCAN k-distance curve with chord-knee selection",body,width,height)


def _plot_sizes(path: Path, profiles: pd.DataFrame):
    items=list(zip(profiles.cluster.astype(int).astype(str),profiles.records.astype(int)))
    width,height=850,max(180,100+len(items)*42); left,top=100,60; plotw=680
    maxv=max((v for _,v in items),default=1) or 1
    body=[]
    for i,(label,value) in enumerate(items):
        y=top+i*42; w=plotw*value/maxv
        body += [f'<text x="{left-10}" y="{y+19}" text-anchor="end" class="small">{label}</text>',
                 f'<rect x="{left}" y="{y}" width="{w:.1f}" height="24" fill="#147d92"/>',
                 f'<text x="{left+w+6:.1f}" y="{y+18}" class="small">{value:,}</text>']
    _svg_plot(path,"DBSCAN cluster sizes (-1 is noise)",body,width,height)


def _plot_profiles(path: Path, summary: pd.DataFrame):
    metrics=[("Mean demand", "mean_demand"),("Median CV","median_cv"),
             ("Zero fraction","mean_zero_sales_fraction"),("Recent mean","mean_recent_demand"),
             ("Promotion rate","mean_promotion_rate")]
    clusters=summary.cluster.astype(int).tolist()
    width,height=1100,600; left,top=90,80; groupw=190; barw=max(10,groupw//max(2,len(clusters)+1))
    body=[]; colors=["#147d92","#d17b0f","#6b69ad","#4b965b","#b44b4b","#606c78"]
    for j,(label,col) in enumerate(metrics):
        vals=summary[col].replace([np.inf,-np.inf],np.nan).fillna(0).to_numpy(dtype=float)
        scale=float(np.max(np.abs(vals))) or 1.0
        x0=left+j*groupw
        body.append(f'<text x="{x0+groupw/2}" y="{top-18}" text-anchor="middle" class="small">{label}</text>')
        for k,(cluster,val) in enumerate(zip(clusters,vals)):
            h=330*max(0,val)/scale; x=x0+10+k*barw; y=top+330-h
            body.append(f'<rect x="{x}" y="{y:.1f}" width="{barw-3}" height="{h:.1f}" fill="{colors[k%len(colors)]}"><title>Cluster {cluster}: {label} {val:.4g}</title></rect>')
        body.append(f'<line x1="{x0}" y1="{top+330}" x2="{x0+groupw-12}" y2="{top+330}" stroke="#657786"/>')
    legend_y=520
    for k,cluster in enumerate(clusters):
        x=left+k*130
        body += [f'<rect x="{x}" y="{legend_y}" width="12" height="12" fill="{colors[k%len(colors)]}"/>',
                 f'<text x="{x+17}" y="{legend_y+11}" class="small">Cluster {cluster}</text>']
    _svg_plot(path,"Cluster demand profile comparison (each metric scaled within its panel)",body,width,height)


def _plot_pca(path: Path, matrix: np.ndarray, labels: np.ndarray):
    pca=PCA(n_components=2,svd_solver="full")
    xy=pca.fit_transform(matrix)
    colors={-1:"#8b9298"}
    palette=["#147d92","#d17b0f","#6b69ad","#4b965b","#b44b4b","#48a4cf","#ad5c9b","#849b38"]
    for label in sorted(set(labels)-{-1}): colors[int(label)]=palette[int(label)%len(palette)]
    xmin,xmax=xy[:,0].min(),xy[:,0].max(); ymin,ymax=xy[:,1].min(),xy[:,1].max()
    if xmax==xmin:xmax=xmin+1
    if ymax==ymin:ymax=ymin+1
    left,right,top,bottom=70,920,60,490; body=[]
    for (x0,y0),label in zip(xy,labels):
        x=left+(right-left)*(x0-xmin)/(xmax-xmin); y=bottom-(bottom-top)*(y0-ymin)/(ymax-ymin)
        body.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="3" fill="{colors[int(label)]}" opacity="0.75"/>')
    body += [f'<text x="{(left+right)/2}" y="535" text-anchor="middle" class="small">PC1 ({pca.explained_variance_ratio_[0]:.1%} variance)</text>',
             f'<text x="20" y="270" transform="rotate(-90 20 270)" class="small">PC2 ({pca.explained_variance_ratio_[1]:.1%} variance)</text>']
    legend_x=70
    for label in sorted(set(labels)):
        body += [f'<circle cx="{legend_x}" cy="570" r="5" fill="{colors[int(label)]}"/>',
                 f'<text x="{legend_x+10}" y="574" class="small">{int(label)}</text>']
        legend_x+=90
    _svg_plot(path,"PCA projection for visualization only (DBSCAN fitted in full scaled space)",body,1000,610)


def run_pipeline(input_path: Path = DEFAULT_INPUT, output_dir: Path = DEFAULT_OUTPUT,
                 model_dir: Path = DEFAULT_MODELS):
    output_dir, model_dir = Path(output_dir), Path(model_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    expected = [
        "store_family_behavior_features.csv", "store_family_scaled_features.csv",
        "store_family_cluster_assignments.csv", "store_family_cluster_profiles.csv",
        "dbscan_evaluation.json", "k_distance.svg", "cluster_sizes.svg",
        "cluster_profiles.svg", "pca_clusters.svg", "report.md",
    ]
    model_paths = [model_dir/"store_family_dbscan_scaler.joblib",
                   model_dir/"store_family_dbscan_imputation.joblib",
                   model_dir/"store_family_dbscan_model.joblib"]
    if any((output_dir/name).exists() for name in expected) or any(p.exists() for p in model_paths):
        raise FileExistsError("Refusing to overwrite an existing clustering artifact")

    profiles=build_behavior_profiles(input_path)
    if len(profiles) != profiles[["store_nbr","family"]].drop_duplicates().shape[0]:
        raise AssertionError("Expected exactly one row per store-family pair")
    scaled, scaler, imputation=prepare_feature_matrix(profiles)
    matrix=scaled.to_numpy(dtype=float)
    eps,min_samples,kdist,knee,param_info=select_dbscan_parameters(matrix)
    model,labels=fit_dbscan(matrix,eps,min_samples)
    evaluation=evaluate_clusters(matrix,labels)
    cluster_profiles=profile_clusters(profiles,labels)
    assignment=profiles[["store_nbr","family"]].copy()
    assignment["cluster"] = labels
    assignment["profile_mean_sales"] = profiles["mean_sales"]
    assignment["profile_zero_sales_fraction"] = profiles["zero_sales_fraction"]

    output_dir.mkdir(parents=True,exist_ok=True); model_dir.mkdir(parents=True,exist_ok=True)
    profiles.to_csv(output_dir/expected[0],index=False)
    scaled_output=profiles[["store_nbr","family"]].copy()
    for col in scaled.columns: scaled_output[col]=scaled[col].to_numpy()
    scaled_output.to_csv(output_dir/expected[1],index=False)
    assignment.to_csv(output_dir/expected[2],index=False)
    cluster_profiles.to_csv(output_dir/expected[3],index=False)
    _plot_k_distance(output_dir/"k_distance.svg",kdist,knee,eps)
    _plot_sizes(output_dir/"cluster_sizes.svg",cluster_profiles)
    _plot_profiles(output_dir/"cluster_profiles.svg",cluster_profiles)
    _plot_pca(output_dir/"pca_clusters.svg",matrix,labels)
    joblib.dump(scaler,model_paths[0])
    joblib.dump(imputation,model_paths[1])
    joblib.dump(model,model_paths[2])

    label_to_profile={int(row.cluster):row for row in cluster_profiles.itertuples(index=False)}
    sizes="; ".join(f"{key}: {value}" for key,value in evaluation["cluster_sizes_excluding_noise"].items()) or "none"
    profile_lines=[]
    for row in cluster_profiles.itertuples(index=False):
        profile_lines.append(
            f"- Cluster {row.cluster}: n={row.records} ({row.share:.1%}); mean demand={row.mean_demand:.3g}; "
            f"median demand={row.median_demand:.3g}; median CV={row.median_cv:.3g}; "
            f"zero-sales fraction={row.mean_zero_sales_fraction:.1%}; recent mean={row.mean_recent_demand:.3g}; "
            f"promotion rate={row.mean_promotion_rate:.1%}."
        )
    noise_row = cluster_profiles.loc[cluster_profiles.cluster == -1]
    nonnoise_rows = cluster_profiles.loc[cluster_profiles.cluster != -1]
    if len(noise_row) and evaluation["noise_fraction"] > 0.5:
        noise_profile = noise_row.iloc[0]
        zero_profiles = nonnoise_rows[
            (nonnoise_rows.mean_demand == 0)
            & (nonnoise_rows.mean_zero_sales_fraction == 1)
        ]
        if len(zero_profiles):
            zero_n = int(zero_profiles.records.sum())
            interpretation = (
                f"High/Medium/Low labels are not justified. After inspecting profiles, one compact group is an exact zero-demand group ({zero_n} records); "
                f"the other discovered cluster(s) and {evaluation['noise_percentage']:.1f}% noise do not form a complete, ordered demand-tier partition. "
                f"Noise itself is heterogeneous (mean demand {noise_profile.mean_demand:.3g}, median {noise_profile.median_demand:.3g}, "
                f"median CV {noise_profile.median_cv:.3g})."
            )
        else:
            interpretation = (
                f"High/Medium/Low labels are not justified: {evaluation['noise_percentage']:.1f}% of profiles are noise, "
                "so the discovered clusters do not form a complete demand-tier partition."
            )
    else:
        interpretation = (
            "Clusters were examined after fitting. Do not assign High/Medium/Low labels unless their observed demand profiles form a clear ordered tier structure; "
            "the full cluster profile table is the basis for that judgment."
        )
    eps_info=(f"eps={eps:.6f} selected at sorted k-distance rank {knee+1}/{len(kdist)} "
              f"({param_info['k_distance_knee_percentile']:.1f} percentile).")
    silhouette=(f"{evaluation['silhouette_score']:.4f}" if evaluation["silhouette_score"] is not None else "not valid")
    report=[
        "# DBSCAN demand-behavior clustering", "",
        f"Input: {input_path.as_posix()}. One profile per store_nbr + family; the daily corrected dataset was read-only. Profile sales end at the final date present in that dataset ({profiles.profile_end_date.max()}). No forecasting model was trained.", "",
        "## Feature construction and missingness", "",
        f"- Store-family records: {len(profiles):,}.",
        "- Overall features: log1p mean sales, log1p sales standard deviation, log1p coefficient of variation, zero-sales fraction, log1p recent 365-day mean, and recent-vs-overall log ratio.",
        "- Demand shape: each weekday and month-of-year mean divided by that profile's overall mean (zero-mean profiles receive zero indices).",
        "- Promotion: row-level promotion frequency and log1p promoted/non-promoted mean-sales difference. This is a historical association, not a causal response. Lift is only computed when each status has at least 20 observations.",
        f"- Missing values: promotion log-lift was missing before imputation for {int(profiles.promotion_log_lift_missing.sum()):,} profiles. It was median-imputed (0 if no finite median existed) and the missingness flag was retained. Other selected features were finite. Imputation medians are saved separately.",
        "- store_nbr and family are retained as row keys only; neither is in the scaled feature matrix. The daily sales target is not used as an individual future-period label; all profile statistics summarize historical rows in the supplied corrected training dataset.", "",
        "## Parameter selection and diagnostics", "",
        f"- Selected min_samples={min_samples}: max(5, number_of_features + 1) = max(5, {len(FEATURE_COLUMNS)} + 1), a dimension-based neighborhood support rule.",
        f"- Selected {eps_info} The k-distance is the distance to the min_samples-th neighbor including the point itself; eps uses the maximum normalized distance above the chord joining sorted curve endpoints. No hand-tuned eps was used.",
        f"- Number of clusters excluding noise: {evaluation['n_clusters_excluding_noise']}.",
        f"- Noise points: {evaluation['n_noise']:,} / {evaluation['n_profiles']:,} ({evaluation['noise_percentage']:.2f}%).",
        f"- Cluster sizes: {sizes}; noise is label -1.",
        f"- Silhouette: {silhouette} ({evaluation['silhouette_note']}).",
        "- PCA plot is a two-dimensional visualization only; DBSCAN ran on the full scaled profile matrix.", "",
        "## Cluster profiles", "", *profile_lines,
        "", "## Interpretation and design notes", "",
        interpretation,
        "Profiles summarize all historical observations in the corrected training file through its final date. If these clusters are used inside temporal model validation, regenerate them at each validation cutoff to avoid using later history.",
        "", "## Saved artifacts", "",
        "- store_family_behavior_features.csv: raw profile summaries and behavior features.",
        "- store_family_scaled_features.csv: identifiers plus the exact scaled matrix used by DBSCAN.",
        "- store_family_cluster_assignments.csv: one label and key per store-family.",
        "- store_family_cluster_profiles.csv and dbscan_evaluation.json: profile and quality summaries.",
        "- k_distance.svg, cluster_sizes.svg, cluster_profiles.svg, pca_clusters.svg.",
        "- models/store_family_dbscan_scaler.joblib, store_family_dbscan_imputation.joblib, and store_family_dbscan_model.joblib.",
        "- src/clustering/dbscan.py and tests/test_dbscan_clustering.py contain the reproducible implementation and focused tests.", "",
    ]
    (output_dir/"report.md").write_text("\n".join(report),encoding="utf-8")
    evaluation.update({"eps":eps,"min_samples":min_samples,"n_features":len(FEATURE_COLUMNS),
                       "feature_columns":FEATURE_COLUMNS,"parameter_selection":param_info})
    (output_dir/"dbscan_evaluation.json").write_text(json.dumps(evaluation,indent=2,allow_nan=False),encoding="utf-8")
    print(json.dumps(evaluation,indent=2))
    print("Cluster profiles:\n",cluster_profiles.to_string(index=False))
    return profiles,scaled,labels,cluster_profiles,evaluation


if __name__ == "__main__":
    run_pipeline()
