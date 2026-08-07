package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * A finished day's Charge must not move when a LATER night lands.
 *
 * The reported symptom: Tue 28 Jul read Charge 54 on the evening of the 28th and Charge 45 the next day,
 * off identical HRV / resting-HR / respiration / Rest. Nothing about the 28th had changed — the engine
 * folded the WHOLE history (including the night of 28→29) into one terminal baseline and re-scored every
 * day in the 21-day window against it, so a better night on the 29th raised the baseline and retroactively
 * demoted the 28th. Because that rewrite happens on every analyze pass (the 15-minute backstop, every
 * offload chunk, every sleep edit), the stored score kept moving.
 *
 * [IntelligenceEngine.PriorBaselines] is the fix: it resolves the baseline as it stood STRICTLY BEFORE a
 * given day, so a day's score is a pure function of its own night plus earlier ones — causal, and
 * idempotent under re-running.
 *
 * These tests drive the real lookup and the real [RecoveryScorer], so they fail on the pre-fix engine.
 */
class ChargeCausalStabilityTest {

    private val hrvCfg = Baselines.hrvCfg
    private val rhrCfg = Baselines.restingHRCfg

    // Three settled weeks, then the day under test (28 Jul) and the better night that followed it.
    private val days: List<String> = (8..28).map { "2026-07-%02d".format(it) }
    private val hrv: List<Double?> = List(20) { 55.0 } + listOf(52.0)   // 28 Jul: 52 ms, the screenshot
    private val rhr: List<Double?> = List(20) { 47.0 } + listOf(44.0)   // 28 Jul: 44 bpm

    private val nextDay = "2026-07-29"
    private val nextHrv = 78.0   // a notably better night — the thing that demoted the 28th
    private val nextRhr = 41.0

    /** Charge for 28 Jul against a given baseline pair, with the screenshot's inputs. */
    private fun chargeFor28th(hrvBase: BaselineState, rhrBase: BaselineState?): Double? =
        RecoveryScorer.recovery(
            hrv = 52.0,
            rhr = 44.0,
            resp = null,
            hrvBaseline = RecoveryScorer.DriverBaseline(hrvBase),
            rhrBaseline = rhrBase?.let { RecoveryScorer.DriverBaseline(it) },
            respBaseline = null,
            sleepPerf = 0.77,
            hrvBaselineUsable = hrvBase.usable,
        )

    @Test
    fun theBaselineForADayIgnoresThatDayAndEverythingAfterIt() {
        val prior = IntelligenceEngine.PriorBaselines(days, hrv, hrvCfg)
        // The 28th is the last key, so its prior baseline is the fold over the 20 nights before it.
        assertEquals(Baselines.foldHistory(hrv.dropLast(1), hrvCfg), prior.before("2026-07-28"))
        // And the first day has nothing before it at all.
        assertEquals(Baselines.emptyState(hrvCfg), prior.before("2026-07-08"))
    }

    @Test
    fun chargeForAFinishedDayIsUnchangedWhenTheNextNightLands() {
        val before = IntelligenceEngine.PriorBaselines(days, hrv, hrvCfg)
        val beforeRhr = IntelligenceEngine.PriorBaselines(days, rhr, rhrCfg)
        val scoredOnThe28th = chargeFor28th(before.before("2026-07-28"), beforeRhr.before("2026-07-28"))

        // The night of 28→29 lands and the engine re-scores the window, exactly as it does every 15 minutes.
        val after = IntelligenceEngine.PriorBaselines(days + nextDay, hrv + nextHrv, hrvCfg)
        val afterRhr = IntelligenceEngine.PriorBaselines(days + nextDay, rhr + nextRhr, rhrCfg)
        val rescoredOnThe29th = chargeFor28th(after.before("2026-07-28"), afterRhr.before("2026-07-28"))

        assertNotNull("the 28th must score at all", scoredOnThe28th)
        assertEquals(
            "a finished day's Charge must survive the next night landing",
            scoredOnThe28th!!,
            rescoredOnThe29th!!,
            0.0,
        )
    }

    @Test
    fun theOldWholeHistoryFoldWouldHaveMovedIt() {
        // Guards the test itself: if this stopped differing, the test above would pass vacuously and stop
        // protecting anything. This is the pre-fix behaviour — one terminal fold over ALL nights, re-applied
        // to every day in the window — and it is exactly what produced 54 → 45.
        val terminalOnThe28th = chargeFor28th(
            Baselines.foldHistory(hrv, hrvCfg),
            Baselines.foldHistory(rhr, rhrCfg),
        )!!
        val terminalOnThe29th = chargeFor28th(
            Baselines.foldHistory(hrv + nextHrv, hrvCfg),
            Baselines.foldHistory(rhr + nextRhr, rhrCfg),
        )!!
        // It drifts DOWNWARD after a better night — the direction the user reported (54 → 45).
        assertTrue(
            "a better following night lowered the earlier day's Charge",
            terminalOnThe29th < terminalOnThe28th,
        )
        assertTrue(
            "the drift must be visible on screen, not a rounding wobble, else this suite proves nothing",
            abs(terminalOnThe28th - terminalOnThe29th) > 1.0,
        )
    }

    @Test
    fun rescoringTheSameHistoryTwiceIsAnExactNoOp() {
        // Why the 15-minute pass is now harmless: re-running it reproduces the identical number, so the
        // stored value stops changing even though the engine keeps recomputing it.
        val first = IntelligenceEngine.PriorBaselines(days, hrv, hrvCfg)
        val second = IntelligenceEngine.PriorBaselines(days, hrv, hrvCfg)
        for (d in days) assertEquals(first.before(d), second.before(d))
    }

    @Test
    fun aDayAfterEveryKnownNightFallsBackToTheFullFold() {
        // Defensive path: a day that sorts past every key (no entry of its own) is scored against the whole
        // history, which is still strictly-prior for that day.
        val prior = IntelligenceEngine.PriorBaselines(days, hrv, hrvCfg)
        assertEquals(Baselines.foldHistory(hrv, hrvCfg), prior.before("2026-08-15"))
    }

    /**
     * Causality alone is not enough: the SET of nights feeding the baseline has to be stable too.
     *
     * Real failure this pins (from a user's strap log, app 8.2.32.78): consecutive analyze passes over the
     * SAME static database scored 12 days in one pass and 21 in the next, because three of the five
     * `analyzeRecent` callers omitted `ownerSource`. Without it every day resolves to the ACTIVE strap id,
     * so on an install whose raw history splits across two lineages the pre-switch days read an empty
     * stream, failed the MIN_HR_SAMPLES gate, and vanished from the fold entirely — not held flat as a
     * missing night, absent. The trace showed day 2026-07-31 folding nValid=9 on the short passes; the long
     * passes folded roughly twice that. The two pass types then rewrote each other's Charge every few
     * minutes (27 ↔ 30, 51 ↔ 54) with every displayed input identical.
     *
     * This test does not exercise the wiring (that lives in the callers) — it pins the CONSEQUENCE, so the
     * sensitivity is documented and measurable rather than argued about.
     */
    @Test
    fun droppingEarlierNightsFromTheFoldMovesALaterDaysCharge() {
        val shortHistory = days                       // 21 keys, the settled recent stretch
        val shortHrv = hrv
        // The same recent stretch PRECEDED by nine earlier, lower nights — the days a pass without an
        // owner source silently drops.
        val earlyDays = (1..9).map { "2026-06-%02d".format(it) }
        val earlyHrv: List<Double?> = List(9) { 44.0 }

        val fromShort = IntelligenceEngine.PriorBaselines(shortHistory, shortHrv, hrvCfg)
            .before("2026-07-28")
        val fromLong = IntelligenceEngine.PriorBaselines(earlyDays + shortHistory, earlyHrv + shortHrv, hrvCfg)
            .before("2026-07-28")

        // Same day, same inputs, different fold membership → a different baseline …
        assertTrue(
            "dropping earlier nights must change how many count toward the baseline",
            fromShort.nValid != fromLong.nValid,
        )
        // … and therefore a different Charge, which is exactly what the user saw flipping.
        val chargeShort = chargeFor28th(fromShort, null)!!
        val chargeLong = chargeFor28th(fromLong, null)!!
        assertTrue(
            "an unstable fold moves the score visibly, not by a rounding wobble",
            abs(chargeShort - chargeLong) > 1.0,
        )
    }

    /**
     * The sliding-window drift, pinned.
     *
     * Real failure (user's log, app 8.2.32.80): every pass scanned exactly `2026-07-12 .. 2026-08-01`,
     * the trailing 21 days counted back from today. With no imported rows, a night was in the baseline
     * only because that pass scanned it — so at midnight the window became `07-13 .. 08-02`, the oldest
     * night fell out, the fold re-seeded on a different first night, and every stored score moved ~1
     * point. Confirmed twice over: scores shifted +0.5..+1.0 across the whole window overnight, and the
     * "Calibrating" boundary walked forward day by day (16 Jul, then 21 Jul five days later) instead of
     * staying pinned to the start of the history.
     *
     * The fix reads the nights from the persisted record rather than from the scan window. This test pins
     * the property that makes that necessary: drop the oldest night, and a much later day moves.
     */
    @Test
    fun droppingTheOldestNightMovesAMuchLaterDaysBaseline() {
        // A history that actually varies — a flat one would make this pass vacuously, since dropping a
        // night identical to its neighbours moves nothing.
        val varied: List<Double?> = listOf(
            64.0, 61.0, 58.0, 57.0, 55.0, 59.0, 53.0, 56.0, 54.0, 52.0,
            57.0, 51.0, 55.0, 53.0, 50.0, 54.0, 49.0, 52.0, 48.0, 51.0, 47.0,
        )
        val full = IntelligenceEngine.PriorBaselines(days, varied, hrvCfg).before("2026-07-28")
        // Same history with the first night gone — exactly what one midnight does to a trailing window.
        val slid = IntelligenceEngine.PriorBaselines(days.drop(1), varied.drop(1), hrvCfg).before("2026-07-28")

        assertTrue(
            "losing the oldest night must change how many count toward a later day's baseline",
            full.nValid != slid.nValid,
        )
        // The seed carries real weight: at a 14-night half-life it still holds ~35% after 21 nights, so
        // re-seeding is not a rounding effect.
        assertTrue(
            "re-seeding the fold visibly moves a later day's baseline",
            abs(full.baseline - slid.baseline) > 0.01,
        )
    }

    @Test
    fun anEmptyHistoryIsNotUsableSoNoScoreIsFabricated() {
        val prior = IntelligenceEngine.PriorBaselines(emptyList(), emptyList(), hrvCfg)
        val state = prior.before("2026-07-28")
        assertFalse(state.usable)
        assertNull(chargeFor28th(state, null))
    }
}
