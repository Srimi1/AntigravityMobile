package app.antigravity.agent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/** Read-only browser for the agent's workspace. Files are created and changed by the agent. */
@Composable
fun FilesScreen(root: File, modifier: Modifier = Modifier) {
    var dir by remember { mutableStateOf(root) }
    var open by remember { mutableStateOf<File?>(null) }
    var refresh by remember { mutableStateOf(0) }

    BackHandler(enabled = open != null || dir != root) {
        if (open != null) open = null else dir = dir.parentFile ?: root
    }

    Column(modifier.fillMaxSize()) {
        val current = open
        Text(
            (current ?: dir).path.removePrefix(root.path).ifEmpty { "/" },
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(16.dp),
        )
        if (current != null) {
            val text = remember(current, refresh) {
                try {
                    if (current.length() > 300_000) "File is too large to preview." else current.readText()
                } catch (e: Exception) {
                    "Cannot read this file."
                }
            }
            SelectionContainer {
                Text(
                    text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                )
            }
        } else {
            val kids = remember(dir, refresh) {
                dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
            }
            if (kids.isEmpty()) {
                Text("Nothing here yet. Ask the agent to create something.", Modifier.padding(16.dp))
            }
            LazyColumn(Modifier.fillMaxWidth()) {
                items(kids, key = { it.path }) { f ->
                    Row(
                        Modifier.fillMaxWidth().clickable { if (f.isDirectory) dir = f else open = f }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (f.isDirectory) Icons.Filled.Folder else Icons.Filled.Description, contentDescription = null)
                        Text(f.name, Modifier.padding(start = 12.dp))
                    }
                }
            }
        }
    }
}
