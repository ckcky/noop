package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure helpers behind the Sleep hero's stage CURVE — the stepped
 * Wake / REM / Light / Deep hypnogram that is now the default hero view — plus the
 * persistence of the view toggle itself.
 *
 * The drawing is Compose and untestable here; everything that decides WHERE a run sits and
 * WHICH hour labels are drawn is pure and pinned below.
 */
class SleepHypnogramCurveTest {

    // MARK: - Stage → row

    @Test
    fun stagesMapToDepthOrderedRows() {
        assertEquals(0, hypnogramLevel("awake"))
        assertEquals(0, hypnogramLevel("wake"))
        assertEquals(1, hypnogramLevel("rem"))
        assertEquals(2, hypnogramLevel("light"))
        assertEquals(3, hypnogramLevel("deep"))
    }

    @Test
    fun stageMatchingIsLenientAndDefaultsToLight() {
        assertEquals(3, hypnogramLevel(" DEEP "))
        assertEquals(1, hypnogramLevel("REM"))
        assertEquals(2, hypnogramLevel("n2"))
        assertEquals(2, hypnogramLevel(""))
    }

    // MARK: - Runs

    @Test
    fun runsTileTheWidthInOrder() {
        val runs = hypnogramRuns(
            listOf("light" to 60f, "deep" to 60f, "rem" to 60f, "awake" to 60f),
        )
        assertEquals(4, runs.size)
        assertEquals(listOf(2, 3, 1, 0), runs.map { it.level })
        assertEquals(0f, runs.first().startFrac, 1e-5f)
        assertEquals(1f, runs.last().endFrac, 1e-5f)
        // Equal weights → equal quarters, laid end-to-end with no gaps.
        runs.forEachIndexed { i, run ->
            assertEquals(i * 0.25f, run.startFrac, 1e-5f)
            assertEquals((i + 1) * 0.25f, run.endFrac, 1e-5f)
        }
    }

    @Test
    fun runsAreProportionalToMinutes() {
        val runs = hypnogramRuns(listOf("light" to 90f, "deep" to 30f))
        assertEquals(2, runs.size)
        assertEquals(0.75f, runs[0].endFrac, 1e-5f)
        assertEquals(0.75f, runs[1].startFrac, 1e-5f)
        assertEquals(1f, runs[1].endFrac, 1e-5f)
    }

    @Test
    fun nonPositiveAndNonFiniteWeightsAreDropped() {
        val runs = hypnogramRuns(
            listOf("light" to 60f, "deep" to 0f, "rem" to -5f, "awake" to Float.NaN, "light" to 60f),
        )
        assertEquals(2, runs.size)
        assertEquals(0.5f, runs[0].endFrac, 1e-5f)
        assertEquals(1f, runs[1].endFrac, 1e-5f)
    }

    @Test
    fun emptyAndZeroTotalYieldNoRuns() {
        assertTrue(hypnogramRuns(emptyList()).isEmpty())
        assertTrue(hypnogramRuns(listOf("light" to 0f, "deep" to 0f)).isEmpty())
    }

    // MARK: - Hour axis

    /** 1970-01-01 23:00 UTC → 1970-01-02 07:00 UTC: the 8-hour night the screenshot draws. */
    private val onset = 82_800L
    private val wake = 82_800L + 8 * 3600L

    @Test
    fun hourlyTicksLandOnWholeHours() {
        val ticks = hypnogramHourTicks(onset, wake, maxTicks = 12, tzOffsetSec = 0L)
        // 23:00 through 07:00 inclusive = 9 whole hours, the first one being onset itself.
        assertEquals(9, ticks.size)
        assertEquals(0f, ticks.first().frac, 1e-5f)
        assertEquals(1f, ticks.last().frac, 1e-5f)
        assertTrue(ticks.all { it.ts % 3600L == 0L })
    }

    @Test
    fun tooManyTicksStepUpToCoarserSpacing() {
        val ticks = hypnogramHourTicks(onset, wake, maxTicks = 7, tzOffsetSec = 0L)
        // 9 hourly ticks exceed the budget → 2-hour spacing: 00:00 · 02:00 · 04:00 · 06:00.
        assertEquals(4, ticks.size)
        assertTrue(ticks.size <= 7)
        assertEquals(0.125f, ticks.first().frac, 1e-5f)
        assertTrue(ticks.all { it.ts % 7200L == 0L })
    }

    @Test
    fun ticksAlignToTheGivenUtcOffsetNotUtc() {
        // Same instants, but read in UTC+1: onset is local midnight, so the first hourly tick IS onset.
        val ticks = hypnogramHourTicks(onset, wake, maxTicks = 12, tzOffsetSec = 3600L)
        assertEquals(0f, ticks.first().frac, 1e-5f)
        assertTrue(ticks.all { (it.ts + 3600L) % 3600L == 0L })
    }

    @Test
    fun ticksStayInsideTheNight() {
        val ticks = hypnogramHourTicks(onset, wake, maxTicks = 12, tzOffsetSec = 0L)
        assertTrue(ticks.all { it.ts in onset..wake })
        assertTrue(ticks.all { it.frac in 0f..1f })
    }

    @Test
    fun degenerateSpansYieldNoTicks() {
        assertTrue(hypnogramHourTicks(onset, onset, maxTicks = 6, tzOffsetSec = 0L).isEmpty())
        assertTrue(hypnogramHourTicks(wake, onset, maxTicks = 6, tzOffsetSec = 0L).isEmpty())
        assertTrue(hypnogramHourTicks(onset, wake, maxTicks = 0, tzOffsetSec = 0L).isEmpty())
    }

    // MARK: - The view toggle

    @Test
    fun curveIsTheDefaultView() {
        assertEquals(HypnogramStyle.CURVE, HypnogramStyle.default)
        assertEquals(HypnogramStyle.CURVE, SleepDisplayPrefs.decodeStyle(null))
    }

    @Test
    fun storedStyleRoundTrips() {
        HypnogramStyle.entries.forEach { style ->
            assertEquals(style, SleepDisplayPrefs.decodeStyle(style.raw))
        }
        assertEquals(HypnogramStyle.STRIP, SleepDisplayPrefs.decodeStyle("  strip "))
    }

    @Test
    fun unknownStoredStyleFallsBackToTheDefault() {
        assertEquals(HypnogramStyle.default, SleepDisplayPrefs.decodeStyle(""))
        assertEquals(HypnogramStyle.default, SleepDisplayPrefs.decodeStyle("pyramid"))
    }
}
