package ru.shapovalov.bedlam.core.vpn

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VpnTcpFlowFilterTest {
    @Test
    fun `system proxy and unverified owners cannot forward through the tunnel`() {
        assertFalse(VpnTcpFlowFilter.isAllowedOwner(10124, 10124))
        assertFalse(VpnTcpFlowFilter.isAllowedOwner(-1, 10124))
        assertFalse(VpnTcpFlowFilter.isAllowedOwner(-1, null))
        assertTrue(VpnTcpFlowFilter.isAllowedOwner(10200, 10124))
        assertTrue(VpnTcpFlowFilter.isAllowedOwner(10200, null))
        assertTrue(VpnTcpFlowFilter.isAllowedOwner(1000, 10124))
    }
}
