package ru.shapovalov.bedlam.resources

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element

class ThemeResourcesTest {

    private val mainSourceSet = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private val resourceDir = File(mainSourceSet, "res")
    private val defaultValuesDir = File(resourceDir, "values")

    @Test
    fun `every theme the manifest names is declared in default resources`() {
        val manifestStyles = styleReferences(File(mainSourceSet, "AndroidManifest.xml"))

        assertTrue(manifestStyles.isNotEmpty())
        assertEquals(emptySet<String>(), manifestStyles - declaredStyles(defaultValuesDir))
    }

    @Test
    fun `every style declared under a qualifier is also declared in default resources`() {
        val qualifiedStyles = resourceDir
            .listFiles { dir -> dir.isDirectory && dir.name.startsWith("values-") }
            .orEmpty()
            .flatMap(::declaredStyles)
            .toSet()

        assertEquals(emptySet<String>(), qualifiedStyles - declaredStyles(defaultValuesDir))
    }

    private fun declaredStyles(valuesDir: File): Set<String> =
        valuesDir.listFiles { file -> file.extension == "xml" }
            .orEmpty()
            .flatMap { file -> elements(file).filter { it.tagName == "style" } }
            .map { it.getAttribute("name") }
            .toSet()

    private fun styleReferences(file: File): Set<String> =
        elements(file)
            .flatMap(::attributeValues)
            .filter { it.startsWith(STYLE_REFERENCE_PREFIX) }
            .map { it.removePrefix(STYLE_REFERENCE_PREFIX) }
            .toSet()

    private fun elements(file: File): List<Element> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("*")
        return List(nodes.length) { nodes.item(it) as Element }
    }

    private fun attributeValues(element: Element): List<String> =
        List(element.attributes.length) { element.attributes.item(it).nodeValue }

    private companion object {
        const val STYLE_REFERENCE_PREFIX = "@style/"
    }
}
