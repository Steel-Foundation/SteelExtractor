package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.ai.attributes.RangedAttribute
import org.slf4j.LoggerFactory

class Attributes : SteelExtractor.Extractor {
    private val logger = LoggerFactory.getLogger("steel-extractor-attributes")

    override fun fileName(): String {
        return "steel-registry/build_assets/attributes.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val attributesArray = JsonArray()

        for (attribute in BuiltInRegistries.ATTRIBUTE) {
            val key = BuiltInRegistries.ATTRIBUTE.getKey(attribute)
            val name = key?.path ?: "unknown"

            val attributeJson = JsonObject()
            val id = BuiltInRegistries.ATTRIBUTE.getId(attribute)

            attributeJson.addProperty("id", id)
            attributeJson.addProperty("name", name)

            try {
                attributeJson.addProperty("translation_key", attribute.descriptionId)
                attributeJson.addProperty("default_value", attribute.defaultValue)
                attributeJson.addProperty("syncable", attribute.isClientSyncable)

                // Get min/max if this is a RangedAttribute
                if (attribute is RangedAttribute) {
                    attributeJson.addProperty("min_value", attribute.minValue)
                    attributeJson.addProperty("max_value", attribute.maxValue)
                }
            } catch (e: Exception) {
                logger.warn("Failed to get info for " + name + ": " + e.message)
            }

            attributesArray.add(attributeJson)
        }

        return attributesArray
    }
}
