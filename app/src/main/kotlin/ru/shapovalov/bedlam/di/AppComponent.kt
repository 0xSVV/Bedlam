package ru.shapovalov.bedlam.di

import android.app.Application
import android.content.Context
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides
import ru.shapovalov.bedlam.core.appfilter.di.AppFilterModule
import ru.shapovalov.bedlam.core.appfilter.domain.repository.AppFilterRepository
import ru.shapovalov.bedlam.core.profile.di.ProfileModule
import ru.shapovalov.bedlam.feature.logs.data.LogBuffer
import ru.shapovalov.bedlam.feature.session.di.SessionModule
import ru.shapovalov.bedlam.navigation.RootComponentFactory
import ru.shapovalov.hysteria.HysteriaClientImpl
import ru.shapovalov.hysteria.api.HysteriaClient

@AppScope
@Component
abstract class AppComponent(
    @get:Provides val application: Application,
) : DatabaseModule, ProfileModule, PresentationModule, AppFilterModule, SessionModule {

    abstract val hysteriaClient: HysteriaClient
    abstract val json: Json
    abstract val appFilterRepository: AppFilterRepository
    abstract val logBuffer: LogBuffer

    abstract val rootComponentFactory: RootComponentFactory

    @get:Provides
    val context: Context
        get() = application

    @AppScope
    @Provides
    fun provideHysteriaClient(): HysteriaClient = HysteriaClientImpl()
}
