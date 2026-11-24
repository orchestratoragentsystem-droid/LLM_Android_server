package com.example.agent

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.io.File

/** Записывает причину падения и показывает её в чате при следующем запуске. */
object CrashLog {

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val text = "Поток: ${t.name}\n" + Log.getStackTraceString(e)
            runCatching { File(app.filesDir, "crash.txt").writeText(text) }
            // Показываем ошибку отдельным экраном (другой процесс), иначе при тихом падении её не видно
            runCatching {
                app.startActivity(Intent(app, CrashActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .putExtra("trace", text.take(20000)))
            }
            runCatching { Thread.sleep(500) }
            android.os.Process.killProcess(android.os.Process.myPid())
            kotlin.system.exitProcess(10)
        }
    }

    /**
     * Если прошлый запуск закончился сбоем или убийством по памяти меньше 3 минут назад, автозагрузку
     * встроенной модели пропускаем: иначе приложение умирает снова и снова, и до настроек не добраться.
     */
    fun recentFatalExit(ctx: Context): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        return try {
            val am = ctx.getSystemService(ActivityManager::class.java)
            val last = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 1).firstOrNull() ?: return null
            val bad = setOf(
                ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
                ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_SIGNALED,
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
            )
            if (last.reason !in bad) return null
            if (System.currentTimeMillis() - last.timestamp > 3 * 60_000L) return null
            "Автозагрузка встроенной модели пропущена: прошлый запуск закончился сбоем (${reasonName(last.reason)}). " +
                "Выберите режим «Сервер» или нажмите «Перезагрузить модель» в Настройках."
        } catch (_: Throwable) {
            null
        }
    }

    private val MEM_REASONS = setOf(ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_SIGNALED)

    /**
     * Если систему убило приложение из-за памяти, окно модели уменьшается вдвое (один раз на каждое
     * завершение). Вызывается перед загрузкой модели, чтобы новое окно сработало сразу.
     */
    fun applyMemoryCap(ctx: Context): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        return try {
            val am = ctx.getSystemService(ActivityManager::class.java)
            val last = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 1).firstOrNull() ?: return null
            val sp = ctx.getSharedPreferences("agent", Context.MODE_PRIVATE)
            if (last.timestamp <= sp.getLong("cap_ts", 0L)) return null
            sp.edit().putLong("cap_ts", last.timestamp).apply()
            if (last.reason !in MEM_REASONS) return null
            val active = sp.getInt("win_active", 0)
            if (active <= 4096) return null
            val cap = maxOf(4096, active / 2)
            sp.edit().putInt(Settings.WIN_CAP, cap).putInt("win_active", 0).apply()
            "🧠 Систему убило приложение из-за нехватки памяти при окне $active ток. " +
                "Окно автоматически уменьшено до $cap (сбросить: Настройки → Контекст чата)."
        } catch (_: Throwable) {
            null
        }
    }

    fun report(ctx: Context) {
        val f = File(ctx.filesDir, "crash.txt")
        if (f.exists()) {
            val text = runCatching { f.readText() }.getOrDefault("")
            f.delete()
            if (text.isNotBlank()) Chat.note("💥 Приложение упало:\n" + text.take(6000))
            return
        }
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val am = ctx.getSystemService(ActivityManager::class.java)
            val last = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 1).firstOrNull() ?: return
            val sp = ctx.getSharedPreferences("agent", Context.MODE_PRIVATE)
            if (last.timestamp <= sp.getLong("exit_ts", 0L)) return
            sp.edit().putLong("exit_ts", last.timestamp).apply()

            val bad = setOf(
                ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
                ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_LOW_MEMORY,
                ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            )
            if (last.reason !in bad) return

            val sb = StringBuilder("💥 Процесс завершён: ${reasonName(last.reason)}")
            if (last.rss > 0) {
                sb.append("\nПамять процесса в момент завершения: ${last.rss / 1024} МБ (RSS), ${last.pss / 1024} МБ (PSS)")
            }
            last.description?.let { sb.append("\n").append(it) }

            val memory = last.reason in MEM_REASONS
            if (memory) {
                sb.append("\nПамяти телефона не хватило. Что поможет: окно поменьше, обработка картинок на CPU, " +
                    "основной движок на CPU (Настройки → Модель и инструменты), закрыть тяжёлые приложения.")
            }

            // Нативный сбой: в трассе строки с именами библиотек, функций, сигналом
            if (last.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                val bytes = runCatching { last.traceInputStream?.readBytes() }.getOrNull()
                if (bytes != null) {
                    val keys = listOf("signal", "SIG", "Abort", "abort", "lib", ".so", "litert", "LiteRT",
                        "tflite", "gemma", "Gemma", "fault", "Check failed", "FATAL")
                    val lines = asciiRuns(bytes).filter { r -> keys.any { r.contains(it) } }
                        .distinct().take(30).map { it.take(150) }
                    if (lines.isNotEmpty()) sb.append("\n\nТрасса (строки из дампа):\n").append(lines.joinToString("\n"))
                }
            }
            Chat.note(sb.toString().take(3500))

            // Логи приложения перед падением нужны только для сбоев, при нехватке памяти они шумят
            if (!memory) {
                Thread {
                    val tail = logcatTail()
                    if (tail.isNotBlank()) Chat.note("📜 Ошибки в логе перед падением:\n$tail")
                }.start()
            }
        } catch (_: Throwable) {
        }
    }

    private fun asciiRuns(b: ByteArray, min: Int = 8): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (x in b) {
            val c = x.toInt() and 0xFF
            if (c in 32..126) sb.append(c.toChar())
            else {
                if (sb.length >= min) out += sb.toString()
                sb.setLength(0)
            }
        }
        if (sb.length >= min) out += sb.toString()
        return out
    }

    private fun logcatTail(): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", "3000"))
        val lines = p.inputStream.bufferedReader().readLines()
        lines.filter { l -> l.contains(" E/") || l.contains(" F/") || l.contains("Fatal") || l.contains("abort", true) }
            .takeLast(25).joinToString("\n") { it.take(180) }.take(3500)
    } catch (_: Throwable) {
        ""
    }

    private fun reasonName(r: Int) = when (r) {
        ApplicationExitInfo.REASON_CRASH -> "ошибка (исключение)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "сбой в нативном коде (модель)"
        ApplicationExitInfo.REASON_ANR -> "приложение не отвечало"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "нехватка памяти"
        ApplicationExitInfo.REASON_SIGNALED -> "убито сигналом (обычно нехватка памяти)"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "сбой запуска"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "чрезмерное потребление ресурсов"
        else -> "код $r"
    }
}
