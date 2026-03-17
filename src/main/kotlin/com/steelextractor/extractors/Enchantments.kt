package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.core.registries.Registries
import net.minecraft.server.MinecraftServer

class EnchantmentsExtractor : SteelExtractor.Extractor {
    override fun fileName(): String {
        return "enchantments.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val topLevelJson = JsonObject()
        val enchantmentsJson = JsonArray()

        val enchantmentRegistry = server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)

        for (holder in enchantmentRegistry.listElements().toList()) {
            val key = holder.key()
            val enchantment = holder.value()
            val name = key.identifier().getPath()
            val id = enchantmentRegistry.getId(enchantment)
            val def = enchantment.definition()

            val enchJson = JsonObject()
            enchJson.addProperty("id", id)
            enchJson.addProperty("name", name)
            enchJson.addProperty("max_level", def.maxLevel())
            enchJson.addProperty("anvil_cost", def.anvilCost())
            enchJson.addProperty("weight", def.weight())

            val minCost = JsonObject()
            minCost.addProperty("base", def.minCost().base())
            minCost.addProperty("per_level_above_first", def.minCost().perLevelAboveFirst())
            enchJson.add("min_cost", minCost)

            val maxCost = JsonObject()
            maxCost.addProperty("base", def.maxCost().base())
            maxCost.addProperty("per_level_above_first", def.maxCost().perLevelAboveFirst())
            enchJson.add("max_cost", maxCost)

            val slotsJson = JsonArray()
            for (slot in def.slots()) {
                slotsJson.add(slot.getSerializedName())
            }
            enchJson.add("slots", slotsJson)

            enchantmentsJson.add(enchJson)
        }

        topLevelJson.add("enchantments", enchantmentsJson)

        return topLevelJson
    }
}
