package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.EntityEvent
import kotlin.reflect.full.staticProperties

class EntityEvents : SteelExtractor.Extractor {
    override fun fileName(): String {
        return "steel-utils/build_assets/entity_events.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val topLevelJson = JsonArray()

        val eventJson = JsonObject()
        // In vanilla this event is hardcoded for some reason https://mcsrc.dev/1/26.1-snapshot-8/net/minecraft/world/entity/projectile/arrow/Arrow#L86
        eventJson.addProperty("name", "tipped_arrow")
        eventJson.addProperty("value", 0)
        topLevelJson.add(eventJson)

        for (event in EntityEvent::class.staticProperties) {
            val eventJson = JsonObject()
            eventJson.addProperty("name", event.name.lowercase())
            eventJson.addProperty("value", event.get() as Byte)
            topLevelJson.add(eventJson)
        }
        return topLevelJson
    }
}
