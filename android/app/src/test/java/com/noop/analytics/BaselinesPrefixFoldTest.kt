package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [Baselines.foldHistoryPrefix] — the causal-baseline primitive.
 *
 * The contract is deliberately narrow: element `i` must equal what [Baselines.foldHistory] produces over
 * `values.take(i)`, i.e. the state after every STRICTLY-EARLIER night and nothing else. Pinning it against
 * the existing fold (rather than against hand-copied numbers) means the prefix path can never drift away
 * from the production model it is supposed to be replaying — if someone changes the EWMA, both move together.
 */
class BaselinesPrefixFoldTest {

    private val cfg = Baselines.hrvCfg

    /** A history with a settling trend, a missing night, and an implausible reading (all three paths). */
    private val values: List<Double?> = listOf(
        62.0, 58.0, 71.0, 55.0, null, 60.0, 49.0, 66.0, 3.0, 57.0, 63.0, 52.0,
    )
    private val dayKeys: List<String> = (1..12).map { "2026-07-%02d".format(it) }

    @Test
    fun everyElementEqualsTheFoldOverStrictlyEarlierNights() {
        val prefix = Baselines.foldHistoryPrefix(values, cfg)
        assertEquals("one state per input night", values.size, prefix.size)
        for (i in values.indices) {
            assertEquals(
                "prefix[$i] must be the fold over the first $i nights",
                Baselines.foldHistory(values.take(i), cfg),
                prefix[i],
            )
        }
    }

    @Test
    fun firstElementIsTheEmptyCalibratingSeed() {
        val prefix = Baselines.foldHistoryPrefix(values, cfg)
        // No prior nights → not usable, so the caller refuses to score rather than measuring the first
        // night against itself. This is what makes the cold start honest.
        assertEquals(Baselines.emptyState(cfg), prefix[0])
        assertEquals(0, prefix[0].nValid)
        assertFalse(prefix[0].usable)
    }

    @Test
    fun appendingANightNeverChangesAnEarlierElement() {
        // THE property the whole causal fix rests on: what came before cannot be rewritten by what comes
        // after. Without it a finished day's Charge moves every time the engine re-runs.
        val short = Baselines.foldHistoryPrefix(values, cfg)
        val long = Baselines.foldHistoryPrefix(values + listOf(88.0), cfg)
        for (i in values.indices) {
            assertEquals("appending a night must not touch prefix[$i]", short[i], long[i])
        }
    }

    @Test
    fun emptyHistoryYieldsNoStates() {
        assertEquals(emptyList<BaselineState>(), Baselines.foldHistoryPrefix(emptyList(), cfg))
    }

    @Test
    fun zeroEpochIsIdenticalToThePlainPrefixFold() {
        assertEquals(
            Baselines.foldHistoryPrefix(values, cfg),
            Baselines.foldHistoryPrefix(values, dayKeys, cfg, baselineEpoch = 0.0),
        )
    }

    @Test
    fun recalibrationEpochDropsEarlierNightsButKeepsTheListParallel() {
        // Recalibrate on 2026-07-08: nights before it are DROPPED (not skip-and-hold), so the baseline
        // re-seeds from the 8th — the same rule the day-keyed foldHistory follows.
        val epoch = java.time.LocalDate.parse("2026-07-08")
            .atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond().toDouble()
        val prefix = Baselines.foldHistoryPrefix(values, dayKeys, cfg, epoch)

        assertEquals("stays parallel to the input", values.size, prefix.size)
        // Index 7 is the 8th (the first on-or-after-epoch night), so nothing has been folded yet.
        assertEquals(Baselines.emptyState(cfg), prefix[7])
        // Every element still equals the epoch-aware fold over the strictly-earlier nights.
        for (i in values.indices) {
            assertEquals(
                "prefix[$i] must match the epoch-aware fold over the first $i nights",
                Baselines.foldHistory(values.take(i), dayKeys.take(i), cfg, epoch),
                prefix[i],
            )
        }
    }
}
