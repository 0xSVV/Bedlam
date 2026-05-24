package ru.shapovalov.bedlam.feature.session.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

private const val SPEED_TEST_URL = "https://speed.cloudflare.com/"

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun SpeedTestWebView(modifier: Modifier = Modifier) {
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
                    mediaPlaybackRequiresUserGesture = false
                }
                webViewClient = WebViewClient()
                webChromeClient = WebChromeClient()
                loadUrl(SPEED_TEST_URL)
            }
        },
    )
}
