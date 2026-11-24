package com.example.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Показывает ошибку, из-за которой упало приложение. Живёт в отдельном процессе (:crash). */
class CrashActivity : AppCompatActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        title = "Приложение упало"
        val trace = intent.getStringExtra("trace") ?: "нет данных"
        val d = resources.displayMetrics.density

        val tv = TextView(this).apply {
            text = trace
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (24 * d).toInt())
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Button(this).apply {
            text = "Копировать ошибку"
            isAllCaps = false
            setOnClickListener {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("crash", trace))
                Toast.makeText(this@CrashActivity, "Скопировано", Toast.LENGTH_SHORT).show()
            }
        })
        col.addView(Button(this).apply {
            text = "Поделиться"
            isAllCaps = false
            setOnClickListener {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, trace), "Отправить ошибку"))
            }
        })
        col.addView(Button(this).apply {
            text = "Открыть приложение снова"
            isAllCaps = false
            setOnClickListener {
                startActivity(Intent(this@CrashActivity, ChatActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            }
        })
        col.addView(ScrollView(this).apply { addView(tv) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(col)
    }
}
