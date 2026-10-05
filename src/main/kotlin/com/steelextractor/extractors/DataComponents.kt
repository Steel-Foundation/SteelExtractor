package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer

/** Extracts the numeric Vanilla data-component registry used by item patches. */
class DataComponents : SteelExtractor.Extractor {
    override fun fileName(): String = "steel-registry/build_assets/data_components.json"

    override fun extract(server: MinecraftServer): JsonElement {
        val components = JsonArray()
        for (component in BuiltInRegistries.DATA_COMPONENT_TYPE) {
            val value = JsonObject()
            value.addProperty("id", BuiltInRegistries.DATA_COMPONENT_TYPE.getId(component))
            value.addProperty("key", BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(component).toString())
            value.addProperty("persistent", !component.isTransient())
            value.addProperty("ignore_swap_animation", component.ignoreSwapAnimation())
            components.add(value)
        }

        return JsonObject().apply { add("components", components) }
    }
}
