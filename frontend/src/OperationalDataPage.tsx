import { useEffect, useState, type FormEvent } from 'react';
import { Plus, Search, X } from 'lucide-react';
import { errorMessage } from './api/client';
import './OperationalDataPage.css';
import { forecastsApi, inventoryApi, mlFamiliesApi, productsApi, recommendationsApi, salesApi, storesApi } from './api';
import { ForecastGenerator } from './ForecastGenerator';
import type { Forecast, Inventory, Product, Recommendation, Sale, Store } from './types';

type Kind = 'stores' | 'products' | 'inventory' | 'sales' | 'forecasts' | 'recommendations';
type Row = Store | Product | Inventory | Sale | Forecast | Recommendation;
type Field = { key: string; label: string; type?: 'text' | 'number' | 'date' | 'checkbox' | 'select'; options?: 'stores' | 'products' | 'families'; required?: boolean; min?: number; step?: number | 'any' };

const metadata: Record<Kind, { title: string; subtitle: string; columns: string[]; fields: Field[]; editable: boolean }> = {
  stores: { title: 'Stores', subtitle: 'Manage your retail locations and store details.', columns: ['Store', 'Code', 'Location', 'Type', 'Status'], editable: true, fields: [
    { key: 'name', label: 'Store name' }, { key: 'storeCode', label: 'Store code' }, { key: 'city', label: 'City' },
    { key: 'state', label: 'State' }, { key: 'address', label: 'Address' }, { key: 'type', label: 'Store type' },
    { key: 'mlStoreNbr', label: 'ML store number', type: 'number', min: 1, required: false },
    { key: 'mlCluster', label: 'ML cluster', type: 'number', min: 1, required: false }, { key: 'active', label: 'Active', type: 'checkbox' },
  ] },
  products: { title: 'Products', subtitle: 'Keep your product catalog accurate across locations.', columns: ['Product', 'SKU', 'Category', 'Price', 'Status'], editable: true, fields: [
    { key: 'storeId', label: 'Store', options: 'stores' }, { key: 'name', label: 'Product name' }, { key: 'sku', label: 'SKU' },
    { key: 'category', label: 'Category' }, { key: 'description', label: 'Description', required: false },
    { key: 'unitPrice', label: 'Unit price', type: 'number', min: 0, step: 'any' },
    { key: 'unit', label: 'Stock unit' }, { key: 'mlFamilyId', label: 'ML family (optional)', options: 'families', required: false },
    { key: 'active', label: 'Active', type: 'checkbox' },
  ] },
  inventory: { title: 'Inventory', subtitle: 'Record on-hand quantities using each product’s stated unit.', columns: ['Product', 'Store', 'On hand', 'Reorder level', 'Safety stock'], editable: true, fields: [
    { key: 'storeId', label: 'Store', options: 'stores' }, { key: 'productId', label: 'Product', options: 'products' },
    { key: 'quantity', label: 'Quantity', type: 'number', min: 0, step: 1 }, { key: 'reorderLevel', label: 'Reorder level', type: 'number', min: 0, step: 1 },
    { key: 'safetyStock', label: 'Safety stock', type: 'number', min: 0, step: 1 },
  ] },
  sales: { title: 'Sales', subtitle: 'Record dated product sales; the API prices them and deducts inventory.', columns: ['Product', 'Store', 'Date', 'Quantity', 'Total'], editable: false, fields: [
    { key: 'storeId', label: 'Store', options: 'stores' }, { key: 'productId', label: 'Product', options: 'products' },
    { key: 'quantity', label: 'Quantity', type: 'number', min: 1, step: 1 }, { key: 'saleDate', label: 'Sale date', type: 'date' },
  ] },
  forecasts: { title: 'Demand forecast', subtitle: 'Run the ML family forecast or review existing product-forecast records.', columns: ['Product', 'Store', 'Forecast date', 'Predicted demand', 'Model'], editable: false, fields: [
    { key: 'productId', label: 'Product', options: 'products' }, { key: 'storeId', label: 'Store', options: 'stores' },
    { key: 'forecastDate', label: 'Forecast date', type: 'date' }, { key: 'predictedDemand', label: 'Predicted demand', type: 'number', min: 0, step: 'any' },
    { key: 'lowerBound', label: 'Lower bound', type: 'number', min: 0, step: 'any', required: false },
    { key: 'upperBound', label: 'Upper bound', type: 'number', min: 0, step: 'any', required: false },
    { key: 'modelVersion', label: 'Model version' },
  ] },
  recommendations: { title: 'Recommendations', subtitle: 'Review saved guidance. These records are not automatically calculated from mismatched forecast and stock units.', columns: ['Product', 'Store', 'Type', 'Quantity', 'Reason'], editable: false, fields: [
    { key: 'productId', label: 'Product', options: 'products' }, { key: 'storeId', label: 'Store', options: 'stores' },
    { key: 'recommendationType', label: 'Type', type: 'select' }, { key: 'recommendedQuantity', label: 'Recommended quantity', type: 'number', min: 0, step: 'any' },
    { key: 'reason', label: 'Reason', required: false }, { key: 'recommendationDate', label: 'Recommendation date', type: 'date' },
  ] },
};

const columns: Record<Kind, string[]> = {
  stores: ['name', 'storeCode', 'city', 'type', 'active'],
  products: ['name', 'sku', 'category', 'unitPrice', 'active'],
  inventory: ['productId', 'storeId', 'quantity', 'reorderLevel', 'safetyStock'],
  sales: ['productId', 'storeId', 'saleDate', 'quantity', 'totalAmount'],
  forecasts: ['productId', 'storeId', 'forecastDate', 'predictedDemand', 'modelVersion'],
  recommendations: ['productId', 'storeId', 'recommendationType', 'recommendedQuantity', 'reason'],
};

function todayLocal() {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
}

function listRecords(kind: Kind): Promise<Row[]> {
  switch (kind) {
    case 'stores': return storesApi.list();
    case 'products': return productsApi.list();
    case 'inventory': return inventoryApi.list();
    case 'sales': return salesApi.list();
    case 'forecasts': return forecastsApi.list();
    case 'recommendations': return recommendationsApi.list();
  }
}

function saveRecord(kind: Kind, id: number | undefined, body: Record<string, unknown>) {
  switch (kind) {
    case 'stores': return id === undefined ? storesApi.create(body) : storesApi.update(id, body);
    case 'products': return id === undefined ? productsApi.create(body) : productsApi.update(id, body);
    case 'inventory': return id === undefined ? inventoryApi.create(body) : inventoryApi.update(id, body);
    case 'sales': return salesApi.create(body);
    case 'forecasts': return forecastsApi.ingest(body);
    case 'recommendations': return recommendationsApi.ingest(body);
  }
}

function displayValue(row: Row, key: string, stores: Store[], products: Product[], families: { id: number; familyCode: string }[]) {
  const value = (row as unknown as Record<string, unknown>)[key];
  if (key === 'storeId') return stores.find((store) => store.id === Number(value))?.name ?? `Store #${value}`;
  if (key === 'productId') {
    const product = products.find((item) => item.id === Number(value));
    return product ? `${product.name} (${product.unit})` : `Product #${value}`;
  }
  if (key === 'mlFamilyId') return families.find((family) => family.id === Number(value))?.familyCode ?? '—';
  if (key === 'unitPrice' || key === 'totalAmount') return new Intl.NumberFormat(undefined, { style: 'currency', currency: 'USD' }).format(Number(value));
  if (key === 'active') return <span className={`badge ${(row as Store | Product).active ? 'good' : 'neutral'}`}>{(row as Store | Product).active ? 'Active' : 'Inactive'}</span>;
  return String(value ?? '—');
}

export function OperationalDataPage({ kind }: { kind: Kind }) {
  const meta = metadata[kind];
  const [rows, setRows] = useState<Row[]>([]);
  const [stores, setStores] = useState<Store[]>([]);
  const [products, setProducts] = useState<Product[]>([]);
  const [families, setFamilies] = useState<{ id: number; familyCode: string }[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [editing, setEditing] = useState<Row | null>(null);
  const [modal, setModal] = useState(false);
  const [query, setQuery] = useState('');

  const load = async () => {
    setLoading(true);
    try {
      const [nextRows, nextStores, nextProducts, nextFamilies] = await Promise.all([
        listRecords(kind), storesApi.list(), productsApi.list(), mlFamiliesApi.list(),
      ]);
      setRows(nextRows);
      setStores(nextStores);
      setProducts(nextProducts);
      setFamilies(nextFamilies);
      setError('');
    } catch (cause) {
      setError(errorMessage(cause));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, [kind]);

  const openCreate = () => { setEditing(null); setNotice(''); setModal(true); };
  const openEdit = (row: Row) => { setEditing(row); setNotice(''); setModal(true); };

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError('');
    const form = new FormData(event.currentTarget);
    const body: Record<string, unknown> = {};
    for (const field of meta.fields) {
      if (field.type === 'checkbox') {
        body[field.key] = form.get(field.key) === 'on';
        continue;
      }
      const value = form.get(field.key);
      if (value === null || value === '') continue;
      body[field.key] = field.type === 'number' || field.options ? Number(value) : value;
    }
    try {
      await saveRecord(kind, editing?.id, body);
      setModal(false);
      setNotice(kind === 'sales' ? 'Sale recorded and inventory updated.' : editing ? 'Record updated.' : 'Record created.');
      await load();
    } catch (cause) {
      setError(errorMessage(cause));
    }
  };

  const visibleRows = rows.filter((row) => JSON.stringify(row).toLowerCase().includes(query.toLowerCase()));
  const relationOptions = (field: Field) => field.options === 'stores' ? stores
    : field.options === 'products' ? products : field.options === 'families' ? families : [];

  return <>
    {kind === 'forecasts' && <ForecastGenerator stores={stores} products={products} />}
    <div className="page-title-row"><div><div className="eyebrow">MANAGE OPERATIONS</div><h1>{meta.title}</h1><p>{meta.subtitle}</p></div>
      {kind !== 'forecasts' && kind !== 'recommendations' && (kind === 'stores' || stores.length > 0) &&
        <button className="primary" onClick={openCreate}><Plus size={16} /> Add {kind.slice(0, -1)}</button>}
    </div>
    {notice && <div className="success-message" role="status">{notice}</div>}
    <section className="panel data-panel">
      <div className="data-toolbar"><span className="record-count">{loading ? 'Loading…' : `${visibleRows.length} ${kind}`}</span>
        <label className="table-search"><Search size={16} /><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder={`Search ${kind}…`} /></label>
      </div>
      {error && <div className="error-box inline">{error}<button onClick={() => void load()}>Retry</button></div>}
      {loading ? <div className="table-loading"><span className="spinner" /> Loading data…</div>
        : visibleRows.length ? <div className="table-wrap"><table><thead><tr>{meta.columns.map((column) => <th key={column}>{column.toUpperCase()}</th>)}{meta.editable && <th>ACTIONS</th>}</tr></thead>
          <tbody>{visibleRows.map((row) => <tr key={row.id}>{columns[kind].map((key) => <td key={key}>{displayValue(row, key, stores, products, families)}</td>)}
            {meta.editable && <td><button className="secondary compact" onClick={() => openEdit(row)}>Edit</button></td>}
          </tr>)}</tbody></table></div>
          : <div className="table-empty"><span className="empty-chart-icon"><Search size={24} /></span>
            <h3>{query ? 'No matches found' : `No ${kind} yet`}</h3>
            <p>{query ? 'Try another search.' : kind === 'stores' ? 'Create a store to start entering authorized operational data.'
              : kind === 'products' ? 'Add a product to a store. Its stock unit is used for inventory and sales.'
                : kind === 'inventory' ? 'Track a product quantity and explicit reorder threshold; quantities use the product unit.'
                  : kind === 'sales' ? 'Add a store, product, and inventory before recording a dated sale.'
                    : 'Run the ML forecast generator after adding mapped stores and historical data.'}</p>
            {kind !== 'forecasts' && kind !== 'recommendations' && (kind === 'stores' || stores.length > 0) &&
              <button className="secondary" onClick={openCreate}><Plus size={15} /> Add record</button>}
          </div>}
    </section>
    {modal && <div className="modal-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget) setModal(false); }}>
      <form className="modal" onSubmit={submit}><div className="modal-head"><div><span className="eyebrow">{editing ? 'UPDATE RECORD' : 'NEW RECORD'}</span>
        <h2>{editing ? 'Edit' : 'Add'} {kind.slice(0, -1)}</h2></div>
        <button type="button" className="icon" onClick={() => setModal(false)} aria-label="Close"><X /></button></div>
        <div className="form-grid">{meta.fields.map((field) => {
          const current = (editing as unknown as Record<string, unknown> | null)?.[field.key];
          const isRequired = field.required ?? !['description', 'mlStoreNbr', 'mlCluster', 'lowerBound', 'upperBound', 'reason', 'mlFamilyId'].includes(field.key);
          return <label key={field.key} className={['address', 'description'].includes(field.key) ? 'span-two' : ''}>{field.label}
            {field.type === 'checkbox' ? <input type="checkbox" name={field.key} defaultChecked={editing ? Boolean(current) : true} />
              : field.options ? <select name={field.key} required={isRequired} defaultValue={current == null ? '' : String(current)}>
                <option value="">{isRequired ? `Select ${field.label}` : 'None'}</option>
                {relationOptions(field).map((option) => <option key={option.id} value={option.id}>
                  {'name' in option ? option.name : option.familyCode}
                </option>)}
              </select>
                : field.type === 'select' ? <select name={field.key} required={isRequired} defaultValue={current == null ? 'REORDER' : String(current)}>
                  {['REORDER', 'LOW_STOCK', 'OVERSTOCK', 'NORMAL'].map((value) => <option key={value}>{value}</option>)}
                </select>
                  : <input name={field.key} type={field.type ?? 'text'} required={isRequired} min={field.min} step={field.step}
                    defaultValue={current == null ? (field.type === 'date' ? todayLocal() : '') : String(current)} />}
          </label>;
        })}</div>
        {error && <div className="error-box">{error}</div>}
        <div className="modal-actions"><button type="button" className="secondary" onClick={() => setModal(false)}>Cancel</button>
          <button className="primary"><Plus size={15} /> {editing ? 'Save changes' : 'Create record'}</button></div>
      </form>
    </div>}
  </>;
}
