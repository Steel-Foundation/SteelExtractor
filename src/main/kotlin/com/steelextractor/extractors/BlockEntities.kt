package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntityType

class BlockEntities : SteelExtractor.Extractor {
    override fun fileName(): String {
        return "steel-registry/build_assets/block_entities.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val topLevelJson = JsonObject()
        val validBlocksField = BlockEntityType::class.java.getDeclaredField("validBlocks")
        validBlocksField.isAccessible = true

        val blockEntitiesJson = JsonArray()
        for (blockEntity in BuiltInRegistries.BLOCK_ENTITY_TYPE) {
            val blockEntityJson = JsonObject()
            val key = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity)
                ?: error("Block entity type has no key: $blockEntity")
            blockEntityJson.addProperty("id", BuiltInRegistries.BLOCK_ENTITY_TYPE.getId(blockEntity))
            blockEntityJson.addProperty("name", key.path)

            @Suppress("UNCHECKED_CAST")
            val validBlocks = validBlocksField.get(blockEntity) as Set<Block>
            val validBlocksJson = JsonArray()
            validBlocks
                .map { BuiltInRegistries.BLOCK.getKey(it)!!.path }
                .sorted()
                .forEach { validBlocksJson.add(it) }
            blockEntityJson.add("valid_blocks", validBlocksJson)

            blockEntitiesJson.add(blockEntityJson)
        }

        topLevelJson.add("block_entity_types", blockEntitiesJson)

        return topLevelJson
    }
}
