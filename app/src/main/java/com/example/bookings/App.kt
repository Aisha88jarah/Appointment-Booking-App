package com.example.bookings

import android.app.Application
import com.example.bookings.core.AppPrefs
import com.example.bookings.core.TokenStore
import com.example.bookings.data.AppDatabase
import com.example.bookings.telegram.MonitorStatus
import com.example.bookings.telegram.TelegramApi
import kotlinx.coroutines.flow.MutableStateFlow

class App : Application() {
    lateinit var db: AppDatabase
    lateinit var tokenStore: TokenStore
    lateinit var prefs: AppPrefs
    val api = TelegramApi()
    val status = MutableStateFlow<MonitorStatus>(MonitorStatus.Stopped)

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.build(this)
        tokenStore = TokenStore(this)
        prefs = AppPrefs(this)
    }
}
