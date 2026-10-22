package com.waltermart.mobileshelftag

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.security.MessageDigest

/**
 * Manual, full masterfile sync:
 *  1. ask the PC what the file is (size, SHA-256);
 *  2. download it to a temporary file, checking the SHA-256 (catches an incomplete download);
 *  3. import EVERY line of it into a STAGING database (no line is refused or ignored);
 *  4. check that stored rows + blank lines = lines in the file, and that the database really holds that many rows;
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
        val sizeBytes = info.getLong("sizeBytes")
        val modified = info.optString("modifiedUtc")

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

            // 3. import every line into staging
            progress("Saving products", 0, 0)
            val staging = store.createStaging()
            var totalLines = 0
            var blank = 0
            var stored = 0
            var incomplete = 0
            var weighted = 0
            var duplicateRows = 0
            val seenBarcodes = HashSet<String>()
            try {
                staging.beginTransaction()
                val stmt = staging.compileStatement(
                    "INSERT INTO items (barcode, sku, short_desc, price, long_desc, raw_line, problem) VALUES (?,?,?,?,?,?,?)"
                )
                BufferedReader(InputStreamReader(tmp.inputStream(), Charsets.UTF_8), 64 * 1024).use { r ->
                    var first = true
                    while (true) {
                        var line = r.readLine() ?: break
                        if (first) { line = line.removePrefix("﻿"); first = false }
                        totalLines++

                        val row = MasterfileParser.parse(line)
                        if (row == null) { blank++; continue }

                        stmt.clearBindings()
                        stmt.bindString(1, row.barcode)
                        stmt.bindString(2, row.sku)
                        stmt.bindString(3, row.shortDesc)
                        stmt.bindString(4, row.price)
                        stmt.bindString(5, row.longDesc)
                        stmt.bindString(6, row.rawLine)
                        stmt.bindString(7, row.problem)
                        stmt.executeInsert()
                        stored++

                        if (!row.usable) incomplete++
                        else if (row.weighted) weighted++
                        else if (!seenBarcodes.add(row.barcode)) duplicateRows++

                        if (stored % 5000 == 0) progress("Saving products", stored.toLong(), 0)
                    }
                }
                staging.setTransactionSuccessful()
            } finally {
                staging.endTransaction()
            }

            // indexes after the bulk insert, then verify what is really in the database
            progress("Preparing lookups", 0, 0)
            val inDb: Int
            try {
                store.createIndexes(staging)
                inDb = staging.rawQuery("SELECT COUNT(*) FROM items", null).use { if (it.moveToFirst()) it.getInt(0) else -1 }
            } finally {
                staging.close()
            }

            // 4. validate: every line is accounted for
            if (stored + blank != totalLines || inDb != stored) {
                throw ApiException("The saved rows ($inDb) do not match the lines in the file ($totalLines). The old data was kept.")
            }
            if (stored == 0) throw ApiException("The masterfile has no data. The old data was kept.")

            // 5. replace
            progress("Activating", 0, 0)
            store.swapInStaging()

            val summary = JSONObject()
                .put("totalLines", totalLines).put("stored", stored).put("blank", blank)
                .put("incomplete", incomplete).put("weighted", weighted).put("duplicateRows", duplicateRows)

            prefs.lastSyncMs = System.currentTimeMillis()
            prefs.recordCount = stored
            prefs.masterfileSha = expectedSha
            prefs.serverModifiedUtc = modified
            prefs.syncSummary = summary.toString()

            return JSONObject(summary.toString()).put("count", stored).put("syncedAtMs", prefs.lastSyncMs)
        } finally {
            tmp.delete()
            store.discardStaging()
        }
    }
}
