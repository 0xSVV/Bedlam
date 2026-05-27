package ru.shapovalov.bedlam.feature.dashboard.presentation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.pushNew
import com.arkivanov.decompose.value.Value
import kotlinx.serialization.Serializable
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.feature.profileconfig.presentation.ProfileConfigComponent
import ru.shapovalov.bedlam.feature.profileconfig.presentation.ProfileConfigComponentFactory
import ru.shapovalov.bedlam.feature.session.presentation.SessionComponent
import ru.shapovalov.bedlam.feature.session.presentation.SessionComponentFactory

class DashboardContainerComponent(
    componentContext: ComponentContext,
    private val dashboardFactory: DashboardComponentFactory,
    private val sessionFactory: SessionComponentFactory,
    private val profileConfigFactory: ProfileConfigComponentFactory,
    private val onStartVpn: OnStartVpn,
    private val onStopVpn: OnStopVpn,
) : ComponentContext by componentContext {

    private val navigation = StackNavigation<Config>()

    val childStack: Value<ChildStack<*, Child>> = childStack(
        source = navigation,
        serializer = Config.serializer(),
        initialConfiguration = Config.Root,
        handleBackButton = false,
        childFactory = { config, ctx ->
            when (config) {
                Config.Root -> Child.Root(
                    dashboardFactory.create(
                        ctx,
                        { profile -> onStartVpn.invoke(profile) },
                        { onStopVpn.invoke() },
                        { navigation.pushNew(Config.Session) },
                        { id -> navigation.pushNew(Config.ProfileConfig(id)) },
                    )
                )

                Config.Session -> Child.Session(
                    sessionFactory.create(ctx, SessionComponent.OnBack { navigation.pop() })
                )

                is Config.ProfileConfig -> Child.ProfileConfig(
                    profileConfigFactory.create(
                        ctx,
                        config.profileId,
                        ProfileConfigComponent.OnBack { navigation.pop() },
                    )
                )
            }
        },
    )

    fun onBack() {
        navigation.pop()
    }

    sealed interface Child {
        data class Root(val component: DashboardComponent) : Child
        data class Session(val component: SessionComponent) : Child
        data class ProfileConfig(val component: ProfileConfigComponent) : Child
    }

    fun interface OnStartVpn {
        fun invoke(profile: Profile)
    }

    fun interface OnStopVpn {
        fun invoke()
    }

    @Serializable
    private sealed interface Config {
        @Serializable
        data object Root : Config

        @Serializable
        data object Session : Config

        @Serializable
        data class ProfileConfig(val profileId: String) : Config
    }
}
