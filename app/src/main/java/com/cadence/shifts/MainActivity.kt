package com.cadence.shifts

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cadence.shifts.ui.CadenceApp
import com.cadence.shifts.ui.theme.CadenceTheme
import com.cadence.shifts.util.NotificationHelper

class MainActivity : ComponentActivity() {
    private var requestedTabRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestedTabRoute = intent?.getStringExtra(NotificationHelper.EXTRA_OPEN_TAB)
        setContent {
            CadenceTheme {
                CadenceApp(
                    requestedTabRoute = requestedTabRoute,
                    onConsumedRequestedTab = { requestedTabRoute = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(NotificationHelper.EXTRA_OPEN_TAB)?.let { route ->
            requestedTabRoute = route
        }
    }
}
