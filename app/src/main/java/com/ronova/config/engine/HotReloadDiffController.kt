package com.ronova.config.engine

import com.ronova.config.data.ConfigState
import com.ronova.config.data.ModuleId

data class DeltaStep(
    val moduleId: ModuleId,
    val optionKey: String,
    /** true = run the apply command (new config); false = run the revert command (old config). */
    val apply: Boolean
)

data class ConfigDelta(val steps: List<DeltaStep>) {
    val isEmpty: Boolean get() = steps.isEmpty()
}

/**
 * Turns "old active config -> new toggled config" into the minimal list of commands.
 * Unchanged options are never touched, so ADB, the VPN and the Wi-Fi lock stay up.
 */
class HotReloadDiffController(private val engine: OptimizationEngine) {

    fun computeDelta(oldConfig: ConfigState, newConfig: ConfigState): ConfigDelta {
        val reverts = ArrayList<DeltaStep>()
        val applies = ArrayList<DeltaStep>()
        val packageChanged = oldConfig.targetPackage != newConfig.targetPackage

        for (def in engine.catalog) {
            val was = def.enabled(oldConfig)
            val now = def.enabled(newConfig)
            when {
                was && !now -> reverts += DeltaStep(def.module, def.key, apply = false)
                !was && now -> applies += DeltaStep(def.module, def.key, apply = true)
                was && now && def.fingerprint(oldConfig) != def.fingerprint(newConfig) -> {
                    if (def.pkgDependent && packageChanged) {
                        reverts += DeltaStep(def.module, def.key, apply = false)
                    }
                    applies += DeltaStep(def.module, def.key, apply = true)
                }
            }
        }
        return ConfigDelta(reverts.asReversed() + applies)
    }

    suspend fun hotReloadDiff(oldConfig: ConfigState, newConfig: ConfigState): ApplyReport {
        val delta = computeDelta(oldConfig, newConfig)
        if (delta.isEmpty) return ApplyReport(emptyList(), 0L)
        return engine.applyDelta(delta, oldConfig, newConfig)
    }
}
