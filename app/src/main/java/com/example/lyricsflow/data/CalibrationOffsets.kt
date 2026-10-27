package com.example.lyricsflow.data

import android.content.SharedPreferences

object CalibrationOffsets {
    private const val CORRECTION = "calibrationOffsetFix20261027"

    /** One-time repair for the accidental call to the former AppGraph.delay(300) setter. */
    fun repairAccidental300(prefs: SharedPreferences): Int {
        if (prefs.getBoolean(CORRECTION, false)) return 0
        val affected =
            prefs.all
                .filter { (key, value) ->
                    key.startsWith("delay:") && value is Number && value.toLong() == 300L
                }
                .keys
        prefs
            .edit()
            .apply {
                affected.forEach(::remove)
                putBoolean(CORRECTION, true)
            }
            .commit()
        return affected.size
    }
}
