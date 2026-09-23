package ru.shapovalov.bedlam.core.vpn

import ru.shapovalov.hysteria.api.HysteriaClient

suspend fun HysteriaClient.resetAfterNetworkChange() = resetConnectionsKeepingDnsCache()
