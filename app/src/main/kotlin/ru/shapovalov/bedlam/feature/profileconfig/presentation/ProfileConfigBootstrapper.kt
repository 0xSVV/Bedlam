package ru.shapovalov.bedlam.feature.profileconfig.presentation

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineBootstrapper
import kotlinx.coroutines.launch
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.core.profile.domain.usecase.ObserveProfileUseCase

internal sealed interface Action {
    data class ProfileLoaded(val profile: Profile) : Action
    data object ProfileMissing : Action
}

internal class ProfileConfigBootstrapper(
    private val profileId: String,
    private val observeProfile: ObserveProfileUseCase,
) : CoroutineBootstrapper<Action>() {

    override fun invoke() {
        scope.launch {
            observeProfile(profileId).collect { profile ->
                if (profile == null) dispatch(Action.ProfileMissing)
                else dispatch(Action.ProfileLoaded(profile))
            }
        }
    }
}
