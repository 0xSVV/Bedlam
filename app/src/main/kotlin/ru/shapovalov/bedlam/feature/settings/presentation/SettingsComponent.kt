package ru.shapovalov.bedlam.feature.settings.presentation

import com.arkivanov.decompose.ComponentContext
import me.tatarka.inject.annotations.Assisted
import me.tatarka.inject.annotations.Inject

@Inject
class SettingsComponent(
    @Assisted componentContext: ComponentContext,
) : ComponentContext by componentContext
