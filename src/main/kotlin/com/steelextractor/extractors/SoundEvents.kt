package com.steelextractor.extractors

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer

class SoundEvents : SteelExtractor.Extractor {
    override fun fileName(): String {
        return "steel-registry/build_assets/sound_events.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val json = JsonObject()

        for (soundEvent in BuiltInRegistries.SOUND_EVENT) {
            val key = BuiltInRegistries.SOUND_EVENT.getKey(soundEvent)
            val id = BuiltInRegistries.SOUND_EVENT.getId(soundEvent)

            // Use the path as key (e.g., "block.stone.place" -> "BLOCK_STONE_PLACE")
            val constName = key?.path?.uppercase()?.replace('.', '_') ?: continue
            json.addProperty(constName, id)
        }

        return json
    }
}
