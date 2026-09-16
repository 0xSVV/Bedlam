package ru.shapovalov.bedlam.testing

import android.content.Context
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.mvikotlin.core.store.StoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import ru.shapovalov.bedlam.core.appfilter.data.AppIconLoader
import ru.shapovalov.bedlam.core.appfilter.domain.usecase.GetInstalledAppsUseCase
import ru.shapovalov.bedlam.core.appfilter.domain.usecase.ObserveAppFilterUseCase
import ru.shapovalov.bedlam.core.appfilter.domain.usecase.SetAppFilterModeUseCase
import ru.shapovalov.bedlam.core.appfilter.domain.usecase.ToggleAppFilterPackageUseCase
import ru.shapovalov.bedlam.core.latency.PingProfileUseCase
import ru.shapovalov.bedlam.core.profile.domain.usecase.DeleteProfileUseCase
import ru.shapovalov.bedlam.core.profile.domain.usecase.GetProfilesUseCase
import ru.shapovalov.bedlam.core.profile.domain.usecase.ImportProfileUseCase
import ru.shapovalov.bedlam.core.profile.domain.usecase.ObserveActiveProfileIdUseCase
import ru.shapovalov.bedlam.core.profile.domain.usecase.ObserveProfileUseCase
import ru.shapovalov.bedlam.core.profile.domain.usecase.SaveProfileUseCase
import ru.shapovalov.bedlam.core.profile.domain.usecase.SetActiveProfileUseCase
import ru.shapovalov.bedlam.core.routing.domain.usecase.AddPresetUseCase
import ru.shapovalov.bedlam.core.routing.domain.usecase.AddRouteSourceUseCase
import ru.shapovalov.bedlam.core.routing.domain.usecase.RefreshRouteSourcesUseCase
import ru.shapovalov.bedlam.core.vpn.ReconcileConnectionStateUseCase
import ru.shapovalov.bedlam.core.vpn.VpnRuntimeStateRepository
import ru.shapovalov.bedlam.core.vpn.VpnServiceLauncher
import ru.shapovalov.bedlam.feature.appselection.presentation.AppSelectionComponentFactory
import ru.shapovalov.bedlam.feature.appselection.presentation.AppSelectionStoreFactory
import ru.shapovalov.bedlam.feature.dashboard.presentation.DashboardBootstrapper
import ru.shapovalov.bedlam.feature.dashboard.presentation.DashboardComponentFactory
import ru.shapovalov.bedlam.feature.dashboard.presentation.DashboardContainerComponentFactory
import ru.shapovalov.bedlam.feature.dashboard.presentation.DashboardStoreFactory
import ru.shapovalov.bedlam.feature.logs.data.LogBuffer
import ru.shapovalov.bedlam.feature.logs.presentation.LogsComponentFactory
import ru.shapovalov.bedlam.feature.logs.presentation.LogsStoreFactory
import ru.shapovalov.bedlam.feature.profileconfig.presentation.ProfileConfigComponentFactory
import ru.shapovalov.bedlam.feature.profileconfig.presentation.ProfileConfigStoreFactory
import ru.shapovalov.bedlam.feature.routing.presentation.RoutingComponentFactory
import ru.shapovalov.bedlam.feature.routing.presentation.RoutingStoreFactory
import ru.shapovalov.bedlam.feature.session.presentation.SessionComponentFactory
import ru.shapovalov.bedlam.feature.session.presentation.SessionStoreFactory
import ru.shapovalov.bedlam.feature.settings.presentation.SettingsComponentFactory
import ru.shapovalov.bedlam.feature.settings.presentation.SettingsStoreFactory
import ru.shapovalov.bedlam.feature.update.domain.usecase.CheckForUpdateUseCase
import ru.shapovalov.bedlam.feature.update.domain.usecase.DownloadUpdateUseCase
import ru.shapovalov.bedlam.feature.update.domain.usecase.SkipUpdateUseCase
import ru.shapovalov.bedlam.feature.update.presentation.UpdateComponentFactory
import ru.shapovalov.bedlam.feature.update.presentation.UpdateStoreFactory
import ru.shapovalov.bedlam.navigation.RootComponent
import ru.shapovalov.bedlam.navigation.RootComponentFactory

class TestGraph(
    val storeFactory: StoreFactory = SkippingBootstrapperStoreFactory { it is DashboardBootstrapper },
    logScope: CoroutineScope = CoroutineScope(Dispatchers.Main),
) {

    val client = FakeHysteriaClient()
    val profiles = FakeProfileRepository()
    val routing = FakeRoutingRepository()
    val resolver = FakeDirectRouteResolver()
    val appFilter = FakeAppFilterRepository()
    val installedApps = FakeInstalledAppsRepository()
    val power = FakePowerReliabilityRepository()
    val tile = FakeQuickSettingsTileRepository()
    val sessionInfo = FakeSessionInfoRepository()
    val updates = FakeUpdateRepository()
    val installer = FakeUpdateInstaller()

    private val android: Context = StubAndroidContext()
    private val addSource = AddRouteSourceUseCase(routing, resolver)

    val appSelectionFactory = AppSelectionComponentFactory(
        AppSelectionStoreFactory(
            storeFactory,
            ObserveAppFilterUseCase(appFilter),
            GetInstalledAppsUseCase(installedApps),
            SetAppFilterModeUseCase(appFilter),
            ToggleAppFilterPackageUseCase(appFilter),
        ),
        AppIconLoader(android),
    )

    val routingFactory = RoutingComponentFactory(
        RoutingStoreFactory(
            storeFactory,
            routing,
            addSource,
            AddPresetUseCase(addSource),
            RefreshRouteSourcesUseCase(routing, resolver),
        )
    )

    val settingsFactory = SettingsComponentFactory(
        appSelectionFactory,
        routingFactory,
        SettingsStoreFactory(storeFactory, power, tile),
    )

    val sessionFactory = SessionComponentFactory(
        SessionStoreFactory(storeFactory, sessionInfo, client)
    )

    val profileConfigFactory = ProfileConfigComponentFactory(
        ProfileConfigStoreFactory(
            storeFactory,
            ObserveProfileUseCase(profiles),
            SaveProfileUseCase(profiles),
            DeleteProfileUseCase(profiles),
            client,
            idleReconnectProfile(client, profiles),
        )
    )

    val updateFactory = UpdateComponentFactory(
        UpdateStoreFactory(
            storeFactory,
            updates,
            DownloadUpdateUseCase(updates),
            SkipUpdateUseCase(updates),
            installer,
        )
    )

    val logBuffer by lazy { LogBuffer(client, logScope) }

    val logsFactory by lazy {
        LogsComponentFactory(LogsStoreFactory(storeFactory, logBuffer))
    }

    val dashboardFactory by lazy {
        val runtimeState = VpnRuntimeStateRepository(android)
        DashboardComponentFactory(
            DashboardStoreFactory(
                storeFactory,
                GetProfilesUseCase(profiles),
                ObserveActiveProfileIdUseCase(profiles),
                SetActiveProfileUseCase(profiles),
                ImportProfileUseCase(profiles, client),
                client,
                PingProfileUseCase(android),
                runtimeState,
                ReconcileConnectionStateUseCase(
                    client,
                    VpnServiceLauncher(android, profiles, Json),
                    runtimeState,
                ),
            )
        )
    }

    val dashboardContainerFactory by lazy {
        DashboardContainerComponentFactory(dashboardFactory, sessionFactory, profileConfigFactory)
    }

    fun root(
        context: ComponentContext,
        onStartVpn: RootComponent.OnStartVpn = RootComponent.OnStartVpn {},
        onStopVpn: RootComponent.OnStopVpn = RootComponent.OnStopVpn {},
    ): RootComponent = RootComponentFactory(
        dashboardContainerFactory,
        settingsFactory,
        logsFactory,
        updateFactory,
        CheckForUpdateUseCase(updates),
    ).create(context, onStartVpn, onStopVpn)
}
