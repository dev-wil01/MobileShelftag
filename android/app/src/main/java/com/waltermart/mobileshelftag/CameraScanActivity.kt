package com.waltermart.mobileshelftag

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import java.util.concurrent.Executors

/**
 * Continuous camera scanning. The camera is opened once; every barcode is looked up in the offline
 * database and, when found, added to the list. A short preview of the item is shown, then the camera
 * goes straight back to scanning. "Done" (or Back) returns to the app.
 * Same rules as a hardware scan: barcode normalisation, SKU mode, unusable rows are shown but never added.
 */
class CameraScanActivity : AppCompatActivity() {
    private lateinit var scanner: DecoratedBarcodeView
    private lateinit var status: TextView
    private lateinit var card: LinearLayout
    private lateinit var cardDesc: TextView
    private lateinit var cardSub: TextView
    private lateinit var cardPrice: TextView
    private lateinit var cardAdded: TextView
    private lateinit var done: Button

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val guard = RepeatGuard()
    private lateinit var products: ProductStore
    private lateinit var scans: ScanListStore
    private lateinit var prefs: Prefs
    private var tone: ToneGenerator? = null
    private var isSku = false
    private var added = 0
    private var busy = false      // a lookup or a preview is showing: ignore camera hits
    private var hasPermission = false
    private val backToIdle = Runnable { showIdle() }

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else {
            status.text = "Camera permission is needed to scan. Allow it in the app settings."
            status.setTextColor(RED)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        isSku = intent.getStringExtra(EXTRA_TYPE) == "sku"
        products = ProductStore(applicationContext)
        scans = ScanListStore(applicationContext)
        prefs = Prefs(applicationContext)
        tone = try { ToneGenerator(AudioManager.STREAM_MUSIC, 80) } catch (e: Exception) { null }

        buildLayout()
        setResult(RESULT_OK)

        if (isSku) {
            // The camera reads printed barcodes; SKU entry is typed. Say so rather than silently looking up the wrong thing.
            status.text = "Camera scans barcodes. Switch to Barcode input to use it."
            status.setTextColor(RED)
            return
        }
        hasPermission = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) startCamera() else permission.launch(Manifest.permission.CAMERA)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildLayout() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        scanner = DecoratedBarcodeView(this)
        // hide the library's own status line; this screen has its own
        scanner.setStatusText("")
        scanner.statusView.visibility = View.GONE
        root.addView(scanner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
            setBackgroundColor(0xE614212E.toInt())
        }

        status = TextView(this).apply {
            text = PROMPT
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(10).toFloat(); setStroke(dp(3), 0xFFFFD100.toInt()) }
            visibility = View.GONE
        }
        cardDesc = TextView(this).apply { setTextColor(0xFF14212E.toInt()); textSize = 17f; typeface = Typeface.DEFAULT_BOLD; maxLines = 2 }
        cardSub = TextView(this).apply { setTextColor(0xFF5B6B7B.toInt()); textSize = 13f }
        cardPrice = TextView(this).apply { setTextColor(0xFF005A9C.toInt()); textSize = 44f; typeface = Typeface.DEFAULT_BOLD }
        cardAdded = TextView(this).apply { setTextColor(0xFF17794A.toInt()); textSize = 14f; typeface = Typeface.DEFAULT_BOLD }
        listOf(cardDesc, cardSub, cardPrice, cardAdded).forEach { card.addView(it) }

        done = Button(this).apply {
            text = "Done"
            setOnClickListener { finish() }
        }

        bottom.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        bottom.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        bottom.addView(done, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        setContentView(root)
    }

    private fun startCamera() {
        hasPermission = true
        scanner.barcodeView.decoderFactory = DefaultDecoderFactory(FORMATS)
        scanner.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult) { onCode(result.text ?: return) }
            override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>) {}
        })
        scanner.resume()
    }

    private fun onCode(raw: String) {
        if (busy) return
        if (!guard.accept(raw, SystemClock.elapsedRealtime())) return
        busy = true
        worker.execute {
            val outcome = lookup(raw)
            ui.post { show(outcome) }
        }
    }

    private sealed class Outcome {
        data class Added(val p: Product, val count: Int) : Outcome()
        data class Unusable(val p: Product) : Outcome()
        data class NotFound(val code: String) : Outcome()
        object Invalid : Outcome()
        object NoData : Outcome()
    }

    private fun lookup(raw: String): Outcome {
        val key = BarcodeRules.normalize(raw) ?: return Outcome.Invalid
        if (products.count() == 0) return Outcome.NoData
        val p = products.find(key) ?: return Outcome.NotFound(key)
        if (!p.usable) return Outcome.Unusable(p)
        scans.add(p)
        prefs.pendingBatchId = ""       // the list changed: any earlier print attempt no longer matches
        prefs.pendingFingerprint = ""
        return Outcome.Added(p, scans.all().size)
    }

    private fun show(o: Outcome) {
        ui.removeCallbacks(backToIdle)
        when (o) {
            is Outcome.Added -> {
                added++
                Haptics.buzz(this, "ok")
                tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                cardDesc.text = o.p.shortDesc
                cardSub.text = "SKU ${o.p.sku}  ·  ${o.p.barcode}"
                cardPrice.text = "₱ " + o.p.price
                cardAdded.text = "Added to the list (${o.count})"
                cardAdded.setTextColor(0xFF17794A.toInt())
                card.visibility = View.VISIBLE
                status.visibility = View.GONE
                done.text = "Done ($added)"
                ui.postDelayed(backToIdle, PREVIEW_MS)
            }
            is Outcome.Unusable -> {
                Haptics.buzz(this, "bad")
                cardDesc.text = o.p.shortDesc
                cardSub.text = "SKU ${o.p.sku}  ·  ${o.p.barcode}"
                cardPrice.text = ""
                cardAdded.text = "Not added: incomplete in the masterfile (${o.p.problem})"
                cardAdded.setTextColor(RED)
                card.visibility = View.VISIBLE
                status.visibility = View.GONE
                ui.postDelayed(backToIdle, PREVIEW_MS)
            }
            is Outcome.NotFound -> problem("Item not found", "bad")
            Outcome.Invalid -> problem("Not a usable barcode", "bad")
            Outcome.NoData -> problem("No product data yet. Sync the masterfile first.", "bad")
        }
    }

    private fun problem(text: String, kind: String) {
        Haptics.buzz(this, kind)
        card.visibility = View.GONE
        status.visibility = View.VISIBLE
        status.text = text
        status.setTextColor(RED)
        ui.postDelayed(backToIdle, NOT_FOUND_MS)
    }

    private fun showIdle() {
        card.visibility = View.GONE
        status.visibility = View.VISIBLE
        status.text = PROMPT
        status.setTextColor(Color.WHITE)
        busy = false
    }

    override fun onResume() {
        super.onResume()
        if (hasPermission && !isSku) scanner.resume()
    }

    override fun onPause() {
        super.onPause()
        scanner.pause()
    }

    override fun onDestroy() {
        ui.removeCallbacks(backToIdle)
        tone?.release()
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TYPE = "type"
        private const val PROMPT = "Point the camera at the item's barcode"
        private const val PREVIEW_MS = 1800L
        private const val NOT_FOUND_MS = 1500L
        private val RED = 0xFFFF6B5E.toInt() // readable on the dark bar; same family as the app's red
        private val FORMATS = listOf(
            BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E,
            BarcodeFormat.CODE_128, BarcodeFormat.CODE_39, BarcodeFormat.ITF, BarcodeFormat.CODABAR
        )
    }
}
