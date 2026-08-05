package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WORKOUT READ UNION (#1008): the Workouts list, the workout detail trace and the auto-detect scan must
 * all survive a strap re-add / "Make active".
 *
 * Pressing "Make active" on a band whose id isn't the canonical "my-whoop" re-points every NEW write at
 * that band's own id (`whoop-<uuid>`) while the entire earlier history stays banked under "my-whoop".
 * Every read pinned to ONE id therefore lost half the data, in whichever direction it was pinned:
 *
 *  - the Workouts list read the ACTIVE id, so the whole pre-switch WHOOP history fell off the screen;
 *  - the auto-detect scan read the CANONICAL id, so it saw zero HR for the last two days and bailed at
 *    its `hr.size < 2` guard before the detector ever ran — detection was silently dead, not mistuned.
 *
 * [WhoopRepository.workoutsUnion] / [WhoopRepository.detectedWorkoutsUnion] now read the union. This pins
 * the pure seams they are built on ([WhoopRepository.importedSourceIdsFor] /
 * [WhoopRepository.computedSourceIdsFor] for the id order, [WhoopRepository.unionWorkouts] for the merge)
 * so they run on the JVM with no Room, mirroring [MotionReadUnionTest].
 */
class WorkoutReadUnionTest {

    private val canonical = "my-whoop"
    private val reAdded = "whoop-ABC123" // the id a re-added / newly activated band gets (whoop-<uuid>)

    // A session recorded BEFORE the strap switch (canonical lineage) and one AFTER it (active lineage).
    private val preSwitch = 1_753_138_020L
    private val postSwitch = 1_753_310_460L

    private fun row(
        deviceId: String,
        startTs: Long,
        sport: String = "Workout",
        source: String = "manual",
        avgHr: Int? = null,
    ) = WorkoutRow(
        deviceId = deviceId,
        startTs = startTs,
        endTs = startTs + 1_800L,
        sport = sport,
        source = source,
        avgHr = avgHr,
    )

    /** A single-WHOOP install reads the canonical id ALONE — one leg, so the merge must hand back the
     *  very same list instance: byte-identical to the pre-union single-id read, no re-sort, no re-key. */
    @Test
    fun singleDeviceInstallReadsCanonicalIdOnly() {
        assertEquals(listOf("my-whoop"), WhoopRepository.importedSourceIdsFor(canonical))
        assertEquals(listOf("my-whoop-noop"), WhoopRepository.computedSourceIdsFor(canonical))

        val only = listOf(row(canonical, preSwitch), row(canonical, postSwitch))
        assertSame(only, WhoopRepository.unionWorkouts(listOf(only)))
    }

    /** After the switch the imported union is (active, canonical), ACTIVE FIRST — the live lineage wins
     *  any session both cover, matching unionByDay / unionMotionByStart. */
    @Test
    fun activatedBandUnionsWithCanonicalActiveFirst() {
        assertEquals(
            listOf("whoop-ABC123", "my-whoop"),
            WhoopRepository.importedSourceIdsFor(reAdded),
        )
        assertEquals(
            listOf("whoop-ABC123-noop", "my-whoop-noop"),
            WhoopRepository.computedSourceIdsFor(reAdded),
        )
    }

    /** The core regression: the Workouts list spans the switch instead of stopping at it. */
    @Test
    fun sessionsUnderEitherLineageBothSurface() {
        val active = listOf(row(reAdded, postSwitch))
        val history = listOf(row(canonical, preSwitch))

        val merged = WhoopRepository.unionWorkouts(listOf(active, history))

        assertEquals(2, merged.size)
        assertEquals(listOf(preSwitch, postSwitch), merged.map { it.startTs })
    }

    /** Oldest-first ordering is restored ACROSS the seam — concatenating the legs would otherwise emit
     *  the active lineage's newer rows before the canonical lineage's older ones. */
    @Test
    fun mergedRowsAreOldestFirstAcrossTheSeam() {
        val active = listOf(row(reAdded, postSwitch), row(reAdded, postSwitch + 7_200L))
        val history = listOf(row(canonical, preSwitch - 86_400L), row(canonical, preSwitch))

        val merged = WhoopRepository.unionWorkouts(listOf(active, history))

        assertEquals(4, merged.size)
        assertEquals(merged.map { it.startTs }.sorted(), merged.map { it.startTs })
    }

    /** The SAME session re-banked under both lineages (same startTs + sport, the workout PK minus the
     *  deviceId) surfaces ONCE, and the ACTIVE strap's copy is the one kept. */
    @Test
    fun activeLineageWinsWhenBothHoldTheSameSession() {
        val active = listOf(row(reAdded, postSwitch, avgHr = 148))
        val stale = listOf(row(canonical, postSwitch, avgHr = 101))

        val merged = WhoopRepository.unionWorkouts(listOf(active, stale))

        assertEquals(1, merged.size)
        assertEquals(reAdded, merged[0].deviceId)
        assertEquals(148, merged[0].avgHr)
    }

    /** Genuinely distinct sessions are never collapsed: a different start is a different session, and two
     *  sports sharing one start (a lift logged over a run) both survive. */
    @Test
    fun distinctSessionsArePreserved() {
        val active = listOf(
            row(reAdded, postSwitch, sport = "Running"),
            row(reAdded, postSwitch, sport = "Weightlifting"),
        )
        val history = listOf(row(canonical, preSwitch, sport = "Running"))

        val merged = WhoopRepository.unionWorkouts(listOf(active, history))

        assertEquals(3, merged.size)
        assertTrue(merged.any { it.startTs == postSwitch && it.sport == "Running" })
        assertTrue(merged.any { it.startTs == postSwitch && it.sport == "Weightlifting" })
        assertTrue(merged.any { it.startTs == preSwitch && it.sport == "Running" })
    }

    /** Detected bouts ride the COMPUTED union the same way, so a bout detected before the switch stays on
     *  the list next to one detected after it. Exact-start twins collapse here; a twin whose start DRIFTED
     *  as more HR arrived is caught downstream by WorkoutEditing.dedupCrossSource (same sport, >50%
     *  overlap), so neither path can show the same bout twice. */
    @Test
    fun detectedBoutsUnionAcrossComputedLineages() {
        val active = listOf(row("$reAdded-noop", postSwitch, sport = "detected", source = "$reAdded-noop"))
        val history = listOf(row("$canonical-noop", preSwitch, sport = "detected", source = "$canonical-noop"))

        val merged = WhoopRepository.unionWorkouts(listOf(active, history))

        assertEquals(listOf(preSwitch, postSwitch), merged.map { it.startTs })
    }

    /** An empty leg never suppresses the other — the common shape right after a re-add, when the fresh
     *  lineage has no sessions yet and the entire list must still come from the canonical history. */
    @Test
    fun emptyActiveLegKeepsCanonicalHistoryVisible() {
        val merged = WhoopRepository.unionWorkouts(
            listOf(emptyList(), listOf(row(canonical, preSwitch))),
        )
        assertEquals(1, merged.size)
        assertEquals(canonical, merged[0].deviceId)
    }
}
