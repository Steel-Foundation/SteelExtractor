package com.steelextractor.extractors

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.gametest.framework.GameTestServer
import net.minecraft.server.Bootstrap
import net.minecraft.server.packs.repository.ServerPacksSource
import net.minecraft.tags.BiomeTags
import net.minecraft.world.item.MapItem
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.storage.LevelStorageSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional

/** Captures actual MapItem biome-preview pixels in a controlled vanilla level. */
object MapItems {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty())
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
        val directory = Files.createTempDirectory(Path.of("."), "map-fixture-")
        try {
            val storage = LevelStorageSource.createDefault(directory).createAccess("capture")
            val packs = ServerPacksSource.createPackRepository(storage)
            val fixture = GameTestServer.create(Thread.currentThread(), storage, packs, Optional.empty(), false, 1).use { server ->
                val initialize = GameTestServer::class.java.getDeclaredMethod("initServer").apply { isAccessible = true }
                check(initialize.invoke(server) as Boolean)
                val level = server.overworld()
                val biomes = level.registryAccess().lookupOrThrow(Registries.BIOME)
                val ocean = biomes.getOrThrow(Biomes.OCEAN)
                val plains = biomes.getOrThrow(Biomes.PLAINS)
                for (cx in -5..4) {
                    for (cz in -5..4) {
                        level.getChunk(cx, cz).fillBiomesFromNoise { x, _, z ->
                            if (x < -8 || (x < 8 && ((x * 3 + z * 5) and 7) < 4)) ocean else plains
                        }
                    }
                }
                val stack = MapItem.create(level, 0, 0, 0, true, true)
                val data = checkNotNull(MapItem.getSavedData(stack, level))
                val watery = JsonArray()
                for (z in -64 until 64) {
                    for (x in -64 until 64) {
                        watery.add(level.getBiome(BlockPos(x, level.seaLevel, z)).`is`(BiomeTags.WATER_ON_MAP_OUTLINES))
                    }
                }
                MapItem.renderBiomePreviewMap(level, stack)
                JsonObject().apply {
                    addProperty("seed", level.seed)
                    addProperty("scale", data.scale)
                    addProperty("center_x", data.centerX)
                    addProperty("center_z", data.centerZ)
                    add("watery", watery)
                    add("colors", JsonArray().apply { data.colors.forEach { add(it.toInt() and 255) } })
                }
            }
            val outputRoot = Path.of(System.getenv("STEEL_EXTRACTOR_OUTPUT_DIRECTORY") ?: "steel_extractor_output")
            val output = outputRoot.resolve("steel-core/test_assets/vanilla_map_preview.json")
            Files.createDirectories(output.parent)
            Files.newBufferedWriter(output).use { GsonBuilder().setPrettyPrinting().create().toJson(fixture, it) }
            println("Wrote $output")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
