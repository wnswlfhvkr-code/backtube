package org.schabi.newpipe.util

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ocpsoft.prettytime.PrettyTime

class LocalizationTest {
    @Test
    fun `default content language follows app locale`() {
        assertEquals(
            Locale.KOREAN,
            Localization.resolvePreferredContentLocale("system", "system", Locale.KOREAN)
        )
    }

    @Test
    fun `explicit content language overrides app locale`() {
        assertEquals(
            Locale.forLanguageTag("en-GB"),
            Localization.resolvePreferredContentLocale("en-GB", "system", Locale.KOREAN)
        )
    }

    @Test
    fun `saved app language is used before activity migration`() {
        assertEquals(
            Locale.KOREAN,
            Localization.resolveAppLocale(null, "ko", "system", Locale.ENGLISH)
        )
    }

    @Test
    fun `current app locale overrides an old saved value`() {
        assertEquals(
            Locale.JAPANESE,
            Localization.resolveAppLocale(Locale.JAPANESE, "ko", "system", Locale.ENGLISH)
        )
    }

    @Test
    fun `system choice follows current resource locale`() {
        assertEquals(
            Locale.KOREAN,
            Localization.resolveAppLocale(null, "system", "system", Locale.KOREAN)
        )
    }

    @Test(expected = NullPointerException::class)
    fun `relativeTime() must fail without initializing pretty time`() {
        Localization.relativeTime(OffsetDateTime.of(2021, 1, 6, 0, 0, 0, 0, ZoneOffset.UTC))
    }

    @Test
    fun `relativeTime() with a OffsetDateTime must work`() {
        val prettyTime = PrettyTime(LocalDate.of(2021, 1, 1), ZoneOffset.UTC)
        prettyTime.locale = Locale.ENGLISH
        Localization.initPrettyTime(prettyTime)

        val offset = OffsetDateTime.of(2021, 1, 6, 0, 0, 0, 0, ZoneOffset.UTC)
        val actual = Localization.relativeTime(offset)

        assertEquals("5 days from now", actual)
    }
}
