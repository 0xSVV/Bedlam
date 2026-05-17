package ru.shapovalov.bedlam.di

import android.app.Application
import android.content.Context
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides
import ru.shapovalov.bedlam.core.profile.domain.usecase.DeleteProfileUseCase
import ru.shapovalov.bedlam.core.profile.domain.usecase.GetProfilesUseCase
import ru.shapovalov.bedlam.core.profile.domain.usecase.ImportProfileFromUriUseCase
import ru.shapovalov.bedlam.core.profile.domain.usecase.ObserveActiveProfileUseCase
import ru.shapovalov.bedlam.core.profile.domain.usecase.ObserveProfileUseCase
import ru.shapovalov.bedlam.core.profile.domain.usecase.SaveProfileUseCase
import ru.shapovalov.bedlam.core.profile.domain.usecase.SetActiveProfileUseCase
import ru.shapovalov.hysteria.HysteriaClientImpl
import ru.shapovalov.hysteria.api.HysteriaClient

@AppScope
@Component
abstract class AppComponent(
    @get:Provides val application: Application,
) : DatabaseModule, ProfileModule {

    abstract val hysteriaClient: HysteriaClient

    abstract val getProfiles: GetProfilesUseCase
    abstract val observeProfile: ObserveProfileUseCase
    abstract val saveProfile: SaveProfileUseCase
    abstract val deleteProfile: DeleteProfileUseCase
    abstract val importProfileFromUri: ImportProfileFromUriUseCase
    abstract val observeActiveProfile: ObserveActiveProfileUseCase
    abstract val setActiveProfile: SetActiveProfileUseCase

    @get:Provides
    val context: Context
        get() = application

    @AppScope
    @Provides
    fun provideHysteriaClient(): HysteriaClient = HysteriaClientImpl()
}
