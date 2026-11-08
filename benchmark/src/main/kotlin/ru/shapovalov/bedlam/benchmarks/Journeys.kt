package ru.shapovalov.bedlam.benchmarks

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

internal const val APP_ID = "ru.shapovalov.bedlam.benchmark"
private const val APP_PACKAGE = "ru.shapovalov.bedlam"
private const val TIMEOUT = 15_000L

internal fun UiDevice.text(value: String) = wait(Until.findObject(By.text(value)), TIMEOUT)
    ?: error("Missing text: $value")
internal fun UiDevice.description(value: String) = wait(Until.findObject(By.desc(value)), TIMEOUT)
    ?: error("Missing content description: $value")

internal fun UiObject2.tap() {
    click()
    Thread.sleep(700)
}

internal fun MacrobenchmarkScope.seedData() {
    device.executeShellCommand("am force-stop $APP_ID")
    device.executeShellCommand("pm grant $APP_ID android.permission.ACCESS_LOCAL_NETWORK")
    device.executeShellCommand("pm grant $APP_ID android.permission.POST_NOTIFICATIONS")
    device.executeShellCommand("appops set $APP_ID ACTIVATE_VPN allow")
    startActivityAndWait(Intent().setComponent(ComponentName(APP_ID, "$APP_PACKAGE.benchmark.BenchmarkSetupActivity")))
    device.text("Lab profile 00")
    Thread.sleep(700)
    device.findObject(By.desc("Disconnect"))?.tap()
    device.description("Connect")
}

internal fun MacrobenchmarkScope.openApps() {
    device.description("Settings").tap()
    device.text("Apps to proxy").tap()
    device.text("Only selected apps").tap()
    check(device.wait(Until.hasObject(By.desc("Search")), TIMEOUT))
}

internal fun MacrobenchmarkScope.openLogs() {
    device.description("Logs").tap()
    check(device.wait(Until.hasObject(By.text("INFO")), TIMEOUT))
}

internal fun MacrobenchmarkScope.openRoute() {
    device.description("Settings").tap()
    device.text("Routing").tap()
    device.description("Refresh all sources")
    val list = device.wait(Until.findObject(By.scrollable(true)), TIMEOUT) ?: error("Routing list missing")
    repeat(4) {
        if (!device.hasObject(By.text("AS64512"))) swipe(list, down = true)
    }
    device.text("AS64512")
}

internal fun MacrobenchmarkScope.scrollList() {
    val list = device.wait(Until.findObject(By.scrollable(true)), TIMEOUT) ?: error("Scrollable list missing")
    repeat(3) { swipe(list, down = true) }
    repeat(3) { swipe(list, down = false) }
}

internal fun MacrobenchmarkScope.scrollNetworks() {
    repeat(6) {
        val pane = device.findObject(By.desc("Resolved networks"))
        if (pane == null || pane.visibleBounds.height() < 400) {
            val outer = device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.height() }
                ?: error("Routing list missing")
            swipe(outer, down = true, atEdge = true)
        }
    }
    val list = device.findObject(By.desc("Resolved networks"))
        ?: device.findObject(By.scrollable(true)) ?: error("Network list missing")
    repeat(3) { swipe(list, down = true) }
    repeat(3) { swipe(list, down = false) }
}

private fun MacrobenchmarkScope.swipe(list: UiObject2, down: Boolean, atEdge: Boolean = false) {
    val bounds = list.visibleBounds
    check(bounds.height() > 100) { "List is not visible: $bounds" }
    val top = bounds.top + bounds.height() / 5
    val bottom = bounds.bottom - bounds.height() / 5
    val x = if (atEdge) bounds.left + 40 else bounds.centerX()
    device.swipe(x, if (down) bottom else top, x, if (down) top else bottom, 24)
    Thread.sleep(250)
}

internal fun MacrobenchmarkScope.openAbout() {
    device.description("Settings").tap()
    device.text("About").tap()
    device.text("GitHub")
}

internal fun MacrobenchmarkScope.renderScenario(scenario: String) {
    startActivityAndWait(
        Intent().setComponent(ComponentName(APP_ID, "$APP_PACKAGE.benchmark.BenchmarkRenderingActivity"))
            .putExtra("scenario", scenario),
    )
}
