package com.example.llmserver

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Настройки сервера (SharedPreferences "server"). */
object Cfg {
    const val WIN_CAP = "win_cap"

    private fun sp(c: Context) = c.getSharedPreferences("server", Context.MODE_PRIVATE)

    fun token(c: Context): String = sp(c).getString("token", null) ?: newToken(c)

    fun newToken(c: Context): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        val t = b.joinToString("") { "%02x".format(it) }
        sp(c).edit().putString("token", t).apply()
        return t
    }

    fun port(c: Context) = sp(c).getInt("port", 8760)
    fun lan(c: Context) = sp(c).getBoolean("lan", false)
    fun autostart(c: Context) = sp(c).getBoolean("autostart", false)
    fun streamTools(c: Context) = sp(c).getBoolean("stream_tools", false)
    fun backendMain(c: Context) = sp(c).getString("backend_main", "gpu") ?: "gpu"
    fun backendVision(c: Context) =
        sp(c).getString("backend_vision", if (totalRamMb(c) / 1024.0 < 9.5) "cpu" else "gpu") ?: "cpu"
    fun windowSaved(c: Context) = sp(c).getInt("window", 0)
    fun winCap(c: Context) = sp(c).getInt(WIN_CAP, 0)

    fun putInt(c: Context, k: String, v: Int) = sp(c).edit().putInt(k, v).apply()
    fun putBool(c: Context, k: String, v: Boolean) = sp(c).edit().putBoolean(k, v).apply()
    fun putStr(c: Context, k: String, v: String) = sp(c).edit().putString(k, v).apply()

    /** Окно модели: заданное вручную или рекомендация по памяти, но не больше автоограничения. */
    fun window(c: Context): Int {
        val saved = windowSaved(c)
        val base = if (saved > 0) saved.coerceAtLeast(2048) else recommended(c)
        val cap = winCap(c)
        return if (cap > 0) minOf(base, cap) else base
    }

    fun totalRamMb(c: Context): Long {
        val mi = ActivityManager.MemoryInfo()
        c.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        return mi.totalMem / 1048576
    }

    fun recommended(c: Context): Int {
        val gb = totalRamMb(c) / 1024.0
        return when {
            gb < 5.5 -> 8192
            gb < 9.5 -> 32768
            else -> 65536
        }
    }

    fun modelFile(c: Context) = File(c.getExternalFilesDir(null), "gemma-4-E2B-it.litertlm")
}

/** Короткий журнал запросов для экрана. */
object ServerLog {
    val lines = MutableStateFlow<List<String>>(emptyList())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun add(s: String) {
        lines.value = (listOf("${fmt.format(Date())}  $s") + lines.value).take(60)
    }
}
