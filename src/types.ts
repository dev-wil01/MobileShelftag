export interface Product {
  id?: number;
  barcode: string;
  sku: string;
  shortDesc: string;
  price: string;
  longDesc: string;
}

export interface AppState {
  host: string;
  port: number;
  hasToken: boolean;
  productCount: number;
  lastSyncMs: number;
  serverModifiedUtc: string;
  listCount: number;
}

export type ScanStatus = 'ok' | 'invalid' | 'notfound' | 'nodata';

export interface ScanResult {
  status: ScanStatus;
  barcode?: string;
  product?: Product;
  listCount?: number;
}

export interface BatchInfo {
  batchId: string;
  state: 'Queued' | 'Printing' | 'HandedToBarTender' | 'Failed' | 'Interrupted';
  total: number;
  completed: number;
  failedSeq: number | null;
  uncertainSeq: number | null;
  error: string | null;
}
