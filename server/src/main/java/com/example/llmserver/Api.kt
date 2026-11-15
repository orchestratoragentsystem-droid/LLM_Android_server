package com.example.llmserver

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Маршруты:
 *   GET  /health                  без ключа: состояние
 *   GET  /v1/models               список моделей
 *   POST /v1/chat/completions     чат в формате OpenAI (текст, image_url, input_audio, video_url, tools)
 *   GET  /queue                   очередь и статистика по клиентам
 * Ключ клиента: Authorization: Bearer <ключ> или X-Auth-Token.
 */
object Api {

    fun errorJson(msg: String, type: String): String =
        JSONObject().put("error", JSONObject().put("message", msg).put("type", type)).toString()

    fun handle(ctx: Context, req: HttpRequest, res: HttpResponse) {
        try {
            if (req.method == "GET" && req.path == "/health") {
                res.json(200, JSONObject().put("ok", true).put("model", Llm.status.value)
                    .put("ready", Llm.ready).put("window", Llm.window).toString())
                return
            }
            val client = authorize(ctx, req)
            if (client == null) {
                res.json(401, errorJson("неверный или отсутствующий ключ клиента", "invalid_api_key"))
                return
            }
            when {
                req.method == "GET" && (req.path == "/v1/models" || req.path == "/models") -> {
                    val m = JSONObject().put("id", "gemma-4-e2b").put("object", "model").put("owned_by", "local")
                    res.json(200, JSONObject().put("object", "list").put("data", JSONArray().put(m)).toString())
                }
                req.method == "GET" && req.path == "/queue" -> res.json(200, queueJson().toString())
                req.method == "POST" && (req.path == "/v1/chat/completions" || req.path == "/chat/completions") ->
                    chat(client, req, res)
                else -> res.json(404, errorJson("нет такого маршрута: ${req.method} ${req.path}", "not_found"))
            }
        } catch (e: BadReq) {
            res.json(e.code, errorJson(e.message ?: "ошибка запроса", "invalid_request_error"))
        }
    }

    private fun authorize(ctx: Context, req: HttpRequest): Client? {
        val a = req.headers["authorization"]
        val tok = if (a != null) a.substringAfter(' ', a).trim() else req.headers["x-auth-token"]?.trim()
        if (tok.isNullOrEmpty()) return null
        return Clients.byToken(ctx, tok)
    }

    private fun queueJson(): JSONObject {
        val now = System.currentTimeMillis()
        val waiting = JSONArray()
        Queue.snapshot().forEachIndexed { i, j ->
            waiting.put(JSONObject().put("position", i + 1).put("client", j.client.name)
                .put("priority", Clients.priorityName(j.priority)).put("waited_sec", (now - j.created) / 1000))
        }
        val run = Queue.running
        val stats = JSONObject()
        for ((k, v) in Queue.stats) {
            stats.put(k, JSONObject().put("ok", v[0]).put("errors", v[1])
                .put("avg_wait_sec", if (v[0] + v[1] > 0) v[2] / (v[0] + v[1]) / 1000.0 else 0.0))
        }
        return JSONObject()
            .put("running", if (run == null) JSONObject.NULL else JSONObject()
                .put("client", run.client.name).put("seconds", (now - run.startedAt) / 1000))
            .put("waiting", waiting)
            .put("stats", stats)
    }

    // ---------- /v1/chat/completions ----------

    private fun chat(client: Client, req: HttpRequest, res: HttpResponse) {
        val cr = Convert.parse(String(req.body, Charsets.UTF_8))
        if (!Llm.ready) throw BadReq(503, "модель не загружена (${Llm.status.value})")
        val est = Convert.estimate(cr)
        if (est > Llm.window - 300) {
            throw BadReq(400, "context_length_exceeded: запрос около $est токенов, окно модели ${Llm.window}")
        }

        val requested = when (req.headers["x-priority"]?.lowercase()) { "high" -> 0; "low" -> 2; "normal" -> 1; else -> null }
        val priority = if (requested == null) client.priority else maxOf(requested, client.priority)
        val waitSec = (req.headers["x-queue-timeout"]?.toLongOrNull() ?: 120L).coerceIn(1L, 900L)
        val job = Job(cr, client, priority, System.currentTimeMillis() + waitSec * 1000,
            req.headers["x-supersede-key"]?.takeIf { it.isNotBlank() }, Convert.payloadBytes(cr))
        Queue.add(job)?.let { throw BadReq(429, it) }

        val id = "chatcmpl-" + job.seq
        val created = System.currentTimeMillis() / 1000
        val hardStop = job.deadline + 20 * 60_000L

        if (cr.stream) streamReply(job, id, created, cr.model, res)
        else syncReply(job, id, created, cr.model, res, hardStop)
    }

    private fun syncReply(job: Job, id: String, created: Long, model: String, res: HttpResponse, hardStop: Long) {
        val text = StringBuilder()
        var calls: List<TC> = emptyList()
        while (true) {
            val ev = job.events.poll(1, TimeUnit.SECONDS)
            if (res.clientGone()) { Queue.cancel(job); return }
            if (ev == null) {
                if (System.currentTimeMillis() > hardStop) {
                    Queue.cancel(job)
                    res.json(503, errorJson("превышено время ожидания ответа", "timeout"))
                    return
                }
                continue
            }
            when (ev) {
                is Ev.Delta -> text.append(ev.text)
                is Ev.Calls -> calls = ev.calls
                is Ev.Err -> { res.json(ev.code, errorJson(ev.message, "server_error")); return }
                is Ev.Done -> {
                    val msg = JSONObject().put("role", "assistant")
                    msg.put("content", if (text.isBlank() && calls.isNotEmpty()) JSONObject.NULL else text.toString())
                    if (calls.isNotEmpty()) msg.put("tool_calls", callsJson(calls, false))
                    val body = JSONObject().put("id", id).put("object", "chat.completion").put("created", created)
                        .put("model", model)
                        .put("choices", JSONArray().put(JSONObject().put("index", 0).put("message", msg)
                            .put("finish_reason", ev.finish)))
                        .put("usage", JSONObject().put("prompt_tokens", ev.promptTokens)
                            .put("completion_tokens", ev.completionTokens)
                            .put("total_tokens", ev.promptTokens + ev.completionTokens))
                    res.json(200, body.toString())
                    return
                }
            }
        }
    }

    private fun streamReply(job: Job, id: String, created: Long, model: String, res: HttpResponse) {
        res.startSse()
        var roleSent = false
        var lastPing = 0L
        fun chunk(delta: JSONObject, finish: String?): String {
            val ch = JSONObject().put("index", 0).put("delta", delta)
                .put("finish_reason", finish ?: JSONObject.NULL)
            return JSONObject().put("id", id).put("object", "chat.completion.chunk").put("created", created)
                .put("model", model).put("choices", JSONArray().put(ch)).toString()
        }
        fun role() {
            if (!roleSent) {
                res.sseData(chunk(JSONObject().put("role", "assistant").put("content", ""), null))
                roleSent = true
            }
        }
        while (true) {
            val ev = job.events.poll(1, TimeUnit.SECONDS)
            if (res.failed || res.clientGone()) { Queue.cancel(job); return }
            if (ev == null) {
                val now = System.currentTimeMillis()
                if (now - lastPing > 5000) {
                    res.sseComment("queued position=${Queue.position(job)}")
                    lastPing = now
                }
                continue
            }
            when (ev) {
                is Ev.Delta -> { role(); res.sseData(chunk(JSONObject().put("content", ev.text), null)) }
                is Ev.Calls -> { role(); res.sseData(chunk(JSONObject().put("tool_calls", callsJson(ev.calls, true)), null)) }
                is Ev.Err -> {
                    res.sseData(errorJson(ev.message, "server_error"))
                    res.sseData("[DONE]")
                    res.endSse()
                    return
                }
                is Ev.Done -> {
                    role()
                    res.sseData(chunk(JSONObject(), ev.finish))
                    res.sseData("[DONE]")
                    res.endSse()
                    return
                }
            }
        }
    }

    private fun callsJson(calls: List<TC>, withIndex: Boolean): JSONArray {
        val a = JSONArray()
        calls.forEachIndexed { i, c ->
            val o = JSONObject().put("id", c.id).put("type", "function")
                .put("function", JSONObject().put("name", c.name).put("arguments", c.args))
            if (withIndex) o.put("index", i)
            a.put(o)
        }
        return a
    }
}
