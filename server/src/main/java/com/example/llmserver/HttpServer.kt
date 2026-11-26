package com.example.llmserver

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,   // ключи в нижнем регистре
    val body: ByteArray,
    val local: Boolean,                 // запрос с этого же телефона
) {
    @Volatile var clientName: String? = null
}

/** Ответ: обычный (с Content-Length) или потоковый SSE (chunked). Соединение всегда закрывается. */
class HttpResponse(private val socket: Socket) {
    private val out = BufferedOutputStream(socket.getOutputStream(), 16384)

    /** HTTP-код отправленного ответа (для журнала). */
    @Volatile var code = 0
        private set

    /** true, если клиент оборвал соединение (запись не удалась). */
    @Volatile var failed = false
        private set

    fun json(code: Int, body: String) =
        send(code, "application/json; charset=utf-8", body.toByteArray(Charsets.UTF_8))

    fun send(code: Int, contentType: String, body: ByteArray) {
        this.code = code
        write("HTTP/1.1 $code ${reason(code)}\r\nContent-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n")
        write(body)
        flush()
    }

    fun startSse() {
        this.code = 200
        write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\n" +
            "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n")
        flush()
    }

    fun sseData(data: String) {
        val b = "data: $data\n\n".toByteArray(Charsets.UTF_8)
        write(Integer.toHexString(b.size) + "\r\n")
        write(b)
        write("\r\n")
        flush()
    }

    /** Комментарий SSE: клиенты его игнорируют, но соединение остаётся живым (позиция в очереди). */
    fun sseComment(text: String) {
        val b = ": $text\n\n".toByteArray(Charsets.UTF_8)
        write(Integer.toHexString(b.size) + "\r\n")
        write(b)
        write("\r\n")
        flush()
    }

    /** Клиент закрыл соединение, пока запрос ждал в очереди или выполнялся? */
    fun clientGone(): Boolean {
        return try {
            socket.soTimeout = 20
            socket.getInputStream().read() < 0
        } catch (e: java.net.SocketTimeoutException) {
            false
        } catch (e: IOException) {
            true
        } finally {
            runCatching { socket.soTimeout = 0 }
        }
    }

    fun endSse() {
        write("0\r\n\r\n")
        flush()
    }

    private fun write(s: String) = write(s.toByteArray(Charsets.UTF_8))

    private fun write(b: ByteArray) {
        if (failed) return
        try { out.write(b) } catch (e: IOException) { failed = true }
    }

    private fun flush() {
        if (failed) return
        try { out.flush() } catch (e: IOException) { failed = true }
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        413 -> "Payload Too Large"
        429 -> "Too Many Requests"
        431 -> "Request Header Fields Too Large"
        503 -> "Service Unavailable"
        else -> if (code in 200..299) "OK" else "Error"
    }
}

/** Минимальный HTTP/1.1-сервер на сокетах: без сторонних библиотек. */
class HttpServer(
    private val port: Int,
    private val bindAll: Boolean,
    private val handler: (HttpRequest, HttpResponse) -> Unit,
) {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }

    @Volatile var running = false
        private set

    fun start() {
        val addr = InetAddress.getByName(if (bindAll) "0.0.0.0" else "127.0.0.1")
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(addr, port), 50)
        server = ss
        running = true
        Thread {
            while (running) {
                val c = try { ss.accept() } catch (e: IOException) { break }
                pool.execute { serve(c) }
            }
        }.apply { isDaemon = true; name = "http-accept"; start() }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
    }

    private fun serve(c: Socket) {
        val res = HttpResponse(c)
        try {
            c.soTimeout = 30_000
            val req = readRequest(c)
            c.soTimeout = 0
            if (req != null) {
                val t0 = System.currentTimeMillis()
                try {
                    handler(req, res)
                } finally {
                    if (req.path != "/health") {
                        ServerLog.add("${req.method} ${req.path} → ${res.code}" +
                            (req.clientName?.let { " [$it]" } ?: "") +
                            " ${(System.currentTimeMillis() - t0) / 1000.0} с")
                    }
                }
            }
        } catch (e: BadReq) {
            res.json(e.code, Api.errorJson(e.message ?: "ошибка запроса", "invalid_request_error"))
        } catch (e: Throwable) {
            res.json(500, Api.errorJson("внутренняя ошибка: ${e.message}", "server_error"))
        } finally {
            runCatching { c.close() }
        }
    }

    private fun readRequest(c: Socket): HttpRequest? {
        val inp = BufferedInputStream(c.getInputStream(), 65536)
        val line = readLine(inp) ?: return null
        val parts = line.split(' ')
        if (parts.size < 2) throw BadReq(400, "плохой запрос")
        val method = parts[0].uppercase()
        val path = parts[1].substringBefore('?').let { if (it.length > 1) it.trimEnd('/') else it }

        val headers = HashMap<String, String>()
        while (true) {
            val l = readLine(inp) ?: break
            if (l.isEmpty()) break
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        if (headers["expect"]?.lowercase() == "100-continue") {
            c.getOutputStream().write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
            c.getOutputStream().flush()
        }

        val body = ByteArrayOutputStream()
        val chunked = headers["transfer-encoding"].orEmpty().lowercase().contains("chunked")
        val cl = headers["content-length"]?.toLongOrNull()
        if (chunked) {
            while (true) {
                val sz = (readLine(inp) ?: break).substringBefore(';').trim().toIntOrNull(16) ?: break
                if (sz == 0) { readLine(inp); break }
                if (body.size() + sz > MAX_BODY) throw BadReq(413, "тело запроса слишком большое")
                copyExact(inp, sz.toLong(), body)
                readLine(inp)
            }
        } else if (cl != null && cl > 0) {
            if (cl > MAX_BODY) throw BadReq(413, "тело запроса слишком большое (максимум ${MAX_BODY / 1048576} МБ)")
            copyExact(inp, cl, body)
        }
        return HttpRequest(method, path, headers, body.toByteArray(), c.inetAddress?.isLoopbackAddress == true)
    }

    private fun readLine(inp: InputStream): String? {
        val bo = ByteArrayOutputStream()
        while (true) {
            val c = inp.read()
            if (c < 0) return if (bo.size() == 0) null else bo.toString("UTF-8")
            if (c == '\n'.code) break
            if (bo.size() > 16384) throw BadReq(431, "слишком длинная строка заголовка")
            bo.write(c)
        }
        return bo.toString("UTF-8").trimEnd('\r')
    }

    private fun copyExact(inp: InputStream, n: Long, out: ByteArrayOutputStream) {
        val buf = ByteArray(65536)
        var left = n
        while (left > 0) {
            val r = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) throw IOException("соединение закрыто до конца тела")
            out.write(buf, 0, r)
            left -= r
        }
    }

    companion object {
        const val MAX_BODY = 48L * 1024 * 1024
    }
}
