package com.noop.analytics

import com.noop.ble.whoopSkinTempFamily
import com.noop.data.DeviceRegistry
import com.noop.data.DeviceStatus
import com.noop.data.SourceKind
import com.noop.data.WhoopRepository
import com.noop.protocol.DeviceFamily

/**
 * [IntelligenceEngine.DayOwnerSource] backed by the [DeviceRegistry] (Phase 1B-4). Supplies the engine
 * with the per-day owner-resolution inputs so a day is scored from exactly ONE device (invariant I2),
 * without giving the pure-JVM engine a Room dependency.
 *
 * Priorities mirror the Swift IntelligenceEngine.resolveDayOwner exactly:
 *   0 = the active strap, 1 = other live (BLE/historyBLE) straps, 2 = imports (cloud/file). Lower wins.
 * Archived devices are excluded. With only the seeded active 'my-whoop' row paired (the default and
 * every single-WHOOP install), the sole candidate is priority 0, so the engine resolves to "my-whoop"
 * for every day and the reads stay byte-identical to the single-source path.
 */
class RegistryDayOwnerSource(private val registry: DeviceRegistry) : IntelligenceEngine.DayOwnerSource {

    override suspend fun candidatePriorities(): List<Pair<String, Int>> {
        val activeId = registry.activeDeviceId()
        val all = registry.all()
        val paired = all
            .filter { it.status != DeviceStatus.archived.name }
            .map { d ->
                val isImport = d.sourceKind == SourceKind.cloudImport.name ||
                    d.sourceKind == SourceKind.fileImport.name
                val priority = when {
                    d.id == activeId -> 0
                    isImport -> 2
                    else -> 1
                }
                d.id to priority
            }
        // IDENTITY FUSION: the canonical legacy lineage has NO pairedDevice row of its own, so it could
        // never become a day owner — the split this fixes. Pressing "Make active" on a strap's REAL id
        // (e.g. "whoop-C5:…") re-points every new raw write at that id while the whole earlier history
        // stays banked under the seeded "my-whoop". Days before the switch then had no candidate holding
        // their data: the resolver fell back to the active id, whose streams are empty for those days, so
        // they scored thin or not at all (and the pass-2 baseline folded ~5 nights instead of ~28).
        //
        // Adding the canonical id as a priority-1 candidate ("another live strap of the same person")
        // lets the EXISTING per-day hasData probe in [IntelligenceEngine.resolveDayOwner] assign each day
        // to whichever lineage actually holds its raw — pre-switch days to "my-whoop", post-switch days to
        // the active id, which outranks it at priority 0 on any overlapping day. Invariant I2 is preserved:
        // a day is still owned, read and scored from exactly ONE source; this only widens the candidate
        // set to include a lineage that was invisible to it.
        //
        // Guarded so nothing else moves: skipped when the active id IS the canonical one (every
        // single-WHOOP install — the resolver then still sees exactly one candidate and takes the #970
        // single-candidate shortcut, byte-identical), when a registry row already carries the canonical id,
        // and when there is no active device at all. Also gated on the active device being a WHOOP: this
        // fuses two lineages of ONE strap, so an active Oura/Garmin/Mi Band must never pull WHOOP history
        // into its days (multi-brand fusion is deliberately out of scope — different sensors and scales).
        val canonical = WhoopRepository.WHOOP_SOURCE
        val activeIsWhoop = activeId != null &&
            all.firstOrNull { it.id == activeId }?.brand == WHOOP_BRAND
        val needsCanonical = activeIsWhoop &&
            activeId != canonical &&
            paired.none { it.first == canonical }
        return if (needsCanonical) paired + (canonical to 1) else paired
    }

    // Any dayOwnership override wins outright, regardless of its `locked` flag — matching the Swift
    // `(try? registry.dayOwner(day))?.deviceId` read in IntelligenceEngine.resolveDayOwner, which uses
    // the stored owner as an authoritative override (the `locked` flag gates the UI, not the read).
    override suspend fun lockedOwner(day: String): String? = registry.dayOwner(day)?.deviceId

    // CAPTURE-B: the registry's active strap id, for the universal dayOwner diagnostic's writeActiveId.
    // This is the SAME id the live read path resolves to (BLEManager/AppModel's activeDeviceId), so the
    // universal line can prove the read owner and the write target are the same device (or surface it
    // when they diverge, the #814/#799 spine symptom).
    override suspend fun activeWriteId(): String? = registry.activeDeviceId()

    // #938: resolve the strap family that wrote [deviceId]'s rows from its registry model, WHEN the model
    // confidently names one (WHOOP 4.0 → WHOOP4 raw-ADC scale; 5/MG → WHOOP5). Returns null — NOT a WHOOP5
    // default — for a bare seeded "WHOOP", an EMPTY registry (a `.noopbak` import frequently has no
    // pairedDevice row at all), a non-WHOOP import, or an absent id: [IntelligenceEngine.analyzeRecent]
    // owns the final fallback, inferring the family from the device's own raw skin-temp magnitude
    // ([AnalyticsEngine.inferSkinTempFamily]) before defaulting, so this must surface "don't know"
    // honestly. [whoopSkinTempFamily] matches the short "4.0"/"5.0 MG" labels the Android wizard persists
    // AND the Swift-parity full labels. Mirrors the Swift IntelligenceEngine.skinTempFamily(forOwner:).
    override suspend fun skinTempFamily(deviceId: String): DeviceFamily? {
        val model = registry.all().firstOrNull { it.id == deviceId }?.model
        return whoopSkinTempFamily(model)
    }

    private companion object {
        /** The `brand` value every WHOOP registry row carries (written by the add-device wizard and the
         *  v7→v8 seed). Gates the canonical-lineage fusion in [candidatePriorities] to WHOOP straps only. */
        const val WHOOP_BRAND = "WHOOP"
    }
}
