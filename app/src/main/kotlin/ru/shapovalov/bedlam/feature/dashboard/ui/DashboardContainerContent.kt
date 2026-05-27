package ru.shapovalov.bedlam.feature.dashboard.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.arkivanov.decompose.ExperimentalDecomposeApi
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.stack.animation.fade
import com.arkivanov.decompose.extensions.compose.stack.animation.predictiveback.predictiveBackAnimation
import com.arkivanov.decompose.extensions.compose.stack.animation.stackAnimation
import ru.shapovalov.bedlam.feature.dashboard.presentation.DashboardContainerComponent
import ru.shapovalov.bedlam.feature.dashboard.presentation.DashboardContainerComponent.Child
import ru.shapovalov.bedlam.feature.profileconfig.ui.ProfileConfigContent
import ru.shapovalov.bedlam.feature.session.ui.SessionContent

@OptIn(ExperimentalDecomposeApi::class)
@Composable
fun DashboardContainerContent(
    component: DashboardContainerComponent,
    modifier: Modifier = Modifier,
) {
    Children(
        stack = component.childStack,
        modifier = modifier.fillMaxSize(),
        animation = predictiveBackAnimation(
            backHandler = component.backHandler,
            fallbackAnimation = stackAnimation(fade()),
            onBack = component::onBack,
        ),
    ) { created ->
        when (val child = created.instance) {
            is Child.Root -> DashboardContent(child.component)
            is Child.Session -> SessionContent(child.component)
            is Child.ProfileConfig -> ProfileConfigContent(child.component)
        }
    }
}
