package com.example.agent

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class RemoteError(val code: Int, msg: String) : Exception(msg)

class RCall(val id: String, val name: String, val args: String)

class RResult(val text: String, val calls: List<RCall>, val finish: String)

/** Клиент к серверу модели (OpenAI-совместимый API): сообщения, потоковый и обычный ответ, проверка связи. */
object Remote {

    fun base(ctx: Context): String = Settings.str(ctx, Settings.SRV_URL, "http://127.0.0.1:8760/v1").trim().trimEnd('/')
    private fun key(ctx: Context) = Settings.str(ctx, Settings.SRV_KEY, "").trim()

    // ---------- сообщения ----------

    private fun textPart(t: String) = JSONObject().put("type", "text").put("text", t)

    private fun imagePart(b: ByteArray) = JSONObject().put("type", "image_url").put("image_url",
        JSONObject().put("url", "data:image/jpeg;base64," + Base64.encodeToString(b, Base64.NO_WRAP)))

    private fun audioPart(wav: ByteArray) = JSONObject().put("type", "input_audio").put("input_audio",
        JSONObject().put("data", Base64.encodeToString(wav, Base64.NO_WRAP)).put("format", "wav"))

    private fun rawToJson(raw: List<RawPart>): JSONArray {
        val a = JSONArray()
        for (p in raw) when (p.kind) {
            "image" -> a.put(imagePart(p.bytes ?: ByteArray(0)))
            "audio" -> a.put(audioPart(p.bytes ?: ByteArray(0)))
            else -> a.put(textPart(p.text ?: ""))
        }
        if (a.length() == 0) a.put(textPart(" "))
        return a
    }

    /** system + история чата + текущая реплика. */
    fun messages(ctx: Context, hist: List<ChatMsg>, live: List<RawPart>): JSONArray {
        val arr = JSONArray()
        arr.put(JSONObject().put("role", "system").put("content", Settings.effectivePrompt(ctx)))
        for (m in hist) {
            if (m.role == "assistant") {
                if (m.text.isNotBlank()) arr.put(JSONObject().put("role", "assistant").put("content", m.text))
            } else {
                val parts = JSONArray()
                if (m.kind == "video") parts.put(textPart("Кадры видео по порядку:"))
                for (img in m.images) {
                    val f = ChatStore.imageFile(img)
                    if (f.exists()) parts.put(imagePart(f.readBytes()))
                }
                val prefix = when {
                    m.role == "event" -> "[Событие] "
                    m.kind == "voice" -> "[Голос] "
                    else -> ""
                }
                val t = prefix + m.text
                if (t.isNotBlank()) parts.put(textPart(t))
                if (parts.length() > 0) arr.put(JSONObject().put("role", "user").put("content", parts))
            }
        }
        arr.put(JSONObject().put("role", "user").put("content", rawToJson(live)))
        return arr
    }

    fun toolsJson(tools: List<AgentTool>): JSONArray {
        val a = JSONArray()
        for (t in tools) {
            a.put(JSONObject().put("type", "function").put("function", JSONObject(t.getToolDescriptionJsonString())))
        }
        return a
    }

    fun assistantCalls(r: RResult): JSONObject {
        val calls = JSONArray()
        for (c in r.calls) {
            calls.put(JSONObject().put("id", c.id).put("type", "function")
                .put("function", JSONObject().put("name", c.name).put("arguments", c.args)))
        }
        return JSONObject().put("role", "assistant")
            .put("content", if (r.text.isBlank()) JSONObject.NULL else r.text)
            .put("tool_calls", calls)
    }

    fun body(ctx: Context, msgs: JSONArray, tools: JSONArray, stream: Boolean): JSONObject {
        val o = JSONObject().put("model", Settings.str(ctx, Settings.SRV_MODEL, "gemma-4-e2b"))
            .put("messages", msgs).put("stream", stream)
        if (tools.length() > 0) o.put("tools", tools)
        return o
    }

    // ---------- запрос ----------

    /** Блокирующий вызов: выполнять только на потоке ввода-вывода. onDelta получает куски потокового ответа. */
    suspend fun post(ctx: Context, body: JSONObject, stream: Boolean, onDelta: suspend (String) -> Unit): RResult {
        Net.check(ctx, base(ctx))
        val c = URL(base(ctx) + "/chat/completions").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 10_000
            c.readTimeout = 900_000     // очередь на сервере + генерация
            c.setRequestProperty("Content-Type", "application/json")
            key(ctx).takeIf { it.isNotEmpty() }?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            c.setRequestProperty("X-Priority", Settings.str(ctx, Settings.SRV_PRIORITY, "normal"))
            c.setRequestProperty("X-Queue-Timeout", "300")
            val data = body.toString().toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(data.size)
            c.outputStream.use { it.write(data) }

            val code = c.responseCode
            if (code !in 200..299) {
                val err = c.errorStream?.bufferedReader()?.readText().orEmpty()
                val msg = try { JSONObject(err).optJSONObject("error")?.optString("message") ?: err } catch (_: Throwable) { err }
                throw RemoteError(code, msg.ifBlank { "HTTP $code" })
            }
            return if (stream) readStream(c, onDelta) else readSync(c)
        } finally {
            c.disconnect()
        }
    }

    private fun readSync(c: HttpURLConnection): RResult {
        val o = JSONObject(c.inputStream.bufferedReader().readText())
        val ch = o.getJSONArray("choices").getJSONObject(0)
        val m = ch.getJSONObject("message")
        val text = if (m.isNull("content")) "" else m.optString("content")
        val calls = ArrayList<RCall>()
        m.optJSONArray("tool_calls")?.let { a ->
            for (i in 0 until a.length()) {
                val t = a.getJSONObject(i)
                val f = t.getJSONObject("function")
                calls += RCall(t.optString("id", "call_$i"), f.getString("name"), f.optString("arguments", "{}"))
            }
        }
        return RResult(text, calls, ch.optString("finish_reason", "stop"))
    }

    private suspend fun readStream(c: HttpURLConnection, onDelta: suspend (String) -> Unit): RResult {
        val text = StringBuilder()
        val calls = HashMap<Int, Array<StringBuilder>>()   // индекс → [id, имя, аргументы]
        var finish = "stop"
        c.inputStream.bufferedReader().use { r ->
            while (true) {
                val line = r.readLine() ?: break
                if (!line.startsWith("data:")) continue          // комментарии «: queued position=N» пропускаем
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                val o = try { JSONObject(payload) } catch (_: Throwable) { continue }
                o.optJSONObject("error")?.let { throw RemoteError(500, it.optString("message", "ошибка сервера")) }
                val ch = o.optJSONArray("choices")?.optJSONObject(0) ?: continue
                if (!ch.isNull("finish_reason")) finish = ch.optString("finish_reason", finish)
                val d = ch.optJSONObject("delta") ?: continue
                val piece = d.optString("content", "")
                if (piece.isNotEmpty()) { text.append(piece); onDelta(piece) }
                d.optJSONArray("tool_calls")?.let { a ->
                    for (i in 0 until a.length()) {
                        val t = a.getJSONObject(i)
                        val acc = calls.getOrPut(t.optInt("index", i)) { arrayOf(StringBuilder(), StringBuilder(), StringBuilder()) }
                        if (t.has("id")) acc[0].append(t.optString("id"))
                        t.optJSONObject("function")?.let { f ->
                            if (f.has("name")) acc[1].append(f.optString("name"))
                            if (f.has("arguments")) acc[2].append(f.optString("arguments"))
                        }
                    }
                }
            }
        }
        val list = calls.toSortedMap().values.map { RCall(it[0].toString().ifBlank { "call_x" }, it[1].toString(), it[2].toString().ifBlank { "{}" }) }
        return RResult(text.toString(), list, finish)
    }

    // ---------- состояние сервера ----------

    private fun healthUrl(ctx: Context) = base(ctx).removeSuffix("/v1") + "/health"

    private fun get(url: String, key: String?): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 4000
            c.readTimeout = 6000
            if (!key.isNullOrEmpty()) c.setRequestProperty("Authorization", "Bearer $key")
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            return code to txt
        } finally {
            c.disconnect()
        }
    }

    /** Короткий статус для главного экрана; заодно запоминает реальное окно модели сервера. */
    fun refreshHealth(ctx: Context): String = try {
        Net.check(ctx, healthUrl(ctx))
        val (code, txt) = get(healthUrl(ctx), null)
        if (code != 200) "Сервер ответил HTTP $code"
        else {
            val o = JSONObject(txt)
            if (o.optBoolean("ready")) {
                AgentRuntime.window = o.optInt("window", 0)
                "Сервер: готов (окно ${AgentRuntime.window} ток.)"
            } else {
                AgentRuntime.window = 0
                "Сервер: модель не готова (${o.optString("model")})"
            }
        }
    } catch (e: Throwable) {
        "Сервер недоступен: ${e.message}"
    }

    /** Подробная проверка для экрана настроек: состояние, ключ, очередь. */
    fun checkText(ctx: Context): String {
        val sb = StringBuilder(refreshHealth(ctx))
        try {
            val base = base(ctx).removeSuffix("/v1")
            Net.check(ctx, base)
            val (code, txt) = get("$base/queue", key(ctx))
            when (code) {
                200 -> {
                    val o = JSONObject(txt)
                    sb.append("\nКлюч принят. Ожидают в очереди: ${o.optJSONArray("waiting")?.length() ?: 0}")
                    sb.append(if (o.isNull("running")) ", сейчас свободен" else ", сейчас занят")
                }
                401 -> sb.append("\nКлюч не подошёл (HTTP 401): проверьте ключ клиента")
                else -> sb.append("\n/queue вернул HTTP $code")
            }
        } catch (e: Throwable) {
            sb.append("\nОчередь недоступна: ${e.message}")
        }
        return sb.toString()
    }
}
