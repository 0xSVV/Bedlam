package ru.shapovalov.bedlam.benchmarks

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

internal val iterations: Int get() = InstrumentationRegistry.getArguments().getString("bedlam.iterations")?.toInt() ?: 5

@RunWith(AndroidJUnit4::class)
@LargeTest
class StartupBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Before fun resetIsolatedApp() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        check(device.executeShellCommand("pm clear $APP_ID").contains("Success"))
    }

    @Test fun coldWithoutProfile() = startup(CompilationMode.None())
    @Test fun coldWithProfile() = startup(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun startup(compilation: CompilationMode) = rule.measureRepeated(
        packageName = APP_ID,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilation,
        startupMode = StartupMode.COLD,
        iterations = iterations,
        setupBlock = { pressHome() },
    ) { startActivityAndWait() }
}
