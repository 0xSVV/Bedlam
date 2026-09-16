package ru.shapovalov.bedlam.resources

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LauncherIconResourcesTest {

    private val res = File("src/main/res")

    private fun text(path: String) = File(res, path).readText().replace("\r\n", "\n")

    private fun androidAttributeValues(path: String, tag: String, attribute: String): List<String> {
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File(res, path))
        val elements = document.getElementsByTagName(tag)
        return (0 until elements.length).map { index ->
            (elements.item(index) as Element).getAttributeNS(ANDROID_NAMESPACE, attribute)
        }
    }

    @Test
    fun `round launcher icon matches the launcher icon`() {
        assertEquals(text("mipmap-anydpi/ic_launcher.xml"), text("mipmap-anydpi/ic_launcher_round.xml"))
    }

    @Test
    fun `adaptive icon background uses the splash background color`() {
        assertEquals(
            listOf("@color/bedlam_splash_background"),
            androidAttributeValues("mipmap-anydpi/ic_launcher.xml", "background", "drawable"),
        )
    }

    @Test
    fun `no drawable or mipmap is declared both as xml and as a bitmap`() {
        assertTrue(res.isDirectory)
        val extensionsByName = res
            .listFiles { dir -> dir.isDirectory && (dir.name.startsWith("drawable") || dir.name.startsWith("mipmap")) }
            .orEmpty()
            .flatMap { it.listFiles().orEmpty().toList() }
            .groupBy({ it.name.substringBefore('.') }, { it.name.substringAfter('.') })
        val declaredTwice = extensionsByName
            .filterValues { extensions -> "xml" in extensions && extensions.any { it != "xml" } }
            .keys
        assertEquals(emptySet<String>(), declaredTwice)
    }

    @Test
    fun `notification icon draws the themed icon paths`() {
        val themedIconPaths = androidAttributeValues("drawable/ic_launcher_monochrome.xml", "path", "pathData")
        assertTrue(themedIconPaths.isNotEmpty())
        assertEquals(themedIconPaths, androidAttributeValues("drawable/ic_stat_bedlam.xml", "path", "pathData"))
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
