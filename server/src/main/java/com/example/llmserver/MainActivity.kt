package com.example.llmserver

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var col: LinearLayout
    private lateinit var info: TextView
    private lateinit var queueView: TextView
    private lateinit var logView: TextView
    private lateinit var clientsBox: LinearLayout
    private lateinit var toggle: Button

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        title = "LLM-сервер"
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(col) })

        info = TextView(this).apply { textSize = 14f; setTextIsSelectable(true); setPadding(0, dp(4), 0, dp(8)) }
        col.addView(info)

        toggle = Button(this).apply {
            isAllCaps = false
            setOnClickListener {
                if (ServerState.running.value) ServerService.stop(this@MainActivity)
                else ServerService.start(this@MainActivity)
            }
        }
        col.addView(toggle)
        button("Скачать модель (2,6 ГБ, лучше по Wi-Fi)") { ServerService.start(this, ServerService.ACTION_DOWNLOAD) }
        button("Перезагрузить модель (применить окно и движок)") { ServerService.start(this, ServerService.ACTION_RELOAD) }

        val testOut = TextView(this).apply { textSize = 12f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        button("Проверка: запрос к самому себе") { selfTest(testOut) }
        col.addView(testOut)

        header("Клиенты (у каждого свой ключ и приоритет)")
        clientsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(clientsBox)
        val nameEdit = EditText(this).apply { hint = "Имя нового клиента, например camera"; setSingleLine() }
        col.addView(nameEdit)
        button("Добавить клиента") {
            if (Clients.add(this, nameEdit.text.toString()) == null)
                Toast.makeText(this, "Имя пустое или уже занято", Toast.LENGTH_SHORT).show()
            nameEdit.setText("")
            renderClients()
        }

        header("Очередь запросов")
        queueView = TextView(this).apply { textSize = 13f; typeface = Typeface.MONOSPACE }
        col.addView(queueView)

        header("Настройки (порт и доступ из сети применяются после перезапуска сервера)")
        numberEdit("Порт", Cfg.port(this)) { Cfg.putInt(this, "port", it) }
        numberEdit("Окно модели, токенов (0 — автоматически по памяти)", Cfg.windowSaved(this)) {
            Cfg.putInt(this, "window", it)
        }
        hint("Память телефона: ${Cfg.totalRamMb(this)} МБ, рекомендуемое окно: ${Cfg.recommended(this)} ток. " +
            "Если памяти не хватит, сервер сам попробует вдвое меньше.")
        if (Cfg.winCap(this) > 0) {
            hint("Автоограничение по памяти: ${Cfg.winCap(this)} ток.")
            button("Сбросить автоограничение") { Cfg.putInt(this, Cfg.WIN_CAP, 0); recreate() }
        }
        check("Доступ из локальной сети (иначе только с этого телефона)", Cfg.lan(this)) { Cfg.putBool(this, "lan", it) }
        check("Принимать запросы с этого телефона без ключа (для сторонних клиентов)", Cfg.openLocal(this)) {
            Cfg.putBool(this, "open_local", it)
        }
        check("Запускать при включении телефона", Cfg.autostart(this)) { Cfg.putBool(this, "autostart", it) }
        check("Потоковый ответ вместе с инструментами (экспериментально)", Cfg.streamTools(this)) {
            Cfg.putBool(this, "stream_tools", it)
        }
        hint("Основной движок модели:")
        radio("backend_main", listOf("gpu" to "GPU (быстрее)", "cpu" to "CPU (экономнее по памяти)"), Cfg.backendMain(this))
        hint("Обработка картинок и видео:")
        radio("backend_vision", listOf("gpu" to "GPU", "cpu" to "CPU (экономнее)"), Cfg.backendVision(this))
        button("Отключить экономию батареи") {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")))
            }
        }

        header("Журнал")
        logView = TextView(this).apply { textSize = 12f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        col.addView(logView)

        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        renderClients()
        lifecycleScope.launch { ServerState.running.collect { renderInfo() } }
        lifecycleScope.launch { ServerState.error.collect { renderInfo() } }
        lifecycleScope.launch { Llm.status.collect { renderInfo() } }
        lifecycleScope.launch { Queue.changed.collect { renderQueue() } }
        lifecycleScope.launch { ServerLog.lines.collect { logView.text = it.joinToString("\n") } }
        lifecycleScope.launch { while (true) { delay(2000); renderQueue() } }
    }

    // ---------- состояние ----------

    private fun renderInfo() {
        val running = ServerState.running.value
        toggle.text = if (running) "Остановить сервер" else "Запустить сервер"
        val sb = StringBuilder()
        sb.append("Сервер: ").append(if (running) "работает" else "остановлен")
        if (ServerState.error.value.isNotEmpty()) sb.append("\n⚠ ").append(ServerState.error.value)
        if (running) {
            sb.append("\nНа этом телефоне: http://127.0.0.1:${Cfg.port(this)}/v1")
            if (Cfg.lan(this)) localIps().forEach { sb.append("\nВ сети: http://$it:${Cfg.port(this)}/v1") }
            else sb.append("\n(доступ из сети выключен)")
        }
        sb.append("\nМодель: ").append(Llm.status.value)
        sb.append("\nLiteRT-LM ${BuildConfig.LITERT_VERSION}")
        info.text = sb.toString()
    }

    private fun renderQueue() {
        val now = System.currentTimeMillis()
        val sb = StringBuilder()
        val run = Queue.running
        sb.append(if (run == null) "Выполняется: ничего\n"
        else "Выполняется: ${run.client.name} (${(now - run.startedAt) / 1000} с)\n")
        val w = Queue.snapshot()
        if (w.isEmpty()) sb.append("Ожидают: никого") else {
            sb.append("Ожидают:")
            w.forEachIndexed { i, j ->
                sb.append("\n  ${i + 1}. ${j.client.name} · ${Clients.priorityName(j.priority)} · ${(now - j.created) / 1000} с")
            }
        }
        if (Queue.stats.isNotEmpty()) {
            sb.append("\nСтатистика:")
            for ((k, v) in Queue.stats) {
                val n = v[0] + v[1]
                sb.append("\n  $k: ответов ${v[0]}, ошибок ${v[1]}, среднее ожидание ${if (n > 0) v[2] / n / 1000.0 else 0.0} с")
            }
        }
        queueView.text = sb.toString()
    }

    private fun localIps(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .filter { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
            .mapNotNull { it.hostAddress }
    } catch (_: Throwable) {
        emptyList()
    }

    private fun renderClients() {
        clientsBox.removeAllViews()
        for (c in Clients.all(this)) {
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
            card.addView(TextView(this).apply {
                text = "${c.name} · приоритет: ${Clients.priorityName(c.priority)}\n${c.token}"
                textSize = 13f
                setTextIsSelectable(true)
            })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fun b(t: String, f: () -> Unit) = row.addView(Button(this).apply {
                text = t; isAllCaps = false; textSize = 12f; setOnClickListener { f() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            b("Копировать") {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("key", c.token))
                Toast.makeText(this, "Ключ скопирован", Toast.LENGTH_SHORT).show()
            }
            b("Приоритет") { Clients.cyclePriority(this, c.name); renderClients() }
            b("Новый ключ") { Clients.regenerate(this, c.name); renderClients() }
            b("Удалить") { Clients.remove(this, c.name); renderClients() }
            card.addView(row)
            clientsBox.addView(card)
        }
    }

    // ---------- проверка ----------

    private fun selfTest(out: TextView) {
        out.text = "Проверяю…"
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                try {
                    val u = URL("http://127.0.0.1:${Cfg.port(this@MainActivity)}/v1/chat/completions")
                    val c = u.openConnection() as HttpURLConnection
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.connectTimeout = 5000
                    c.readTimeout = 300_000
                    c.setRequestProperty("Content-Type", "application/json")
                    c.setRequestProperty("Authorization", "Bearer ${Clients.all(this@MainActivity).first().token}")
                    val body = JSONObject().put("messages", JSONArray().put(
                        JSONObject().put("role", "user").put("content", "Ответь одним словом: привет")))
                    c.outputStream.use { it.write(body.toString().toByteArray()) }
                    val code = c.responseCode
                    val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
                    "HTTP $code\n" + txt.take(900)
                } catch (e: Throwable) {
                    "Ошибка: ${e.message}"
                }
            }
            out.text = r
        }
    }

    // ---------- элементы ----------

    private fun header(t: String) {
        col.addView(TextView(this).apply { text = t; textSize = 16f; setTypeface(null, Typeface.BOLD); setPadding(0, dp(18), 0, dp(4)) })
    }

    private fun hint(t: String) {
        col.addView(TextView(this).apply { text = t; textSize = 13f; alpha = 0.75f; setPadding(0, dp(6), 0, dp(2)) })
    }

    private fun button(label: String, onClick: () -> Unit) {
        col.addView(Button(this).apply { text = label; isAllCaps = false; setOnClickListener { onClick() } })
    }

    private fun check(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
        col.addView(CheckBox(this).apply {
            text = label
            isChecked = value
            setOnCheckedChangeListener { _, on -> onChange(on) }
        })
    }

    private fun radio(key: String, options: List<Pair<String, String>>, current: String) {
        val g = RadioGroup(this)
        options.forEachIndexed { i, (value, label) ->
            g.addView(RadioButton(this).apply { id = 2000 + i; text = label; isChecked = value == current })
        }
        g.setOnCheckedChangeListener { _, id -> Cfg.putStr(this, key, options[id - 2000].first) }
        col.addView(g)
    }

    private fun numberEdit(label: String, value: Int, onChange: (Int) -> Unit) {
        col.addView(TextView(this).apply { text = label; setPadding(0, dp(8), 0, 0) })
        col.addView(EditText(this).apply {
            setText(value.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { s?.toString()?.toIntOrNull()?.let(onChange) }
            })
        })
    }
}
