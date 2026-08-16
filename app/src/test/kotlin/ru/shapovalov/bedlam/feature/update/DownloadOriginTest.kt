package ru.shapovalov.bedlam.feature.update

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.feature.update.data.isTrustedDownloadUrl

class DownloadOriginTest {

    @Test
    fun `a github release asset is trusted`() {
        assertTrue(
            isTrustedDownloadUrl(
                "https://github.com/0xSVV/Bedlam/releases/download/v1.4.0/bedlam-v1.4.0-arm64-v8a.apk"
            )
        )
    }

    @Test
    fun `the redirect target github serves assets from is trusted`() {
        assertTrue(isTrustedDownloadUrl("https://objects.githubusercontent.com/gh/asset?token=x"))
    }

    @Test
    fun `the host match is case insensitive`() {
        assertTrue(isTrustedDownloadUrl("https://GitHub.com/0xSVV/Bedlam/releases/download/a.apk"))
        assertTrue(isTrustedDownloadUrl("HTTPS://github.com/a.apk"))
    }

    @Test
    fun `plaintext http is refused`() {
        assertFalse(isTrustedDownloadUrl("http://github.com/0xSVV/Bedlam/releases/download/a.apk"))
    }

    @Test
    fun `another host is refused`() {
        assertFalse(isTrustedDownloadUrl("https://example.com/bedlam.apk"))
        assertFalse(isTrustedDownloadUrl("https://githubusercontent.com/a.apk"))
    }

    @Test
    fun `a lookalike host is refused`() {
        assertFalse(isTrustedDownloadUrl("https://github.com.evil.example/a.apk"))
        assertFalse(isTrustedDownloadUrl("https://notgithub.com/a.apk"))
        assertFalse(isTrustedDownloadUrl("https://evil.example/?x=github.com"))
    }

    @Test
    fun `userinfo cannot spoof the host`() {
        assertFalse(isTrustedDownloadUrl("https://github.com@evil.example/a.apk"))
    }

    @Test
    fun `a non-url is refused`() {
        assertFalse(isTrustedDownloadUrl(""))
        assertFalse(isTrustedDownloadUrl("not a url"))
        assertFalse(isTrustedDownloadUrl("file:///data/local/tmp/evil.apk"))
    }
}
