package ru.shapovalov.bedlam.navigation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.bringToFront
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.value.Value
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.feature.dashboard.presentation.DashboardComponent
import ru.shapovalov.bedlam.feature.dashboard.presentation.DashboardComponentFactory
import ru.shapovalov.bedlam.feature.session.presentation.SessionComponent
import ru.shapovalov.bedlam.feature.session.presentation.SessionComponentFactory
import ru.shapovalov.bedlam.feature.settings.presentation.SettingsComponent
import ru.shapovalov.bedlam.feature.settings.presentation.SettingsComponentFactory

class RootComponent(
    componentContext: ComponentContext,
    private val dashboardFactory: DashboardComponentFactory,
    private val settingsFactory: SettingsComponentFactory,
    private val sessionFactory: SessionComponentFactory,
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
                    dashboardFactory.create(
                        ctx,
                        DashboardComponent.OnStartVpn { profile -> dispatchStartVpn(profile) },
                        DashboardComponent.OnStopVpn { onStopVpn.invoke() },
                        DashboardComponent.OnOpenSession { navigation.bringToFront(Config.Session) },
                    )
                )

                Config.Settings -> Child.Settings(settingsFactory.create(ctx))

                Config.Session -> Child.Session(
                    sessionFactory.create(ctx, SessionComponent.OnBack { navigation.pop() })
                )
            }
        },
    )

    fun onTabSelected(tab: Tab) {
        val target = when (tab) {
            Tab.Dashboard -> Config.Dashboard
            Tab.Settings -> Config.Settings
        }
        navigation.bringToFront(target)
    }

    private fun dispatchStartVpn(profile: Profile) {
        val payload = json.encodeToString(profile.config)
        onStartVpn.invoke(profile.id, payload, profile.name)
    }

    sealed interface Child {
        val tab: Tab

        data class Dashboard(val component: DashboardComponent) : Child {
            override val tab: Tab get() = Tab.Dashboard
        }

        data class Settings(val component: SettingsComponent) : Child {
            override val tab: Tab get() = Tab.Settings
        }

        data class Session(val component: SessionComponent) : Child {
            override val tab: Tab get() = Tab.Dashboard
        }
    }

    enum class Tab { Dashboard, Settings }

    fun interface OnStartVpn { fun invoke(profileId: String, configJson: String, profileName: String) }
    fun interface OnStopVpn { fun invoke() }

    @Serializable
    private sealed interface Config {
        @Serializable
        data object Dashboard : Config

        @Serializable
        data object Settings : Config

        @Serializable
        data object Session : Config
    }
}
