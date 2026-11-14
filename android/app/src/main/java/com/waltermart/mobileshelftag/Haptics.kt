package com.waltermart.mobileshelftag

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** A short buzz for a good scan, two for a bad one. Best effort: a device without a vibrator is simply silent. */
object Haptics {
    @Suppress("DEPRECATION")
    fun buzz(context: Context, kind: String) {
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31)
                context.getSystemService(VibratorManager::class.java).defaultVibrator
            else context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            val pattern = if (kind == "ok") longArrayOf(0, 35) else longArrayOf(0, 80, 60, 80)
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, -1)) else v.vibrate(pattern, -1)
        } catch (e: Exception) {
            // feedback is optional
        }
    }
}
