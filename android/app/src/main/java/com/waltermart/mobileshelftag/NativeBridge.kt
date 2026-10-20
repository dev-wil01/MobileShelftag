package com.waltermart.mobileshelftag

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

/**
 * The only door between the web screens and the device. The web page has no network access
 * of its own: every network call, database access and DataWedge action happens here.
 *
 * Two single-thread workers:
 *  - "data" handles scans and the bulk list strictly one after another (so scan order is kept);
 *  - "io" handles network work, so a slow PC never blocks scanning.
 */
class NativeBridge(private val context: Context, private val webView: WebView) {
    private val prefs = Prefs(context)
    private val products = ProductStore(context)
    private val scans = ScanListStore(context)
    private val api = ApiClient(prefs)
    private val sync = SyncService(context, prefs, api, products)

    private val dataWorker = Executors.newSingleThreadExecutor()
    private val ioWorker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun call(id: String, method: String, argsJson: String) {
        val worker = if (method in IO_METHODS) ioWorker else dataWorker
        worker.execute {
            try {
                val result = dispatch(method, JSONObject(argsJson.ifBlank { "{}" }))
                resolve(id, true, result.toString())
            } catch (e: ApiException) {
                resolve(id, false, e.message ?: "Network error")
            } catch (e: Exception) {
                resolve(id, false, "Unexpected error: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** Called by MainActivity when DataWedge delivers a scan. */
    fun emitScan(barcode: String) = emit("scan", JSONObject().put("barcode", barcode))

    private fun dispatch(method: String, a: JSONObject): Any = when (method) {
        "getState" -> state()
        "saveSettings" -> saveSettings(a)
        "setupScanner" -> { DataWedge.configure(context); JSONObject().put("ok", true) }
        "ping" -> ping()
        "sync" -> runSync()
        "scan" -> scan(a)
        "listGet" -> listGet()
        "listRemove" -> { scans.remove(a.getLong("id")); resetPending(); listGet() }
        "listClear" -> { scans.clear(); resetPending(); listGet() }
        "printList" -> printList()
        "printSingle" -> printSingle(a)
        "batchStatus" -> batchStatus(a.getString("batchId"))
        /** Removes the oldest [completed] labels: the ones already sent. */
        "resendRemaining" -> { scans.removeOldest(a.getInt("completed")); resetPending(); listGet() }
        else -> throw IllegalArgumentException("Unknown method $method")
    }

    // ---- state / settings ------------------------------------------------------------

    private fun state(): JSONObject = JSONObject()
        .put("host", prefs.host)
        .put("port", prefs.port)
        .put("hasToken", prefs.token.isNotBlank())
        .put("productCount", products.count())
        .put("lastSyncMs", prefs.lastSyncMs)
        .put("serverModifiedUtc", prefs.serverModifiedUtc)
        .put("listCount", scans.all().size)

    private fun saveSettings(a: JSONObject): JSONObject {
        val host = a.getString("host").trim()
        val port = a.getInt("port")
        HostRules.problem(host)?.let { throw ApiException(it) }
        if (port < 1024 || port > 65535) throw ApiException("The port must be between 1024 and 65535.")
        prefs.host = host
        prefs.port = port
        val token = a.optString("token").trim()
        if (token.isNotEmpty()) prefs.token = token
        return state()
    }

    private fun requireConnection() {
        if (!prefs.hasConnection()) throw ApiException("Enter the PC address and token in Settings first.")
    }

    private fun ping(): JSONObject {
        requireConnection()
        val t0 = System.currentTimeMillis()
        val r = api.get("/api/v1/ping", 8000)
        if (r.code != 200) throw ApiException(ApiClient.describe(r.code, r.body))
        val j = JSONObject(r.body)
        return JSONObject().put("ok", true).put("version", j.optString("version"))
            .put("masterfileAvailable", j.optBoolean("masterfileAvailable"))
            .put("printingAvailable", j.optBoolean("printingAvailable", true))
            .put("latencyMs", System.currentTimeMillis() - t0)
    }

    private fun runSync(): JSONObject {
        requireConnection()
        var lastEmit = 0L
        return sync.run { phase, done, total ->
            val now = System.currentTimeMillis()
            if (now - lastEmit > 250 || (total > 0 && done >= total)) {
                lastEmit = now
                emit("syncProgress", JSONObject().put("phase", phase).put("done", done).put("total", total))
            }
        }
    }

    // ---- scanning / bulk list --------------------------------------------------------

    /** Looks a barcode up in the offline database. With add=true a found product is appended to the bulk list. */
    private fun scan(a: JSONObject): JSONObject {
        val key = BarcodeRules.normalize(a.optString("barcode"))
            ?: return JSONObject().put("status", "invalid")
        if (products.count() == 0) return JSONObject().put("status", "nodata")
        val p = products.find(key) ?: return JSONObject().put("status", "notfound").put("barcode", key)

        val out = JSONObject().put("status", "ok").put("product", productJson(p))
        if (a.optBoolean("add")) {
            scans.add(p)
            resetPending()
            out.put("listCount", scans.all().size)
        }
        return out
    }

    private fun productJson(p: Product) = JSONObject()
        .put("barcode", p.barcode).put("sku", p.sku).put("shortDesc", p.shortDesc)
        .put("price", p.price).put("longDesc", p.longDesc)

    private fun listGet(): JSONObject {
        val arr = JSONArray()
        val items = scans.all()
        items.forEach { arr.put(productJson(it.product).put("id", it.id)) }
        return JSONObject().put("items", arr).put("count", items.size)
    }

    // ---- printing --------------------------------------------------------------------

    /** The same list always keeps the same batch id, so pressing SEND again after a timeout cannot print twice. */
    private fun printList(): JSONObject {
        requireConnection()
        val items = scans.all()
        if (items.isEmpty()) throw ApiException("The list is empty.")

        val fingerprint = BatchPlan.fingerprint(items.map { it.id to it.product.barcode })
        if (prefs.pendingBatchId.isBlank() || prefs.pendingFingerprint != fingerprint) {
            prefs.pendingBatchId = newBatchId()
            prefs.pendingFingerprint = fingerprint
        }
        val batchId = prefs.pendingBatchId

        val body = JSONObject().put("batchId", batchId)
        val arr = JSONArray()
        BatchPlan.lines(items.map { it.product.barcode }).forEach {
            arr.put(JSONObject().put("seq", it.seq).put("barcode", it.barcode))
        }
        body.put("items", arr)

        return submit(batchId, body)
    }

    private fun printSingle(a: JSONObject): JSONObject {
        requireConnection()
        val batchId = newBatchId()
        val body = JSONObject().put("batchId", batchId).put(
            "items", JSONArray().put(JSONObject().put("seq", 1).put("barcode", a.getString("barcode")))
        )
        return submit(batchId, body)
    }

    private fun submit(batchId: String, body: JSONObject): JSONObject {
        val r = api.postJson("/api/v1/print-batches", body.toString())
        return when (r.code) {
            200, 202 -> JSONObject(r.body).put("batchId", batchId)
            422 -> throw ApiException("The PC does not know these barcodes: " + barcodesOf(r.body) + ". Sync the masterfile and try again.")
            400 -> throw ApiException("The PC refused the request: " + r.body)
            409 -> throw ApiException("This batch was already sent with different items. Clear the list and start again.")
            else -> throw ApiException(ApiClient.describe(r.code, r.body))
        }
    }

    private fun barcodesOf(json: String): String = try {
        val a = JSONObject(json).optJSONArray("barcodes") ?: JSONArray()
        (0 until a.length()).joinToString(", ") { a.getString(it) }
    } catch (e: Exception) { "?" }

    private fun batchStatus(batchId: String): JSONObject {
        requireConnection()
        val r = api.get("/api/v1/print-batches/$batchId", 10000)
        if (r.code == 404) throw ApiException("The PC no longer knows this print job (WM App may have restarted). Check the printer before sending again.")
        if (r.code != 200) throw ApiException(ApiClient.describe(r.code, r.body))
        return JSONObject(r.body)
    }

    private fun resetPending() {
        prefs.pendingBatchId = ""
        prefs.pendingFingerprint = ""
    }

    private fun newBatchId() = UUID.randomUUID().toString().replace("-", "")

    // ---- to the web page -------------------------------------------------------------

    private fun resolve(id: String, ok: Boolean, payload: String) {
        val js = "window.__native && window.__native.resolve(${JSONObject.quote(id)}, $ok, ${JSONObject.quote(payload)})"
        ui.post { webView.evaluateJavascript(js, null) }
    }

    private fun emit(name: String, payload: JSONObject) {
        val js = "window.__native && window.__native.emit(${JSONObject.quote(name)}, ${JSONObject.quote(payload.toString())})"
        ui.post { webView.evaluateJavascript(js, null) }
    }

    companion object {
        private val IO_METHODS = setOf("ping", "sync", "printList", "printSingle", "batchStatus")
    }
}
