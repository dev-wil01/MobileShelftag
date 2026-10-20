package com.waltermart.mobileshelftag

import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * Zebra DataWedge setup. The app creates its own DataWedge profile that
 *  - is bound to this app only,
 *  - delivers each scan as a broadcast INTENT (no keystroke typing),
 *  - leaves every other scanner setting at the Zebra default.
 * The profile can also be created by hand in the DataWedge app (see the README).
 */
object DataWedge {
    const val PROFILE = "MobileShelfTag"
    const val SCAN_ACTION = "com.waltermart.mobileshelftag.SCAN"
    const val EXTRA_DATA = "com.symbol.datawedge.data_string"

    private const val API_ACTION = "com.symbol.datawedge.api.ACTION"
    private const val SET_CONFIG = "com.symbol.datawedge.api.SET_CONFIG"

    fun configure(context: Context) {
        val appList = Bundle().apply {
            putString("PACKAGE_NAME", context.packageName)
            putStringArray("ACTIVITY_LIST", arrayOf("*"))
        }

        // 1) create the profile and bind it to this app
        send(context, Bundle().apply {
            putString("PROFILE_NAME", PROFILE)
            putString("PROFILE_ENABLED", "true")
            putString("CONFIG_MODE", "CREATE_IF_NOT_EXIST")
            putParcelableArray("APP_LIST", arrayOf(appList))
        })

        // 2) barcode input on, default scanner
        send(context, plugin("BARCODE", Bundle().apply {
            putString("scanner_selection", "auto")
            putString("scanner_input_enabled", "true")
        }))

        // 3) intent output: broadcast to this app
        send(context, plugin("INTENT", Bundle().apply {
            putString("intent_output_enabled", "true")
            putString("intent_action", SCAN_ACTION)
            putString("intent_delivery", "2") // 2 = broadcast
        }))

        // 4) keystroke output off
        send(context, plugin("KEYSTROKE", Bundle().apply {
            putString("keystroke_output_enabled", "false")
        }))
    }

    private fun plugin(name: String, params: Bundle) = Bundle().apply {
        putString("PROFILE_NAME", PROFILE)
        putString("CONFIG_MODE", "UPDATE")
        putBundle("PLUGIN_CONFIG", Bundle().apply {
            putString("PLUGIN_NAME", name)
            putString("RESET_CONFIG", "true")
            putBundle("PARAM_LIST", params)
        })
    }

    private fun send(context: Context, config: Bundle) {
        context.sendBroadcast(Intent().apply {
            action = API_ACTION
            putExtra(SET_CONFIG, config)
        })
    }
}
