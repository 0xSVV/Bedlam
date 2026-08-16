package ru.shapovalov.bedlam.feature.session.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import ru.shapovalov.bedlam.R
import ru.shapovalov.bedlam.ui.theme.spacing

private const val SPEED_TEST_URL = "https://speed.cloudflare.com/"
private const val SPEED_TEST_HOST = "speed.cloudflare.com"

private class SpeedTestWebViewClient(
    private val onLoadFailed: () -> Unit,
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean = request.url.host != SPEED_TEST_HOST

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        if (request.isForMainFrame) onLoadFailed()
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun SpeedTestWebView(modifier: Modifier = Modifier) {
    var failed by remember { mutableStateOf(false) }

    if (failed) {
        SpeedTestError(onRetry = { failed = false }, modifier = modifier)
        return
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mediaPlaybackRequiresUserGesture = true
                    allowFileAccess = false
                    allowContentAccess = false
                }
                webViewClient = SpeedTestWebViewClient(onLoadFailed = { failed = true })
                webChromeClient = WebChromeClient()
                loadUrl(SPEED_TEST_URL)
            }
        },
        onRelease = { webView ->
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        },
    )
}

@Composable
private fun SpeedTestError(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = MaterialTheme.spacing
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxSize()
            .padding(spacing.large),
    ) {
        Text(
            text = stringResource(R.string.session_speed_test_error),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onRetry,
            modifier = Modifier.padding(top = spacing.medium),
        ) {
            Text(stringResource(R.string.session_action_retry))
        }
    }
}
