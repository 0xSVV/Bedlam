package ru.shapovalov.bedlam.benchmarks

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class UiBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun appScrolling() = rule.measureRepeated(
        packageName = APP_ID,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(),
        iterations = iterations,
        setupBlock = { seedData(); openApps() },
    ) { scrollList() }

    @Test fun logScrolling() = rule.measureRepeated(
        packageName = APP_ID,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(),
        iterations = iterations,
        setupBlock = { seedData(); openLogs() },
    ) { scrollList() }

    @Test fun routingDetails() = rule.measureRepeated(
        packageName = APP_ID,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(),
        iterations = iterations,
        setupBlock = { seedData(); openRoute() },
    ) {
        device.text("AS64512").tap()
        Thread.sleep(700)
        scrollNetworks()
    }

    @Test fun aboutOpening() = rule.measureRepeated(
        packageName = APP_ID,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(),
        iterations = iterations,
        setupBlock = { seedData() },
    ) { openAbout() }

    @Test fun connectionMorph() = render("connecting")
    @Test fun sessionSkeleton() = render("shimmer")

    private fun render(scenario: String) = rule.measureRepeated(
        packageName = APP_ID,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(),
        iterations = iterations,
        setupBlock = { renderScenario(scenario) },
    ) { Thread.sleep(3_000) }
}
