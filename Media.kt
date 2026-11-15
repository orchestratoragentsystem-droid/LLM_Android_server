package com.example.llmserver

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Подготовка картинок, видео и звука перед отправкой в модель. */
object Media {
    const val IMAGE_SIDE = 1024     // длинная сторона фото
    const val FRAME_SIDE = 448      // длинная сторона кадра видео

    /** Большие фото уменьшаем и перекодируем в JPEG: экономит память и токены. */
    fun shrinkImage(src: ByteArray, maxSide: Int = IMAGE_SIDE): ByteArray {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(src, 0, src.size, bounds)
            if (bounds.outWidth <= 0) return src
            if (maxOf(bounds.outWidth, bounds.outHeight) <= maxSide && src.size < 600_000) return src
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(src, 0, src.size,
                BitmapFactory.Options().apply { inSampleSize = sample }) ?: return src
            val rot = try {
                when (ExifInterface(ByteArrayInputStream(src)).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } catch (_: Throwable) { 0 }
            encode(bmp, rot, maxSide)
        } catch (_: Throwable) {
            src
        }
    }

    /** n кадров из середин равных отрезков видео: (время мс, JPEG). */
    fun videoFrames(video: ByteArray, n: Int, maxSide: Int): List<Pair<Long, ByteArray>> {
        val tmp = File.createTempFile("vid", ".bin", App.instance.cacheDir)
        val r = MediaMetadataRetriever()
        try {
            tmp.writeBytes(video)
            r.setDataSource(tmp.path)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val count = if (dur <= 0) 1 else n
            val out = ArrayList<Pair<Long, ByteArray>>()
            for (i in 0 until count) {
                val t = if (count == 1) 0L else dur * (2 * i + 1) / (2 * count)
                val bmp = r.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
                out += t to encode(bmp, 0, maxSide)
            }
            return out
        } finally {
            runCatching { r.release() }
            tmp.delete()
        }
    }

    fun fmt(ms: Long) = "%d:%02d".format(ms / 60000, ms / 1000 % 60)

    /** Сырой PCM16 mono → WAV (модель принимает аудио только в контейнере). */
    fun wrapWav(pcm: ByteArray, rate: Int): ByteArray {
        val byteRate = rate * 2
        val b = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + pcm.size); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1)
        b.putInt(rate); b.putInt(byteRate); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(pcm.size); b.put(pcm)
        return b.array()
    }

    private fun encode(src: Bitmap, rotation: Int, maxSide: Int): ByteArray {
        val scale = minOf(1f, maxSide.toFloat() / maxOf(src.width, src.height))
        val m = Matrix().apply { postScale(scale, scale); postRotate(rotation.toFloat()) }
        val bmp = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos)
        return bos.toByteArray()
    }
}
