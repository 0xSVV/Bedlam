package ru.shapovalov.bedlam.resources

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Attr
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class IconDrawableResourcesTest {

    private val sourceRoot = listOf(File("src"), File("app/src")).first { File(it, "main").isDirectory }
    private val resourceDir = File(sourceRoot, "main/res")

    private fun iconDrawables(): List<File> =
        resourceDir
            .listFiles { dir -> dir.isDirectory && dir.name.startsWith("drawable") }
            .orEmpty()
            .flatMap { dir -> dir.listFiles().orEmpty().toList() }
            .filter { it.name.startsWith("ic_") && it.extension == "xml" }
            .filter { it.nameWithoutExtension !in BRAND_DRAWABLES }
            .sortedBy { it.invariantSeparatorsPath }

    @Test
    fun `every icon drawable is a 24dp vector on a 960 viewport`() {
        val icons = iconDrawables()
        assertTrue(icons.isNotEmpty())

        val shapes = icons.associate { file ->
            val root = elements(file).first()
            val size = "${root.android("width")} x ${root.android("height")}"
            val viewport = "${root.android("viewportWidth")} x ${root.android("viewportHeight")}"
            name(file) to "${root.tagName} $size, viewport $viewport"
        }

        assertEquals(emptyMap<String, String>(), shapes.filterValues { it != SYMBOL_SHAPE })
    }

    @Test
    fun `no icon drawable carries a tint or a theme attribute`() {
        val tinted = iconDrawables()
            .associate { file ->
                name(file) to elements(file)
                    .flatMap(::attributes)
                    .filter { it.isAndroidTint() || it.value.startsWith("?") }
                    .map { "${it.localName}=${it.value}" }
            }
            .filterValues { it.isNotEmpty() }

        assertEquals(emptyMap<String, List<String>>(), tinted)
    }

    @Test
    fun `only the back arrow, forward chevron and notes icons mirror in right-to-left layouts`() {
        val mirrored = iconDrawables()
            .filter { file -> elements(file).first().android("autoMirrored") == "true" }
            .map(::name)

        assertEquals(MIRRORED_ICONS, mirrored)
    }

    @Test
    fun `no kotlin source imports material icons`() {
        val importers = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { MATERIAL_ICONS_IMPORT.containsMatchIn(it.readText()) }
            .map { it.relativeTo(sourceRoot).invariantSeparatorsPath }
            .sorted()
            .toList()

        assertEquals(emptyList<String>(), importers)
    }

    private fun name(file: File) = file.relativeTo(resourceDir).invariantSeparatorsPath

    private fun elements(file: File): List<Element> {
        val nodes = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)
            .getElementsByTagName("*")
        return List(nodes.length) { nodes.item(it) as Element }
    }

    private fun attributes(element: Element): List<Attr> =
        List(element.attributes.length) { element.attributes.item(it) as Attr }

    private fun Element.android(attribute: String): String = getAttributeNS(ANDROID_NAMESPACE, attribute)

    private fun Attr.isAndroidTint() = namespaceURI == ANDROID_NAMESPACE && localName == "tint"

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val SYMBOL_SHAPE = "vector 24dp x 24dp, viewport 960 x 960"
        val BRAND_DRAWABLES = setOf("ic_launcher_monochrome", "ic_stat_bedlam")
        val MIRRORED_ICONS = listOf(
            "drawable/ic_arrow_back.xml",
            "drawable/ic_keyboard_arrow_right.xml",
            "drawable/ic_notes.xml",
        )
        val MATERIAL_ICONS_IMPORT =
            Regex("""^\s*import\s+androidx\.compose\.material\.icons\b""", RegexOption.MULTILINE)
    }
}
