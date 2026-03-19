package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.sounds.SoundEvent
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.material.Fluid
import org.slf4j.LoggerFactory
import java.lang.reflect.Modifier

class Classes : SteelExtractor.Extractor {
    private val logger = LoggerFactory.getLogger("steel-extractor-block-classes")

    override fun fileName(): String {
        return "steel-core/build/classes.json"
    }

    private fun camelToSnake(name: String): String {
        return name.replace(Regex("([a-z0-9])([A-Z])")) {
            "${it.groupValues[1]}_${it.groupValues[2]}"
        }.lowercase()
    }

    /// Serialize a field value into the JSON object based on its type.
    /// Primitives, enums, and registry entries are serialized directly.
    /// Unknown object types are recursed into (one level deep) to extract their fields.
    private fun trySerialize(value: Any, key: String, json: JsonObject, depth: Int) {
        when (value) {
            is Boolean -> json.addProperty(key, value)
            is Number -> json.addProperty(key, value)
            is String -> json.addProperty(key, value)
            is Enum<*> -> json.addProperty(key, value.name.lowercase())
            is Fluid -> BuiltInRegistries.FLUID.getKey(value)?.path?.let { json.addProperty(key, it) }
            is SoundEvent -> BuiltInRegistries.SOUND_EVENT.getKey(value)?.path?.let { json.addProperty(key, it) }
            is Block -> BuiltInRegistries.BLOCK.getKey(value)?.path?.let { json.addProperty(key, it) }
            is Item -> BuiltInRegistries.ITEM.getKey(value)?.path?.let { json.addProperty(key, it) }
            else -> {
                if (depth < 1) {
                    extractDeclaredFields(value, value.javaClass, json, key, depth + 1)
                }
            }
        }
    }

    /// Extract all declared instance fields from the given class on the given object.
    private fun extractDeclaredFields(
        obj: Any, clazz: Class<*>, json: JsonObject, prefix: String = "", depth: Int = 0
    ) {
        for (field in clazz.declaredFields) {
            if (Modifier.isStatic(field.modifiers) || field.isSynthetic) continue
            field.isAccessible = true
            try {
                val value = field.get(obj) ?: continue
                val key = camelToSnake(field.name).let { if (prefix.isEmpty()) it else "${prefix}_$it" }
                trySerialize(value, key, json, depth)
            } catch (_: Exception) {
                // Skip inaccessible fields
            }
        }
    }

    /// Walk up the class hierarchy from the concrete class to (but not including) the stop class,
    /// extracting declared fields at each level.
    private fun extractSubclassFields(obj: Any, stopClass: Class<*>, json: JsonObject) {
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null && clazz != stopClass) {
            extractDeclaredFields(obj, clazz, json)
            clazz = clazz.superclass
        }
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val topLevelJson = JsonObject()

        val blocksJson = JsonArray()
        for (block in BuiltInRegistries.BLOCK) {
            val blockJson = JsonObject()
            blockJson.addProperty("name", BuiltInRegistries.BLOCK.getKey(block)?.path ?: "unknown")
            blockJson.addProperty("class", block.javaClass.simpleName)
            extractSubclassFields(block, Block::class.java, blockJson)
            blocksJson.add(blockJson)
        }
        topLevelJson.add("blocks", blocksJson)

        val itemsJson = JsonArray()
        for (item in BuiltInRegistries.ITEM) {
            val itemJson = JsonObject()
            itemJson.addProperty("name", BuiltInRegistries.ITEM.getKey(item)?.path ?: "unknown")
            itemJson.addProperty("class", item.javaClass.simpleName)
            extractSubclassFields(item, Item::class.java, itemJson)
            itemsJson.add(itemJson)
        }
        topLevelJson.add("items", itemsJson)

        return topLevelJson
    }
}
