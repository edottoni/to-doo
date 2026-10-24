package com.familia.tarefas

import android.app.Application
import androidx.glance.appwidget.updateAll
import com.familia.core.sync.Graph
import com.familia.core.sync.SyncScheduler

class MainApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this, "main")
        Graph.repo.onDataChanged = { runCatching { FamiliaWidget().updateAll(this@MainApp) } }
        Graph.afterSync = { ctx, outcome -> MainNotifier.check(ctx, outcome) }
        SyncScheduler.schedule(this)
    }
}
