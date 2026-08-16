package ru.shapovalov.bedlam.feature.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.feature.update.data.GitHubReleaseDto
import ru.shapovalov.bedlam.feature.update.data.isNewer
import ru.shapovalov.bedlam.feature.update.data.pickAsset

class AssetSelectionTest {

    private fun asset(name: String) = GitHubReleaseDto.AssetDto(
        name = name,
        downloadUrl = "https://github.com/0xSVV/Bedlam/releases/download/v1.4.1/$name",
        size = 1,
    )

    private val release = listOf(
        asset("bedlam-v1.4.1-universal.apk"),
        asset("bedlam-v1.4.1-armeabi-v7a.apk"),
        asset("bedlam-v1.4.1-arm64-v8a.apk"),
        asset("bedlam-v1.4.1-x86_64.apk"),
        asset("output-metadata.json"),
    )

    @Nested
    inner class PickAsset {

        @Test
        fun `the first supported ABI wins`() {
            val picked = pickAsset(release, "1.4.1", listOf("arm64-v8a", "armeabi-v7a"))
            assertEquals("bedlam-v1.4.1-arm64-v8a.apk", picked?.name)
        }

        @Test
        fun `ABI order decides, not asset order`() {
            val picked = pickAsset(release, "1.4.1", listOf("armeabi-v7a", "arm64-v8a"))
            assertEquals("bedlam-v1.4.1-armeabi-v7a.apk", picked?.name)
        }

        @Test
        fun `an unknown ABI falls back to universal`() {
            val picked = pickAsset(release, "1.4.1", listOf("riscv64"))
            assertEquals("bedlam-v1.4.1-universal.apk", picked?.name)
        }

        @Test
        fun `no ABIs at all still finds universal`() {
            assertEquals("bedlam-v1.4.1-universal.apk", pickAsset(release, "1.4.1", emptyList())?.name)
        }

        @Test
        fun `a version with no matching assets picks nothing`() {
            assertNull(pickAsset(release, "9.9.9", listOf("arm64-v8a")))
        }

        @Test
        fun `a release without a universal asset and no ABI match picks nothing`() {
            val partial = listOf(asset("bedlam-v1.4.1-arm64-v8a.apk"))
            assertNull(pickAsset(partial, "1.4.1", listOf("x86_64")))
        }

        @Test
        fun `the metadata file is never picked`() {
            val onlyMetadata = listOf(asset("output-metadata.json"))
            assertNull(pickAsset(onlyMetadata, "1.4.1", listOf("arm64-v8a")))
        }
    }

    @Nested
    inner class IsNewer {

        @Test
        fun `a higher version is newer`() {
            assertTrue(isNewer("1.4.1", "1.4.0"))
            assertTrue(isNewer("1.5.0", "1.4.9"))
            assertTrue(isNewer("2.0.0", "1.99.99"))
        }

        @Test
        fun `the same version is not newer`() {
            assertFalse(isNewer("1.4.0", "1.4.0"))
        }

        @Test
        fun `a lower version is not newer`() {
            assertFalse(isNewer("1.4.0", "1.4.1"))
            assertFalse(isNewer("1.3.9", "1.4.0"))
        }

        @Test
        fun `a shorter version compares as trailing zeroes`() {
            assertFalse(isNewer("1.4", "1.4.0"))
            assertTrue(isNewer("1.4.1", "1.4"))
            assertFalse(isNewer("1.4", "1.4.1"))
        }

        @Test
        fun `an unparseable version is never newer`() {
            assertFalse(isNewer("", "1.4.0"))
            assertFalse(isNewer("1.4.0", ""))
            assertFalse(isNewer("nightly", "1.4.0"))
        }

        @Test
        fun `a tag with a prefix still compares on its numbers`() {
            assertTrue(isNewer("v1.4.1", "1.4.0"))
        }
    }
}
