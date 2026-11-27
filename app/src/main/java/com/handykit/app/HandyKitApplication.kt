package com.handykit.app

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

val Application.navOrderDataStore: DataStore<Preferences> by preferencesDataStore(name = "nav_order")

class HandyKitApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 启动阶段只装配必要组件。不打开笔记库、不连接 BLE、不解析附件。
    }
}
