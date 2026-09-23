package ru.shapovalov.hysteria

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HysteriaLinkListTest {

    @Test
    fun `splits links on separate lines`() {
        val text = "hysteria2://a@one.example/#One\nhy2://b@two.example/#Two\r\n"

        assertEquals(
            listOf("hysteria2://a@one.example/#One", "hy2://b@two.example/#Two"),
            splitHysteriaLinks(text),
        )
    }

    @Test
    fun `splits links separated by spaces and blank lines`() {
        val text = "  hysteria2://a@one.example/#One   \n\n\thysteria2://b@two.example/  "

        assertEquals(
            listOf("hysteria2://a@one.example/#One", "hysteria2://b@two.example/"),
            splitHysteriaLinks(text),
        )
    }

    @Test
    fun `keeps a raw space inside a single link's name`() {
        assertEquals(
            listOf("hysteria2://a@one.example/#My Profile"),
            splitHysteriaLinks("hysteria2://a@one.example/#My Profile\n"),
        )
    }

    @Test
    fun `drops a label line before the links`() {
        assertEquals(
            listOf("hysteria2://a@one.example/", "hysteria2://b@two.example/"),
            splitHysteriaLinks("My servers:\nhysteria2://a@one.example/\nhysteria2://b@two.example/"),
        )
    }

    @Test
    fun `drops a trailing line that is not a link`() {
        assertEquals(
            listOf("hysteria2://pw@h.example/#Home"),
            splitHysteriaLinks("hysteria2://pw@h.example/#Home\nThanks!"),
        )
    }

    @Test
    fun `a trailing line does not leak into the last query value`() {
        val link = splitHysteriaLinks("hysteria2://pw@h.example/?insecure=1\r\nsee you").single()

        assertEquals(true, parseHysteriaUri(link).config.tls.tlsInsecure)
    }

    @Test
    fun `returns text without a link as one entry`() {
        assertEquals(listOf("not a link"), splitHysteriaLinks("  not a link \n"))
    }

    @Test
    fun `returns several lines without a link as one entry`() {
        assertEquals(listOf("not\na link"), splitHysteriaLinks("not\na link\n"))
    }

    @Test
    fun `returns nothing for blank text`() {
        assertEquals(emptyList<String>(), splitHysteriaLinks(" \n "))
    }

    @Test
    fun `each split link parses with its own name`() {
        val names = splitHysteriaLinks("hysteria2://a@one.example/#One hysteria2://b@two.example/#Two")
            .map { parseHysteriaUri(it).name }

        assertEquals(listOf("One", "Two"), names)
    }
}
