import { useCallback, useEffect, useRef, useState } from 'react';
import { call, onEvent } from './native';
import type { AppState, BatchInfo, InputType, Product, ScanResult, UpdateInfo } from './types';

type Tab = 'scan' | 'list' | 'sync' | 'settings';
type Mode = 'single' | 'bulk';

const peso = (p: string) => '₱' + Number(p).toLocaleString('en-PH', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const when = (ms: number) => (ms ? new Date(ms).toLocaleString() : 'never');
const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));
const errText = (e: unknown) => (e instanceof Error ? e.message : String(e));

export default function App() {
  const [tab, setTab] = useState<Tab>('scan');
  const [mode, setMode] = useState<Mode>('single');
  const [state, setState] = useState<AppState | null>(null);
  const [items, setItems] = useState<Product[]>([]);
  const [last, setLast] = useState<ScanResult | null>(null);
  const [notice, setNotice] = useState<{ kind: 'ok' | 'warn' | 'err'; text: string } | null>(null);
  const [job, setJob] = useState<BatchInfo | null>(null);
  const [jobError, setJobError] = useState<string | null>(null);
  const [busyPrint, setBusyPrint] = useState(false);
  const [update, setUpdate] = useState<UpdateInfo | null>(null);
  const [tick, setTick] = useState(0); // counts scans, so each result card animates once

  // Input type: scanning/typing is a BARCODE by default; "SKU / Weighted" looks the typed or scanned value up as a SKU
  const [inputType, setInputType] = useState<InputType>('barcode');
  const inputTypeRef = useRef(inputType);
  inputTypeRef.current = inputType;

  const modeRef = useRef(mode);
  modeRef.current = mode;

  const refresh = useCallback(async () => {
    try {
      setState(await call<AppState>('getState'));
      const l = await call<{ items: Product[] }>('listGet');
      setItems(l.items);
    } catch (e) {
      setNotice({ kind: 'err', text: errText(e) });
    }
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  // Quiet update check when the app opens (needs internet; a failure is simply ignored here)
  useEffect(() => {
    if (!state?.updateEnabled || update) return;
    call<UpdateInfo>('updateCheck').then(setUpdate).catch(() => {});
  }, [state?.updateEnabled]);

  // ---- scanning ----
  const handleScan = useCallback(async (code: string) => {
    try {
      const add = modeRef.current === 'bulk';
      const type = inputTypeRef.current;
      const r = await call<ScanResult>('scan', { barcode: code, add, type });
      setLast(r);
      setTick((n) => n + 1);
      call('haptic', { kind: r.status === 'ok' ? 'ok' : 'bad' }).catch(() => {});
      if (r.status === 'ok') {
        setNotice(null);
        if (add) {
          const l = await call<{ items: Product[] }>('listGet');
          setItems(l.items);
        }
      } else if (r.status === 'invalid') {
        setNotice({
          kind: 'warn',
          text:
            type === 'sku'
              ? `"${code}" is not a valid SKU (letters and digits only, up to 20).`
              : `"${code}" is not a valid barcode. Weighted items have barcode 0: switch Input to "SKU / Weighted" and type their SKU.`,
        });
      } else if (r.status === 'nodata') {
        setNotice({ kind: 'err', text: 'No product data on this device yet. Open the Sync tab and sync the masterfile.' });
      } else if (r.status === 'nosku') {
        setNotice({ kind: 'err', text: 'This device has no SKU data yet. Open the Sync tab and sync the masterfile again.' });
      } else {
        setNotice({ kind: 'warn', text: `${type === 'sku' ? 'SKU' : 'Product'} not found: ${r.barcode ?? code}` });
      }
    } catch (e) {
      setNotice({ kind: 'err', text: errText(e) });
    }
  }, []);

  useEffect(() => onEvent<{ barcode: string }>('scan', (p) => handleScan(p.barcode)), [handleScan]);

  // ---- printing ----
  const follow = useCallback(async (first: BatchInfo, isList: boolean) => {
    let info = first;
    setJob(info);
    for (let i = 0; i < 180 && info.state !== 'HandedToBarTender' && info.state !== 'Failed' && info.state !== 'Interrupted'; i++) {
      await wait(1000);
      try {
        info = await call<BatchInfo>('batchStatus', { batchId: first.batchId });
        setJob(info);
      } catch (e) {
        setJobError(`Lost contact with the PC while printing. The labels may still be printing; check the printer before sending again. (${errText(e)})`);
        return;
      }
    }
    if (info.state === 'HandedToBarTender' && isList) {
      // remove only the labels that were sent (scans made while printing stay in the list)
      const l = await call<{ items: Product[] }>('resendRemaining', { completed: info.total });
      setItems(l.items);
      setState(await call<AppState>('getState'));
    }
  }, []);

  const send = useCallback(async (method: 'printList' | 'printSingle', args: object = {}) => {
    setBusyPrint(true);
    setJob(null);
    setJobError(null);
    try {
      const info = await call<BatchInfo>(method, args);
      await follow(info, method === 'printList');
    } catch (e) {
      setJobError(errText(e));
    } finally {
      setBusyPrint(false);
    }
  }, [follow]);

  const resendRemaining = async () => {
    if (!job) return;
    const l = await call<{ items: Product[] }>('resendRemaining', { completed: job.completed });
    setItems(l.items);
    setJob(null);
    setJobError(null);
  };

  const tabs: [Tab, string, IconName][] = [
    ['scan', 'Scan', 'scan'],
    ['list', 'List', 'list'],
    ['sync', 'Sync', 'sync'],
    ['settings', 'Settings', 'settings'],
  ];

  return (
    <div className="flex h-full flex-col">
      <header className="flex items-center justify-between bg-brand px-4 py-2.5 text-white">
        <div className="flex items-center gap-2">
          <Icon name="tag" className="h-5 w-5 text-tag" />
          <span className="text-base font-bold tracking-tight">MobileShelfTag</span>
        </div>
        <SyncPill state={state} />
      </header>

      {update?.available && tab !== 'settings' && (
        <button
          onClick={() => setTab('settings')}
          className="flex items-center justify-between bg-tag px-4 py-2.5 text-left text-sm font-bold text-ink"
        >
          <span>Version {update.version} is ready to install</span>
          <span className="underline">Install</span>
        </button>
      )}

      <main className="flex-1 overflow-y-auto px-4 pb-2 pt-3">
        {tab === 'scan' && (
          <ScanTab
            mode={mode}
            setMode={setMode}
            inputType={inputType}
            setInputType={setInputType}
            last={last}
            tick={tick}
            notice={notice}
            onManual={handleScan}
            onCamera={() => call('cameraScan').catch((e) => setNotice({ kind: 'err', text: errText(e) }))}
            listCount={items.length}
            job={job}
            jobError={jobError}
            busy={busyPrint}
            onPrintOne={(p) => send('printSingle', p.kind === 'sku' ? { barcode: p.sku, type: 'sku' } : { barcode: p.barcode })}
          />
        )}
        {tab === 'list' && (
          <ListTab
            items={items}
            job={job}
            jobError={jobError}
            busy={busyPrint}
            onSend={() => send('printList')}
            onResend={resendRemaining}
            onRemove={async (id) => setItems((await call<{ items: Product[] }>('listRemove', { id })).items)}
            onClear={async () => setItems((await call<{ items: Product[] }>('listClear')).items)}
          />
        )}
        {tab === 'sync' && <SyncTab state={state} onDone={refresh} />}
        {tab === 'settings' && <SettingsTab state={state} onSaved={refresh} update={update} setUpdate={setUpdate} />}
      </main>

      <nav className="grid grid-cols-4 border-t border-slate-300 bg-white pb-1">
        {tabs.map(([key, label, icon]) => {
          const active = tab === key;
          return (
            <button
              key={key}
              onClick={() => setTab(key)}
              aria-current={active ? 'page' : undefined}
              className={`relative flex flex-col items-center gap-0.5 pb-1.5 pt-2.5 text-xs font-semibold ${active ? 'text-brand' : 'text-slate-500'}`}
            >
              {active && <span className="absolute inset-x-6 top-0 h-[3px] rounded-b bg-tag" />}
              <span className="relative">
                <Icon name={icon} className="h-6 w-6" />
                {key === 'list' && items.length > 0 && (
                  <span className="absolute -right-3 -top-1.5 min-w-[18px] rounded-full bg-brand px-1 text-center text-[11px] font-bold leading-[18px] text-white">
                    {items.length}
                  </span>
                )}
                {key === 'settings' && update?.available && (
                  <span className="absolute -right-1 -top-0.5 h-2.5 w-2.5 rounded-full bg-tag ring-2 ring-white" />
                )}
              </span>
              {label}
            </button>
          );
        })}
      </nav>
    </div>
  );
}

// ---------------------------------------------------------------- shared bits

type IconName = 'scan' | 'list' | 'sync' | 'settings' | 'camera' | 'tag' | 'alert' | 'check' | 'trash';

const ICONS: Record<IconName, string> = {
  scan: 'M4 7V5a1 1 0 0 1 1-1h2M17 4h2a1 1 0 0 1 1 1v2M20 17v2a1 1 0 0 1-1 1h-2M7 20H5a1 1 0 0 1-1-1v-2M8 9v6M12 9v6M16 9v6',
  list: 'M9 6h11M9 12h11M9 18h11M4.5 6h.01M4.5 12h.01M4.5 18h.01',
  sync: 'M20 11a8 8 0 0 0-14.9-3M4 4v4h4M4 13a8 8 0 0 0 14.9 3M20 20v-4h-4',
  settings: 'M4 7h9M17 7h3M4 17h3M11 17h9M15 5v4M9 15v4',
  camera: 'M4 8h3l1.5-2.5h7L17 8h3v11H4zM12 17a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7z',
  tag: 'M3 12V4h8l10 10-8 8zM7.5 8.5h.01',
  alert: 'M12 8v5M12 16.5h.01M10.3 4.2 2.7 17.5A2 2 0 0 0 4.4 20.5h15.2a2 2 0 0 0 1.7-3L13.7 4.2a2 2 0 0 0-3.4 0z',
  check: 'M5 12.5l4.5 4.5L19 7.5',
  trash: 'M5 7h14M10 7V5h4v2M7 7l1 13h8l1-13M10 11v5M14 11v5',
};

function Icon({ name, className = 'h-5 w-5' }: { name: IconName; className?: string }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className} aria-hidden="true">
      <path d={ICONS[name]} />
    </svg>
  );
}

/** How fresh the offline price data is, at a glance: the thing that matters before trusting a price. */
function SyncPill({ state }: { state: AppState | null }) {
  if (!state) return <span className="text-xs opacity-80">Starting…</span>;
  const days = state.lastSyncMs ? (Date.now() - state.lastSyncMs) / 86_400_000 : Infinity;
  const [dot, text] =
    state.lastSyncMs === 0
      ? ['bg-red-400', 'Not synced']
      : days < 1
        ? ['bg-emerald-400', 'Synced today']
        : days < 2
          ? ['bg-emerald-400', 'Synced yesterday']
          : days < 7
            ? ['bg-amber-300', `Synced ${Math.floor(days)} days ago`]
            : ['bg-red-400', `Synced ${Math.floor(days)} days ago`];
  return (
    <span className="flex items-center gap-1.5 rounded-full bg-white/15 px-2.5 py-1 text-xs font-semibold">
      <span className={`h-2 w-2 rounded-full ${dot}`} />
      {text}
    </span>
  );
}

function Banner({ kind, children }: { kind: 'ok' | 'warn' | 'err'; children: React.ReactNode }) {
  const style =
    kind === 'ok'
      ? 'border-ok bg-emerald-50 text-emerald-950'
      : kind === 'warn'
        ? 'border-amber-500 bg-amber-50 text-amber-950'
        : 'border-bad bg-red-50 text-red-950';
  return (
    <div role={kind === 'err' ? 'alert' : 'status'} className={`mb-3 flex gap-2.5 rounded-xl border-l-4 p-3 text-sm font-medium leading-snug ${style}`}>
      <Icon name={kind === 'ok' ? 'check' : 'alert'} className="mt-0.5 h-5 w-5 shrink-0" />
      <div className="min-w-0">{children}</div>
    </div>
  );
}

function JobStatus({ job, jobError }: { job: BatchInfo | null; jobError: string | null }) {
  if (jobError) return <Banner kind="err">{jobError}</Banner>;
  if (!job) return null;
  if (job.state === 'Interrupted')
    return (
      <Banner kind="err">
        {job.error} {job.completed} of {job.total} label(s) were sent.
        {job.uncertainSeq ? ` Label ${job.uncertainSeq} may or may not have printed: check the printer.` : ''}
      </Banner>
    );
  if (job.state === 'Failed')
    return (
      <Banner kind="err">
        Printing stopped at label {job.failedSeq}: {job.error}. {job.completed} of {job.total} label(s) were already sent to
        BarTender.
      </Banner>
    );
  if (job.state === 'HandedToBarTender')
    return (
      <Banner kind="ok">
        Sent {job.total} label(s) to the printer program. Check the printer for the labels.
      </Banner>
    );
  return (
    <Banner kind="warn">
      {job.state === 'Queued' ? 'Waiting for the PC…' : 'Printing'} {job.completed} / {job.total}
    </Banner>
  );
}

const btn = 'w-full rounded-2xl py-4 text-base font-bold tracking-tight transition active:scale-[0.99] disabled:opacity-50';
const card = 'rounded-2xl bg-white p-4 shadow-sm ring-1 ring-black/5';

function Segmented<T extends string>(p: { value: T; onChange: (v: T) => void; options: [T, string][] }) {
  return (
    <div className="grid grid-flow-col auto-cols-fr gap-1 rounded-2xl bg-slate-200/80 p-1">
      {p.options.map(([v, label]) => (
        <button
          key={v}
          onClick={() => p.onChange(v)}
          aria-pressed={p.value === v}
          className={`rounded-xl py-2.5 text-sm font-bold transition ${p.value === v ? 'bg-white text-brand shadow' : 'text-slate-600'}`}
        >
          {label}
        </button>
      ))}
    </div>
  );
}

/** The price as it reads on a shelf tag: peso sign small, amount large. */
function Price({ value, className = '' }: { value: string; className?: string }) {
  const n = Number(value);
  const text = Number.isFinite(n) ? n.toLocaleString('en-PH', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) : value;
  return (
    <span className={`font-black tabular-nums tracking-tight ${className}`}>
      <span className="mr-1 align-[0.55em] text-[0.45em] font-extrabold">₱</span>
      {text}
    </span>
  );
}

// ---------------------------------------------------------------- Scan

function ScanTab(p: {
  mode: Mode;
  setMode: (m: Mode) => void;
  inputType: InputType;
  setInputType: (t: InputType) => void;
  last: ScanResult | null;
  tick: number;
  notice: { kind: 'ok' | 'warn' | 'err'; text: string } | null;
  onManual: (code: string) => void;
  onCamera: () => void;
  listCount: number;
  job: BatchInfo | null;
  jobError: string | null;
  busy: boolean;
  onPrintOne: (p: Product) => void;
}) {
  const [text, setText] = useState('');
  const product = p.last?.status === 'ok' ? p.last.product : undefined;
  const sku = p.inputType === 'sku';

  return (
    <div className="space-y-2.5">
      <Segmented
        value={p.mode}
        onChange={p.setMode}
        options={[
          ['single', 'Single label'],
          ['bulk', 'Bulk list'],
        ]}
      />
      <Segmented
        value={p.inputType}
        onChange={p.setInputType}
        options={[
          ['barcode', 'Barcode'],
          ['sku', 'SKU / weighted'],
        ]}
      />

      <form
        className="flex gap-2 pt-1"
        onSubmit={(e) => {
          e.preventDefault();
          if (text.trim()) p.onManual(text.trim());
          setText('');
        }}
      >
        <input
          value={text}
          onChange={(e) => setText(e.target.value)}
          inputMode={sku ? 'text' : 'numeric'}
          maxLength={20}
          autoComplete="off"
          placeholder={sku ? 'Type the SKU' : 'Scan or type a barcode'}
          className="min-w-0 flex-1 rounded-2xl border border-slate-300 bg-white px-4 py-3.5 text-lg tabular-nums"
        />
        <button className="rounded-2xl bg-brand px-5 text-base font-bold text-white">Find</button>
        <button
          type="button"
          onClick={() => p.onCamera()}
          aria-label="Scan with the camera"
          className="grid w-14 place-items-center rounded-2xl border-2 border-brand bg-white text-brand"
        >
          <Icon name="camera" className="h-6 w-6" />
        </button>
      </form>

      <div className="pt-1">
        {p.notice && <Banner kind={p.notice.kind}>{p.notice.text}</Banner>}
        <JobStatus job={p.job} jobError={p.jobError} />
      </div>

      {product ? (
        // The result is drawn like the shelf tag it will print: description on top, the price, a yellow base.
        <div key={p.tick} className="settle overflow-hidden rounded-2xl bg-white shadow-md ring-1 ring-black/10">
          <div className="px-4 pt-4">
            <div className="text-lg font-bold leading-snug">{product.longDesc || product.shortDesc}</div>
            <div className="mt-1 text-sm tabular-nums text-slate-500">
              {product.kind === 'sku' ? (
                <>SKU {product.sku}{product.barcode === '0' ? ' · weighted item' : ` · barcode ${product.barcode}`}</>
              ) : (
                <>{product.barcode} · SKU {product.sku}</>
              )}
            </div>
          </div>
          <div className="px-4 pb-4 pt-2">
            <Price value={product.price} className="text-[64px] leading-none text-brand" />
          </div>
          <div className="h-2 bg-tag" />
          <div className="p-3">
            {p.mode === 'single' ? (
              <button disabled={p.busy} onClick={() => p.onPrintOne(product)} className={`${btn} bg-tag text-ink`}>
                {p.busy ? 'Printing…' : 'Print 1 label'}
              </button>
            ) : (
              <div className="flex items-center justify-center gap-2 rounded-xl bg-emerald-50 py-3 text-sm font-bold text-ok">
                <Icon name="check" className="h-5 w-5" />
                Added to the list ({p.listCount})
              </div>
            )}
          </div>
        </div>
      ) : (
        !p.notice && (
          <div className="flex flex-col items-center px-6 pt-10 text-center text-slate-500">
            <div className="grid h-24 w-24 place-items-center rounded-full bg-white text-brand shadow-sm ring-1 ring-black/5">
              <Icon name="scan" className="h-12 w-12" />
            </div>
            <div className="mt-4 text-lg font-bold text-ink">Ready to scan</div>
            <div className="mt-1 text-sm leading-snug">
              {sku ? 'Type a SKU to find weighted items.' : 'Pull the trigger on a barcode, or tap the camera.'}
            </div>
          </div>
        )
      )}
    </div>
  );
}

// ---------------------------------------------------------------- List

function ListTab(p: {
  items: Product[];
  job: BatchInfo | null;
  jobError: string | null;
  busy: boolean;
  onSend: () => void;
  onResend: () => void;
  onRemove: (id: number) => void;
  onClear: () => void;
}) {
  const [confirmClear, setConfirmClear] = useState(false);

  return (
    <div>
      <JobStatus job={p.job} jobError={p.jobError} />
      {(p.job?.state === 'Failed' || p.job?.state === 'Interrupted') && (
        <button onClick={p.onResend} className={`${btn} mb-3 bg-slate-700 text-white`}>
          {p.job.completed > 0
            ? `Check the printer, then remove the ${p.job.completed} sent label(s) and start a new job`
            : 'Nothing was sent: start a new job'}
        </button>
      )}

      {p.items.length === 0 ? (
        <div className="flex flex-col items-center px-6 pt-12 text-center text-slate-500">
          <div className="grid h-20 w-20 place-items-center rounded-full bg-white text-slate-400 shadow-sm ring-1 ring-black/5">
            <Icon name="list" className="h-10 w-10" />
          </div>
          <div className="mt-4 text-lg font-bold text-ink">No labels in the list</div>
          <div className="mt-1 text-sm leading-snug">On the Scan tab, choose Bulk list and scan the products to print together.</div>
        </div>
      ) : (
        <>
          <div className="mb-2 flex items-baseline justify-between">
            <div className="text-base font-bold">{p.items.length} {p.items.length === 1 ? 'label' : 'labels'}</div>
            <div className="text-xs text-slate-500">Prints top to bottom, first scanned first</div>
          </div>
          <ol className="space-y-2">
            {p.items.map((it, i) => (
              <li key={it.id} className="flex items-center gap-3 rounded-xl bg-white py-2.5 pl-3 pr-1.5 shadow-sm ring-1 ring-black/5">
                <div className="grid h-8 w-8 shrink-0 place-items-center rounded-full bg-aisle text-sm font-bold tabular-nums text-slate-600">{i + 1}</div>
                <div className="min-w-0 flex-1">
                  <div className="truncate text-sm font-semibold">{it.longDesc || it.shortDesc}</div>
                  <div className="text-xs tabular-nums text-slate-500">{it.kind === 'sku' ? `SKU ${it.sku}` : it.barcode}</div>
                </div>
                <Price value={it.price} className="text-lg text-brand" />
                <button
                  aria-label={`Remove item ${i + 1}`}
                  onClick={() => p.onRemove(it.id!)}
                  disabled={p.busy}
                  className="grid h-11 w-11 place-items-center rounded-lg text-slate-400 active:bg-red-50 active:text-bad disabled:opacity-40"
                >
                  <Icon name="trash" className="h-5 w-5" />
                </button>
              </li>
            ))}
          </ol>
          <div className="sticky bottom-0 -mx-4 mt-4 space-y-2 bg-aisle/95 px-4 pb-1 pt-3 backdrop-blur">
            <button disabled={p.busy} onClick={p.onSend} className={`${btn} bg-tag text-ink`}>
              {p.busy ? 'Sending…' : `Print all ${p.items.length}`}
            </button>
            <button
              disabled={p.busy}
              onClick={() => {
                if (confirmClear) {
                  setConfirmClear(false);
                  p.onClear();
                } else {
                  setConfirmClear(true);
                }
              }}
              className={`w-full rounded-2xl border py-3 text-sm font-bold ${confirmClear ? 'border-bad bg-bad text-white' : 'border-slate-300 bg-white text-slate-600'}`}
            >
              {confirmClear ? 'Tap again to remove all items' : 'Clear list'}
            </button>
          </div>
        </>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- Sync

function SyncTab({ state, onDone }: { state: AppState | null; onDone: () => void }) {
  const [running, setRunning] = useState(false);
  const [progress, setProgress] = useState<{ phase: string; done: number; total: number } | null>(null);
  const [result, setResult] = useState<{ kind: 'ok' | 'err'; text: string } | null>(null);

  useEffect(() => onEvent('syncProgress', setProgress), []);

  const run = async () => {
    setRunning(true);
    setResult(null);
    setProgress(null);
    try {
      const r = await call<{ count: number }>('sync');
      setResult({ kind: 'ok', text: `Done. ${r.count.toLocaleString()} products are now available offline.` });
    } catch (e) {
      setResult({ kind: 'err', text: `${errText(e)} Your previous product data is still in use.` });
    } finally {
      setRunning(false);
      setProgress(null);
      onDone();
    }
  };

  const pct = progress && progress.total > 0 ? Math.min(100, Math.round((progress.done / progress.total) * 100)) : null;

  return (
    <div>
      <div className="mb-4 rounded-2xl bg-white p-4 shadow-sm ring-1 ring-black/5">
        <div className="text-sm text-slate-500">On this device</div>
        <div className="text-2xl font-extrabold">{(state?.productCount ?? 0).toLocaleString()} products</div>
        <div className="mt-1 text-sm text-slate-600">Last sync: {when(state?.lastSyncMs ?? 0)}</div>
        {state?.serverModifiedUtc && (
          <div className="text-sm text-slate-600">PC file dated: {new Date(state.serverModifiedUtc).toLocaleString()}</div>
        )}
      </div>

      {result && <Banner kind={result.kind}>{result.text}</Banner>}

      {running && (
        <div className="mb-4">
          <div className="mb-1 text-sm font-semibold">
            {progress?.phase ?? 'Starting'} {pct !== null ? `${pct}%` : ''}
          </div>
          <div className="h-3 overflow-hidden rounded-full bg-slate-200">
            <div className="h-full bg-brand transition-all" style={{ width: `${pct ?? 5}%` }} />
          </div>
        </div>
      )}

      <button disabled={running} onClick={run} className={`${btn} bg-brand text-white`}>
        {running ? 'Syncing…' : 'SYNC MASTERFILE NOW'}
      </button>
      <p className="mt-3 text-xs text-slate-500">
        Downloads the complete product list from the PC. Lookups work without Wi-Fi afterwards. If anything goes wrong,
        the previous list stays in use.
      </p>
    </div>
  );
}

// ---------------------------------------------------------------- Settings

function SettingsTab({
  state,
  onSaved,
  update,
  setUpdate,
}: {
  state: AppState | null;
  onSaved: () => void;
  update: UpdateInfo | null;
  setUpdate: (u: UpdateInfo | null) => void;
}) {
  const [upBusy, setUpBusy] = useState(false);
  const [upProgress, setUpProgress] = useState<{ done: number; total: number } | null>(null);
  const [upMsg, setUpMsg] = useState<{ kind: 'ok' | 'warn' | 'err'; text: string } | null>(null);

  useEffect(() => onEvent<{ done: number; total: number }>('updateProgress', setUpProgress), []);

  const checkUpdate = async () => {
    setUpBusy(true);
    setUpMsg(null);
    try {
      const u = await call<UpdateInfo>('updateCheck');
      setUpdate(u);
      if (!u.available) setUpMsg({ kind: 'ok', text: 'You have the latest version.' });
    } catch (e) {
      setUpMsg({ kind: 'err', text: errText(e) });
    } finally {
      setUpBusy(false);
    }
  };

  const installUpdate = async () => {
    setUpBusy(true);
    setUpMsg(null);
    setUpProgress(null);
    try {
      const r = await call<{ status: 'installing' | 'permission' | 'uptodate' }>('updateInstall');
      if (r.status === 'installing') setUpMsg({ kind: 'ok', text: 'Downloaded. Confirm the install on the Android screen. Your list and settings are kept.' });
      else if (r.status === 'permission')
        setUpMsg({ kind: 'warn', text: 'Allow "Install unknown apps" for MobileShelfTag on the screen that just opened, go back, then press the button again.' });
      else setUpMsg({ kind: 'ok', text: 'You already have the latest version.' });
    } catch (e) {
      setUpMsg({ kind: 'err', text: errText(e) });
    } finally {
      setUpBusy(false);
    }
  };

  const [host, setHost] = useState('');
  const [port, setPort] = useState('5088');
  const [token, setToken] = useState('');
  const [msg, setMsg] = useState<{ kind: 'ok' | 'err'; text: string } | null>(null);

  useEffect(() => {
    if (state) {
      setHost(state.host);
      setPort(String(state.port));
    }
  }, [state?.host, state?.port]);

  const save = async () => {
    try {
      await call('saveSettings', { host, port: Number(port), token });
      setToken('');
      setMsg({ kind: 'ok', text: 'Saved.' });
      onSaved();
    } catch (e) {
      setMsg({ kind: 'err', text: errText(e) });
    }
  };

  const test = async () => {
    try {
      await call('saveSettings', { host, port: Number(port), token });
      setToken('');
      const r = await call<{ latencyMs: number; version: string; masterfileAvailable: boolean; printingAvailable: boolean }>('ping');
      setMsg({
        kind: r.printingAvailable ? 'ok' : 'err',
        text:
          `Connected to WM App ${r.version} (${r.latencyMs} ms). ${r.masterfileAvailable ? 'Masterfile found.' : 'The PC has no masterfile yet.'}` +
          (r.printingAvailable ? '' : ' WARNING: printing is switched off on the PC (its print history could not be read). Lookups and sync still work. Tell whoever looks after WM App.'),
      });
      onSaved();
    } catch (e) {
      setMsg({ kind: 'err', text: errText(e) });
    }
  };

  const scanner = async () => {
    try {
      await call('setupScanner');
      setMsg({ kind: 'ok', text: 'Scanner profile sent to DataWedge. Scan a barcode to test.' });
    } catch (e) {
      setMsg({ kind: 'err', text: errText(e) });
    }
  };

  const field = 'mb-3 w-full rounded-2xl border border-slate-300 bg-white px-4 py-3.5 text-base';

  return (
    <div>
      {msg && <Banner kind={msg.kind}>{msg.text}</Banner>}

      <label className="mb-1 block text-sm font-semibold">PC address</label>
      <input className={field} value={host} onChange={(e) => setHost(e.target.value)} placeholder="PC address, e.g. 192.168.1.20" />

      <label className="mb-1 block text-sm font-semibold">Port</label>
      <input className={field} value={port} inputMode="numeric" onChange={(e) => setPort(e.target.value)} />

      <label className="mb-1 block text-sm font-semibold">Access token</label>
      <input
        className={field}
        type="password"
        value={token}
        onChange={(e) => setToken(e.target.value)}
        placeholder={state?.hasToken ? '(saved — leave blank to keep)' : 'from mobile-api.json on the PC'}
      />

      <button onClick={test} className={`${btn} mb-2 bg-brand text-white`}>
        SAVE &amp; TEST CONNECTION
      </button>
      <button onClick={save} className={`${btn} mb-6 bg-white text-slate-700 border border-slate-300`}>
        Save only
      </button>

      <button onClick={scanner} className={`${btn} mb-6 bg-white text-slate-700 border border-slate-300`}>
        Set up scanner (DataWedge)
      </button>

      <div className="rounded-2xl bg-white p-4 shadow-sm ring-1 ring-black/5">
        <div className="text-sm font-semibold">App updates</div>
        <div className="mb-3 text-xs text-slate-500">Installed version {state?.version ?? '…'}</div>
        {!state?.updateEnabled ? (
          <div className="text-sm text-slate-500">Update checking is not available in this build.</div>
        ) : (
          <>
            {upMsg && <Banner kind={upMsg.kind}>{upMsg.text}</Banner>}
            {update?.available && (
              <div className="mb-3 rounded-lg bg-amber-50 p-3 text-sm">
                <div className="font-bold">Version {update.version} is available</div>
                {update.notes && <div className="mt-1 whitespace-pre-wrap text-slate-600">{update.notes}</div>}
                {upBusy && upProgress && upProgress.total > 0 && (
                  <div className="mt-2 text-xs text-slate-500">
                    Downloading {Math.round((upProgress.done / upProgress.total) * 100)}%
                  </div>
                )}
                <button disabled={upBusy} onClick={installUpdate} className={`${btn} mt-3 bg-tag text-slate-900 disabled:opacity-50`}>
                  {upBusy ? 'Please wait…' : 'DOWNLOAD & INSTALL UPDATE'}
                </button>
              </div>
            )}
            <button
              disabled={upBusy}
              onClick={checkUpdate}
              className={`${btn} bg-white text-slate-700 border border-slate-300 disabled:opacity-50`}
            >
              {upBusy && !update?.available ? 'Checking…' : 'Check for updates'}
            </button>
          </>
        )}
      </div>
    </div>
  );
}
