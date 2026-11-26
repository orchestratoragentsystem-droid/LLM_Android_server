package com.example.agent

import android.content.Context
import java.io.IOException
import java.net.URI

/** Офлайн-режим: обращения к внешним адресам запрещены, разрешены только localhost и адреса домашней сети. */
object Net {
    /** Только буквенные IP (имена хостов не разрешаем, чтобы не делать даже DNS-запрос). */
    fun isLocal(host: String): Boolean {
        val h = host.lowercase()
        if (h == "localhost") return true
        val p = h.split('.')
        if (p.size != 4) return false
        val n = p.map { it.toIntOrNull() ?: return false }
        return n[0] == 127 || n[0] == 10 || (n[0] == 192 && n[1] == 168) ||
            (n[0] == 172 && n[1] in 16..31) || (n[0] == 169 && n[1] == 254)
    }

    fun check(ctx: Context, url: String) {
        if (!Settings.offline(ctx)) return
        val host = try { URI(url).host ?: "" } catch (_: Throwable) { "" }
        if (!isLocal(host)) {
            throw IOException("офлайн-режим: адрес «$host» вне вашей сети запрещён " +
                "(Настройки → Модель и инструменты → Офлайн-режим)")
        }
    }
}
