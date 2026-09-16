package ru.shapovalov.bedlam.core.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class TimeFormattingTest {

    @ParameterizedTest
    @ValueSource(longs = [-5_000, 0, 59_999])
    fun `less than a minute is just now`(deltaMillis: Long) {
        assertEquals(RelativeAge.JustNow, relativeAge(deltaMillis))
    }

    @ParameterizedTest
    @CsvSource("60000, 1", "119999, 1", "3599999, 59")
    fun `less than an hour counts whole minutes`(deltaMillis: Long, minutes: Int) {
        assertEquals(RelativeAge.Minutes(minutes), relativeAge(deltaMillis))
    }

    @ParameterizedTest
    @CsvSource("3600000, 1", "7199999, 1", "86399999, 23")
    fun `less than a day counts whole hours`(deltaMillis: Long, hours: Int) {
        assertEquals(RelativeAge.Hours(hours), relativeAge(deltaMillis))
    }

    @ParameterizedTest
    @CsvSource("86400000, 1", "172799999, 1", "259200000, 3")
    fun `a day or more counts whole days`(deltaMillis: Long, days: Int) {
        assertEquals(RelativeAge.Days(days), relativeAge(deltaMillis))
    }
}
