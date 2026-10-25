package dev.colormemo.app.data

import dev.colormemo.app.ui.NoteDraft
import dev.colormemo.core.model.Note
import dev.colormemo.core.sync.SyncProtocolException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class DraftState(
    val loaded: Boolean = false,
    val session: EditorSession? = null,
    val saving: Boolean = false,
    val closing: Boolean = false,
    val recovered: Boolean = false,
    val error: String? = null,
)

/** Application-owned writer: navigation/rotation cannot cancel an accepted edit or commit. */
class DraftController(private val persistence: DraftPersistence, private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(DraftState())
    val state = mutableState.asStateFlow()
    private val wakeup = Channel<Unit>(Channel.CONFLATED)
    private val mutex = Mutex()

    init {
        scope.launch {
            try {
                val session = persistence.loadDraft()
                mutableState.value = DraftState(loaded = true, session = session, recovered = session != null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                // Do not overwrite an unreadable draft by allowing a new editor to open.
                mutableState.value = DraftState(error = "无法读取本地草稿，请重新打开应用；原草稿仍保留")
            }
            for (ignored in wakeup) checkpoint()
        }
    }

    fun open(note: Note?, whiteboard: Boolean = false) {
        mutableState.update {
            if (it.loaded && it.session == null) it.copy(session = EditorSession.open(note, whiteboard), recovered = false, error = null) else it
        }
    }

    fun update(session: EditorSession) {
        mutableState.update {
            if (it.session?.id == session.id && !it.closing && it.session != session)
                it.copy(session = session, saving = true, error = null) else it
        }
        wakeup.trySend(Unit)
    }

    /** Also retries a failed write when the activity moves to the background. No debounce. */
    fun flush() { wakeup.trySend(Unit) }

    private suspend fun checkpoint() = mutex.withLock {
        val current = state.value
        val session = current.session ?: return@withLock
        if (!current.saving || current.closing) return@withLock
        try {
            persistence.writeDraft(session)
            mutableState.update { if (it.session == session) it.copy(saving = false, error = null) else it }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutableState.update { it.copy(error = "草稿未能保存，请检查存储空间并重试，暂勿退出应用") }
        }
    }

    suspend fun save(draft: NoteDraft): Boolean = finish { session ->
        // The note writer may be waiting behind sync/import. Protect the latest edit first,
        // even if this save acquired the mutex before the queued background checkpoint.
        persistence.writeDraft(session)
        persistence.commitDraft(draft, session.id)
    }
    suspend fun discard(): Boolean = finish { session -> persistence.discardDraft(session.id) }

    private suspend fun finish(action: suspend (EditorSession) -> Unit): Boolean {
        val expectedId = state.value.session?.id ?: return false
        return scope.async {
            mutex.withLock {
                val session = state.value.session?.takeIf { it.id == expectedId } ?: return@withLock false
                mutableState.update { it.copy(closing = true) }
                // Once started, finish the transaction and publish its result even if the UI disappears.
                withContext(NonCancellable) {
                    try {
                        action(session)
                        mutableState.value = DraftState(loaded = true)
                        true
                    } catch (error: Exception) {
                        mutableState.update { it.copy(closing = false, error = error.message.takeIf { error is NoteInputException || error is SyncProtocolException }
                            ?: "操作未完成，草稿仍保留，请重试") }
                        false
                    }
                }
            }
        }.await()
    }
}
