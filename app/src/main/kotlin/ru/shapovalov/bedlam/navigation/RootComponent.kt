package ru.shapovalov.bedlam.navigation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.bringToFront
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.value.Value
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.feature.dashboard.presentation.DashboardContainerComponent
import ru.shapovalov.bedlam.feature.dashboard.presentation.DashboardContainerComponentFactory
import ru.shapovalov.bedlam.feature.logs.presentation.LogsComponent
import ru.shapovalov.bedlam.feature.logs.presentation.LogsComponentFactory
import ru.shapovalov.bedlam.feature.settings.presentation.SettingsComponent
import ru.shapovalov.bedlam.feature.settings.presentation.SettingsComponentFactory

class RootComponent(
    componentContext: ComponentContext,
    private val dashboardContainerFactory: DashboardContainerComponentFactory,
    private val settingsFactory: SettingsComponentFactory,
    private val logsFactory: LogsComponentFactory,
    private val json: Json,
    private val onStartVpn: OnStartVpn,
    private val onStopVpn: OnStopVpn,
) : ComponentContext by componentContext {

    private val navigation = StackNavigation<Config>()

    val childStack: Value<ChildStack<*, Child>> = childStack(
        source = navigation,
        serializer = Config.serializer(),
        initialConfiguration = Config.Dashboard,
        handleBackButton = false,
        childFactory = { config, ctx ->
            when (config) {
                Config.Dashboard -> Child.Dashboard(
                    dashboardContainerFactory.create(
                        ctx,
                        { profile -> dispatchStartVpn(profile) },
                        { onStopVpn.invoke() },
                    )
                )

                Config.Settings -> Child.Settings(settingsFactory.create(ctx))

                Config.Logs -> Child.Logs(logsFactory.create(ctx))
            }
        },
    )

    fun onTabSelected(tab: Tab) {
        val target = when (tab) {
            Tab.Dashboard -> Config.Dashboard
            Tab.Settings -> Config.Settings
            Tab.Logs -> Config.Logs
        }
        navigation.bringToFront(target)
    }

    private fun dispatchStartVpn(profile: Profile) {
        val payload = json.encodeToString(profile.config)
        onStartVpn.invoke(profile.id, payload, profile.name)
    }

    sealed interface Child {
        val tab: Tab

        data class Dashboard(val component: DashboardContainerComponent) : Child {
            override val tab: Tab get() = Tab.Dashboard
        }

        data class Settings(val component: SettingsComponent) : Child {
            override val tab: Tab get() = Tab.Settings
        }

        data class Logs(val component: LogsComponent) : Child {
            override val tab: Tab get() = Tab.Logs
        }
    }

    enum class Tab { Logs, Dashboard, Settings }

    fun interface OnStartVpn { fun invoke(profileId: String, configJson: String, profileName: String) }
    fun interface OnStopVpn { fun invoke() }

    @Serializable
    private sealed interface Config {
        @Serializable
        data object Dashboard : Config

        @Serializable
        data object Settings : Config

        @Serializable
        data object Logs : Config
    }
}
