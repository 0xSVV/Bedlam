package ru.shapovalov.bedlam.feature.session.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ru.shapovalov.bedlam.R
import ru.shapovalov.bedlam.feature.session.presentation.SessionComponent
import ru.shapovalov.bedlam.ui.theme.spacing

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SessionContent(component: SessionComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    val spacing = MaterialTheme.spacing
    var showSpeedTest by remember { mutableStateOf(false) }

    BackHandler {
        if (showSpeedTest) showSpeedTest = false else component.onBackPressed()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(if (showSpeedTest) R.string.session_speed_test_title else R.string.session_title),
                        style = MaterialTheme.typography.titleLargeEmphasized,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = { if (showSpeedTest) showSpeedTest = false else component.onBackPressed() },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    if (!showSpeedTest) {
                        IconButton(onClick = component::onRefresh, enabled = !state.isLoading) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.session_action_refresh_cd),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (showSpeedTest) {
            SpeedTestWebView(modifier = Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.large, vertical = spacing.medium),
            verticalArrangement = Arrangement.spacedBy(spacing.medium),
        ) {
            val cardState: CardState = when {
                state.isLoading -> CardState.Loading
                state.errorMessage != null -> CardState.Error(state.errorMessage!!)
                state.info != null -> CardState.Success(state.info!!)
                else -> CardState.Loading
            }

            AnimatedContent(
                targetState = cardState,
                modifier = Modifier.fillMaxWidth(),
                transitionSpec = {
                    if (initialState is CardState.Loading || targetState is CardState.Loading) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        (fadeIn(tween(durationMillis = 220, delayMillis = 90)) +
                            scaleIn(tween(durationMillis = 220, delayMillis = 90), initialScale = 0.96f))
                            .togetherWith(
                                fadeOut(tween(durationMillis = 90)) +
                                    scaleOut(tween(durationMillis = 90), targetScale = 0.96f),
                            )
                    }
                },
                contentKey = { it::class },
                label = "session-card",
            ) { target ->
                when (target) {
                    CardState.Loading -> SkeletonInfoCard()
                    is CardState.Error -> ErrorCard(message = target.message, onRetry = component::onRefresh)
                    is CardState.Success -> InfoCard(info = target.info)
                }
            }

            Text(
                text = stringResource(R.string.session_ip_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = spacing.small),
            )

            SpeedTestCard(onOpen = { showSpeedTest = true })

            Spacer(Modifier.height(spacing.large))
        }
    }
}
