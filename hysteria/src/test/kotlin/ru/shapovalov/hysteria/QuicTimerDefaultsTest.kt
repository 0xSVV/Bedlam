package ru.shapovalov.hysteria

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.shapovalov.hysteria.config.QuicTimerDefaults

class QuicTimerDefaultsTest {

    private val goSource = File("golib/config.go").readText()

    private fun goConstant(name: String): Int {
        val match = requireNotNull(Regex("""$name\s*=\s*(\d+)""").find(goSource)) {
            "$name not found in golib/config.go"
        }
        return match.groupValues[1].toInt()
    }

    @Test
    fun `idle timeout default matches the Go constant`() {
        assertEquals(goConstant("defaultMaxIdleTimeoutSec"), QuicTimerDefaults.MAX_IDLE_TIMEOUT_SEC)
    }

    @Test
    fun `keepalive default matches the Go constant`() {
        assertEquals(goConstant("defaultKeepAlivePeriodSec"), QuicTimerDefaults.KEEP_ALIVE_PERIOD_SEC)
    }
}
