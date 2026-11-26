package com.example.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Модель распознавания речи Vosk (45 МБ). Работает на телефоне, голос никуда не отправляется.
 * Модель вшивается в APK при сборке (assets) или скачивается ТОЛЬКО по кнопке в настройках.
 */
object VoskModel {
    private const val ZIP_URL = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
    private const val ASSET = "vosk-model-small-ru-0.22.zip"
    private const val DIR = "vosk-model-small-ru-0.22"

    private fun root(ctx: Context) = File(ctx.filesDir, "vosk")
    fun dir(ctx: Context) = File(root(ctx), DIR)

    fun installed(ctx: Context): Boolean {
        val d = dir(ctx)
        return d.isDirectory && (d.list()?.isNotEmpty() == true)
    }

    fun bundled(ctx: Context): Boolean = try {
        ctx.applicationContext.assets.open(ASSET).close()
        true
    } catch (_: Throwable) {
        false
    }

    /** Без сети: готовая папка или распаковка из APK. Нет модели — исключение с понятным текстом. */
    suspend fun ensure(ctx: Context, say: (String) -> Unit): File = withContext(Dispatchers.IO) {
        if (installed(ctx)) return@withContext dir(ctx)
        if (bundled(ctx)) {
            say("Распаковываю модель распознавания речи из приложения…")
            unzip(ctx.applicationContext.assets.open(ASSET), root(ctx))
            check(installed(ctx)) { "после распаковки нет папки $DIR" }
            return@withContext dir(ctx)
        }
        throw IllegalStateException(
            "модель распознавания речи не установлена (Настройки → Активация → «Скачать модель распознавания речи»)")
    }

    /** Только по явной просьбе пользователя: скачивает файл модели, голос при этом не передаётся. */
    suspend fun download(ctx: Context, say: (String) -> Unit): File = withContext(Dispatchers.IO) {
        if (installed(ctx)) return@withContext dir(ctx)
        root(ctx).mkdirs()
        val zip = File(ctx.cacheDir, "vosk-ru.zip")
        say("Скачиваю файл модели (45 МБ)…")
        val c = URL(ZIP_URL).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 30_000
        c.inputStream.use { i -> zip.outputStream().use { o -> i.copyTo(o) } }
        say("Распаковываю…")
        unzip(zip.inputStream(), root(ctx))
        zip.delete()
        check(installed(ctx)) { "после распаковки нет папки $DIR" }
        dir(ctx)
    }

    private fun unzip(input: InputStream, root: File) {
        root.mkdirs()
        ZipInputStream(input.buffered()).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val out = File(root, e.name)
                if (!out.canonicalPath.startsWith(root.canonicalPath)) throw IOException("плохой архив")
                if (e.isDirectory) out.mkdirs()
                else { out.parentFile?.mkdirs(); out.outputStream().use { z.copyTo(it) } }
                z.closeEntry()
                e = z.nextEntry
            }
        }
    }
}
