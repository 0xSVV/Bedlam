package ru.shapovalov.bedlam.core.routing.domain.model

/** ISO-3166-1 alpha-2, normalized to upper case. */
@JvmInline
value class CountryCode(val raw: String) {
    init {
        require(raw.length == 2 && raw.all { it in 'A'..'Z' }) {
            "Invalid ISO country code: $raw"
        }
    }

    companion object {
        fun of(raw: String): CountryCode = CountryCode(raw.uppercase())
        fun ofOrNull(raw: String?): CountryCode? = raw?.let {
            runCatching { of(it) }.getOrNull()
        }
    }
}
