package ru.shapovalov.bedlam.di

import android.app.Application
import android.content.Context
import com.arkivanov.decompose.ComponentContext
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides
import ru.shapovalov.bedlam.navigation.RootComponent
import ru.shapovalov.bedlam.navigation.RootComponentImpl
import ru.shapovalov.hysteria.HysteriaClientImpl
import ru.shapovalov.hysteria.api.HysteriaClient

@AppScope
@Component
abstract class AppComponent(
    @get:Provides val application: Application,
) : DatabaseModule, ProfileModule, PresentationModule {

    abstract val hysteriaClient: HysteriaClient
    abstract val json: Json

    abstract val rootComponentFactory: (ComponentContext, RootComponent.OnStartVpn, RootComponent.OnStopVpn) -> RootComponentImpl

    @get:Provides
    val context: Context
        get() = application

    @AppScope
    @Provides
    fun provideHysteriaClient(): HysteriaClient = HysteriaClientImpl()

}
