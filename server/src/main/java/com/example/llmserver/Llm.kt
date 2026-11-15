package com.example.llmserver

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Единственная модель и один рабочий поток, который берёт запросы из очереди по одному.
 * Диалог модели кэшируется: если следующий запрос продолжает предыдущий (тот же промпт, инструменты и
 * история), он досылает только новое сообщение, а не пересчитывает всё заново.
 */
object Llm {
    val status = MutableStateFlow("модель не загружена")

    @Volatile var window = 0
    @Volatile var ready = false

    private val initMutex = Mutex()
    private val runLock = Any()
    private var engine: Engine? = null
    private var conv: Conversation? = null
    private var liveSig = ""
    private var worker: Thread? = null

    // ---------- рабочий поток ----------

    fun startWorker() {
        synchronized(this) {
            if (worker?.isAlive == true) return
            worker = Thread { loop() }.apply { isDaemon = true; name = "llm-worker"; start() }
        }
    }

    private fun loop() {
        while (true) {
            val job = try { Queue.take() } catch (_: InterruptedException) { return }
            if (job.cancelled) continue
            val now = System.currentTimeMillis()
            if (now > job.deadline) {
                job.events.add(Ev.Err(503, "превышено время ожидания в очереди"))
                Queue.record(job.client.name, false, now - job.created)
                continue
            }
            job.startedAt = now
            Queue.running = job
            Queue.changed.value++
            var ok = true
            try {
                synchronized(runLock) { run(job) }
                ServerLog.add("${job.client.name}: ответ за ${"%.1f".format((System.currentTimeMillis() - now) / 1000.0)} с")
            } catch (e: BadReq) {
                ok = false
                job.events.add(Ev.Err(e.code, e.message ?: "ошибка запроса"))
            } catch (t: Throwable) {
                ok = false
                synchronized(runLock) { dropConv() }
                ServerLog.add("${job.client.name}: ошибка ${t.message}")
                job.events.add(Ev.Err(500, "ошибка модели: ${t.message}"))
            } finally {
                Queue.record(job.client.name, ok, now - job.created)
                Queue.running = null
                Queue.changed.value++
            }
        }
    }

    private fun dropConv() {
        runCatching { conv?.close() }
        conv = null
        liveSig = ""
    }

    private fun run(job: Job) {
        val e = engine ?: throw BadReq(503, "модель не загружена")
        val req = job.req
        val msgs = req.msgs

        // Хвост из сообщений tool — это результаты инструментов, иначе последнее сообщение user.
        var i = msgs.size
        while (i > 0 && msgs[i - 1].role == "tool") i--
        val liveTools = msgs.subList(i, msgs.size)
        val hist: List<Msg>
        val live: List<Msg>
        if (liveTools.isNotEmpty()) {
            hist = msgs.subList(0, i)
            live = liveTools
        } else {
            if (msgs.isEmpty() || msgs.last().role != "user") {
                throw BadReq(400, "последнее сообщение должно быть от user или tool")
            }
            hist = msgs.dropLast(1)
            live = listOf(msgs.last())
        }

        val conf = Convert.confKey(req)
        val histKeys = hist.map { Convert.key(it) }
        val liveKeys = live.map { Convert.key(it) }
        val reqSig = conf + "#" + histKeys.joinToString(",")
        val hit = conv != null && reqSig == liveSig
        if (!hit) {
            dropConv()
            conv = e.createConversation(Convert.config(req, hist))
        }
        val c = conv!!
        liveSig = ""    // пока идёт запрос, кэш недействителен

        val toolTurn = liveTools.isNotEmpty()
        val names = hist.flatMap { it.toolCalls }.associate { it.id to it.name }
        val useFlow = !toolTurn && req.stream && (req.tools.isEmpty() || Cfg.streamTools(App.instance))
        val text = StringBuilder()
        val calls = ArrayList<TC>()

        fun take(m: Message) {
            for (tc in m.toolCalls) {
                val args = Convert.argsJson(tc.arguments)
                if (calls.none { it.name == tc.name && it.args == args }) {
                    calls += TC(Convert.callId(tc.name, args, calls.size), tc.name, args)
                }
            }
        }

        if (useFlow) {
            runBlocking {
                c.sendMessageAsync(Convert.liveContents(live, names))
                    .takeWhile { !job.cancelled }
                    .collect { m ->
                        val chunk0 = m.toString()
                        val chunk = if (text.isEmpty()) chunk0.trimStart() else chunk0
                        if (chunk.isNotEmpty()) {
                            text.append(chunk)
                            job.events.add(Ev.Delta(chunk))
                        }
                        take(m)
                    }
            }
            if (job.cancelled) { dropConv(); return }
        } else {
            val msg: Message = if (toolTurn && hit) c.sendMessage(Convert.toolMessage(live, names))
            else c.sendMessage(Convert.liveContents(live, names))
            text.append((msg.text ?: "").trim())
            take(msg)
            if (text.isNotEmpty()) job.events.add(Ev.Delta(text.toString()))
        }

        if (calls.isNotEmpty()) job.events.add(Ev.Calls(calls))
        val finish = if (calls.isNotEmpty()) "tool_calls" else "stop"
        val pt = Convert.estimate(req)
        val ct = (text.length + calls.sumOf { it.name.length + it.args.length }) / 3 + 1

        // Как этот ответ вернётся от клиента в следующем запросе: по этому отпечатку находим кэш
        val aKey = Convert.key(Msg("assistant",
            if (text.isBlank()) emptyList() else listOf(Part("text", text.toString().trim(), null)),
            calls, null))
        liveSig = conf + "#" + (histKeys + liveKeys + aKey).joinToString(",")
        job.events.add(Ev.Done(finish, pt, ct))
    }

    // ---------- загрузка модели ----------

    /**
     * Загружает движок с максимально возможным окном. Не хватило памяти — пробует вдвое меньше (до 4096).
     * Если процесс убило во время загрузки, при следующем запуске начинает с половины прошлой попытки.
     */
    suspend fun init(ctx: Context): String = initMutex.withLock {
        if (ready) return@withLock "уже загружена"
        val file = Cfg.modelFile(ctx)
        if (!file.exists()) {
            status.value = "модели нет: скачайте её"
            throw IllegalStateException("нет файла модели")
        }
        status.value = "загружаю модель…"
        val sp = ctx.getSharedPreferences("server", Context.MODE_PRIVATE)
        Mem.applyMemoryCap(ctx)?.let { ServerLog.add(it) }
        val wanted = Cfg.window(ctx)
        val interrupted = sp.getInt("win_trying", 0)
        val start = if (interrupted in 1..wanted) maxOf(interrupted / 2, 4096) else wanted
        val ladder = generateSequence(start) { if (it > 4096) maxOf(it / 2, 4096) else null }.toList()

        var last: Throwable? = null
        for (w in ladder) {
            sp.edit().putInt("win_trying", w).apply()
            try {
                val eng = withContext(Dispatchers.Default) { openEngine(ctx, file, w) }
                synchronized(runLock) { dropConv(); engine = eng }
                window = w
                ready = true
                sp.edit().putInt("win_trying", 0).putInt("win_active", w).apply()
                val msg = "готова (окно $w ток.)"
                status.value = msg
                ServerLog.add("модель $msg")
                return@withLock msg
            } catch (t: Throwable) {
                last = t
                Log.w("Llm", "окно $w не взлетело: $t")
            }
        }
        sp.edit().putInt("win_trying", 0).apply()
        status.value = "ошибка загрузки: ${last?.message}"
        throw last ?: IllegalStateException("не удалось загрузить модель")
    }

    private fun openEngine(ctx: Context, file: File, window: Int): Engine {
        val cpuOnly = Cfg.backendMain(ctx) == "cpu"
        fun attempt(b: Backend): Engine {
            val e = Engine(
                EngineConfig(
                    modelPath = file.path,
                    backend = b,
                    visionBackend = if (Cfg.backendVision(ctx) == "cpu") Backend.CPU() else Backend.GPU(),
                    audioBackend = Backend.CPU(),
                    maxNumTokens = window,
                    cacheDir = ctx.cacheDir.path,
                )
            )
            try {
                e.initialize()
                return e
            } catch (t: Throwable) {
                runCatching { e.close() }
                throw t
            }
        }
        return try {
            attempt(if (cpuOnly) Backend.CPU() else Backend.GPU())
        } catch (t: Throwable) {
            if (cpuOnly) throw t
            Log.w("Llm", "GPU не взлетел, CPU: $t")
            attempt(Backend.CPU())
        }
    }

    fun close() {
        synchronized(runLock) {
            dropConv()
            runCatching { engine?.close() }
            engine = null
            ready = false
        }
        window = 0
        status.value = "модель выгружена"
    }

    suspend fun download(ctx: Context) {
        val file = Cfg.modelFile(ctx)
        if (file.exists()) { status.value = "модель уже скачана"; return }
        if (Dl.running) return
        Dl.running = true
        try {
            var last = 0L
            val part = Downloader.download(file) { done, total ->
                val now = SystemClock.elapsedRealtime()
                if (now - last > 1000 && total > 0) {
                    last = now
                    status.value = "скачивание: ${done / 1048576} из ${total / 1048576} МБ (${done * 100 / total}%)"
                }
            }
            status.value = "проверяю файл…"
            if (!Downloader.sha256Ok(part)) {
                part.delete()
                status.value = "файл повреждён, скачайте ещё раз"
                return
            }
            part.renameTo(file)
            init(ctx)
        } catch (t: Throwable) {
            status.value = "ошибка скачивания: ${t.message}"
        } finally {
            Dl.running = false
        }
    }
}
