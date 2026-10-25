package dev.colormemo.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.colormemo.core.model.Note
import kotlinx.coroutines.launch

// Keep EDITOR readable for navigation Bundles created by older app versions.
private enum class MemoScreen { NOTES, EDITOR, SETTINGS, AI_SETTINGS }

internal enum class MemoSection(val label: String) {
    NOTES("便签"), WHITEBOARDS("白板"), REMINDERS("提醒"), ARCHIVE("归档"), TRASH("回收站")
}

@Composable
fun ColorMemoApp(
    state: MemoUiState,
    callbacks: MemoCallbacks,
    modifier: Modifier = Modifier,
) {
    var screen by rememberSaveable { mutableStateOf(MemoScreen.NOTES) }
    var section by rememberSaveable { mutableStateOf(MemoSection.NOTES) }
    val editorSession = state.editor.session
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val openNote: (Note?) -> Unit = { note ->
        callbacks.onOpenEditor(note, false)
        screen = MemoScreen.NOTES
    }

    LaunchedEffect(state.message) {
        state.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            callbacks.onMessageShown()
        }
    }
    LaunchedEffect(state.requestedNoteId, state.loading, editorSession?.id, state.editor.loaded) {
        val requestedId = state.requestedNoteId
        // Finish an existing draft before opening another note from a notification.
        if (requestedId != null && !state.loading && state.editor.loaded && editorSession == null) {
            val note = state.notes.firstOrNull { it.id == requestedId && !it.isPurged && it.deletedAt == null }
            if (note != null) openNote(note)
            callbacks.onConsumeRequestedNote()
            if (note == null) snackbarHostState.showSnackbar("这条便签已被移除")
        } else if (requestedId != null && editorSession?.draft?.id == requestedId) {
            callbacks.onConsumeRequestedNote()
        }
    }

    Box(modifier.fillMaxSize()) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = screen == MemoScreen.NOTES && editorSession == null,
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
                    onAiSettings = {
                        screen = MemoScreen.AI_SETTINGS
                        scope.launch { drawerState.close() }
                    },
                )
            },
        ) {
            if (editorSession != null) {
                key(editorSession.id) {
                    NoteEditorScreen(
                        session = editorSession,
                        state = state,
                        callbacks = callbacks,
                        onClose = { screen = MemoScreen.NOTES },
                    )
                }
            } else if (!state.editor.loaded) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (state.editor.error != null) Text(state.editor.error, Modifier.padding(24.dp))
                    else CircularProgressIndicator()
                }
            } else when (screen) {
                MemoScreen.NOTES, MemoScreen.EDITOR -> NotesScreen(
                    state = state,
                    callbacks = callbacks,
                    section = section,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onOpenNote = openNote,
                    onCreateWhiteboard = { callbacks.onOpenEditor(null, true) },
                )
                MemoScreen.SETTINGS -> SyncSettingsScreen(
                    state = state,
                    callbacks = callbacks,
                    onBack = { screen = MemoScreen.NOTES },
                )
                MemoScreen.AI_SETTINGS -> AiSettingsScreen(state, callbacks, onBack = { screen = MemoScreen.NOTES })
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
                .navigationBarsPadding().imePadding().padding(bottom = 12.dp),
        )
    }
}
