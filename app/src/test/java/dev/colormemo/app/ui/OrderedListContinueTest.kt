package dev.colormemo.app.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class OrderedListContinueTest {
    @Test
    fun enterAfterOrderedItemContinuesNumbering() {
        val result = continueOrderedListAfterEnter(
            TextFieldValue("1. 第一步", TextRange(6)),
            TextFieldValue("1. 第一步\n", TextRange(7)),
        )

        assertEquals("1. 第一步\n2. ", result.text)
        assertEquals(10, result.selection.min)
    }

    @Test
    fun enterAfterNestedOrderedItemKeepsIndentAndDelimiter() {
        val result = continueOrderedListAfterEnter(
            TextFieldValue("  9. 步骤", TextRange(7)),
            TextFieldValue("  9. 步骤\n", TextRange(8)),
        )

        assertEquals("  9. 步骤\n  10. ", result.text)
    }

    @Test
    fun enterAfterEmptyOrderedItemExitsList() {
        val result = continueOrderedListAfterEnter(
            TextFieldValue("1. ", TextRange(4)),
            TextFieldValue("1. \n", TextRange(5)),
        )

        assertEquals("\n", result.text)
        assertEquals(0, result.selection.min)
    }

    @Test
    fun enterOutsideOrderedListIsUnchanged() {
        val next = TextFieldValue("普通文本\n", TextRange(6))

        assertEquals(next, continueOrderedListAfterEnter(TextFieldValue("普通文本", TextRange(5)), next))
    }
}
