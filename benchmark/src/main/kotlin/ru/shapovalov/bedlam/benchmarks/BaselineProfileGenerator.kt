package ru.shapovalov.bedlam.benchmarks

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test fun startup() = rule.collect(
        packageName = APP_ID,
        includeInStartupProfile = true,
        maxIterations = 3,
        stableIterations = 2,
    ) {
        pressHome()
        startActivityAndWait()
        device.description("Home")
    }

    @Test fun criticalJourneys() = rule.collect(
        packageName = APP_ID,
        maxIterations = 3,
        stableIterations = 2,
    ) {
        seedData()
        device.description("Home").tap()
        device.text("Session").tap()
        device.pressBack()
        device.description("Open profile configuration").tap()
        device.description("Edit").tap()
        device.text("Save")
        device.pressBack()
        device.description("Edit")
        device.pressBack()
        openApps()
        scrollList()
        device.pressBack()
        openRoute()
        device.text("AS64512").tap()
        Thread.sleep(500)
        scrollNetworks()
        device.pressBack()
        openLogs()
        scrollList()
        openAbout()
        scrollList()
        renderScenario("shimmer")
        Thread.sleep(2_000)
        renderScenario("connecting")
        Thread.sleep(2_000)
    }
}
