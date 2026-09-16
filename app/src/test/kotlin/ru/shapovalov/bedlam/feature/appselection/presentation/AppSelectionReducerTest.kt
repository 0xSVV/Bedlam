package ru.shapovalov.bedlam.feature.appselection.presentation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    fun `toggling a package keeps the list order`() {
        val loaded = AppSelectionReducer.reduceAll(
            AppSelectionStore.State(),
            Msg.FilterLoaded(AppFilterMode.Allowlist, setOf("c.gamma")),
            Msg.AppsLoaded(apps),
        )

        val toggled = AppSelectionReducer.reduceAll(
            loaded,
            Msg.FilterLoaded(AppFilterMode.Allowlist, setOf("c.gamma", "b.beta")),
        )

        assertEquals(listOf("c.gamma", "a.alpha", "b.beta"), toggled.packages())
        assertEquals(setOf("c.gamma", "b.beta"), toggled.selectedPackages)
    }

    @Test
    fun `a query narrows the list without reordering after a toggle`() {
        val loaded = AppSelectionReducer.reduceAll(
            AppSelectionStore.State(),
            Msg.FilterLoaded(AppFilterMode.Allowlist, setOf("c.gamma")),
            Msg.AppsLoaded(apps + installedApp("d.delta", "Delta")),
        )

        val narrowed = AppSelectionReducer.reduceAll(loaded, Msg.QueryChanged("TA"))
        assertEquals(listOf("b.beta", "d.delta"), narrowed.packages())

        val toggled = AppSelectionReducer.reduceAll(
            narrowed,
            Msg.FilterLoaded(AppFilterMode.Allowlist, setOf("c.gamma", "d.delta")),
        )
        assertEquals(listOf("b.beta", "d.delta"), toggled.packages())

        val cleared = AppSelectionReducer.reduceAll(toggled, Msg.QueryChanged(""))
        assertEquals(listOf("c.gamma", "a.alpha", "b.beta", "d.delta"), cleared.packages())
    }

    @Test
    fun `the list stays loading until both the filter and the apps arrive`() {
        val filter = Msg.FilterLoaded(AppFilterMode.Allowlist, setOf("c.gamma"))

        val appsFirst = AppSelectionReducer.reduceAll(AppSelectionStore.State(), Msg.AppsLoaded(apps))
        assertTrue(appsFirst.isLoading)
        assertFalse(AppSelectionReducer.reduceAll(appsFirst, filter).isLoading)

        val filterFirst = AppSelectionReducer.reduceAll(AppSelectionStore.State(), filter)
        assertTrue(filterFirst.isLoading)
        assertFalse(AppSelectionReducer.reduceAll(filterFirst, Msg.AppsLoaded(apps)).isLoading)
    }

    private fun AppSelectionStore.State.packages(): List<String> =
        filteredApps.map { it.packageName }
}
