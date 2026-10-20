export interface Product {
  id?: number;
  barcode: string;
  sku: string;
  shortDesc: string;
  price: string;
  longDesc: string;
  /** how it was looked up: by barcode, or by SKU (weighted items) */
  kind?: 'barcode' | 'sku';
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
}

export interface UpdateInfo {
  available: boolean;
  version?: string;
  code?: number;
  notes?: string;
  sizeBytes?: number;
}

export type ScanStatus = 'ok' | 'invalid' | 'notfound' | 'nodata' | 'nosku';

export type InputType = 'barcode' | 'sku';

export interface ScanResult {
  status: ScanStatus;
  barcode?: string;
  type?: InputType;
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
