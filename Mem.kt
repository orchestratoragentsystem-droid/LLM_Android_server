package com.example.llmserver

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

object Mem {
    /**
     * Если систему убило процесс из-за памяти, окно модели уменьшается вдвое (один раз на каждое
     * завершение). Вызывается перед загрузкой модели.
     */
    fun applyMemoryCap(ctx: Context): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        return try {
            val am = ctx.getSystemService(ActivityManager::class.java)
            val last = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 1).firstOrNull() ?: return null
            val sp = ctx.getSharedPreferences("server", Context.MODE_PRIVATE)
            if (last.timestamp <= sp.getLong("cap_ts", 0L)) return null
            sp.edit().putLong("cap_ts", last.timestamp).apply()
            if (last.reason != ApplicationExitInfo.REASON_LOW_MEMORY &&
                last.reason != ApplicationExitInfo.REASON_SIGNALED) return null
            val active = sp.getInt("win_active", 0)
            if (active <= 4096) return null
            val cap = maxOf(4096, active / 2)
            sp.edit().putInt(Cfg.WIN_CAP, cap).putInt("win_active", 0).apply()
            "Систему убило процесс из-за памяти при окне $active ток. Окно уменьшено до $cap."
        } catch (_: Throwable) {
            null
        }
    }
}
