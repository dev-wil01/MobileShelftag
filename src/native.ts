// The page has no network and no storage of its own: everything goes through the Android bridge.

declare global {
  interface Window {
    Android?: { call(id: string, method: string, argsJson: string): void };
    __native?: {
      resolve(id: string, ok: boolean, payload: string): void;
      emit(name: string, payload: string): void;
    };
  }
}

type Pending = { resolve: (v: any) => void; reject: (e: Error) => void };
const pending = new Map<string, Pending>();
const listeners = new Map<string, Set<(payload: any) => void>>();
let counter = 0;

window.__native = {
  resolve(id, ok, payload) {
    const p = pending.get(id);
    if (!p) return;
    pending.delete(id);
    if (ok) p.resolve(JSON.parse(payload));
    else p.reject(new Error(payload));
  },
  emit(name, payload) {
    const data = JSON.parse(payload);
    listeners.get(name)?.forEach((cb) => cb(data));
  },
};

export function call<T = any>(method: string, args: object = {}): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    if (!window.Android) {
      reject(new Error('This screen only works inside the MobileShelfTag app on the handheld.'));
      return;
    }
    const id = `c${++counter}`;
    pending.set(id, { resolve, reject });
    window.Android.call(id, method, JSON.stringify(args));
  });
}

export function onEvent<T = any>(name: string, cb: (payload: T) => void): () => void {
  if (!listeners.has(name)) listeners.set(name, new Set());
  listeners.get(name)!.add(cb);
  return () => listeners.get(name)!.delete(cb);
}
