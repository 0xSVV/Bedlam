package ru.shapovalov.bedlam.feature.update.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.di.AppScope
import ru.shapovalov.bedlam.feature.update.domain.model.InstallStatus
import ru.shapovalov.bedlam.feature.update.domain.repository.UpdateInstaller
import java.io.File

@AppScope
@Inject
class ApkInstallerImpl(
    private val context: Context,
) : UpdateInstaller {

    private val _status = MutableStateFlow<InstallStatus>(InstallStatus.Idle)
    override val status: StateFlow<InstallStatus> = _status.asStateFlow()

    override suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        _status.value = InstallStatus.InProgress
        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            ).apply {
                setAppPackageName(context.packageName)
                setSize(apk.length())
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite(apk.name, 0, apk.length()).use { output ->
                    apk.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                val statusIntent = Intent(context, UpdateInstallReceiver::class.java)
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    statusIntent,
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                session.commit(pendingIntent.intentSender)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _status.value = InstallStatus.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    override fun reset() {
        _status.value = InstallStatus.Idle
    }

    fun onInstallStatus(intent: Intent) {
        val statusCode = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        when (statusCode) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm =
                    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                }
            }

            PackageInstaller.STATUS_SUCCESS -> _status.value = InstallStatus.Idle

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                _status.value = InstallStatus.Failed(message ?: "Install failed (code $statusCode)")
            }
        }
    }
}
