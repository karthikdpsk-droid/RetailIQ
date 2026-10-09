import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { forecastsApi } from './api';
import { errorMessage } from './api/client';
import type { Product, Store, StoreFamilyForecast } from './types';

export function ForecastGenerator({ stores, products }: { stores: Store[]; products: Product[] }) {
  const [storeId, setStoreId] = useState('');
  const [productId, setProductId] = useState('');
  const [forecastDate, setForecastDate] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [result, setResult] = useState<StoreFamilyForecast | null>(null);
  const availableProducts = useMemo(() => products.filter(p => p.storeId === Number(storeId) && p.mlFamilyCode), [products, storeId]);

  useEffect(() => {
    setProductId(''); setResult(null); setError('');
    if (!storeId) return;
    let current = true;
    forecastsApi.byStoreFamily(Number(storeId)).then(rows => { if (current) setResult(rows[0] ?? null); })
      .catch(cause => { if (current) setError(errorMessage(cause)); });
    return () => { current = false; };
  }, [storeId]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const product = availableProducts.find(p => p.id === Number(productId));
    if (!product?.mlFamilyCode) { setError('Choose a product with an explicit ML family mapping.'); return; }
    setBusy(true); setError(''); setResult(null);
    try {
      const forecast = await forecastsApi.createStoreFamily({ storeId: Number(storeId), mlFamily: product.mlFamilyCode, forecastDate });
      setResult(forecast);
    } catch (cause) { setError(errorMessage(cause)); }
    finally { setBusy(false); }
  }

  return <section className="panel forecast-generator">
    <div className="panel-head"><div><span className="eyebrow">MODEL INFERENCE</span><h2>Generate a store family forecast</h2></div></div>
    <p>RetailIQ builds historical features and calls the ML service. The result is monetary sales demand, not product units.</p>
    <form className="forecast-form" onSubmit={submit}>
      <label>Store<select required value={storeId} onChange={e => setStoreId(e.target.value)}><option value="">Choose a store</option>{stores.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}</select></label>
      <label>Product / ML family<select required value={productId} onChange={e => setProductId(e.target.value)} disabled={!storeId}><option value="">Choose a mapped product</option>{availableProducts.map(p => <option key={p.id} value={p.id}>{p.name} · {p.mlFamilyCode}</option>)}</select></label>
      <label>Forecast date<input required type="date" value={forecastDate} onChange={e => setForecastDate(e.target.value)} /></label>
      <button className="primary" disabled={busy || !availableProducts.length}>{busy ? 'Generating…' : 'Generate forecast'}</button>
    </form>
    {!busy && storeId && !availableProducts.length && <p className="forecast-hint">No products at this store have an explicit ML family mapping.</p>}
    {error && <div className="error-box inline" role="alert">{error}</div>}
    {result && <div className="forecast-result" role="status"><div><span className="eyebrow">{result.mlFamily} · {result.forecastDate}</span><strong>{Number(result.forecastDemand).toLocaleString(undefined, { maximumFractionDigits: 2 })}</strong><small>Monetary sales forecast · {result.demandUnit}</small></div><div><b>Model</b><span>{result.modelVersion}</span><small>One day ahead · cutoff {result.predictionCutoff}</small></div></div>}
  </section>;
}
