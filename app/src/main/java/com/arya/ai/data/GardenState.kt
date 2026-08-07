package com.arya.ai.data

enum class GrowthStage { EMPTY, SEEDED, SPROUT, GROWN, WILTED }

data class Plot(
    val index: Int,
    val seedName: String? = null,
    val stage: GrowthStage = GrowthStage.EMPTY,
    val waterCount: Int = 0
) {
    val needsWater: Boolean get() = stage == GrowthStage.SEEDED || stage == GrowthStage.SPROUT
    val readyToHarvest: Boolean get() = stage == GrowthStage.GROWN

    fun emoji(): String = when (stage) {
        GrowthStage.EMPTY -> "🟫"
        GrowthStage.SEEDED -> "🌱"
        GrowthStage.SPROUT -> "🌿"
        GrowthStage.GROWN -> "🌸"
        GrowthStage.WILTED -> "🥀"
    }
}

data class GardenState(
    val plots: List<Plot> = (0 until 6).map { Plot(it) },
    val harvestedCount: Int = 0,
    val log: List<String> = emptyList()
) {
    fun withLog(entry: String): GardenState = copy(log = (log + entry).takeLast(20))

    fun plant(plotIndex: Int, seedName: String): GardenState {
        if (plotIndex !in plots.indices) return withLog("❌ Plot $plotIndex exist nahi karta.")
        val plot = plots[plotIndex]
        if (plot.stage != GrowthStage.EMPTY) return withLog("❌ Plot $plotIndex already occupied hai.")
        val updated = plots.toMutableList().apply {
            this[plotIndex] = plot.copy(seedName = seedName, stage = GrowthStage.SEEDED, waterCount = 0)
        }
        return copy(plots = updated).withLog("🌱 Plot $plotIndex me $seedName laga diya.")
    }

    fun water(plotIndex: Int): GardenState {
        if (plotIndex !in plots.indices) return withLog("❌ Plot $plotIndex exist nahi karta.")
        val plot = plots[plotIndex]
        if (!plot.needsWater) return withLog("💧 Plot $plotIndex ko abhi paani ki zaroorat nahi.")
        val newWaterCount = plot.waterCount + 1
        val newStage = when {
            newWaterCount >= 2 && plot.stage == GrowthStage.SPROUT -> GrowthStage.GROWN
            plot.stage == GrowthStage.SEEDED -> GrowthStage.SPROUT
            else -> plot.stage
        }
        val updated = plots.toMutableList().apply {
            this[plotIndex] = plot.copy(waterCount = newWaterCount, stage = newStage)
        }
        val msg = if (newStage == GrowthStage.GROWN) "🌸 Plot $plotIndex khil gaya! Ab harvest kar sakte ho."
                  else "💧 Plot $plotIndex ko paani diya."
        return copy(plots = updated).withLog(msg)
    }

    fun harvest(plotIndex: Int): GardenState {
        if (plotIndex !in plots.indices) return withLog("❌ Plot $plotIndex exist nahi karta.")
        val plot = plots[plotIndex]
        if (!plot.readyToHarvest) return withLog("❌ Plot $plotIndex abhi harvest ke liye ready nahi.")
        val updated = plots.toMutableList().apply {
            this[plotIndex] = Plot(plotIndex)
        }
        return copy(plots = updated, harvestedCount = harvestedCount + 1)
            .withLog("🧺 Plot $plotIndex se ${plot.seedName} harvest kar liya!")
    }
}
