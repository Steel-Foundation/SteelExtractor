package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory

class MobEffects : SteelExtractor.Extractor {
    private val logger = LoggerFactory.getLogger("steel-extractor-mob-effects")

    override fun fileName(): String {
        return "steel-registry/build_assets/mob_effects.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val effectsArray = JsonArray()

        for (effect in BuiltInRegistries.MOB_EFFECT) {
            val key = BuiltInRegistries.MOB_EFFECT.getKey(effect)
            val name = key?.path ?: "unknown"

            val effectJson = JsonObject()
            val id = BuiltInRegistries.MOB_EFFECT.getId(effect)

            effectJson.addProperty("id", id)
            effectJson.addProperty("name", name)

            try {
                effectJson.addProperty("category", effect.category.name)
                effectJson.addProperty("color", effect.color)
            } catch (e: Exception) {
                logger.warn("Failed to get info for " + name + ": " + e.message)
            }

            effectsArray.add(effectJson)
        }

        return effectsArray
    }
}
