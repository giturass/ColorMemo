package dev.colormemo.app.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/** Capture edits in the input callback, without waiting for recomposition or a debounce timer. */
internal class DraftCheckpoint {
    var onChange: () -> Unit = {}
    private var batching = false

    fun batch(block: () -> Unit) {
        batching = true
        try { block() } finally {
            batching = false
            onChange()
        }
    }

    fun <T> stateOf(initial: T): MutableState<T> = object : MutableState<T> {
        private val state = mutableStateOf(initial)
        override var value: T
            get() = state.value
            set(value) {
                if (state.value != value) {
                    state.value = value
                    if (!batching) onChange()
                }
            }
        override fun component1(): T = value
        override fun component2(): (T) -> Unit = { value = it }
    }
}
