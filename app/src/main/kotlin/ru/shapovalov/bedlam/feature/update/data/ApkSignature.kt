package ru.shapovalov.bedlam.feature.update.data

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import android.os.Build

internal fun signersMatch(installed: List<ByteArray>, candidate: List<ByteArray>): Boolean {
    if (installed.isEmpty() || candidate.isEmpty()) return false
    val left = installed.mapTo(HashSet()) { it.toHexString() }
    val right = candidate.mapTo(HashSet()) { it.toHexString() }
    return left.size == installed.size && right.size == candidate.size && left == right
}

internal fun PackageManager.installedSigners(packageName: String): List<ByteArray> =
    packageInfoWithSigners(packageName).signers()

internal fun PackageManager.archiveSigners(path: String): List<ByteArray> =
    archiveInfoWithSigners(path).signers()

private fun PackageManager.packageInfoWithSigners(packageName: String): PackageInfo? =
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(SIGNING_FLAG.toLong()))
        } else {
            @Suppress("DEPRECATION")
            getPackageInfo(packageName, SIGNING_FLAG)
        }
    }.getOrNull()

private fun PackageManager.archiveInfoWithSigners(path: String): PackageInfo? =
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(SIGNING_FLAG.toLong()))
        } else {
            @Suppress("DEPRECATION")
            getPackageArchiveInfo(path, SIGNING_FLAG)
        }
    }.getOrNull()

private fun PackageInfo?.signers(): List<ByteArray> {
    val info: SigningInfo = this?.signingInfo ?: return emptyList()
    return info.apkContentsSigners.orEmpty().map { it.toByteArray() }
}

private fun ByteArray.toHexString(): String =
    joinToString("") { byte -> "%02x".format(byte) }

private const val SIGNING_FLAG = PackageManager.GET_SIGNING_CERTIFICATES
