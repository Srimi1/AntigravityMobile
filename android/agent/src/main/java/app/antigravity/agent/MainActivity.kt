package app.antigravity.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.antigravity.agent.ui.AgentTheme
import app.antigravity.agent.ui.AppRoot

class MainActivity : ComponentActivity() {
    private val vm: AgentViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AgentTheme {
                AppRoot(vm)
            }
        }
    }
}
