package io.github.duwenji.yorisoi.sanpoguide.guide

/**
 * Contact details the AI must not read out (platform ADR-004 H-6 ③): a reply with a URL, an email
 * address or a phone number is not used, and a fixed line stands in.
 */
object OutputCheck {
    private val URL = Regex("https?://|www\\.", RegexOption.IGNORE_CASE)
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+")
    // Japanese numbers start with 0: 03-1234-5678, 0120-123-456, 090 1234 5678, (0467)22-1234, 0312345678.
    private val PHONE = Regex("(?<![0-9])[(]?0[0-9]{1,4}[)]?[-\\s]?[0-9]{1,4}[-\\s]?[0-9]{3,4}(?![0-9])")

    /** What in [text] makes it unusable, or null when it may be used. */
    fun violation(text: String): String? {
        val plain = normalize(text)
        return when {
            URL.containsMatchIn(plain) -> "a URL"
            EMAIL.containsMatchIn(plain) -> "an email address"
            PHONE.containsMatchIn(plain) -> "a phone number"
            else -> null
        }
    }

    /** Full-width digits, letters and signs as their ASCII forms, and every dash as '-'. */
    private fun normalize(text: String): String = buildString(text.length) {
        for (c in text) {
            append(
                when (c) {
                    in '！'..'～' -> c - 0xFEE0
                    '‐', '‑', '‒', '–', '—', '―', 'ー', '−' -> '-'
                    '　' -> ' '
                    else -> c
                },
            )
        }
    }
}
