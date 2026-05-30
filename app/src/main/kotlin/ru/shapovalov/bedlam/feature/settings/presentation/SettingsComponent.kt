package ru.shapovalov.bedlam.feature.settings.presentation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.pushNew
import com.arkivanov.decompose.value.Value
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import ru.shapovalov.bedlam.core.util.componentScope
import ru.shapovalov.bedlam.feature.appselection.presentation.AppSelectionComponent
import ru.shapovalov.bedlam.feature.appselection.presentation.AppSelectionComponentFactory
import ru.shapovalov.bedlam.feature.routing.presentation.RoutingComponent
import ru.shapovalov.bedlam.feature.routing.presentation.RoutingComponentFactory

class SettingsComponent(
    componentContext: ComponentContext,
    private val appSelectionFactory: AppSelectionComponentFactory,
    private val routingFactory: RoutingComponentFactory,
    storeFactory: SettingsStoreFactory,
) : ComponentContext by componentContext {

    private val navigation = StackNavigation<Config>()
    private val store = instanceKeeper.getStore { storeFactory.create() }
    private val scope = componentScope()

    val state: StateFlow<SettingsStore.State> = store.stateFlow(scope)

    val childStack: Value<ChildStack<*, Child>> = childStack(
        source = navigation,
        serializer = Config.serializer(),
        initialConfiguration = Config.Root,
        handleBackButton = false,
        childFactory = { config, ctx ->
            when (config) {
                Config.Root -> Child.Root
                Config.AppSelection -> Child.AppSelection(
                    appSelectionFactory.create(
                        ctx
                    ) { navigation.pop() }
                )

                Config.Routing -> Child.Routing(
                    routingFactory.create(ctx, RoutingComponent.OnBack { navigation.pop() })
                )

                Config.BatteryReliability -> Child.BatteryReliability
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

    fun onOpenBatteryReliability() {
        navigation.pushNew(Config.BatteryReliability)
    }

    fun onSetQuickSettingsTileAdded(added: Boolean) {
        store.accept(SettingsStore.Intent.SetQuickSettingsTileAdded(added))
    }

    fun onMarkReliabilityConfirmed(fingerprint: String) {
        store.accept(SettingsStore.Intent.MarkReliabilityConfirmed(fingerprint))
    }

    sealed interface Child {
        data object Root : Child
        data class AppSelection(val component: AppSelectionComponent) : Child
        data class Routing(val component: RoutingComponent) : Child
        data object BatteryReliability : Child
    }

    @Serializable
    private sealed interface Config {
        @Serializable
        data object Root : Config

        @Serializable
        data object AppSelection : Config

        @Serializable
        data object Routing : Config

        @Serializable
        data object BatteryReliability : Config
    }
}
