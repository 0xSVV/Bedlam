package ru.shapovalov.bedlam.navigation

import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.value.Value
import ru.shapovalov.bedlam.feature.dashboard.presentation.DashboardComponent
import ru.shapovalov.bedlam.feature.settings.presentation.SettingsComponent

interface RootComponent {

    val childStack: Value<ChildStack<*, Child>>

    fun onTabSelected(tab: Tab)

    sealed interface Child {
        val tab: Tab

        data class Dashboard(val component: DashboardComponent) : Child {
            override val tab: Tab get() = Tab.Dashboard
        }

        data class Settings(val component: SettingsComponent) : Child {
            override val tab: Tab get() = Tab.Settings
        }
    }

    enum class Tab { Dashboard, Settings }

    fun interface OnStartVpn { fun invoke(profileId: String, configJson: String, profileName: String) }
    fun interface OnStopVpn { fun invoke() }
}
