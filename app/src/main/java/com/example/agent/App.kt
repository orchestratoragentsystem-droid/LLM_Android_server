package com.example.agent

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        // Экран ошибки живёт в отдельном процессе :crash: там не нужны ни чат, ни обработчик падений
        if (Application.getProcessName() != packageName) return
        ChatStore.init(this)
        CrashLog.install(this)
    }

    companion object {
        @Volatile var instance: App? = null
    }
}
