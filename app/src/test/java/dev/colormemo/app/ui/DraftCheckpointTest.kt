package dev.colormemo.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.junit.Assert.assertEquals
import org.junit.Test

class DraftCheckpointTest {
    @Test
    fun inputIsCapturedBeforeTheNextCompositionAndImageChangesAreAtomic() {
        val checkpoint = DraftCheckpoint()
        var text by checkpoint.stateOf("")
        var attachment by checkpoint.stateOf<String?>(null)
        val captured = mutableListOf<Pair<String, String?>>()
        checkpoint.onChange = { captured += text to attachment }
        text = "输入"
        assertEquals(listOf("输入" to null), captured)
        checkpoint.batch {
            attachment = "图片文件"
            text = "输入 ![图片](图片文件)"
        }
        assertEquals(listOf("输入" to null, "输入 ![图片](图片文件)" to "图片文件"), captured)
    }
}
