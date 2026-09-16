package ru.shapovalov.bedlam.feature.logs.ui

import androidx.compose.foundation.lazy.LazyListLayoutInfo

internal fun logTailIndex(lineCount: Int, droppedCount: Long): Int =
    lineCount - 1 + if (droppedCount > 0L) 1 else 0

internal fun LazyListLayoutInfo.isAtTail(tailIndex: Int, slopPx: Int): Boolean {
    val last = visibleItemsInfo.lastOrNull() ?: return false
    return last.index >= tailIndex && last.offset + last.size <= contentEndOffset + slopPx
}

internal fun LazyListLayoutInfo.shouldSnapTo(tailIndex: Int): Boolean {
    val lastInContent = visibleItemsInfo.lastOrNull { it.offset < contentEndOffset }
        ?: return true
    return tailIndex - lastInContent.index > SmoothFollowMaxLines
}

private val LazyListLayoutInfo.contentEndOffset: Int
    get() = viewportEndOffset - afterContentPadding

private const val SmoothFollowMaxLines = 3
