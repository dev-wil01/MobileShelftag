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
    val kind: String = "barcode"
)

/**
 * The offline product database (SQLite file products.db). It is only ever REPLACED as a whole,
 * after a complete, verified download has been imported into a separate staging file, so a
 * failed sync can never damage the last good data.
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

    @Synchronized
    fun find(normalizedBarcode: String): Product? {
        val d = open() ?: return null
        d.rawQuery(
            "SELECT barcode, sku, short_desc, price, long_desc FROM products WHERE barcode = ? LIMIT 1",
            arrayOf(normalizedBarcode)
        ).use { c ->
            if (!c.moveToFirst()) return null
            return Product(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4))
        }
    }

    /** First masterfile row of a SKU (also weighted items). The product keeps the row's own barcode, which is "0" for weighted items. */
    @Synchronized
    fun findSku(sku: String): Product? {
        val d = open() ?: return null
        d.rawQuery(
            "SELECT barcode, sku, short_desc, price, long_desc FROM skus WHERE sku = ? LIMIT 1",
            arrayOf(sku)
        ).use { c ->
            if (!c.moveToFirst()) return null
            return Product(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), "sku")
        }
    }

    /** False for a database made by an older app version (before SKU input existed): a new sync is needed. */
    @Synchronized
    fun hasSkus(): Boolean {
        val d = open() ?: return false
        d.rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'skus'", null).use { c ->
            return c.moveToFirst()
        }
    }

    @Synchronized
    fun count(): Int {
        val d = open() ?: return 0
        d.rawQuery("SELECT COUNT(*) FROM products", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /** Creates an empty staging database (any old one is removed). The caller fills and closes it. */
    fun createStaging(): SQLiteDatabase {
        deleteWithSidecars(stagingFile)
        val s = SQLiteDatabase.openOrCreateDatabase(stagingFile, null)
        s.execSQL(
            "CREATE TABLE products (barcode TEXT PRIMARY KEY, sku TEXT, short_desc TEXT, " +
                "price TEXT, long_desc TEXT, raw_line TEXT) WITHOUT ROWID"
        )
        s.execSQL(
            "CREATE TABLE skus (sku TEXT PRIMARY KEY, barcode TEXT, short_desc TEXT, " +
                "price TEXT, long_desc TEXT, raw_line TEXT) WITHOUT ROWID"
        )
        return s
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
