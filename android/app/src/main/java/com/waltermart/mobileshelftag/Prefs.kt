package com.waltermart.mobileshelftag

import android.content.Context
import android.content.SharedPreferences

/** Small settings store. Everything here survives restarts. */
class Prefs(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("mobileshelftag", Context.MODE_PRIVATE)

    var host: String
        get() = p.getString("host", "") ?: ""
        set(v) = p.edit().putString("host", v).apply()

    var port: Int
        get() = p.getInt("port", 5088)
        set(v) = p.edit().putInt("port", v).apply()

    var token: String
        get() = p.getString("token", "") ?: ""
        set(v) = p.edit().putString("token", v).apply()

    var lastSyncMs: Long
        get() = p.getLong("lastSyncMs", 0L)
        set(v) = p.edit().putLong("lastSyncMs", v).apply()

    var recordCount: Int
        get() = p.getInt("recordCount", 0)
        set(v) = p.edit().putInt("recordCount", v).apply()

    var masterfileSha: String
        get() = p.getString("masterfileSha", "") ?: ""
        set(v) = p.edit().putString("masterfileSha", v).apply()

    var serverModifiedUtc: String
        get() = p.getString("serverModifiedUtc", "") ?: ""
        set(v) = p.edit().putString("serverModifiedUtc", v).apply()

    /** The batch id of the list currently being sent, so a retry can never print twice. */
    var pendingBatchId: String
        get() = p.getString("pendingBatchId", "") ?: ""
        set(v) = p.edit().putString("pendingBatchId", v).apply()

    var pendingFingerprint: String
        get() = p.getString("pendingFingerprint", "") ?: ""
        set(v) = p.edit().putString("pendingFingerprint", v).apply()

    fun hasConnection(): Boolean = host.isNotBlank() && token.isNotBlank()
}
