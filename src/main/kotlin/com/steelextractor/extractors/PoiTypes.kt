package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
class PoiTypesExtractor : SteelExtractor.Extractor {
    override fun fileName(): String {
        return "steel-registry/build_assets/poi_types.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val topLevelJson = JsonObject()
        val poiTypesJson = JsonArray()

        val poiTypeRegistry = BuiltInRegistries.POINT_OF_INTEREST_TYPE

        for (poiType in poiTypeRegistry) {
            val key = poiTypeRegistry.getKey(poiType)
            val name = key?.path ?: "unknown"
            val id = poiTypeRegistry.getId(poiType)

            val poiJson = JsonObject()
            poiJson.addProperty("id", id)
            poiJson.addProperty("name", name)
            poiJson.addProperty("ticket_count", poiType.maxTickets())
            poiJson.addProperty("valid_range", poiType.validRange())

            val blockStatesJson = JsonArray()
            for (blockState in poiType.matchingStates()) {
                val block = blockState.block
                val blockKey = BuiltInRegistries.BLOCK.getKey(block)
                val blockStateId = net.minecraft.world.level.block.Block.getId(blockState)

                val stateJson = JsonObject()
                stateJson.addProperty("block", blockKey?.path ?: "unknown")
                stateJson.addProperty("state_id", blockStateId)

                if (blockState.values.isNotEmpty()) {
                    val propsJson = JsonObject()
                    for ((prop, value) in blockState.values) {
                        @Suppress("UNCHECKED_CAST")
                        val propTyped = prop as net.minecraft.world.level.block.state.properties.Property<Comparable<Any>>
                        propsJson.addProperty(prop.name, propTyped.getName(value as Comparable<Any>))
                    }
                    stateJson.add("properties", propsJson)
                }

                blockStatesJson.add(stateJson)
            }
            poiJson.add("block_states", blockStatesJson)

            poiTypesJson.add(poiJson)
        }

        topLevelJson.add("poi_types", poiTypesJson)

        return topLevelJson
    }
}
