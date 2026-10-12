import { useEffect, useState } from 'react';
import { Activity, AlertTriangle, Boxes, ChartNoAxesCombined, Package, ShoppingCart, Sparkles, Store } from 'lucide-react';
import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { dashboardApi, inventoryApi, productsApi, recommendationsApi, salesApi, storesApi } from './api';
import { errorMessage } from './api/client';
import type { DashboardSummary, Inventory, Product, Recommendation, Sale, Store as StoreRecord } from './types';

const emptySummary: DashboardSummary = {
  stores: 0, products: 0, trackedInventoryItems: 0, lowStockItems: 0, manualSaleRecords: 0,
  manualSalesAmount: 0, importedFamilySalesRecords: 0, importedFamilySalesAmount: 0,
  importedTransactionRows: 0, importedTransactions: 0, forecastRecords: 0,
  recommendationRecords: 0, reorderRecommendations: 0, manualSalesTrend: [], importedFamilySalesTrend: [],
};

function currency(value: number) {
  return new Intl.NumberFormat(undefined, { style: 'currency', currency: 'USD' }).format(value);
}

export function DashboardPage() {
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState('');
  const [summary, setSummary] = useState(emptySummary);
  const [inventory, setInventory] = useState<Inventory[]>([]);
  const [products, setProducts] = useState<Product[]>([]);
  const [sales, setSales] = useState<Sale[]>([]);
  const [stores, setStores] = useState<StoreRecord[]>([]);
  const [recommendations, setRecommendations] = useState<Recommendation[]>([]);

  const refresh = async () => {
    setBusy(true);
    try {
      const [nextSummary, nextStores, nextProducts, nextInventory, nextSales, nextRecommendations] =
        await Promise.all([
          dashboardApi.summary(), storesApi.list(), productsApi.list(), inventoryApi.list(),
          salesApi.list(), recommendationsApi.list(),
        ]);
      setSummary(nextSummary);
      setStores(nextStores);
      setProducts(nextProducts);
      setInventory(nextInventory);
      setSales(nextSales);
      setRecommendations(nextRecommendations);
      setError('');
    } catch (cause) {
      setError(errorMessage(cause));
    } finally {
      setBusy(false);
    }
  };

  useEffect(() => { void refresh(); }, []);

  const lowStock = inventory.filter((item) => item.quantity <= item.reorderLevel);
  const productName = (id: number) => products.find((item) => item.id === id)?.name ?? `Product #${id}`;
  const productUnit = (id: number) => products.find((item) => item.id === id)?.unit ?? '';
  const manualTrend = summary.manualSalesTrend.map((point) => ({ date: point.date, amount: Number(point.amount) }));
  const importedTrend = summary.importedFamilySalesTrend.map((point) => ({ date: point.date, amount: Number(point.amount) }));
  const latestSales = [...sales].sort((a, b) => b.saleDate.localeCompare(a.saleDate)).slice(0, 5);

  const metrics = [
    [ShoppingCart, 'Recorded sales', currency(Number(summary.manualSalesAmount)), `${summary.manualSaleRecords} sale entries`, 'green'],
    [ChartNoAxesCombined, 'Imported family sales', currency(Number(summary.importedFamilySalesAmount)), `${summary.importedFamilySalesRecords} dated family values`, 'violet'],
    [Activity, 'Imported transactions', Number(summary.importedTransactions).toLocaleString(), `${summary.importedTransactionRows} store/day counts; not revenue`, 'blue'],
    [Boxes, 'Tracked inventory items', String(summary.trackedInventoryItems), 'Quantities remain in each product’s unit', 'blue'],
    [AlertTriangle, 'Low-stock items', String(summary.lowStockItems), 'At or below their explicit reorder level', 'amber'],
    [Store, 'Stores', String(summary.stores), 'Available to your account', 'green'],
    [Package, 'Products', String(summary.products), 'Available to your account', 'blue'],
    [ChartNoAxesCombined, 'Forecast records', String(summary.forecastRecords), 'ML family forecasts are monetary sales', 'violet'],
    [Sparkles, 'Saved recommendations', String(summary.recommendationRecords), 'No automatic unit-compatible orders yet', 'teal'],
  ] as const;

  return <>
    <div className="welcome-row">
      <div><div className="eyebrow">YOUR RETAIL PULSE <span className="live-dot" /> LIVE DATA</div>
        <h1>Retail operations overview</h1>
        <p>Sales, source transactions, and inventory are shown separately so their units stay clear.</p>
      </div>
      <button className="secondary" onClick={() => void refresh()} disabled={busy}><Activity size={16} /> Refresh data</button>
    </div>
    {error && <div className="error-box inline"><AlertTriangle size={16} />{error}<button onClick={() => void refresh()}>Retry</button></div>}
    <div className="metric-grid">
      {metrics.map(([Icon, label, value, note, color]) => <article className="metric-card" key={label}>
        <div className={`metric-icon ${color}`}><Icon size={18} /></div><span className="metric-label">{label}</span>
        <strong>{busy ? <span className="skeleton" /> : value}</strong><small>{note}</small>
      </article>)}
    </div>
    <div className="chart-grid">
      <section className="panel"><div className="panel-head"><div><span className="eyebrow">MANUAL RECORDS</span><h2>Recorded sales trend</h2></div></div>
        {manualTrend.length ? <ResponsiveContainer width="100%" height={260}><AreaChart data={manualTrend}>
          <CartesianGrid strokeDasharray="3 4" stroke="#edf1ef" vertical={false} /><XAxis dataKey="date" tickLine={false} axisLine={false} />
          <YAxis tickLine={false} axisLine={false} /><Tooltip formatter={(value) => currency(Number(value))} />
          <Area type="monotone" dataKey="amount" name="Recorded sales" stroke="#0b9475" strokeWidth={2.5} fill="#d8f0e9" />
        </AreaChart></ResponsiveContainer> : <div className="small-empty"><b>{busy ? 'Loading sales…' : 'No manual sales recorded'}</b><small>Add a sale to start this trend.</small></div>}
      </section>
      <section className="panel"><div className="panel-head"><div><span className="eyebrow">IMPORTED SOURCE DATA</span><h2>Historical family sales</h2></div></div>
        {importedTrend.length ? <ResponsiveContainer width="100%" height={260}><AreaChart data={importedTrend}>
          <CartesianGrid strokeDasharray="3 4" stroke="#edf1ef" vertical={false} /><XAxis dataKey="date" tickLine={false} axisLine={false} />
          <YAxis tickLine={false} axisLine={false} /><Tooltip formatter={(value) => currency(Number(value))} />
          <Area type="monotone" dataKey="amount" name="Family-level monetary sales" stroke="#5684d9" strokeWidth={2.5} fill="#e1eafa" />
        </AreaChart></ResponsiveContainer> : <div className="small-empty"><b>{busy ? 'Loading source data…' : 'No imported history'}</b><small>Validated dataset import can add family-level historical sales.</small></div>}
      </section>
    </div>
    <div className="bottom-grid">
      <section className="panel"><div className="panel-head"><div><span className="eyebrow">NEEDS ATTENTION</span><h2>Low stock</h2></div><span className="count-badge">{lowStock.length}</span></div>
        {lowStock.length ? lowStock.slice(0, 5).map((item) => <div className="list-row" key={item.id}>
          <span className="product-mark">{productName(item.productId).slice(0, 1)}</span>
          <div className="row-main"><b>{productName(item.productId)}</b><small>Reorder level {item.reorderLevel} {productUnit(item.productId)}</small></div>
          <strong className="stock-number">{item.quantity} {productUnit(item.productId)} left</strong>
        </div>) : <div className="small-empty"><b>{busy ? 'Loading inventory…' : 'No low-stock items'}</b><small>Low stock is based on each item’s explicit reorder threshold.</small></div>}
      </section>
      <section className="panel"><div className="panel-head"><div><span className="eyebrow">REPLENISHMENT</span><h2>Saved recommendations</h2></div><Sparkles size={17} /></div>
        {recommendations.length ? recommendations.slice(0, 4).map((item) => <div className="recommend-row" key={item.id}>
          <span className="rec-icon"><Sparkles size={15} /></span><div><b>{item.recommendationType.replace('_', ' ')}</b>
            <small>{productName(item.productId)} · {item.recommendedQuantity} {productUnit(item.productId)}</small>
            <p>{item.reason ?? 'Saved recommendation; not generated from the monetary family forecast.'}</p>
          </div>
        </div>) : <div className="small-empty"><b>{busy ? 'Loading recommendations…' : 'No recommendations saved'}</b>
          <small>Automatic replenishment needs a forecast and inventory in matching units. Current ML forecasts are monetary.</small></div>}
      </section>
    </div>
    <section className="panel recent-panel"><div className="panel-head"><div><span className="eyebrow">LATEST ACTIVITY</span><h2>Recent manual sales</h2></div></div>
      {latestSales.length ? <div className="table-wrap"><table><thead><tr><th>PRODUCT</th><th>STORE</th><th>DATE</th><th>QUANTITY</th><th>AMOUNT</th></tr></thead>
        <tbody>{latestSales.map((sale) => <tr key={sale.id}><td><b>{productName(sale.productId)}</b></td>
          <td>{stores.find((store) => store.id === sale.storeId)?.name ?? `Store #${sale.storeId}`}</td><td>{sale.saleDate}</td>
          <td>{sale.quantity} {productUnit(sale.productId)}</td><td><b>{currency(Number(sale.totalAmount))}</b></td></tr>)}</tbody></table></div>
        : <div className="small-empty"><b>{busy ? 'Loading sales…' : 'No manual sales yet'}</b><small>Add a store, product, and inventory before recording a sale.</small></div>}
    </section>
  </>;
}
