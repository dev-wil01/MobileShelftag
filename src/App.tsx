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

  const tabs: [Tab, string][] = [
    ['scan', 'Scan'],
    ['list', `List${items.length ? ` (${items.length})` : ''}`],
    ['sync', 'Sync'],
    ['settings', 'Settings'],
  ];

  return (
    <div className="flex h-full flex-col text-slate-900">
      <header className="bg-[#005A9C] px-4 py-3 text-white">
        <div className="text-lg font-bold">MobileShelfTag</div>
        <div className="text-xs opacity-90">
          {state ? `${state.productCount.toLocaleString()} barcodes · ${state.skuCount.toLocaleString()} SKUs · synced ${when(state.lastSyncMs)} · v${state.version}` : 'Starting…'}
        </div>
      </header>

      {update?.available && tab !== 'settings' && (
        <button onClick={() => setTab('settings')} className="bg-[#FFD100] px-4 py-2 text-left text-sm font-bold text-slate-900">
          Update available: version {update.version}. Tap to install.
        </button>
      )}

      <main className="flex-1 overflow-y-auto p-4">
        {tab === 'scan' && (
          <ScanTab
            mode={mode}
            setMode={setMode}
            inputType={inputType}
            setInputType={setInputType}
            last={last}
            notice={notice}
            onManual={handleScan}
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

      <nav className="grid grid-cols-4 border-t border-slate-300 bg-white">
        {tabs.map(([key, label]) => (
          <button
            key={key}
            onClick={() => setTab(key)}
            className={`py-3 text-sm font-semibold ${tab === key ? 'bg-[#FFD100] text-slate-900' : 'text-slate-600'}`}
          >
            {label}
          </button>
        ))}
      </nav>
    </div>
  );
}

// ---------------------------------------------------------------- shared bits

function Banner({ kind, children }: { kind: 'ok' | 'warn' | 'err'; children: React.ReactNode }) {
  const color =
    kind === 'ok' ? 'bg-emerald-100 text-emerald-900' : kind === 'warn' ? 'bg-amber-100 text-amber-900' : 'bg-red-100 text-red-900';
  return <div className={`mb-3 rounded-lg p-3 text-sm font-medium ${color}`}>{children}</div>;
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

const btn = 'w-full rounded-xl py-4 text-base font-bold disabled:opacity-50';

// ---------------------------------------------------------------- Scan

function ScanTab(p: {
  mode: Mode;
  setMode: (m: Mode) => void;
  inputType: InputType;
  setInputType: (t: InputType) => void;
  last: ScanResult | null;
  notice: { kind: 'ok' | 'warn' | 'err'; text: string } | null;
  onManual: (code: string) => void;
  listCount: number;
  job: BatchInfo | null;
  jobError: string | null;
  busy: boolean;
  onPrintOne: (p: Product) => void;
}) {
  const [text, setText] = useState('');
  const product = p.last?.status === 'ok' ? p.last.product : undefined;

  return (
    <div>
      <div className="mb-3 grid grid-cols-2 overflow-hidden rounded-xl border border-slate-300 bg-white">
        {(['single', 'bulk'] as Mode[]).map((m) => (
          <button
            key={m}
            onClick={() => p.setMode(m)}
            className={`py-3 text-sm font-bold ${p.mode === m ? 'bg-[#005A9C] text-white' : 'text-slate-600'}`}
          >
            {m === 'single' ? 'Single label' : 'Bulk list'}
          </button>
        ))}
      </div>

      <div className="mb-3 grid grid-cols-2 overflow-hidden rounded-xl border border-slate-300 bg-white">
        {(['barcode', 'sku'] as InputType[]).map((k) => (
          <button
            key={k}
            onClick={() => p.setInputType(k)}
            className={`py-2 text-sm font-bold ${p.inputType === k ? 'bg-[#FFD100] text-slate-900' : 'text-slate-600'}`}
          >
            {k === 'barcode' ? 'Input: Barcode' : 'Input: SKU / Weighted'}
          </button>
        ))}
      </div>

      {p.notice && <Banner kind={p.notice.kind}>{p.notice.text}</Banner>}
      <JobStatus job={p.job} jobError={p.jobError} />

      <form
        className="mb-4 flex gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          if (text.trim()) p.onManual(text.trim());
          setText('');
        }}
      >
        <input
          value={text}
          onChange={(e) => setText(e.target.value)}
          inputMode={p.inputType === 'sku' ? 'text' : 'numeric'}
          placeholder={p.inputType === 'sku' ? 'Type the SKU (weighted items)' : 'Scan, or type a barcode'}
          className="min-w-0 flex-1 rounded-xl border border-slate-300 bg-white px-3 py-3 text-base"
        />
        <button className="rounded-xl bg-[#005A9C] px-4 font-bold text-white">Find</button>
      </form>

      {product ? (
        <div className="rounded-2xl bg-white p-4 shadow">
          <div className="text-sm text-slate-500">
            {product.kind === 'sku' ? `SKU ${product.sku} · found by SKU` : `${product.barcode} · SKU ${product.sku}`}
          </div>
          <div className="mt-1 text-lg font-bold leading-tight">{product.longDesc || product.shortDesc}</div>
          <div className="mt-2 text-4xl font-extrabold text-[#005A9C]">{peso(product.price)}</div>
          {p.mode === 'single' ? (
            <button
              disabled={p.busy}
              onClick={() => p.onPrintOne(product)}
              className={`${btn} mt-4 bg-[#FFD100] text-slate-900`}
            >
              {p.busy ? 'Printing…' : 'PRINT 1 LABEL'}
            </button>
          ) : (
            <div className="mt-3 rounded-lg bg-emerald-50 p-2 text-center text-sm font-semibold text-emerald-800">
              Added to the list · {p.listCount} item(s)
            </div>
          )}
        </div>
      ) : (
        <div className="mt-10 text-center text-slate-400">
          {p.inputType === 'sku' ? 'Type a SKU (for weighted items)' : 'Scan a product barcode'}
        </div>
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
        <div className="mt-10 text-center text-slate-400">The list is empty. Switch to Bulk list and scan products.</div>
      ) : (
        <>
          <div className="mb-2 text-xs text-slate-500">Labels print in this order (first scanned first).</div>
          <ol className="mb-4 space-y-2">
            {p.items.map((it, i) => (
              <li key={it.id} className="flex items-center gap-3 rounded-xl bg-white p-3 shadow-sm">
                <div className="w-7 text-center text-sm font-bold text-slate-400">{i + 1}</div>
                <div className="min-w-0 flex-1">
                  <div className="truncate text-sm font-semibold">{it.longDesc || it.shortDesc}</div>
                  <div className="text-xs text-slate-500">{it.kind === 'sku' ? `SKU ${it.sku}` : it.barcode}</div>
                </div>
                <div className="text-sm font-bold text-[#005A9C]">{peso(it.price)}</div>
                <button
                  aria-label="Remove"
                  onClick={() => p.onRemove(it.id!)}
                  disabled={p.busy}
                  className="rounded-lg px-2 py-1 text-lg text-red-600 disabled:opacity-40"
                >
                  ✕
                </button>
              </li>
            ))}
          </ol>
          <button disabled={p.busy} onClick={p.onSend} className={`${btn} mb-2 bg-[#FFD100] text-slate-900`}>
            {p.busy ? 'Sending…' : `PRINT ALL (${p.items.length})`}
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
            className={`${btn} bg-white text-red-700 border border-red-300`}
          >
            {confirmClear ? 'Tap again to remove ALL items' : 'Clear list'}
          </button>
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
      <div className="mb-4 rounded-2xl bg-white p-4 shadow">
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
            <div className="h-full bg-[#005A9C] transition-all" style={{ width: `${pct ?? 5}%` }} />
          </div>
        </div>
      )}

      <button disabled={running} onClick={run} className={`${btn} bg-[#005A9C] text-white`}>
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

  const field = 'mb-3 w-full rounded-xl border border-slate-300 bg-white px-3 py-3 text-base';

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

      <button onClick={test} className={`${btn} mb-2 bg-[#005A9C] text-white`}>
        SAVE &amp; TEST CONNECTION
      </button>
      <button onClick={save} className={`${btn} mb-6 bg-white text-slate-700 border border-slate-300`}>
        Save only
      </button>

      <button onClick={scanner} className={`${btn} mb-6 bg-white text-slate-700 border border-slate-300`}>
        Set up scanner (DataWedge)
      </button>

      <div className="rounded-2xl bg-white p-4 shadow-sm">
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
                <button disabled={upBusy} onClick={installUpdate} className={`${btn} mt-3 bg-[#FFD100] text-slate-900 disabled:opacity-50`}>
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
