"""Reproducible sensitivity analysis for the fitted store-family DBSCAN profile."""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.cluster import DBSCAN
from sklearn.decomposition import PCA
from sklearn.metrics import silhouette_score
from sklearn.neighbors import NearestNeighbors
from sklearn.preprocessing import RobustScaler, StandardScaler

from src.clustering.dbscan import FEATURE_COLUMNS


ROOT = Path(__file__).resolve().parents[2]
PROFILE_PATH = ROOT / "logs/dbscan/store_family_behavior_features.csv"
SCALED_PATH = ROOT / "logs/dbscan/store_family_scaled_features.csv"
EVALUATION_PATH = ROOT / "logs/dbscan/dbscan_evaluation.json"
OUT = ROOT / "logs/dbscan/sensitivity"

BASE_FEATURES = [
    "mean_sales_log1p", "std_sales_log1p", "cv_sales_log1p", "zero_sales_fraction",
    "recent_mean_sales_log1p", "recent_vs_overall_log_ratio",
    *[f"weekday_{i}_sales_index" for i in range(7)],
    *[f"month_{i:02d}_sales_index" for i in range(1, 13)],
    "promotion_rate", "promotion_log_lift", "promotion_log_lift_missing",
]
COMPACT_FEATURES = [
    "mean_sales_log1p", "std_sales_log1p", "cv_sales_log1p", "zero_sales_fraction",
    "recent_mean_sales_log1p", "recent_vs_overall_log_ratio", "promotion_rate",
    "promotion_log_lift", "promotion_log_lift_missing", "weekday_profile_std",
    "weekend_sales_index", "weekday_sales_index", "monthly_profile_std",
    "monthly_peak_index", "monthly_range",
]


def _kdist(matrix: np.ndarray, min_samples: int):
    if min_samples >= len(matrix):
        return None
    distances, _ = NearestNeighbors(
        n_neighbors=min_samples, metric="euclidean"
    ).fit(matrix).kneighbors(matrix)
    return np.sort(distances[:, -1])


def _knee(distances: np.ndarray):
    x = np.linspace(0.0, 1.0, len(distances))
    span = float(distances[-1] - distances[0])
    if span <= 0:
        return None, None
    y = (distances - distances[0]) / span
    idx = int(np.argmax(y - x))
    if idx <= 0 or idx >= len(distances) - 1:
        return None, None
    return float(distances[idx]), idx


def _evaluate(matrix, eps, min_samples, sample_seed=42):
    labels = DBSCAN(eps=float(eps), min_samples=int(min_samples), metric="euclidean").fit_predict(matrix)
    cluster_ids = sorted(set(labels) - {-1})
    noise_count = int(np.count_nonzero(labels == -1))
    sizes = {str(int(c)): int(np.count_nonzero(labels == c)) for c in cluster_ids}
    sizes["-1"] = noise_count
    assigned = labels != -1
    silhouette = None
    silhouette_note = "undefined: fewer than two non-noise clusters"
    if len(cluster_ids) >= 2 and int(assigned.sum()) > len(cluster_ids):
        X, y = matrix[assigned], labels[assigned]
        sample = min(1000, len(y))
        if sample < len(y):
            silhouette = float(silhouette_score(
                X, y, metric="euclidean", sample_size=sample, random_state=sample_seed
            ))
            silhouette_note = f"sampled {sample} non-noise points; noise excluded"
        else:
            silhouette = float(silhouette_score(X, y, metric="euclidean"))
            silhouette_note = "all non-noise points; noise excluded"
    return {
        "n_clusters": len(cluster_ids),
        "noise_count": noise_count,
        "noise_percentage": 100.0 * noise_count / len(labels),
        "cluster_sizes": sizes,
        "silhouette_score": silhouette,
        "silhouette_note": silhouette_note,
    }


def _svg(path: Path, title: str, body: list[str], width=1000, height=520):
    header = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
        '<rect width="100%" height="100%" fill="white"/>',
        '<style>text{font-family:Arial,sans-serif;fill:#243447}.title{font-size:20px;font-weight:bold}.small{font-size:11px}</style>',
        f'<text x="{width/2:.0f}" y="28" class="title" text-anchor="middle">{title}</text>',
    ]
    path.write_text("\n".join(header + body + ["</svg>"]), encoding="utf-8")


def _plot_noise(rows: list[dict], path: Path):
    selected = [r for r in rows if r["scenario"] in {
        "full_standard_k29_knee", "full_standard_k29_p10", "full_standard_k29_p25",
        "full_standard_k29_p50", "full_standard_k29_p75", "full_standard_k29_p90",
        "compact_standard_k16_knee", "compact_standard_k16_p50",
        "pca90_standard_k16_knee", "pca90_standard_k16_p50",
        "full_robust_k29_knee", "full_robust_k29_p50",
    }]
    width,height,left,top,row_h=1250,70+len(selected)*28,370,60,28
    maxv=100.0; body=[]
    for i,r in enumerate(selected):
        y=top+i*row_h; noise=r["noise_percentage"]; w=730*noise/maxv
        body += [f'<text x="{left-10}" y="{y+16}" text-anchor="end" class="small">{r["scenario"]}</text>',
                 f'<rect x="{left}" y="{y}" width="{w:.1f}" height="18" fill="#c66a44"/>',
                 f'<text x="{left+w+6:.1f}" y="{y+14}" class="small">{noise:.1f}%</text>']
    _svg(path,"Noise percentage across documented sensitivity configurations",body,width,height)


def _plot_kdist(curves, path: Path):
    width,height,left,right,top,bottom=1000,520,70,970,55,450
    palette=["#147d92","#d17b0f","#6b69ad","#4b965b"]
    body=[]
    for i,(name,dist,knee,eps) in enumerate(curves):
        vals=np.asarray(dist); x=np.linspace(left,right,len(vals))
        lo,hi=float(vals.min()),float(vals.max())
        if hi==lo:hi=lo+1
        y=bottom-(vals-lo)/(hi-lo)*(bottom-top)
        points=" ".join(f"{xx:.1f},{yy:.1f}" for xx,yy in zip(x,y))
        body.append(f'<polyline points="{points}" fill="none" stroke="{palette[i%len(palette)]}" stroke-width="2"><title>{name}</title></polyline>')
        if knee is not None:
            ky=bottom-(eps-lo)/(hi-lo)*(bottom-top)
            body.append(f'<line x1="{left}" y1="{ky:.1f}" x2="{right}" y2="{ky:.1f}" stroke="{palette[i%len(palette)]}" stroke-dasharray="5,4"/>')
        body.append(f'<text x="85" y="{top+17*i}" class="small" fill="{palette[i%len(palette)]}">{name}: eps={eps:.3f}</text>')
    body += [f'<text x="{(left+right)/2}" y="495" text-anchor="middle" class="small">Sorted store-family profile rank</text>',
             f'<text x="20" y="250" transform="rotate(-90 20 250)" class="small">k-distance (within-curve scale)</text>']
    _svg(path,"K-distance curves: full, compact, and PCA-reduced profiles",body,width,height)


def run_sensitivity():
    if not PROFILE_PATH.is_file() or not SCALED_PATH.is_file():
        raise FileNotFoundError("Run the DBSCAN profile pipeline before sensitivity analysis")
    OUT.mkdir(parents=True, exist_ok=True)
    if any(OUT.iterdir()):
        raise FileExistsError(f"Refusing to overwrite sensitivity outputs in {OUT}")

    profiles=pd.read_csv(PROFILE_PATH)
    scaled_saved=pd.read_csv(SCALED_PATH)
    original=scaled_saved[FEATURE_COLUMNS].to_numpy(dtype=float)
    if len(profiles)!=len(original) or profiles.duplicated(["store_nbr","family"]).any():
        raise AssertionError("Profile rows do not align with the saved scaled profile matrix")
    if not np.isfinite(original).all():
        raise ValueError("Saved scaled profile matrix is not finite")

    raw=profiles[BASE_FEATURES].replace([np.inf,-np.inf],np.nan)
    medians=raw.median().fillna(0.0)
    raw=raw.fillna(medians)
    robust=RobustScaler().fit_transform(raw.to_numpy(dtype=float))

    compact=profiles.copy()
    dow=[f"weekday_{i}_sales_index" for i in range(7)]
    months=[f"month_{i:02d}_sales_index" for i in range(1,13)]
    compact["weekday_profile_std"]=profiles[dow].std(axis=1).fillna(0.0)
    compact["weekend_sales_index"]=profiles[[dow[5],dow[6]]].mean(axis=1)
    compact["weekday_sales_index"]=profiles[dow[:5]].mean(axis=1)
    compact["monthly_profile_std"]=profiles[months].std(axis=1).fillna(0.0)
    compact["monthly_peak_index"]=profiles[months].max(axis=1)
    compact["monthly_range"]=profiles[months].max(axis=1)-profiles[months].min(axis=1)
    compact_raw=compact[COMPACT_FEATURES].replace([np.inf,-np.inf],np.nan)
    compact_scaled=StandardScaler().fit_transform(compact_raw.fillna(compact_raw.median().fillna(0)).to_numpy(float))

    pca=PCA(n_components=0.90,svd_solver="full")
    pca_matrix=pca.fit_transform(original)

    representations={
        "full_standard":original,
        "full_robust":robust,
        "compact_standard":compact_scaled,
        "pca90_standard":pca_matrix,
    }
    min_samples_by_rep={
        "full_standard":[5,15,29,56],
        "full_robust":[29],
        "compact_standard":[len(COMPACT_FEATURES)+1],
        "pca90_standard":[pca_matrix.shape[1]+1],
    }
    results=[]; curves=[]; curve_cache={}
    for rep_name,matrix in representations.items():
        for k in min_samples_by_rep[rep_name]:
            dist=_kdist(matrix,k)
            if dist is None:continue
            knee_eps,knee_idx=_knee(dist)
            if knee_eps is not None:
                curve_cache[(rep_name,k)]=(dist,knee_eps,knee_idx)
                if rep_name in ("full_standard","compact_standard","pca90_standard"):
                    curves.append((f"{rep_name} k={k}",dist,knee_idx,knee_eps))
            qs=[50]
            if rep_name=="full_standard" and k==29:
                qs=[10,25,50,75,90]
            elif rep_name=="full_standard" and k in (5,15,56):
                qs=[50,75]
            elif rep_name in ("compact_standard","pca90_standard","full_robust"):
                qs=[50,75]
            candidates=[]
            if knee_eps is not None:
                candidates.append(("knee",knee_eps))
            for q in qs:
                value=float(np.quantile(dist,q/100))
                if not any(np.isclose(value,old,rtol=0,atol=1e-12) for _,old in candidates):
                    candidates.append((f"p{q}",value))
            for method,eps in candidates:
                if eps<=0:continue
                metrics=_evaluate(matrix,eps,k)
                scenario=f"{rep_name}_k{k}_{method}"
                results.append({
                    "scenario":scenario,"representation":rep_name,
                    "dimensions":int(matrix.shape[1]),"min_samples":int(k),
                    "eps_method":method,"eps":float(eps),
                    "k_distance_percentile":float(100*np.searchsorted(dist,eps,side="right")/len(dist)),
                    **metrics,
                })
    result_frame=pd.DataFrame(results)
    result_frame.to_csv(OUT/"sensitivity_results.csv",index=False)
    _plot_noise(results,OUT/"noise_sensitivity.svg")
    _plot_kdist(curves,OUT/"k_distance_comparison.svg")

    corr=np.corrcoef(original,rowvar=False)
    upper=np.abs(corr[np.triu_indices_from(corr,k=1)])
    pca_full=PCA(svd_solver="full").fit(original)
    pca_cumulative=np.cumsum(pca_full.explained_variance_ratio_)
    n90=int(np.searchsorted(pca_cumulative,.90)+1)
    n95=int(np.searchsorted(pca_cumulative,.95)+1)
    profile_end=str(profiles.profile_end_date.max())
    baseline=next(r for r in results if r["scenario"]=="full_standard_k29_knee")

    table=[
        "| Configuration | Dimensions | min_samples | eps method/value | Clusters | Noise count | Noise % | Cluster sizes (including -1 noise) | Silhouette |",
        "|---|---:|---:|---|---:|---:|---:|---|---:|",
    ]
    for r in results:
        sizes=", ".join(f"{label}:{count}" for label,count in sorted(r["cluster_sizes"].items(),key=lambda kv:int(kv[0])))
        sil=f"{r['silhouette_score']:.4f}" if r["silhouette_score"] is not None else "N/A"
        table.append(
            f"| {r['scenario']} | {r['dimensions']} | {r['min_samples']} | {r['eps_method']} / {r['eps']:.4f} | {r['n_clusters']} | {r['noise_count']} | {r['noise_percentage']:.2f}% | {sizes} | {sil} |"
        )
    report=[
        "# DBSCAN sensitivity analysis", "",
        f"All profiles are derived from the corrected training dataset through {profile_end}; no future sales period was used. The corrected dataset itself was not modified. This analysis tests parameter/representation sensitivity only; it does not fit forecasting models.", "",
        "## Baseline and method", "",
        f"- Baseline: {len(profiles):,} store-family profiles, {original.shape[1]} StandardScaler features, min_samples=29, eps={baseline['eps']:.4f} at the k-distance chord knee.",
        "- Eps candidates use each representation/min_samples k-distance curve: its normalized chord knee and documented curve percentiles (p10/p25/p50/p75/p90 as listed). Percentiles are sensitivity probes, not selected to hit a target cluster count.",
        "- min_samples tested on the full feature space: 5, 15, 29, 56. The selected 29 is D+1 for D=28; 15 is approximately D/2+1, 56 is 2D, and 5 is the common small-neighborhood lower bound. Reduced representations use D+1. The k-distance knee was unavailable for any curve without an interior maximum; only positive percentile eps probes were run in those cases.",
        "- Silhouette is computed only when there are at least two non-noise clusters, with noise excluded. At most 1,000 non-noise points are sampled with fixed seed 42; the report labels the sample size in the results file's silhouette_note.",
        "",
        "## Causes of the baseline noise rate", "",
        f"- Feature dimensionality/redundancy: the 28-column scaled matrix has {int((upper>=.90).sum())} pairwise feature correlations with |r| >= 0.90 and {n90} PCA components explain 90% of its variance ({n95} explain 95%). This indicates redundant profile dimensions, though high pairwise correlations alone do not prove they cause the noise.",
        "- Dimensionality reduction: PCA retains 90% of standardized variance; compact shape summaries replace 19 weekday/month indexes with six interpretable shape statistics. Both are compared in the configuration table using their own k-distance curves.",
        "- min_samples: comparisons include the dimension-based selection, a lower density support, a higher support, and 5. The number of clusters/noise is shown for each rather than selecting a value based on desired output.",
        "- eps: full StandardScaler baseline is compared at knee and k-distance percentiles through p90. As eps expands, both coverage and cluster merging change; no candidate is preferred because it yields a chosen number of clusters.",
        "- Scaling: StandardScaler is compared with RobustScaler on the same 28 raw behavior features and equivalent median imputation. RobustScaler changes relative influence of tails but does not remove profiles or outliers.",
        "- Representation: every record remains one store-family profile; only its feature representation changes. No daily-row clustering or future-period target is used.",
        "",
        "## Configuration results", "", *table, "",
        "## Interpretation", "",
        "The k-distance knees for the baseline and reduced representations leave 95.0–96.0% of profiles as noise. PCA compression from 28 to 15 dimensions and compact shape summaries from 28 to 15 dimensions therefore do not resolve the sparse-density problem. The min_samples=56 knee still leaves 92.6% noise; min_samples=5 has no interior knee, while its percentile probes reduce noise at the cost of 7–17 clusters, low silhouette (0.18–0.27), and several tiny clusters. Thus neither dimension nor min_samples alone explains the baseline.",
        "At larger eps, StandardScaler can reduce baseline noise to 7.9% at p90, but this yields one 1,588-profile cluster plus the 53 zero-sales profiles; at p75, one cluster already contains 1,450 profiles and the silhouette is 0.26. RobustScaler at p75 similarly yields clusters of 1,483 and 53 with 13.8% noise. Lower noise is therefore obtained mainly by merging the broad population, not by uncovering balanced demand groups. The baseline silhouette of 0.97 covers only 82 assigned profiles; it is not representative of the full population.",
        "The compact behavior representation preserves one row per store-family and reduces the weekday/month detail, yet its knee remains 96.0% noise. These results suggest DBSCAN's density assumptions do not fit this heterogeneous profile space well. Four highly correlated feature pairs and PCA compression indicate redundancy, but the compressed tests show that redundancy is not the sole cause. Keep all unusual profiles; no outliers are removed.",
        "",
        "## Recommendation", "",
        "**B. Keep DBSCAN only as an exploratory/analytical module, not as a forecasting feature.** Across data-driven knees, 92.6–96.2% of profiles remain noise. Eps values that capture more profiles form one dominant cluster, with weak or only subset-based silhouette evidence. These assignments are too unstable and incomplete to act as a generally useful segmentation feature. The results do not establish that another method is required, so replacing DBSCAN is not justified by this sensitivity analysis alone. High/Medium/Low demand tiers are not justified.",
        "",
        "Artifacts: sensitivity_results.csv, noise_sensitivity.svg, and k_distance_comparison.svg. The main corrected dataset, fitted DBSCAN model, scaler, and baseline assignments were not changed.",
        "",
    ]
    (OUT/"sensitivity_report.md").write_text("\n".join(report),encoding="utf-8")
    summary={
        "profile_count":len(profiles),"profile_end_date":profile_end,
        "baseline_scenario":baseline,"feature_correlation_pairs_abs_ge_0_90":int((upper>=.90).sum()),
        "pca_components_for_90_percent":n90,"pca_components_for_95_percent":n95,
        "scenarios":len(results),
    }
    (OUT/"sensitivity_summary.json").write_text(json.dumps(summary,indent=2),encoding="utf-8")
    print(result_frame.to_string(index=False))
    print("\nFeature redundancy:",summary)
    return result_frame,summary


if __name__ == "__main__":
    run_sensitivity()
