package com.steelextractor

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.steelextractor.extractors.Attributes
import com.steelextractor.extractors.Classes
import com.steelextractor.extractors.BlockEntities
import com.steelextractor.extractors.Blocks
import com.steelextractor.extractors.Entities
import com.steelextractor.extractors.EntityEvents
import com.steelextractor.extractors.Fluids
import com.steelextractor.extractors.GameRulesExtractor
import com.steelextractor.extractors.Items
import com.steelextractor.extractors.MenuTypes
import com.steelextractor.extractors.MobEffects
import com.steelextractor.extractors.Packets
import com.steelextractor.extractors.LevelEvents
import com.steelextractor.extractors.SoundEvents
import com.steelextractor.extractors.SoundTypes
import com.steelextractor.extractors.MultiNoiseBiomeParameters
import com.steelextractor.extractors.BiomeHashes
import com.steelextractor.extractors.ChunkStageHashes
import com.steelextractor.extractors.Weathering
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.status.ChunkStatus
import com.steelextractor.extractors.Potions
import com.steelextractor.extractors.StructureStarts
import com.steelextractor.extractors.Tags
import com.steelextractor.extractors.Waxables
import kotlinx.io.IOException
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory
import java.io.FileWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.measureTimeMillis

object SteelExtractor : ModInitializer {
    private val logger = LoggerFactory.getLogger("steel-extractor")
    private const val TRACKING_RADIUS = 5

    override fun onInitialize() {
        logger.info("Hello Fabric world!")

        val test = BuiltInRegistries.BLOCK.byId(5);
        logger.info(test.toString())

        val test2 = BuiltInRegistries.FLUID.byId(2)
        logger.info(test2.toString())

        val immediateExtractors = arrayOf(
            Blocks(),
            BlockEntities(),
            Items(),
            Packets(),
            MenuTypes(),
            Entities(),
            EntityEvents(),
            Fluids(),
            GameRulesExtractor(),
            Classes(),
            Attributes(),
            MobEffects(),
            Potions(),
            SoundTypes(),
            SoundEvents(),
            SoundTypes(),
            MultiNoiseBiomeParameters(),
            BiomeHashes(),
            LevelEvents(),
            Tags(),
            StructureStarts(),
            Waxables(),
            Weathering(),
        )

        val chunkStageExtractor = ChunkStageHashes()

        ServerLifecycleEvents.SERVER_STARTING.register { _ ->
            logger.info("Setting up chunk stage hash tracking (radius=$TRACKING_RADIUS)")
            val chunksToTrack = mutableSetOf<ChunkPos>()
            for (x in -TRACKING_RADIUS..TRACKING_RADIUS) {
                for (z in -TRACKING_RADIUS..TRACKING_RADIUS) {
                    chunksToTrack.add(ChunkPos(x, z))
                }
            }
            ChunkStageHashStorage.startTracking(chunksToTrack)
        }

        val outputDirectory: Path
        try {
            outputDirectory = Files.createDirectories(Paths.get("steel_extractor_output"))
        } catch (e: IOException) {
            logger.info("Failed to create output directory.", e)
            return
        }

        val gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()

        ServerLifecycleEvents.SERVER_STARTED.register(ServerLifecycleEvents.ServerStarted { server: MinecraftServer ->
            val timeInMillis = measureTimeMillis {
                for (ext in immediateExtractors) {
                    try {
                        val out = outputDirectory.resolve(ext.fileName())
                        val fileWriter = FileWriter(out.toFile(), StandardCharsets.UTF_8)
                        gson.toJson(ext.extract(server), fileWriter)
                        fileWriter.close()
                        logger.info("Wrote " + out.toAbsolutePath())
                    } catch (e: java.lang.Exception) {
                        logger.error(("Extractor for \"" + ext.fileName()) + "\" failed.", e)
                    }
                }
            }
            logger.info("Immediate extractors done, took ${timeInMillis}ms")
            logger.info("Forcing generation of ${(TRACKING_RADIUS * 2 + 1) * (TRACKING_RADIUS * 2 + 1)} chunks...")

            for (level in listOf(server.overworld(), server.getLevel(Level.NETHER), server.getLevel(Level.END))) {
                if (level == null) continue
                for (x in -TRACKING_RADIUS..TRACKING_RADIUS) {
                    for (z in -TRACKING_RADIUS..TRACKING_RADIUS) {
                        level.getChunk(x, z, ChunkStatus.FULL, true)
                    }
                }
            }

            // Mark any chunks that were loaded from disk (not freshly generated)
            // as ready, since the mixin only fires during generation.
            var manuallyMarked = 0
            for (x in -TRACKING_RADIUS..TRACKING_RADIUS) {
                for (z in -TRACKING_RADIUS..TRACKING_RADIUS) {
                    val pos = ChunkPos(x, z)
                    if (ChunkStageHashStorage.markReady(pos)) {
                        manuallyMarked++
                    }
                }
            }
            if (manuallyMarked > 0) {
                logger.warn("$manuallyMarked chunks were loaded from disk (no intermediate stage hashes). Delete the world folder for full tracking.")
            }

            logger.info("Chunk generation forced, waiting for completion...")
        })

        var tickCount = 0
        var chunkExtractorDone = false
        ServerTickEvents.END_SERVER_TICK.register { server ->
            if (chunkExtractorDone) return@register

            tickCount++
            if (tickCount % 100 == 0) {
                logger.info("Waiting for chunks (${ChunkStageHashStorage.getReadyCount()}/${ChunkStageHashStorage.getTrackedCount()} ready)...")
            }

            if (ChunkStageHashStorage.getReadyCount() >= ChunkStageHashStorage.getTrackedCount()) {
                chunkExtractorDone = true
                try {
                    val out = outputDirectory.resolve(chunkStageExtractor.fileName())
                    val fileWriter = FileWriter(out.toFile(), StandardCharsets.UTF_8)
                    gson.toJson(chunkStageExtractor.extract(server), fileWriter)
                    fileWriter.close()
                    logger.info("Wrote " + out.toAbsolutePath())
                } catch (e: java.lang.Exception) {
                    logger.error("Extractor for \"${chunkStageExtractor.fileName()}\" failed.", e)
                }
                logger.info("All extractors complete!")
            }
        }
    }

    interface Extractor {
        fun fileName(): String

        @Throws(Exception::class)
        fun extract(server: MinecraftServer): JsonElement
    }
}
