package ru.shapovalov.bedlam.testing

import com.arkivanov.mvikotlin.core.utils.isAssertOnMainThreadEnabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherExtension(
    private val createDispatcher: () -> TestDispatcher = { UnconfinedTestDispatcher() },
) : BeforeEachCallback, AfterEachCallback {

    lateinit var dispatcher: TestDispatcher
        private set

    override fun beforeEach(context: ExtensionContext) {
        isAssertOnMainThreadEnabled = false
        dispatcher = createDispatcher()
        Dispatchers.setMain(dispatcher)
    }

    override fun afterEach(context: ExtensionContext) {
        Dispatchers.resetMain()
        isAssertOnMainThreadEnabled = true
    }
}
