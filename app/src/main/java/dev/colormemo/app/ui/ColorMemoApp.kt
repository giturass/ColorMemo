package dev.colormemo.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.colormemo.core.model.Note
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private enum class MemoScreen { NOTES, EDITOR, SETTINGS }

internal enum class MemoSection(val label: String) {
    NOTES("便笺"), WHITEBOARDS("白板"), REMINDERS("提醒"), ARCHIVE("归档"), TRASH("回收站")
}

private val SnapshotJson = Json { ignoreUnknownKeys = true }
private val NoteSnapshotSaver = Saver<Note?, String>(
    // Draft fields are saved separately. Keep only the concurrency baseline here.
    save = { note -> note?.let { SnapshotJson.encodeToString(it.copy(title = "", content = "", whiteboard = null)) } ?: "" },
    restore = { value -> value.takeIf(String::isNotEmpty)?.let { SnapshotJson.decodeFromString<Note>(it) } },
)

@Composable
fun ColorMemoApp(
    state: MemoUiState,
    callbacks: MemoCallbacks,
    modifier: Modifier = Modifier,
) {
    var screen by rememberSaveable { mutableStateOf(MemoScreen.NOTES) }
    var section by rememberSaveable { mutableStateOf(MemoSection.NOTES) }
    var editingNote by rememberSaveable(stateSaver = NoteSnapshotSaver) { mutableStateOf<Note?>(null) }
    var editingWhiteboard by rememberSaveable { mutableStateOf(false) }
    var editorSession by rememberSaveable { mutableLongStateOf(0L) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val openNote: (Note?) -> Unit = { note ->
        editingNote = note
        editingWhiteboard = note?.whiteboard != null
        editorSession += 1
        screen = MemoScreen.EDITOR
    }

    LaunchedEffect(state.message) {
        state.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            callbacks.onMessageShown()
        }
    }
    LaunchedEffect(state.requestedNoteId, state.loading, screen) {
        val requestedId = state.requestedNoteId
        // Finish an existing draft before opening another note from a notification.
        if (requestedId != null && !state.loading && screen != MemoScreen.EDITOR) {
            val note = state.notes.firstOrNull { it.id == requestedId && !it.isPurged && it.deletedAt == null }
            if (note != null) openNote(note)
            callbacks.onConsumeRequestedNote()
            if (note == null) snackbarHostState.showSnackbar("这条便笺已被移除")
        } else if (requestedId != null && screen == MemoScreen.EDITOR && editingNote?.id == requestedId) {
            callbacks.onConsumeRequestedNote()
        }
    }

    Box(modifier.fillMaxSize()) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = screen == MemoScreen.NOTES,
            drawerContent = {
                MemoDrawer(
                    selected = section,
                    notes = state.notes,
                    onSelect = { selected ->
                        section = selected
                        screen = MemoScreen.NOTES
                        scope.launch { drawerState.close() }
                    },
                    onSettings = {
                        screen = MemoScreen.SETTINGS
                        scope.launch { drawerState.close() }
                    },
                )
            },
        ) {
            when (screen) {
                MemoScreen.NOTES -> NotesScreen(
                    state = state,
                    callbacks = callbacks,
                    section = section,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onOpenNote = openNote,
                    onCreateWhiteboard = { openNote(null); editingWhiteboard = true },
                )
                MemoScreen.EDITOR -> key(editorSession) {
                    NoteEditorScreen(
                        note = editingNote,
                        state = state,
                        callbacks = callbacks,
                        onClose = { screen = MemoScreen.NOTES },
                        newWhiteboard = editingWhiteboard,
                    )
                }
                MemoScreen.SETTINGS -> SyncSettingsScreen(
                    state = state,
                    callbacks = callbacks,
                    onBack = { screen = MemoScreen.NOTES },
                )
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
                .navigationBarsPadding().imePadding().padding(bottom = 12.dp),
        )
    }
}
