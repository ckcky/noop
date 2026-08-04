package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MOTION READ UNION (#407 × #1008): the Sleep tab's per-epoch MOVEMENT strip must survive a strap
 * re-add / "Make active".
 *
 * The engine banks each night's `motionJSON` under the COMPUTED twin of the ACTIVE strap id
 * (`IntelligenceEngine.analyzeRecent` takes `importedDeviceId = <registry active id>` and persists under
 * `"<importedDeviceId>-noop"`). Activating a band whose id isn't the canonical "my-whoop" therefore hands
 * every newly scored night to `"whoop-<uuid>-noop"` while the history stays under `"my-whoop-noop"`. The
 * Sleep tab used to read the canonical id ALONE, so from the switch onward the strip showed the empty
 * state ("No movement detail for this night.") even though the series were on disk , while the hypnogram,
 * whose session read was already unioned, kept drawing. [WhoopRepository.sessionMotions] now reads the
 * union; this pins the pure seams it is built on ([WhoopRepository.computedSourceIdsFor] for the id order,
 * [WhoopRepository.unionMotionByStart] for the merge), so they run on the JVM with no Room.
 */
class MotionReadUnionTest {

    private val canonical = "my-whoop"
    private val reAdded = "whoop-ABC123" // the id a re-added / newly activated band gets (whoop-<uuid>)

    private val tueNight = 1_753_138_020L // a night scored BEFORE the strap switch (canonical id)
    private val thuNight = 1_753_310_460L // a night scored AFTER it (active id)

    private val tueSeries = listOf(0.0, 0.4, 1.2, 0.1)
    private val thuSeries = listOf(0.2, 0.9, 0.3)

    /** A single-WHOOP install reads the canonical computed id ONLY , one source, so the merge is
     *  byte-identical to the pre-union single-id read. */
    @Test
    fun singleDeviceInstallReadsCanonicalComputedIdOnly() {
        assertEquals(listOf("my-whoop-noop"), WhoopRepository.computedSourceIdsFor(canonical))

        val merged = WhoopRepository.unionMotionByStart(
            listOf(mapOf(tueNight to tueSeries)),
        )
        assertEquals(mapOf(tueNight to tueSeries), merged)
    }

    /** After the switch the computed union is (active's sibling, canonical), active FIRST. */
    @Test
    fun activatedBandUnionsWithCanonicalActiveFirst() {
        assertEquals(
            listOf("whoop-ABC123-noop", "my-whoop-noop"),
            WhoopRepository.computedSourceIdsFor(reAdded),
        )
    }

    /** The core regression: nights scored under EITHER computed sibling surface together, so the strip
     *  spans the switch instead of stopping at it. */
    @Test
    fun nightsScoredUnderEitherSiblingBothSurface() {
        // What each id holds after the switch: the fresh id has the new nights, the canonical the history.
        val active = mapOf(thuNight to thuSeries)
        val canonicalHistory = mapOf(tueNight to tueSeries)

        val merged = WhoopRepository.unionMotionByStart(listOf(active, canonicalHistory))

        assertEquals(2, merged.size)
        assertEquals(thuSeries, merged[thuNight])
        assertEquals(tueSeries, merged[tueNight])
    }

    /** Both siblings holding a series for the SAME start (a night re-scored under the new id): the active
     *  strap's copy wins, matching unionByDay's active-wins rule. */
    @Test
    fun activeSiblingWinsWhenBothHoldTheSameStart() {
        val activeCopy = listOf(9.0, 9.5)
        val merged = WhoopRepository.unionMotionByStart(
            listOf(mapOf(thuNight to activeCopy), mapOf(thuNight to thuSeries)),
        )
        assertEquals(activeCopy, merged[thuNight])
    }

    /** An EMPTY series under the active id must not mask a real one banked under the canonical id ,
     *  otherwise the union would reintroduce the very blank the fix removes. */
    @Test
    fun emptyActiveSeriesDoesNotMaskTheCanonicalOne() {
        val merged = WhoopRepository.unionMotionByStart(
            listOf(mapOf(tueNight to emptyList<Double>()), mapOf(tueNight to tueSeries)),
        )
        assertEquals(tueSeries, merged[tueNight])
    }

    /** HONESTY: a start with no series under ANY id stays ABSENT (never a fabricated zero array), so the
     *  Sleep tab still renders its empty state for a night that genuinely has no motion recorded. */
    @Test
    fun startWithNoSeriesAnywhereStaysAbsent() {
        val merged = WhoopRepository.unionMotionByStart(
            listOf(mapOf(tueNight to tueSeries), emptyMap<Long, List<Double>>()),
        )
        assertTrue("only the night that has a series is keyed", merged.keys == setOf(tueNight))
        assertNull(merged[thuNight])
    }
}
