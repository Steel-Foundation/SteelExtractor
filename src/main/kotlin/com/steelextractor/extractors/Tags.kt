package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory

class Tags : SteelExtractor.Extractor {
    private val logger = LoggerFactory.getLogger("steel-extractor-tags")

    override fun fileName(): String {
        return "steel-registry/build_assets/tags.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val topLevelJson = JsonObject()
        val blockTagsJson = JsonObject()

        BuiltInRegistries.BLOCK.getTags().forEach { namedHolderSet ->
            if (namedHolderSet.size() > 0 && namedHolderSet.key().location().namespace != "minecraft") {
                val entriesArray = JsonArray()
                namedHolderSet.stream().forEach { holder ->
                    holder.unwrapKey().ifPresent { key ->
                        entriesArray.add(key.identifier().toString())
                    }
                }
                blockTagsJson.add(namedHolderSet.key().location().toString(), entriesArray)
            }
        }
        topLevelJson.add("block", blockTagsJson)
        BuiltInRegistries.ITEM.getTags().forEach { namedHolderSet ->
            if (namedHolderSet.size() > 0 && namedHolderSet.key().location().namespace != "minecraft") {
                val entriesArray = JsonArray()
                namedHolderSet.stream().forEach { holder ->
                    holder.unwrapKey().ifPresent { key ->
                        entriesArray.add(key.identifier().toString())
                    }
                }
                blockTagsJson.add(namedHolderSet.key().location().toString(), entriesArray)
            }
        }
        topLevelJson.add("item", blockTagsJson)
        return topLevelJson
    }
}