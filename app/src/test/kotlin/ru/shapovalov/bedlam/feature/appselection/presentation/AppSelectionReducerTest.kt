package ru.shapovalov.bedlam.feature.appselection.presentation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.core.appfilter.domain.model.AppFilterMode
import ru.shapovalov.bedlam.testing.installedApp
import ru.shapovalov.bedlam.testing.reduceAll

class AppSelectionReducerTest {

    private val apps = listOf(
        installedApp("a.alpha", "Alpha"),
        installedApp("b.beta", "Beta"),
        installedApp("c.gamma", "Gamma"),
    )

    @Test
    fun `apps load with selected packages first`() {
        val loaded = AppSelectionReducer.reduceAll(
            AppSelectionStore.State(),
            Msg.FilterLoaded(AppFilterMode.Allowlist, setOf("c.gamma")),
            Msg.AppsLoaded(apps),
        )

        assertEquals(listOf("c.gamma", "a.alpha", "b.beta"), loaded.packages())
        assertFalse(loaded.isLoading)
    }

    @Test
    fun `a filter that arrives after the apps still puts selected apps first`() {
        val loaded = AppSelectionReducer.reduceAll(
            AppSelectionStore.State(),
            Msg.AppsLoaded(apps),
            Msg.FilterLoaded(AppFilterMode.Allowlist, setOf("b.beta")),
        )

        assertEquals(listOf("b.beta", "a.alpha", "c.gamma"), loaded.packages())
    }

    @Test
    fun `a query matches the label or the package ignoring case`() {
        val loaded = AppSelectionReducer.reduceAll(
            AppSelectionStore.State(),
            Msg.FilterLoaded(AppFilterMode.Allowlist, emptySet()),
            Msg.AppsLoaded(
                listOf(
                    installedApp("org.example.browser", "Firefox"),
                    installedApp("com.example.mail", "Inbox"),
                )
            ),
        )

        assertEquals(
            listOf("org.example.browser"),
            AppSelectionReducer.reduceAll(loaded, Msg.QueryChanged("FIRE")).packages(),
        )
        assertEquals(
            listOf("com.example.mail"),
            AppSelectionReducer.reduceAll(loaded, Msg.QueryChanged("MAIL")).packages(),
        )
        assertEquals(
            listOf("org.example.browser", "com.example.mail"),
            AppSelectionReducer.reduceAll(loaded, Msg.QueryChanged("Example")).packages(),
        )
        assertEquals(
            listOf("org.example.browser", "com.example.mail"),
            AppSelectionReducer.reduceAll(loaded, Msg.QueryChanged("  ")).packages(),
        )
    }

    private fun AppSelectionStore.State.packages(): List<String> =
        filteredApps.map { it.packageName }
}
