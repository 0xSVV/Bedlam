package ru.shapovalov.bedlam.feature.logs.ui

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LogTailTest {

    @Test
    fun `the tail index counts the dropped lines notice`() {
        assertEquals(-1, logTailIndex(lineCount = 0, droppedCount = 0L))
        assertEquals(2, logTailIndex(lineCount = 3, droppedCount = 0L))
        assertEquals(3, logTailIndex(lineCount = 3, droppedCount = 12L))
    }

    @Test
    fun `a tail that ends at the content edge is at the end`() {
        val atEnd = rowsEndingAt(tailIndex = 9, tailBottom = ContentEnd)

        assertTrue(atEnd.isAtTail(tailIndex = 9, slopPx = Slop))
        assertTrue(rowsEndingAt(9, ContentEnd + Slop).isAtTail(tailIndex = 9, slopPx = Slop))
        assertTrue(rowsEndingAt(9, ContentEnd - RowSize).isAtTail(tailIndex = 9, slopPx = Slop))
    }

    @Test
    fun `a tail scrolled into the bottom padding is not at the end`() {
        val scrolledUpOneLine = rowsEndingAt(tailIndex = 9, tailBottom = ContentEnd + RowSize)
        val scrolledUpTwoLines = rowsEndingAt(tailIndex = 9, tailBottom = ContentEnd + 2 * RowSize)

        assertFalse(scrolledUpOneLine.isAtTail(tailIndex = 9, slopPx = Slop))
        assertFalse(scrolledUpTwoLines.isAtTail(tailIndex = 9, slopPx = Slop))
        assertFalse(rowsEndingAt(9, ContentEnd + Slop + 1).isAtTail(tailIndex = 9, slopPx = Slop))
    }

    @Test
    fun `a tail that is not laid out yet is not at the end`() {
        val laidOut = rowsEndingAt(tailIndex = 9, tailBottom = ContentEnd)

        assertFalse(laidOut.isAtTail(tailIndex = 10, slopPx = Slop))
        assertFalse(layout(emptyList()).isAtTail(tailIndex = 0, slopPx = Slop))
    }

    @Test
    fun `up to three new lines animate and more snap`() {
        val atEnd = rowsEndingAt(tailIndex = 9, tailBottom = ContentEnd)

        assertFalse(atEnd.shouldSnapTo(tailIndex = 9))
        assertFalse(atEnd.shouldSnapTo(tailIndex = 12))
        assertTrue(atEnd.shouldSnapTo(tailIndex = 13))
    }

    @Test
    fun `new lines laid out in the bottom padding still count as arrived`() {
        val twoNewRowsInPadding = rowsEndingAt(11, ContentEnd + 2 * RowSize)

        assertFalse(twoNewRowsInPadding.shouldSnapTo(tailIndex = 12))
        assertTrue(twoNewRowsInPadding.shouldSnapTo(tailIndex = 13))
    }

    @Test
    fun `a list with nothing laid out snaps`() {
        assertTrue(layout(emptyList()).shouldSnapTo(tailIndex = 0))
    }

    private fun rowsEndingAt(tailIndex: Int, tailBottom: Int): LazyListLayoutInfo {
        val rows = (0..tailIndex).map { index ->
            Row(index = index, offset = tailBottom - (tailIndex - index + 1) * RowSize)
        }
        return layout(rows.filter { it.offset + it.size > 0 && it.offset < ViewportEnd })
    }

    private fun layout(rows: List<LazyListItemInfo>): LazyListLayoutInfo =
        object : LazyListLayoutInfo {
            override val visibleItemsInfo: List<LazyListItemInfo> = rows
            override val viewportStartOffset: Int = 0
            override val viewportEndOffset: Int = ViewportEnd
            override val totalItemsCount: Int = rows.size
            override val afterContentPadding: Int = BottomPadding
        }

    private data class Row(
        override val index: Int,
        override val offset: Int,
    ) : LazyListItemInfo {
        override val key: Any = index
        override val size: Int = RowSize
    }
}

private const val RowSize = 40
private const val BottomPadding = 3 * RowSize - 10
private const val ContentEnd = 1000
private const val ViewportEnd = ContentEnd + BottomPadding
private const val Slop = 8
