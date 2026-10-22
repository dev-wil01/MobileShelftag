package com.waltermart.mobileshelftag

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

data class Product(
    val barcode: String,
    val sku: String,
    val shortDesc: String,
    val price: String,
    val longDesc: String,
    /** "barcode" or "sku": how it was looked up (decides how it is sent to the printer). */
    val kind: String = "barcode",
    /** Empty when the masterfile row can be printed, otherwise why it cannot. */
    val problem: String = ""
) {
    val usable: Boolean get() = problem.isEmpty()
}

/**
 * The offline product database (SQLite file products.db): ONE table, `items`, with every line of the
 * masterfile in file order (id = line order). It is only ever REPLACED as a whole, after a complete,
 * verified download has been imported into a separate staging file, so a failed sync can never damage
 * the last good data.
 *
 * Lookups return the FIRST printable row (same rule as the PC); if only unprintable rows match, the first
 * of those is returned with its problem so the screen can explain it.
 */
class ProductStore(context: Context) {
    private val dir: File = context.getDatabasePath("products.db").parentFile!!.also { it.mkdirs() }
    val liveFile = File(dir, "products.db")
    val stagingFile = File(dir, "products_staging.db")
    private val previousFile = File(dir, "products_prev.db")

    private var db: SQLiteDatabase? = null

    @Synchronized
    private fun open(): SQLiteDatabase? {
        db?.let { if (it.isOpen) return it }
        if (!liveFile.exists()) return null
        db = SQLiteDatabase.openDatabase(liveFile.path, null, SQLiteDatabase.OPEN_READONLY)
        return db
    }

    private fun hasItemsTable(d: SQLiteDatabase): Boolean =
        d.rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'items'", null).use { it.moveToFirst() }

    @Synchronized
    fun find(barcode: String): Product? = lookup("barcode", barcode, "barcode")

    @Synchronized
    fun findSku(sku: String): Product? = lookup("sku", sku, "sku")

    private fun lookup(column: String, value: String, kind: String): Product? {
        val d = open() ?: return null
        if (!hasItemsTable(d)) return null
        // printable rows first (problem = ''), then file order
        d.rawQuery(
            "SELECT barcode, sku, short_desc, price, long_desc, problem FROM items WHERE $column = ? " +
                "ORDER BY (problem <> ''), id LIMIT 1",
            arrayOf(value)
        ).use { c ->
            if (!c.moveToFirst()) return null
            return Product(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), kind, c.getString(5))
        }
    }

    /** Number of masterfile rows stored on this device. 0 when there is no data, or the data is from an older app version (sync again). */
    @Synchronized
    fun count(): Int {
        val d = open() ?: return 0
        if (!hasItemsTable(d)) return 0
        d.rawQuery("SELECT COUNT(*) FROM items", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    @Synchronized
    fun skuCount(): Int {
        val d = open() ?: return 0
        if (!hasItemsTable(d)) return 0
        d.rawQuery("SELECT COUNT(DISTINCT sku) FROM items WHERE sku <> ''", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /** Creates an empty staging database (any old one is removed). The caller fills it, calls [createIndexes], and closes it. */
    fun createStaging(): SQLiteDatabase {
        deleteWithSidecars(stagingFile)
        val s = SQLiteDatabase.openOrCreateDatabase(stagingFile, null)
        s.execSQL(
            "CREATE TABLE items (id INTEGER PRIMARY KEY, barcode TEXT NOT NULL, sku TEXT NOT NULL, " +
                "short_desc TEXT NOT NULL, price TEXT NOT NULL, long_desc TEXT NOT NULL, raw_line TEXT NOT NULL, " +
                "problem TEXT NOT NULL)"
        )
        return s
    }

    /** Built after the bulk insert (much faster than maintaining them row by row). */
    fun createIndexes(s: SQLiteDatabase) {
        s.execSQL("CREATE INDEX idx_items_barcode ON items (barcode)")
        s.execSQL("CREATE INDEX idx_items_sku ON items (sku)")
    }

    /** Puts the verified staging file in place. If anything goes wrong the previous database is restored. */
    @Synchronized
    fun swapInStaging() {
        db?.close()
        db = null

        deleteWithSidecars(previousFile)
        val hadLive = liveFile.exists()
        if (hadLive && !liveFile.renameTo(previousFile)) {
            throw IllegalStateException("Could not set aside the current product database.")
        }
        if (!stagingFile.renameTo(liveFile)) {
            if (hadLive) previousFile.renameTo(liveFile)
            throw IllegalStateException("Could not activate the new product database; the old one was kept.")
        }
        deleteWithSidecars(previousFile)
    }

    fun discardStaging() = deleteWithSidecars(stagingFile)

    private fun deleteWithSidecars(f: File) {
        f.delete()
        File(f.path + "-journal").delete()
        File(f.path + "-wal").delete()
        File(f.path + "-shm").delete()
    }
}
