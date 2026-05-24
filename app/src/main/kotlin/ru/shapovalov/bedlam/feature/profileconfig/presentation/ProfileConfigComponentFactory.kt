package ru.shapovalov.bedlam.feature.profileconfig.presentation

import com.arkivanov.decompose.ComponentContext
import me.tatarka.inject.annotations.Inject

@Inject
class ProfileConfigComponentFactory(
    private val storeFactory: ProfileConfigStoreFactory,
) {
    fun create(
        componentContext: ComponentContext,
        profileId: String,
        onBack: ProfileConfigComponent.OnBack,
    ): ProfileConfigComponent = ProfileConfigComponent(
        componentContext,
        profileId,
        storeFactory,
        onBack,
    )
}
