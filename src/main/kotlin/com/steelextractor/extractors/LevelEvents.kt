package com.steelextractor.extractors

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.block.LevelEvent
import java.lang.reflect.Modifier

class LevelEvents : SteelExtractor.Extractor {
    override fun fileName(): String {
        return "steel-registry/build_assets/level_events.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val json = JsonObject()

        // Use reflection to get all public static final int fields from LevelEvent
        for (field in LevelEvent::class.java.declaredFields) {
            val modifiers = field.modifiers
            if (Modifier.isPublic(modifiers) &&
                Modifier.isStatic(modifiers) &&
                Modifier.isFinal(modifiers) &&
                field.type == Int::class.javaPrimitiveType
            ) {
                val name = field.name
                val value = field.getInt(null)
                json.addProperty(name, value)
            }
        }

        return json
    }
}
