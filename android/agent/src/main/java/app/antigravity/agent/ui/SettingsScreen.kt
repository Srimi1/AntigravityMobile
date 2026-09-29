package app.antigravity.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.antigravity.agent.AgentViewModel
import app.antigravity.agent.data.SettingsStore

@Composable
fun SettingsScreen(vm: AgentViewModel, modifier: Modifier = Modifier) {
    val hasKey by vm.hasKey.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(vm.settings.model) }
    var autoApprove by remember { mutableStateOf(vm.settings.autoApproveShell) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Gemini API key", style = MaterialTheme.typography.titleMedium)
        Text(
            "Create a free key at aistudio.google.com/apikey. A Google AI Pro plan does not include API access, " +
                "but the free AI Studio key works with usage limits. The key is stored encrypted on this phone.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text(if (hasKey) "Key saved (enter a new one to replace)" else "Paste API key") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.saveApiKey(key); key = "" }, enabled = key.isNotBlank()) { Text("Save key") }
            if (hasKey) OutlinedButton(onClick = { vm.saveApiKey("") }) { Text("Remove key") }
        }

        Text("Model", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = model,
            onValueChange = { model = it; vm.settings.model = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsStore.SUGGESTED_MODELS.take(2).forEach { m ->
                AssistChip(onClick = { model = m; vm.settings.model = m }, label = { Text(m) })
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Run shell commands without asking", style = MaterialTheme.typography.titleMedium)
                Text("Off is safer. The agent asks before every command.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = autoApprove, onCheckedChange = { autoApprove = it; vm.settings.autoApproveShell = it })
        }

        Text("Workspace", style = MaterialTheme.typography.titleMedium)
        Text(vm.workspaceDir.path, style = MaterialTheme.typography.bodySmall)

        OutlinedButton(onClick = { vm.clearChat() }) { Text("Clear conversation") }
    }
}
