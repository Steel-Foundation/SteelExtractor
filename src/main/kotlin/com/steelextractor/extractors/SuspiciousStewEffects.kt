package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.block.SuspiciousEffectHolder

/** Extracts the effect lists returned by vanilla `SuspiciousEffectHolder` items. */
class SuspiciousStewEffects : SteelExtractor.Extractor {
    override fun fileName(): String {
        return "steel-registry/build_assets/suspicious_stew_effects.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val holders = JsonArray()

        for (item in BuiltInRegistries.ITEM) {
            val effects = SuspiciousEffectHolder.tryGet(item) ?: continue
            val itemKey = BuiltInRegistries.ITEM.getKey(item)
            val holderJson = JsonObject()
            holderJson.addProperty("item", itemKey.toString())

            val effectJson = JsonArray()
            for (entry in effects.suspiciousEffects.effects()) {
                val effectKey = entry.effect()
                    .unwrapKey()
                    .orElseThrow { IllegalStateException("suspicious stew effect has no registry key") }
                    .identifier()
                val entryJson = JsonObject()
                entryJson.addProperty("effect", effectKey.toString())
                entryJson.addProperty("duration", entry.duration())
                effectJson.add(entryJson)
            }
            holderJson.add("effects", effectJson)
            holders.add(holderJson)
        }

        return holders
    }
}
