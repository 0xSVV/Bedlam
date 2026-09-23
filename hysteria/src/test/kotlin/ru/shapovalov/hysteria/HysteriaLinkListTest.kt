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
    fun `keeps text before the first link as its own entry`() {
        assertEquals(
            listOf("servers:", "hysteria2://a@one.example/"),
            splitHysteriaLinks("servers:\nhysteria2://a@one.example/"),
        )
    }

    @Test
    fun `returns text without a link as one entry`() {
        assertEquals(listOf("not a link"), splitHysteriaLinks("  not a link \n"))
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
