package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer

class BlockEntities : SteelExtractor.Extractor {
    override fun fileName(): String {
        return "steel-registry/build_assets/block_entities.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val topLevelJson = JsonObject()

        val blockEntitiesJson = JsonArray()
        for (blockEntity in BuiltInRegistries.BLOCK_ENTITY_TYPE) {
            blockEntitiesJson.add(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity)!!.path)
        }

        topLevelJson.add("block_entity_types", blockEntitiesJson)

        return topLevelJson
    }
}
