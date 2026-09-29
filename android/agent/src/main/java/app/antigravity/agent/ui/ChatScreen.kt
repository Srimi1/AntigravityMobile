package app.antigravity.agent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.antigravity.agent.AgentViewModel
import app.antigravity.agent.core.ChatItem

@Composable
fun ChatScreen(vm: AgentViewModel, modifier: Modifier = Modifier, openSettings: () -> Unit) {
    val items by vm.items.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val hasKey by vm.hasKey.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    val lastKey = (items.lastOrNull() as? ChatItem.ToolRun)?.output
    LaunchedEffect(items.size, lastKey, pending) {
        if (items.isNotEmpty()) listState.animateScrollToItem(items.lastIndex)
    }

    Column(modifier.fillMaxSize().imePadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (items.isEmpty()) {
                EmptyState(hasKey, openSettings)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(items, key = { it.id }) { item -> ItemView(item) }
                }
            }
        }

        pending?.let { p ->
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("Run this command?", style = MaterialTheme.typography.titleSmall)
                    Text(
                        p.summary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.answerApproval(true) }) { Text("Run") }
                        OutlinedButton(onClick = { vm.answerApproval(false) }) { Text("Deny") }
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask the agent to build or fix something") },
                maxLines = 6,
                shape = RoundedCornerShape(24.dp),
            )
            if (running) {
                IconButton(onClick = { vm.stop() }) { Icon(Icons.Filled.Stop, contentDescription = "Stop") }
            } else {
                IconButton(
                    onClick = {
                        vm.send(input)
                        input = ""
                    },
                    enabled = input.isNotBlank(),
                ) { Icon(Icons.Filled.ArrowUpward, contentDescription = "Send") }
            }
        }
        if (running && pending == null) {
            CircularProgressIndicator(
                modifier = Modifier.padding(start = 16.dp, bottom = 6.dp).size(16.dp),
                strokeWidth = 2.dp,
            )
        }
    }
}

@Composable
private fun EmptyState(hasKey: Boolean, openSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Antigravity Agent", style = MaterialTheme.typography.headlineSmall)
        Text(
            "A coding agent that works on files in a private workspace on this phone.",
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!hasKey) {
            Text(
                "First, add your Gemini API key in Settings.",
                modifier = Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = openSettings) { Text("Open Settings") }
        }
    }
}

@Composable
private fun ItemView(item: ChatItem) {
    when (item) {
        is ChatItem.User -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth(0.85f),
            ) {
                SelectionContainer { Text(item.text, Modifier.padding(12.dp)) }
            }
        }
        is ChatItem.Model -> MessageText(item.text)
        is ChatItem.Notice -> Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(item.text, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
        }
        is ChatItem.ToolRun -> ToolCard(item)
    }
}

@Composable
private fun ToolCard(item: ChatItem.ToolRun) {
    var open by remember { mutableStateOf(false) }
    val output = item.output
    val done = output != null
    Card(
        Modifier.fillMaxWidth().clickable(enabled = done) { open = !open },
        colors = CardDefaults.cardColors(
            containerColor = if (item.isError) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!done) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    (if (done) "" else "  ") + item.name,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(
                item.summary,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                maxLines = if (open) Int.MAX_VALUE else 2,
            )
            if (open && output != null) {
                Text(
                    output,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else if (done) {
                Text("Tap to show output", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Plain text with fenced code blocks shown in a monospace, scrollable box. */
@Composable
private fun MessageText(text: String) {
    val parts = text.split("```")
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            parts.forEachIndexed { i, part ->
                if (i % 2 == 1) {
                    // First line of a fence may be the language tag.
                    val body = part.substringAfter('\n', part).trimEnd()
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            body,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(10.dp),
                        )
                    }
                } else if (part.isNotBlank()) {
                    Text(part.trim())
                }
            }
        }
    }
}
