package com.steelextractor.extractors

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.block.WeatheringCopper

class Weathering : SteelExtractor.Extractor {
    override fun fileName(): String {
        return "steel-core/build/weathering.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val topLevelJson = JsonObject()

        for ((from, to) in WeatheringCopper.NEXT_BY_BLOCK.get()) {
            topLevelJson.addProperty(BuiltInRegistries.BLOCK.getKey(from).path, BuiltInRegistries.BLOCK.getKey(to).path)
        }

        return topLevelJson
    }
}