package ru.shapovalov.bedlam

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.shapovalov.bedlam.core.database.BedlamDatabase
import ru.shapovalov.bedlam.core.profile.data.local.HysteriaConfigConverter
import ru.shapovalov.bedlam.core.routing.data.RoutingRepositoryImpl
import ru.shapovalov.bedlam.core.routing.domain.model.DnsMode
import ru.shapovalov.hysteria.api.DnsTransport

@RunWith(AndroidJUnit4::class)
class DirectDnsPersistenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val migration = MigrationTestHelper(instrumentation, BedlamDatabase::class.java)

    @Test
    fun upgradePreservesExistingDnsAndStartsWithNoCustomBootstrap() {
        migration.createDatabase("dns-migration", 7).use { db ->
            db.execSQL(
                "INSERT INTO routing_config " +
                    "(id,bypassLan,ipv6Mode,dnsMode,customDnsCsv,dnsTransport,mtu," +
                    "directDnsMode,directDnsTransport,directCustomDnsCsv,dnsCacheEnabled) " +
                    "VALUES (1,0,'BypassOnly','Custom','9.9.9.9:53','Tcp',1400," +
                    "'Custom','Tcp','192.168.1.1:53',0)",
            )
        }
        migration.runMigrationsAndValidate("dns-migration", 8, validateDroppedTables = true).use { db ->
            db.query("SELECT * FROM routing_config WHERE id=1").use { row ->
                check(row.moveToFirst())
                assertEquals("{}", row.getString(row.getColumnIndexOrThrow("directDnsBootstrapJson")))
                assertEquals("Custom", row.getString(row.getColumnIndexOrThrow("directDnsMode")))
                assertEquals("Tcp", row.getString(row.getColumnIndexOrThrow("directDnsTransport")))
                assertEquals("192.168.1.1:53", row.getString(row.getColumnIndexOrThrow("directCustomDnsCsv")))
                assertEquals("9.9.9.9:53", row.getString(row.getColumnIndexOrThrow("customDnsCsv")))
                assertEquals(1400, row.getInt(row.getColumnIndexOrThrow("mtu")))
                assertEquals(0, row.getInt(row.getColumnIndexOrThrow("dnsCacheEnabled")))
            }
        }
    }

    @Test
    fun customEncryptedDnsSurvivesDatabaseReopen() = runBlocking {
        val context = instrumentation.targetContext
        val name = "dns-persistence"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, BedlamDatabase::class.java, name)
            .addTypeConverter(HysteriaConfigConverter(Json))
            .addMigrations(BedlamDatabase.MIGRATION_6_7)
            .build()
        val servers = listOf("https://dns.fixture.test:18444/dns-query")
        val bootstrap = mapOf("dns.fixture.test" to listOf("10.0.2.2", "2001:db8::53"))
        var db = open()
        try {
            RoutingRepositoryImpl(db.routingDao()).setDirectDns(
                DnsMode.Custom, DnsTransport.Https, servers, bootstrap,
            )
            db.close()
            db = open()
            with(RoutingRepositoryImpl(db.routingDao()).get()) {
                assertEquals(DnsMode.Custom, directDnsMode)
                assertEquals(DnsTransport.Https, directDnsTransport)
                assertEquals(servers, directCustomDns)
                assertEquals(bootstrap, directDnsBootstrap)
                assertEquals(DnsMode.Cloudflare, dnsMode)
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
