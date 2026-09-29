package app.antigravity.agent.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import app.antigravity.agent.AgentViewModel

private data class Tab(val label: String, val icon: ImageVector)

@Composable
fun AppRoot(vm: AgentViewModel) {
    val tabs = listOf(
        Tab("Chat", Icons.Filled.Chat),
        Tab("Files", Icons.Filled.Folder),
        Tab("Settings", Icons.Filled.Settings),
    )
    var selected by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = selected == i,
                        onClick = { selected = i },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        val m = Modifier.padding(pad)
        when (selected) {
            0 -> ChatScreen(vm, m, openSettings = { selected = 2 })
            1 -> FilesScreen(vm.workspaceDir, m)
            else -> SettingsScreen(vm, m)
        }
    }
}
