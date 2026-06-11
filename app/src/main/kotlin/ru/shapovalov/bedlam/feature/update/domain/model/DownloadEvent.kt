package ru.shapovalov.bedlam.feature.update.domain.model

import java.io.File

sealed interface DownloadEvent {
    data class Progress(val downloadedBytes: Long, val totalBytes: Long) : DownloadEvent
    data class Completed(val file: File) : DownloadEvent
}
