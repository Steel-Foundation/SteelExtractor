package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mojang.serialization.JsonOps
import com.steelextractor.SteelExtractor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.sounds.SoundEvent
import net.minecraft.tags.TagKey
import net.minecraft.util.valueproviders.IntProvider
import net.minecraft.util.valueproviders.IntProviders
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.material.Fluid
import org.slf4j.LoggerFactory
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class Classes : SteelExtractor.Extractor {
    private val logger = LoggerFactory.getLogger("steel-extractor-classes")

    override fun fileName(): String {
        return "steel-core/build/classes.json"
    }

    private fun camelToSnake(name: String): String {
        return name.replace(Regex("([a-z0-9])([A-Z])")) {
            "${it.groupValues[1]}_${it.groupValues[2]}"
        }.lowercase()
    }

    /// Serialize a field value into the JSON object based on its type.
    /// Primitives, value providers, enums, and registry entries are serialized directly.
    /// Unknown object types are recursed into (one level deep) to extract their fields.
    private fun trySerialize(value: Any, key: String, json: JsonObject, depth: Int) {
        when (value) {
            is Boolean -> json.addProperty(key, value)
            is Number -> json.addProperty(key, value)
            is String -> json.addProperty(key, value)
            is IntProvider -> json.add(
                key,
                IntProviders.CODEC.encodeStart(JsonOps.INSTANCE, value).getOrThrow()
            )
            is Enum<*> -> json.addProperty(key, value.name.lowercase())
            is Fluid -> BuiltInRegistries.FLUID.getKey(value)?.path?.let { json.addProperty(key, it) }
            is SoundEvent -> BuiltInRegistries.SOUND_EVENT.getKey(value)?.path?.let { json.addProperty(key, it) }
            is Block -> BuiltInRegistries.BLOCK.getKey(value)?.path?.let { json.addProperty(key, it) }
            is Item -> BuiltInRegistries.ITEM.getKey(value)?.path?.let { json.addProperty(key, it) }
            is EntityType<*> -> BuiltInRegistries.ENTITY_TYPE.getKey(value)?.path?.let { json.addProperty(key, it) }
            is ResourceKey<*> -> json.addProperty(key, value.identifier().path)
            is TagKey<*> -> json.addProperty(key, value.location().path)
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

    private fun itemPath(item: Item): String? {
        return BuiltInRegistries.ITEM.getKey(item).path
    }

    private fun entityJavaClass(entityType: EntityType<*>, entity: Entity?): Class<*> {
        if (entity != null) {
            return entity.javaClass
        }
        if (entityType == EntityTypes.PLAYER) {
            return Player::class.java
        }
        return entityType.baseClass
    }

    @Suppress("UNCHECKED_CAST")
    private fun createEntity(entityType: EntityType<*>, server: MinecraftServer, name: String): Entity? {
        return try {
            (entityType as EntityType<Entity>).create(server.overworld(), EntitySpawnReason.LOAD)
        } catch (e: Exception) {
            logger.warn("Failed to create entity instance for $name: ${e.message}")
            null
        }
    }

    private fun findNoArgMethod(entityClass: Class<*>, methodName: String): Method? {
        var clazz: Class<*>? = entityClass
        while (clazz != null && Entity::class.java.isAssignableFrom(clazz)) {
            for (method in clazz.declaredMethods) {
                val isTargetMethod = method.name == methodName && method.parameterCount == 0
                if (isTargetMethod && !Modifier.isAbstract(method.modifiers)) {
                    method.isAccessible = true
                    return method
                }
            }
            clazz = clazz.superclass
        }
        return null
    }

    private fun extractDropItem(entity: Entity): String? {
        val method = findNoArgMethod(entity.javaClass, "getDropItem") ?: return null
        if (!Item::class.java.isAssignableFrom(method.returnType)) {
            return null
        }

        return try {
            val item = method.invoke(entity) as? Item ?: return null
            itemPath(item)
        } catch (e: Exception) {
            logger.warn("Failed to extract drop item for ${entity.javaClass.name}: ${e.message}")
            null
        }
    }

    private fun extractEntityConstructorData(entity: Entity, json: JsonObject) {
        val dropItem = extractDropItem(entity)
        if (dropItem != null) {
            json.addProperty("drop_item", dropItem)
        }
    }

    private fun extractEntities(server: MinecraftServer): JsonArray {
        val entitiesJson = JsonArray()

        for (entityType in BuiltInRegistries.ENTITY_TYPE) {
            val name = BuiltInRegistries.ENTITY_TYPE.getKey(entityType).path
            val entity = createEntity(entityType, server, name)
            try {
                val entityClass = entityJavaClass(entityType, entity)
                val entityJson = JsonObject()
                entityJson.addProperty("name", name)
                entityJson.addProperty("class", entityClass.simpleName)
                entityJson.addProperty("java_class", entityClass.name)
                if (entity != null) {
                    extractEntityConstructorData(entity, entityJson)
                }
                entitiesJson.add(entityJson)
            } finally {
                entity?.discard()
            }
        }

        return entitiesJson
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

        topLevelJson.add("entities", extractEntities(server))

        return topLevelJson
    }
}
