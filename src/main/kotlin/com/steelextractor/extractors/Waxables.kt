package com.steelextractor.extractors

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.world.item.HoneycombItem

class Waxables : SteelExtractor.Extractor {
    override fun fileName(): String {
        return "waxables.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val topLevelJson = JsonObject()

        for ((normal, waxed) in HoneycombItem.WAXABLES.get()) {
            topLevelJson.addProperty(BuiltInRegistries.BLOCK.getKey(normal).path, BuiltInRegistries.BLOCK.getKey(waxed).path)
        }

        return topLevelJson
    }
}