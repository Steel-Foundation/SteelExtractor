package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.block.SoundType
import java.lang.reflect.Modifier

class SoundTypes : SteelExtractor.Extractor {
    override fun fileName(): String {
        return "steel-registry/build_assets/sound_types.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val json = JsonObject()

        // Extract all public static final SoundType fields from SoundType class
        for (field in SoundType::class.java.declaredFields) {
            val modifiers = field.modifiers
            if (Modifier.isPublic(modifiers) &&
                Modifier.isStatic(modifiers) &&
                Modifier.isFinal(modifiers) &&
                field.type == SoundType::class.java
            ) {
                val name = field.name
                val soundType = field.get(null) as SoundType

                val typeJson = JsonObject()
                typeJson.addProperty("volume", soundType.volume)
                typeJson.addProperty("pitch", soundType.pitch)

                // Get sound event IDs from the registry
                typeJson.addProperty("break_sound", getSoundEventId(soundType.breakSound))
                typeJson.addProperty("step_sound", getSoundEventId(soundType.stepSound))
                typeJson.addProperty("place_sound", getSoundEventId(soundType.placeSound))
                typeJson.addProperty("hit_sound", getSoundEventId(soundType.hitSound))
                typeJson.addProperty("fall_sound", getSoundEventId(soundType.fallSound))

                json.add(name, typeJson)
            }
        }

        return json
    }

    private fun getSoundEventId(soundEvent: net.minecraft.sounds.SoundEvent): Int {
        return BuiltInRegistries.SOUND_EVENT.getId(soundEvent)
    }
}
