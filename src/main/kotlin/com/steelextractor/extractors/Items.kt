package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mojang.serialization.JsonOps
import com.steelextractor.SteelExtractor
import net.minecraft.core.component.DataComponentMap
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.RegistryOps
import net.minecraft.server.MinecraftServer
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Items
import net.minecraft.world.item.StandingAndWallBlockItem
import net.minecraft.world.level.block.Block

class Items : SteelExtractor.Extractor {
    override fun fileName(): String {
        return "steel-registry/build_assets/items.json"
    }

    private fun sortJsonObjectByKeys(obj: JsonObject): JsonObject {
        val sorted = JsonObject()
        obj.keySet().sorted().forEach { key ->
            sorted.add(key, obj.get(key))
        }
        return sorted
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val topLevelJson = JsonObject()

        val itemsJson = JsonArray()
        val blockItemMappings = JsonObject()


        for (item in BuiltInRegistries.ITEM) {
            val itemJson = JsonObject()


            itemJson.addProperty("id", BuiltInRegistries.ITEM.getId(item))
            itemJson.addProperty("name", BuiltInRegistries.ITEM.getKey(item).path)

            if (item is BlockItem) {
                itemJson.addProperty("blockItem", BuiltInRegistries.BLOCK.getKey(item.block).path)
            }
            if (item is StandingAndWallBlockItem) {
                val wallBlockField = StandingAndWallBlockItem::class.java.getDeclaredField("wallBlock")
                wallBlockField.isAccessible = true
                itemJson.addProperty(
                    "wallBlock",
                    BuiltInRegistries.BLOCK.getKey(wallBlockField.get(item) as Block).path
                )
            }

            val temp = DataComponentMap.CODEC.encodeStart(
                RegistryOps.create(JsonOps.INSTANCE, server.registryAccess()),
                item.components()
            ).getOrThrow()

            val sortedComponents = if (temp is JsonObject) {
                sortJsonObjectByKeys(temp)
            } else {
                temp
            }

            itemJson.add("components", sortedComponents)

            itemJson.addProperty("class", item.javaClass.simpleName)


            itemsJson.add(itemJson)
        }

        for (block in BuiltInRegistries.BLOCK) {
            val item = block.asItem()
            if (item != Items.AIR) {
                blockItemMappings.addProperty(
                    BuiltInRegistries.BLOCK.getKey(block).path,
                    BuiltInRegistries.ITEM.getKey(item).path
                )
            }
        }

        topLevelJson.add("items", itemsJson)
        topLevelJson.add("blockItemMappings", blockItemMappings)

        return topLevelJson
    }
}
