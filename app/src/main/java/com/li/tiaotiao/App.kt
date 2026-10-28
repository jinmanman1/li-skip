package com.li.tiaotiao

import android.app.Application
import com.li.tiaotiao.core.RuleStore

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        RuleStore.init(this)
    }
}
