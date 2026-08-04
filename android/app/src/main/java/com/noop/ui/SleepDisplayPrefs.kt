package com.noop.ui

import android.content.Context

// MARK: - Sleep hero display options
//
// The Sleep hero draws the navigated night's stage architecture. It used to have exactly ONE
// presentation: the proportional stage STRIP — a left→right row of rounded coloured blocks (short
// stages floored to a square, so a fragmented night reads as a row of dots). That strip answers
// "how much of each stage", but it flattens the SHAPE of the night: you can't see the descent into
// deep sleep, the REM rebounds, or the cycles.
//
// This adds a second presentation — the CURVE, the textbook hypnogram: Wake / REM / Light / Deep on
// the y-axis, time on the x-axis, drawn as a stepped trace. It is the DEFAULT; the strip stays one
// tap away. Display-only: both views render the SAME segments the hero already resolved, no metric
// is computed or stored differently.
//
// Persistence follows the KeyMetricPrefs pattern exactly — one string in the shared NoopPrefs
// SharedPreferences ("sleep.hypnogramStyle"), decoded leniently so an unknown/absent value falls back
// to the default rather than crashing.

/**
 * How the Sleep hero draws the night's stages. [raw] is the stable persisted identifier — keep it
 * byte-identical if a macOS/iOS counterpart is added, so a backup/restore reads the same choice.
 */
enum class HypnogramStyle(val raw: String, val label: String) {
    /** The stepped Wake / REM / Light / Deep trace over the night's clock — the classic hypnogram. */
    CURVE("curve", "Curve"),

    /** The proportional coloured stage strip — the original hero presentation. */
    STRIP("strip", "Blocks");

    companion object {
        /** The default presentation for a fresh install / an unrecognised stored value. */
        val default: HypnogramStyle = CURVE

        fun fromRaw(raw: String?): HypnogramStyle? = entries.firstOrNull { it.raw == raw }
    }
}

/**
 * Display-only persistence for the Sleep hero's hypnogram presentation. SharedPreferences isn't
 * reactive, so the Sleep screen reads this once into remembered state (like the other prefs) and
 * writes through on every toggle.
 */
object SleepDisplayPrefs {
    private const val KEY_HYPNOGRAM_STYLE = "sleep.hypnogramStyle"

    /** The stored presentation, or [HypnogramStyle.default] when unset/unrecognised. */
    fun hypnogramStyle(context: Context): HypnogramStyle =
        decodeStyle(NoopPrefs.of(context).getString(KEY_HYPNOGRAM_STYLE, null))

    /** Persist the chosen presentation. */
    fun setHypnogramStyle(context: Context, style: HypnogramStyle) {
        NoopPrefs.of(context).edit().putString(KEY_HYPNOGRAM_STYLE, style.raw).apply()
    }

    /**
     * Decode the stored token. Anything unknown — a value written by a newer build, a corrupted
     * pref, or nothing at all — yields the default, so the hero always has a presentation to draw.
     */
    fun decodeStyle(raw: String?): HypnogramStyle =
        HypnogramStyle.fromRaw(raw?.trim()) ?: HypnogramStyle.default
}
