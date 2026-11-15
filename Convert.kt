package com.example.llmserver

import android.util.Base64
import com.google.ai.edge.litertlm.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

class BadReq(val code: Int, msg: String) : Exception(msg)

/** Часть сообщения: text | image | audio. */
class Part(val kind: String, val text: String?, val bytes: ByteArray?)

class TC(val id: String, val name: String, val args: String)

class Msg(val role: String, val parts: List<Part>, val toolCalls: List<TC>, val toolCallId: String?) {
    val text: String get() = parts.filter { it.kind == "text" }.joinToString("\n") { it.text ?: "" }
}

class ChatReq(
    val system: String,
    val msgs: List<Msg>,
    val tools: List<JSONObject>,        // {"name","description","parameters"}
    val stream: Boolean,
    val temperature: Double?,
    val topP: Double?,
    val model: String,
)

/** Заглушка для LiteRT: ручной режим, execute() сервер не вызывает. */
class StubTool(private val spec: String) : OpenApiTool {
    override fun getToolDescriptionJsonString(): String = spec
    override fun execute(paramsJsonString: String): String = "{}"
}

object Convert {

    // ---------- разбор запроса OpenAI ----------

    fun parse(body: String): ChatReq {
        val o = try { JSONObject(body) } catch (e: Exception) { throw BadReq(400, "тело запроса не является JSON") }
        val arr = o.optJSONArray("messages") ?: throw BadReq(400, "нужно поле messages")
        val sys = StringBuilder()
        val msgs = ArrayList<Msg>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val role = m.optString("role")
            val parts = parseContent(m.opt("content"))
            when (role) {
                "system", "developer" -> {
                    if (sys.isNotEmpty()) sys.append("\n")
                    sys.append(parts.filter { it.kind == "text" }.joinToString("\n") { it.text ?: "" })
                }
                "user" -> msgs += Msg("user", parts, emptyList(), null)
                "assistant" -> msgs += Msg("assistant", parts, parseCalls(m.optJSONArray("tool_calls")), null)
                "tool" -> msgs += Msg("tool", parts, emptyList(), m.optString("tool_call_id").ifBlank { null })
                else -> throw BadReq(400, "неизвестная роль сообщения: $role")
            }
        }
        val tools = ArrayList<JSONObject>()
        val ta = o.optJSONArray("tools")
        if (ta != null) {
            for (i in 0 until ta.length()) {
                val f = ta.optJSONObject(i)?.optJSONObject("function") ?: continue
                tools += JSONObject()
                    .put("name", f.optString("name"))
                    .put("description", f.optString("description", ""))
                    .put("parameters", f.optJSONObject("parameters")
                        ?: JSONObject().put("type", "object").put("properties", JSONObject()))
            }
        }
        return ChatReq(
            sys.toString(), msgs, tools, o.optBoolean("stream", false),
            if (o.has("temperature")) o.optDouble("temperature") else null,
            if (o.has("top_p")) o.optDouble("top_p") else null,
            o.optString("model", "gemma-4-e2b").ifBlank { "gemma-4-e2b" },
        )
    }

    private fun parseContent(c: Any?): List<Part> {
        if (c == null || c == JSONObject.NULL) return emptyList()
        if (c is String) return if (c.isEmpty()) emptyList() else listOf(Part("text", c, null))
        if (c !is JSONArray) return emptyList()
        val out = ArrayList<Part>()
        for (i in 0 until c.length()) {
            val p = c.optJSONObject(i) ?: continue
            when (p.optString("type")) {
                "text" -> out += Part("text", p.optString("text"), null)
                "image_url" -> {
                    val iu = p.opt("image_url")
                    val url = if (iu is JSONObject) iu.optString("url") else iu?.toString().orEmpty()
                    out += Part("image", null, Media.shrinkImage(decodeData(url, "изображения")))
                }
                "input_audio" -> {
                    val a = p.optJSONObject("input_audio")
                    val data = decodeB64(a?.optString("data").orEmpty())
                    val fmt = a?.optString("format", "wav")?.lowercase() ?: "wav"
                    val bytes = if (fmt == "pcm16" || fmt == "pcm")
                        Media.wrapWav(data, a?.optInt("sample_rate", 16000) ?: 16000) else data
                    out += Part("audio", null, bytes)
                }
                "video_url" -> {
                    val vu = p.opt("video_url")
                    val o = vu as? JSONObject
                    val url = if (o != null) o.optString("url") else vu?.toString().orEmpty()
                    val n = (o?.optInt("frames", 6) ?: 6).coerceIn(1, 16)
                    val side = (o?.optInt("max_side", Media.FRAME_SIDE) ?: Media.FRAME_SIDE).coerceIn(128, 1024)
                    val frames = Media.videoFrames(decodeData(url, "видео"), n, side)
                    if (frames.isEmpty()) throw BadReq(400, "не удалось достать кадры из видео")
                    out += Part("text", "Это ${frames.size} кадров одного видео по порядку " +
                        "(моменты: ${frames.joinToString { Media.fmt(it.first) }}).", null)
                    frames.forEach { out += Part("image", null, it.second) }
                }
            }
        }
        return out
    }

    /** Сколько байт данных (картинки, звук) несёт запрос: для лимита очереди по объёму. */
    fun payloadBytes(r: ChatReq): Long =
        r.msgs.sumOf { m -> m.parts.sumOf { (it.bytes?.size ?: 0).toLong() } }

    private fun decodeData(url: String, what: String): ByteArray {
        if (!url.startsWith("data:")) throw BadReq(400, "поддерживаются только $what в виде data:URL (base64)")
        return decodeB64(url.substringAfter("base64,"))
    }

    private fun decodeB64(s: String): ByteArray = try {
        Base64.decode(s, Base64.DEFAULT)
    } catch (e: Exception) {
        throw BadReq(400, "не удалось декодировать base64")
    }

    private fun parseCalls(a: JSONArray?): List<TC> {
        if (a == null) return emptyList()
        val out = ArrayList<TC>()
        for (i in 0 until a.length()) {
            val c = a.optJSONObject(i) ?: continue
            val f = c.optJSONObject("function") ?: continue
            val args = f.opt("arguments").let { if (it is JSONObject) it.toString() else it?.toString().orEmpty() }
            out += TC(c.optString("id").ifBlank { callId(f.optString("name"), args, i) },
                f.optString("name"), args.ifBlank { "{}" })
        }
        return out
    }

    // ---------- ключи кэша ----------

    private fun sha1(vararg parts: ByteArray?): String {
        val md = MessageDigest.getInstance("SHA-1")
        for (p in parts) if (p != null) md.update(p)
        return md.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    /** Отпечаток сообщения: текст нормализуется (trim), чтобы пробелы на концах не ломали кэш. */
    fun key(m: Msg): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(m.role.toByteArray())
        for (p in m.parts) {
            md.update(p.kind.toByteArray())
            p.text?.let { md.update(it.trim().toByteArray()) }
            p.bytes?.let { md.update(it) }
        }
        for (t in m.toolCalls) { md.update(t.name.toByteArray()); md.update(t.args.toByteArray()) }
        m.toolCallId?.let { md.update(it.toByteArray()) }
        return md.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    /** Отпечаток настроек диалога: промпт, инструменты, сэмплер. */
    fun confKey(r: ChatReq): String =
        sha1(r.system.toByteArray(), r.tools.joinToString("|") { it.toString() }.toByteArray(),
            "${r.temperature}/${r.topP}".toByteArray())

    fun callId(name: String, args: String, idx: Int): String = "call_" + sha1("$name$args$idx".toByteArray()).take(10)

    fun argsJson(a: Any?): String = when (a) {
        null -> "{}"
        is Map<*, *> -> JSONObject(a).toString()
        is String -> a.ifBlank { "{}" }
        else -> a.toString()
    }

    fun estimate(r: ChatReq): Int {
        var n = r.system.length / 3 + r.tools.sumOf { it.toString().length } / 3
        for (m in r.msgs) {
            n += m.text.length / 3 + 6
            n += m.toolCalls.sumOf { it.name.length + it.args.length } / 3
            for (p in m.parts) {
                if (p.kind == "image") n += 280
                if (p.kind == "audio") n += (p.bytes?.size ?: 0) / 32000 * 25
            }
        }
        return n
    }

    // ---------- в формат LiteRT-LM ----------

    private fun toContent(parts: List<Part>): List<Content> = parts.map {
        when (it.kind) {
            "image" -> Content.ImageBytes(it.bytes ?: ByteArray(0))
            "audio" -> Content.AudioBytes(it.bytes ?: ByteArray(0))
            else -> Content.Text(it.text ?: "")
        }
    }

    private fun assistantText(m: Msg): String {
        val sb = StringBuilder(m.text)
        for (t in m.toolCalls) sb.append("\n[вызов инструмента ${t.name}(${t.args})]")
        return sb.toString().trim()
    }

    /** История → сообщения user/model. Результаты инструментов превращаются в текст реплики пользователя. */
    fun history(hist: List<Msg>): List<Message> {
        val names = hist.flatMap { it.toolCalls }.associate { it.id to it.name }
        val out = ArrayList<Message>()
        val up = ArrayList<Content>()
        val mt = StringBuilder()
        var mode = 0

        fun closeUser() {
            if (up.isNotEmpty()) out += Message.user(Contents.of(*up.toTypedArray()))
            up.clear()
        }
        fun closeModel() {
            if (mt.isNotBlank()) out += Message.model(mt.toString().trim())
            mt.setLength(0)
        }

        for (m in hist) {
            if (m.role == "assistant") {
                if (mode == 1) { closeUser(); mode = 2 }
                if (mode == 2) {
                    if (mt.isNotEmpty()) mt.append("\n")
                    mt.append(assistantText(m))
                }
            } else {
                if (mode == 2) { closeModel(); mode = 1 }
                if (mode == 0) mode = 1
                if (m.role == "tool") {
                    up += Content.Text("[Результат инструмента ${names[m.toolCallId] ?: "tool"}]: ${m.text}")
                } else {
                    up += toContent(m.parts)
                }
            }
        }
        if (mode == 2) closeModel()
        return out
    }

    fun config(r: ChatReq, hist: List<Msg>): ConversationConfig {
        val sys = Contents.of(r.system.ifBlank { "Ты полезный ассистент. Отвечай кратко." })
        val stubs = r.tools.map { tool(StubTool(it.toString())) }
        val sampler = SamplerConfig(topK = 40, topP = r.topP ?: 0.95, temperature = r.temperature ?: 0.7)
        val initial = history(hist)
        return if (initial.isEmpty()) {
            ConversationConfig(
                systemInstruction = sys,
                tools = stubs,
                automaticToolCalling = false,
                samplerConfig = sampler,
            )
        } else {
            ConversationConfig(
                systemInstruction = sys,
                initialMessages = initial,
                tools = stubs,
                automaticToolCalling = false,
                samplerConfig = sampler,
            )
        }
    }

    /** Живая реплика обычным сообщением (user, либо результаты инструментов текстом). */
    fun liveContents(live: List<Msg>, names: Map<String, String>): Contents {
        val parts = ArrayList<Content>()
        for (m in live) {
            if (m.role == "tool") parts += Content.Text("[Результат инструмента ${names[m.toolCallId] ?: "tool"}]: ${m.text}")
            else parts += toContent(m.parts)
        }
        if (parts.isEmpty()) parts += Content.Text(" ")
        return Contents.of(*parts.toTypedArray())
    }

    /** Результаты инструментов настоящим tool-сообщением (когда диалог уже ждёт их). */
    fun toolMessage(live: List<Msg>, names: Map<String, String>): Message {
        val rs = ArrayList<Content.ToolResponse>()
        for (m in live) rs += Content.ToolResponse(names[m.toolCallId] ?: "tool", m.text)
        return Message.tool(Contents.of(rs))
    }
}
