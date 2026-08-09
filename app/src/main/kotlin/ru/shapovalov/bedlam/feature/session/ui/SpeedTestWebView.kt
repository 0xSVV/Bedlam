package ru.shapovalov.bedlam.feature.session.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

private const val SPEED_TEST_URL = "https://speed.cloudflare.com/"
private const val SPEED_TEST_HOST = "speed.cloudflare.com"

private class SpeedTestWebViewClient : WebViewClient() {
    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean = request.url.host != SPEED_TEST_HOST
}

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
                    mediaPlaybackRequiresUserGesture = true
                    allowFileAccess = false
                    allowContentAccess = false
                }
                webViewClient = SpeedTestWebViewClient()
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
