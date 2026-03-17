package com.steelextractor.extractors

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.block.CandleBlock
import net.minecraft.world.level.block.CandleCakeBlock

class CandleCakes : SteelExtractor.Extractor {
    override fun fileName(): String {
        return "steel-core/build/candle_cakes.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val field = CandleCakeBlock::class.java.getDeclaredField("BY_CANDLE")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST") val BY_CANDLES: Map<CandleBlock, CandleCakeBlock> = field.get(null) as Map<CandleBlock, CandleCakeBlock>
        return JsonObject().apply { BY_CANDLES.forEach { (key, value) -> addProperty(BuiltInRegistries.BLOCK.getKey(key).path, BuiltInRegistries.BLOCK.getKey(value).path, ) } }
    }
}