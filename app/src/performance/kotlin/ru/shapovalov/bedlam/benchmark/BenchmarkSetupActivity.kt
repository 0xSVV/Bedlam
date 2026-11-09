package ru.shapovalov.bedlam.benchmark

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.shapovalov.bedlam.MainActivity
import ru.shapovalov.bedlam.core.appfilter.domain.model.AppFilterMode
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.core.routing.domain.model.Cidr
import ru.shapovalov.bedlam.core.routing.domain.model.DirectRouteSource
import ru.shapovalov.bedlam.core.routing.domain.model.ResolvedSource
import ru.shapovalov.bedlam.di.appComponent
import ru.shapovalov.hysteria.config.HysteriaConfig
import ru.shapovalov.hysteria.config.ServerCredentials
import ru.shapovalov.hysteria.config.TlsOptions

class BenchmarkSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val component = appComponent
            withContext(Dispatchers.IO) {
                val config = HysteriaConfig(
                    server = ServerCredentials(
                        intent.getStringExtra("server") ?: "10.0.2.2:18443",
                        intent.getStringExtra("auth") ?: "bedlam-ui-review",
                    ),
                    tls = TlsOptions(tlsInsecure = true),
                )
                repeat(40) { index ->
                    val name = "Lab profile %02d".format(index)
                    component.profileRepository.upsert(
                        Profile("benchmark-$index", name, config, index.toLong(), index.toLong()),
                    )
                }
                component.profileRepository.setActiveId("benchmark-0")
                component.appFilterRepository.setMode(AppFilterMode.All)
                component.appFilterRepository.setPackages(emptySet())
                val source = DirectRouteSource.Asn("benchmark-asn", 64512, "Benchmark cached network", false, 0)
                component.routingRepository.removeSource(source.id)
                val cidrs = List(300) { Cidr.parse("198.18.${it / 256}.${it % 256}/32") }
                component.routingRepository.restoreSource(
                    ResolvedSource(source, cidrs, 1_700_000_000_000, null),
                )
            }
            component.logBuffer.clear()
            repeat(600) { index ->
                component.appLog.info("benchmark", "Network event $index: connected, bytes=$index, route=local lab")
                if (index % 16 == 0) delay(5)
            }
            startActivity(
                Intent(this@BenchmarkSetupActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            finish()
        }
    }
}
