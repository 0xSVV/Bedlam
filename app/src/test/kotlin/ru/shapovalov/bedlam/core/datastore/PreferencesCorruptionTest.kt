package ru.shapovalov.bedlam.core.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PreferencesCorruptionTest {

    @TempDir
    lateinit var directory: File

    private val reported = mutableListOf<Pair<String, Throwable>>()
    private val jobs = mutableListOf<Job>()

    @AfterEach
    fun tearDown() = runTest {
        jobs.forEach { it.cancelAndJoin() }
        PreferencesCorruption.reporter = PreferencesCorruption.logReporter
    }

    private fun store(name: String, file: File): Pair<DataStore<Preferences>, Job> {
        PreferencesCorruption.reporter = { storeName, error -> reported += storeName to error }
        val job = SupervisorJob().also { jobs += it }
        val store = PreferenceDataStoreFactory.create(
            corruptionHandler = PreferencesCorruption.handler(name),
            scope = CoroutineScope(job + Dispatchers.IO),
            produceFile = { file },
        )
        return store to job
    }

    @Test
    fun `the handler reports the store name and falls back to empty preferences`() = runTest {
        PreferencesCorruption.reporter = { name, error -> reported += name to error }
        val cause = CorruptionException("Unable to parse preferences proto.")

        val replacement = PreferencesCorruption.handler("vpn_runtime_state").handleCorruption(cause)

        assertEquals(emptyPreferences(), replacement)
        assertEquals(listOf("vpn_runtime_state" to cause), reported)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a zero-filled preferences file is replaced and the store keeps working`() = runTest {
        val file = File(directory, "runtime.preferences_pb").apply { writeBytes(ByteArray(64)) }
        val (store, _) = store("vpn_runtime_state", file)

        assertEquals(emptyPreferences(), store.data.first())
        store.edit { it[KEY] = "after" }
        assertEquals("after", store.data.first()[KEY])

        assertEquals(listOf("vpn_runtime_state"), reported.map { it.first })
        assertTrue(file.readBytes().any { it != 0.toByte() })
    }

    @Test
    fun `a healthy preferences file is read without a report`() = runTest {
        val file = File(directory, "healthy.preferences_pb")
        val (writer, writerJob) = store("update", file)
        writer.edit { it[KEY] = "kept" }
        writerJob.cancelAndJoin()

        val (reader, _) = store("update", file)
        assertEquals("kept", reader.data.first()[KEY])
        assertEquals(emptyList<Pair<String, Throwable>>(), reported)
    }

    private companion object {
        val KEY = stringPreferencesKey("value")
    }
}
