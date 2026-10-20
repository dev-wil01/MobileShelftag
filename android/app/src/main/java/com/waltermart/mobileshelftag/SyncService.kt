package com.waltermart.mobileshelftag

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.security.MessageDigest

/**
 * Manual, full masterfile sync:
 *  1. ask the PC what the file is (size, record count, SHA-256);
 *  2. download it to a temporary file, checking the SHA-256 (catches an incomplete download);
 *  3. import it into a STAGING database in one transaction;
 *  4. check the imported count equals the PC's count;
 *  5. only then replace the live database.
 * Any failure leaves the previous database in use.
 */
class SyncService(
    private val context: Context,
    private val prefs: Prefs,
    private val api: ApiClient,
    private val store: ProductStore
) {
    fun run(progress: (phase: String, done: Long, total: Long) -> Unit): JSONObject {
        progress("Checking the PC", 0, 0)
        val infoRes = api.get("/api/v1/masterfile/info", 30000)
        if (infoRes.code != 200) throw ApiException(ApiClient.describe(infoRes.code, infoRes.body))

        val info = JSONObject(infoRes.body)
        val expectedSha = info.getString("sha256")
        val expectedCount = info.getInt("recordCount")
        val expectedSkuCount = info.optInt("skuCount", -1) // -1: an older WMApp that does not know SKUs
        val sizeBytes = info.getLong("sizeBytes")
        val modified = info.optString("modifiedUtc")

        if (expectedCount <= 0) throw ApiException("The masterfile on the PC has no usable records.")

        val tmp = File(context.cacheDir, "masterfile.tmp")
        tmp.delete()

        try {
            // 2. download + hash
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            api.download("/api/v1/masterfile") { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        received += n
                        progress("Downloading", received, sizeBytes)
                    }
                }
            }
            val actualSha = digest.digest().joinToString("") { "%02X".format(it) }
            if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                throw ApiException("The download was incomplete or the file changed while downloading. Try again.")
            }

            // 3. import into staging
            progress("Saving products", 0, expectedCount.toLong())
            val staging = store.createStaging()
            var inserted = 0
            var skuInserted = 0
            try {
                staging.beginTransaction()
                val stmt = staging.compileStatement(
                    "INSERT OR IGNORE INTO products (barcode, sku, short_desc, price, long_desc, raw_line) VALUES (?,?,?,?,?,?)"
                )
                val skuStmt = staging.compileStatement(
                    "INSERT OR IGNORE INTO skus (sku, barcode, short_desc, price, long_desc, raw_line) VALUES (?,?,?,?,?,?)"
                )
                BufferedReader(InputStreamReader(tmp.inputStream(), Charsets.UTF_8), 64 * 1024).use { r ->
                    var first = true
                    var lineNo = 0
                    while (true) {
                        var line = r.readLine() ?: break
                        if (first) { line = line.removePrefix("﻿"); first = false }
                        val any = MasterfileParser.parseAny(line) ?: continue

                        // SKU table: every usable row, the FIRST row of a SKU wins (same order as the PC)
                        if (any.sku.isNotEmpty()) {
                            skuStmt.clearBindings()
                            skuStmt.bindString(1, any.sku)
                            skuStmt.bindString(2, any.barcode)
                            skuStmt.bindString(3, any.shortDesc)
                            skuStmt.bindString(4, any.price)
                            skuStmt.bindString(5, any.longDesc)
                            skuStmt.bindString(6, any.rawLine)
                            if (skuStmt.executeInsert() != -1L) skuInserted++
                        }

                        // barcode table: weighted/blank barcodes (0) stay out
                        val row = MasterfileParser.parseLine(line)
                        if (row != null) {
                            stmt.clearBindings()
                            stmt.bindString(1, row.barcode)
                            stmt.bindString(2, row.sku)
                            stmt.bindString(3, row.shortDesc)
                            stmt.bindString(4, row.price)
                            stmt.bindString(5, row.longDesc)
                            stmt.bindString(6, row.rawLine)
                            if (stmt.executeInsert() != -1L) inserted++
                        }
                        lineNo++
                        if (lineNo % 5000 == 0) progress("Saving products", inserted.toLong(), expectedCount.toLong())
                    }
                }
                staging.setTransactionSuccessful()
            } finally {
                staging.endTransaction()
                staging.close()
            }

            // 4. validate
            if (inserted != expectedCount) {
                throw ApiException("The imported product count ($inserted) does not match the PC ($expectedCount). The old data was kept.")
            }
            if (expectedSkuCount >= 0 && skuInserted != expectedSkuCount) {
                throw ApiException("The imported SKU count ($skuInserted) does not match the PC ($expectedSkuCount). The old data was kept.")
            }

            // 5. replace
            progress("Activating", 0, 0)
            store.swapInStaging()

            prefs.lastSyncMs = System.currentTimeMillis()
            prefs.recordCount = inserted
            prefs.masterfileSha = expectedSha
            prefs.serverModifiedUtc = modified

            return JSONObject().put("count", inserted).put("syncedAtMs", prefs.lastSyncMs)
        } finally {
            tmp.delete()
            store.discardStaging()
        }
    }
}
