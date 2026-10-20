package com.waltermart.mobileshelftag

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class ScanItem(val id: Long, val product: Product)

/**
 * The bulk list. Every scan is written to disk immediately (so it survives a crash or a power
 * loss) and the row id only ever increases, so "ORDER BY id" IS the scan order.
 * It lives in its own file, so replacing the product database never touches it.
 */
class ScanListStore(context: Context) :
    SQLiteOpenHelper(context, "scanlist.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE scans (id INTEGER PRIMARY KEY AUTOINCREMENT, barcode TEXT NOT NULL, " +
                "sku TEXT, short_desc TEXT, price TEXT, long_desc TEXT, scanned_at INTEGER NOT NULL, " +
                "kind TEXT NOT NULL DEFAULT 'barcode')"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 -> v2: remember how each item was looked up; the saved list and its order stay untouched
        if (oldVersion < 2) db.execSQL("ALTER TABLE scans ADD COLUMN kind TEXT NOT NULL DEFAULT 'barcode'")
    }

    @Synchronized
    fun add(p: Product): Long {
        val v = ContentValues().apply {
            put("barcode", p.barcode)
            put("sku", p.sku)
            put("short_desc", p.shortDesc)
            put("price", p.price)
            put("long_desc", p.longDesc)
            put("scanned_at", System.currentTimeMillis())
            put("kind", p.kind)
        }
        return writableDatabase.insertOrThrow("scans", null, v)
    }

    /** Oldest scan first. */
    @Synchronized
    fun all(): List<ScanItem> {
        val out = ArrayList<ScanItem>()
        readableDatabase.rawQuery(
            "SELECT id, barcode, sku, short_desc, price, long_desc, kind FROM scans ORDER BY id ASC", null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(ScanItem(c.getLong(0), Product(c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6))))
            }
        }
        return out
    }

    @Synchronized
    fun remove(id: Long) {
        writableDatabase.delete("scans", "id = ?", arrayOf(id.toString()))
    }

    /** Removes the oldest [n] rows (labels that were already handed to the printer). */
    @Synchronized
    fun removeOldest(n: Int) {
        if (n <= 0) return
        writableDatabase.execSQL(
            "DELETE FROM scans WHERE id IN (SELECT id FROM scans ORDER BY id ASC LIMIT $n)"
        )
    }

    @Synchronized
    fun clear() {
        writableDatabase.delete("scans", null, null)
    }
}
