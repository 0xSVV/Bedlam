package ru.shapovalov.bedlam.feature.settings.presentation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.pushNew
import com.arkivanov.decompose.value.Value
import kotlinx.serialization.Serializable
import ru.shapovalov.bedlam.feature.appselection.presentation.AppSelectionComponent
import ru.shapovalov.bedlam.feature.appselection.presentation.AppSelectionComponentFactory
import ru.shapovalov.bedlam.feature.routing.presentation.RoutingComponent
import ru.shapovalov.bedlam.feature.routing.presentation.RoutingComponentFactory

class SettingsComponent(
    componentContext: ComponentContext,
    private val appSelectionFactory: AppSelectionComponentFactory,
    private val routingFactory: RoutingComponentFactory,
) : ComponentContext by componentContext {

    private val navigation = StackNavigation<Config>()

    val childStack: Value<ChildStack<*, Child>> = childStack(
        source = navigation,
        serializer = Config.serializer(),
        initialConfiguration = Config.Root,
        handleBackButton = false,
        childFactory = { config, ctx ->
            when (config) {
                Config.Root -> Child.Root
                Config.AppSelection -> Child.AppSelection(
                    appSelectionFactory.create(ctx, AppSelectionComponent.OnBack { navigation.pop() })
                )
                Config.Routing -> Child.Routing(
                    routingFactory.create(ctx, RoutingComponent.OnBack { navigation.pop() })
                )
            }
        },
    )

    fun onBack() {
        navigation.pop()
    }

    fun onOpenAppSelection() {
        navigation.pushNew(Config.AppSelection)
    }

    fun onOpenRouting() {
        navigation.pushNew(Config.Routing)
    }

    sealed interface Child {
        data object Root : Child
        data class AppSelection(val component: AppSelectionComponent) : Child
        data class Routing(val component: RoutingComponent) : Child
    }

    @Serializable
    private sealed interface Config {
        @Serializable
        data object Root : Config

        @Serializable
        data object AppSelection : Config

        @Serializable
        data object Routing : Config
    }
}
