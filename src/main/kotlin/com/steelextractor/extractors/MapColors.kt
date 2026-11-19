package com.steelextractor.extractors

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.material.MapColor
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path

class MapColors : SteelExtractor.Extractor {
    override fun fileName(): String = "steel-registry/build_assets/map_colors.json"

    override fun extract(server: MinecraftServer): JsonElement = extractData()

    private fun extractData(): JsonElement {
        val colors = JsonArray()
        MapColor::class.java.fields
            .filter { it.type == MapColor::class.java && Modifier.isStatic(it.modifiers) }
            .map { it.name to (it.get(null) as MapColor) }
            .sortedBy { it.second.id }
            .forEach { (name, color) ->
                colors.add(JsonObject().apply {
                    addProperty("name", name)
                    addProperty("id", color.id)
                    addProperty("color", color.col)
                })
            }
        val brightness = JsonArray()
        MapColor.Brightness.entries.forEach { value ->
            brightness.add(JsonObject().apply {
                addProperty("name", value.name)
                addProperty("id", value.id)
                addProperty("modifier", value.modifier)
            })
        }
        return JsonObject().apply {
            add("colors", colors)
            add("brightness", brightness)
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val root = Path.of(System.getenv("STEEL_EXTRACTOR_OUTPUT_DIRECTORY") ?: "steel_extractor_output")
            val extractor = MapColors()
            val output = root.resolve(extractor.fileName())
            Files.createDirectories(output.parent)
            Files.newBufferedWriter(output).use { writer ->
                GsonBuilder().setPrettyPrinting().create().toJson(extractor.extractData(), writer)
            }
            println("Wrote $output")
        }
    }
}
