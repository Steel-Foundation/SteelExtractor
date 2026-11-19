package com.steelextractor.extractors

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.Bootstrap
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.level.material.MapColor
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path

class BlockMapColors : SteelExtractor.Extractor {
    override fun fileName(): String = "steel-registry/build_assets/block_map_colors.json"

    override fun extract(server: MinecraftServer): JsonElement = extractData()

    private fun extractData(): JsonElement {
        val names = MapColor::class.java.fields
            .filter { it.type == MapColor::class.java && Modifier.isStatic(it.modifiers) }
            .associate { (it.get(null) as MapColor) to it.name }
        return JsonObject().apply {
            BuiltInRegistries.BLOCK.forEach { block ->
                val colors = JsonArray()
                block.stateDefinition.possibleStates.forEach { state ->
                    val color = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
                    colors.add(checkNotNull(names[color]) { "Unnamed map color ${color.id}" })
                }
                add(BuiltInRegistries.BLOCK.getKey(block).toString(), colors)
            }
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
            val root = Path.of(System.getenv("STEEL_EXTRACTOR_OUTPUT_DIRECTORY") ?: "steel_extractor_output")
            val extractor = BlockMapColors()
            val output = root.resolve(extractor.fileName())
            Files.createDirectories(output.parent)
            Files.newBufferedWriter(output).use { writer ->
                GsonBuilder().setPrettyPrinting().create().toJson(extractor.extractData(), writer)
            }
            println("Wrote $output")
        }
    }
}
