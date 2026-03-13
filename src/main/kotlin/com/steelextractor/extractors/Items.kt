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
import net.minecraft.world.item.SignItem
import net.minecraft.world.item.StandingAndWallBlockItem
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.SignBlock
import org.slf4j.LoggerFactory
import java.lang.reflect.Field

class Items : SteelExtractor.Extractor {
    private val logger = LoggerFactory.getLogger("steel-extractor-items")

    override fun fileName(): String {
        return "steel-registry/build_assets/items.json"
    }

    fun getConstantName(clazz: Class<*>, value: Any?): String? {
        for (f in clazz.getFields()) {          // only public fields
            try {
                // we expect a static final constant, so no instance needed
                val fieldValue = f.get(null)
                if (fieldValue === value) {           // reference equality is what we want
                    return f.getName()
                }
            } catch (e: IllegalAccessException) {
                // shouldn't happen with getFields(), but ignore it just in case
            }
        }
        return null // no match found
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


        for (item in BuiltInRegistries.ITEM) {
            val itemJson = JsonObject()


            itemJson.addProperty("id", BuiltInRegistries.ITEM.getId(item))
            itemJson.addProperty("name", BuiltInRegistries.ITEM.getKey(item).path)

            if (item is BlockItem) {
                itemJson.addProperty("blockItem", BuiltInRegistries.BLOCK.getKey(item.block).path)
            }
            if (item is StandingAndWallBlockItem) {
                itemJson.addProperty(
                    "standingAndWallBlockItem",
                    BuiltInRegistries.BLOCK.getKey(item.javaClass.getField("wallBlock").get(item) as Block).path
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


        topLevelJson.add("items", itemsJson)

        return topLevelJson
    }
}
