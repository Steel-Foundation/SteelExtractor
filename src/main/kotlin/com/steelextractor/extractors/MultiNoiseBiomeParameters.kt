package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.biome.Climate
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList
import org.slf4j.LoggerFactory

/**
 * Extracts the flattened multi-noise biome source parameter lists for all known presets
 * (overworld and nether). Uses [MultiNoiseBiomeSourceParameterList.knownPresets] to get the
 * final computed parameter lists, rather than extracting intermediate builder tables.
 *
 * Output format:
 * ```json
 * {
 *   "minecraft:overworld": [
 *     { "biome": "minecraft:plains", "parameters": { "temperature": [-0.45, 0.2], ... } },
 *     ...
 *   ],
 *   "minecraft:nether": [ ... ]
 * }
 * ```
 */
class MultiNoiseBiomeParameters : SteelExtractor.Extractor {
    private val logger = LoggerFactory.getLogger("steel-extractor-multi-noise-biome-parameters")

    override fun fileName(): String {
        return "steel-registry/build_assets/multi_noise_biome_source_parameters.json"
    }

    private fun parameterToJson(param: Climate.Parameter): JsonArray {
        val arr = JsonArray()
        arr.add(Climate.unquantizeCoord(param.min()))
        arr.add(Climate.unquantizeCoord(param.max()))
        return arr
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val result = JsonObject()

        for ((preset, parameterList) in MultiNoiseBiomeSourceParameterList.knownPresets()) {
            val entries = JsonArray()

            for (pair in parameterList.values()) {
                val point = pair.first
                val biomeKey = pair.second

                val entry = JsonObject()
                entry.addProperty("biome", biomeKey.identifier().toString())

                val params = JsonObject()
                params.add("temperature", parameterToJson(point.temperature()))
                params.add("humidity", parameterToJson(point.humidity()))
                params.add("continentalness", parameterToJson(point.continentalness()))
                params.add("erosion", parameterToJson(point.erosion()))
                params.add("depth", parameterToJson(point.depth()))
                params.add("weirdness", parameterToJson(point.weirdness()))
                params.addProperty("offset", Climate.unquantizeCoord(point.offset()))

                entry.add("parameters", params)
                entries.add(entry)
            }

            result.add(preset.id().toString(), entries)
        }

        logger.info("Extracted multi-noise biome source parameters for ${result.size()} presets")
        return result
    }
}
