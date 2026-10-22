export interface Product {
  id?: number;
  barcode: string;
  sku: string;
  shortDesc: string;
  price: string;
  longDesc: string;
  /** how it was looked up: by barcode, or by SKU (weighted items) */
  kind?: 'barcode' | 'sku';
  /** empty when the masterfile row can be printed */
  problem?: string;
}

export interface AppState {
  host: string;
  port: number;
  hasToken: boolean;
  productCount: number;
  skuCount: number;
  lastSyncMs: number;
  serverModifiedUtc: string;
  listCount: number;
  version: string;
  versionCode: number;
  updateEnabled: boolean;
  /** Reconciliation of the last sync: every line of the masterfile is accounted for. */
  summary?: SyncSummary;
}

export interface SyncSummary {
  totalLines?: number;
  stored?: number;
  blank?: number;
  incomplete?: number;
  weighted?: number;
  duplicateRows?: number;
}

export interface UpdateInfo {
  available: boolean;
  version?: string;
  code?: number;
  notes?: string;
  sizeBytes?: number;
}

export type ScanStatus = 'ok' | 'invalid' | 'notfound' | 'nodata' | 'unusable';

export type InputType = 'barcode' | 'sku';

export interface ScanResult {
  status: ScanStatus;
  barcode?: string;
  type?: InputType;
  product?: Product;
  problem?: string;
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
