package com.familia.tarefas

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.familia.core.sync.Graph
import com.familia.core.ui.AppGate
import com.familia.core.ui.FamiliaTheme

class MainActivity : ComponentActivity() {
    private var route by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        route = intent?.getStringExtra("route")
        setContent {
            FamiliaTheme(light = Color(0xFF0E7490), dark = Color(0xFF4FB3CF)) {
                AppGate("Casa em dia", "Tarefas da casa, trocas e saldos") {
                    val st by Graph.session.state.collectAsState()
                    if (st.meRole == "subadmin") SubRoot() else MainRoot(route)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        route = intent.getStringExtra("route")
    }
}
