package ru.shapovalov.bedlam.feature.settings.presentation

import com.arkivanov.decompose.ComponentContext
import me.tatarka.inject.annotations.Assisted
import me.tatarka.inject.annotations.Inject

@Inject
class SettingsComponentImpl(
    @Assisted componentContext: ComponentContext,
) : SettingsComponent, ComponentContext by componentContext
