package ru.shapovalov.bedlam.core.routing.data.resolver

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.routing.domain.model.Cidr
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

@Inject
class DnsDomainResolver {

    suspend fun resolve(hostname: String): Result<List<Cidr>> = runCatching {
        withContext(Dispatchers.IO) {
            val addresses = InetAddress.getAllByName(hostname)
            addresses.mapNotNull { it.toHostCidr() }.distinct()
        }
    }

    private fun InetAddress.toHostCidr(): Cidr? = when (this) {
        is Inet4Address -> Cidr.V4(address, 32)
        is Inet6Address -> Cidr.V6(address, 128)
        else -> null
    }
}
