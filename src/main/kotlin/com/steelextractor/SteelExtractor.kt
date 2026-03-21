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
import com.steelextractor.extractors.Strippables
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.status.ChunkStatus
import com.steelextractor.extractors.PoiTypesExtractor
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
import kotlin.random.Random
import kotlin.system.measureTimeMillis

object SteelExtractor : ModInitializer {
    private val logger = LoggerFactory.getLogger("steel-extractor")

    /** Set to false to skip chunk generation and chunk stage hash extraction. */
    private const val ENABLE_CHUNK_EXTRACTION = false

    /** Set to false to skip storing per-chunk block data in memory and writing binary dump files. */
    private const val ENABLE_BINARY_DUMP = false

    /** Sampling parameters: place random CLUSTER_SIZE x CLUSTER_SIZE clusters within a SAMPLE_HALF_RANGE*2 x SAMPLE_HALF_RANGE*2 area. */
    const val CHUNK_SAMPLE_SEED: Long = 13580
    private const val CLUSTER_SIZE: Int = 20 // 20x20 chunks per cluster
    private const val NUM_CLUSTERS: Int = 25 // 25 clusters * 400 = 10,000 chunks
    const val NUM_SAMPLE_CHUNKS: Int = NUM_CLUSTERS * CLUSTER_SIZE * CLUSTER_SIZE
    private const val SAMPLE_HALF_RANGE: Int = 50_000 // 100,000x100,000 chunk area

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
            MultiNoiseBiomeParameters(),
            BiomeHashes(),
            LevelEvents(),
            Tags(),
            StructureStarts(),
            Strippables(),
            Weathering(),
            Waxables(),
            PoiTypesExtractor()
        )


        val chunkStageExtractor = ChunkStageHashes()

        val dimensions = listOf(
            "minecraft:overworld" to Level.OVERWORLD,
            "minecraft:the_nether" to Level.NETHER,
            "minecraft:the_end" to Level.END
        )

        // Pre-compute sampled chunk positions: random cluster origins, each expanded to CLUSTER_SIZE x CLUSTER_SIZE
        val sampledPositions = run {
            val rng = Random(CHUNK_SAMPLE_SEED)
            val positions = mutableListOf<ChunkPos>()
            for (i in 0 until NUM_CLUSTERS) {
                val originX = rng.nextInt(-SAMPLE_HALF_RANGE, SAMPLE_HALF_RANGE)
                val originZ = rng.nextInt(-SAMPLE_HALF_RANGE, SAMPLE_HALF_RANGE)
                for (dx in 0 until CLUSTER_SIZE) {
                    for (dz in 0 until CLUSTER_SIZE) {
                        positions.add(ChunkPos(originX + dx, originZ + dz))
                    }
                }
            }
            positions
        }

        if (ENABLE_CHUNK_EXTRACTION) {
            ServerLifecycleEvents.SERVER_STARTING.register { _ ->
                logger.info("Setting up chunk stage hash tracking ($NUM_SAMPLE_CHUNKS sampled chunks from ${SAMPLE_HALF_RANGE * 2}x${SAMPLE_HALF_RANGE * 2} area, ${dimensions.size} dimensions)")
                val chunksToTrack = mutableSetOf<DimChunkPos>()
                for ((dimId, _) in dimensions) {
                    for (pos in sampledPositions) {
                        chunksToTrack.add(DimChunkPos(pos, dimId))
                    }
                }
                ChunkStageHashStorage.enableBinaryDump = ENABLE_BINARY_DUMP
                ChunkStageHashStorage.startTracking(chunksToTrack)
            }
        } else {
            logger.info("Chunk extraction DISABLED")
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
                    runExtractor(ext, outputDirectory, gson, server)
                }
            }
            logger.info("Immediate extractors done, took ${timeInMillis}ms")


            if (!ENABLE_CHUNK_EXTRACTION) {
                logger.info("All extractors complete! (chunk extraction skipped)")
            }
        })

        if (!ENABLE_CHUNK_EXTRACTION) return

        // Build per-dimension chunk queues
        data class DimensionWork(
            val dimensionKey: ResourceKey<Level>,
            val dimId: String,
            val chunkQueue: ArrayDeque<ChunkPos>
        )

        val dimWork = dimensions.map { (dimId, key) ->
            val queue = ArrayDeque<ChunkPos>()
            for (pos in sampledPositions) {
                queue.add(pos)
            }
            DimensionWork(key, dimId, queue)
        }
        val chunksPerDim = sampledPositions.size
        val totalChunks = chunksPerDim * dimWork.size
        val chunksPerTick = 64

        var generationStarted = false
        var currentDimIdx = 0
        var allGenerationDone = false
        var chunkExtractorDone = false
        var manuallyMarked = 0

        ServerTickEvents.END_SERVER_TICK.register { server ->
            if (chunkExtractorDone) return@register

            // Start generation on first tick after server is ready
            if (!generationStarted) {
                generationStarted = true
                logger.info("Forcing generation of $totalChunks chunks across ${dimWork.size} dimensions ($chunksPerTick per tick)...")
            }

            // Generate a batch of chunks per tick, one dimension at a time
            if (!allGenerationDone) {
                val dim = dimWork[currentDimIdx]
                ChunkStageHashStorage.currentDimension = dim.dimId
                val level: ServerLevel = server.getLevel(dim.dimensionKey) ?: run {
                    logger.warn("Could not get level for ${dim.dimId}, skipping")
                    currentDimIdx++
                    if (currentDimIdx >= dimWork.size) allGenerationDone = true
                    return@register
                }

                var generated = 0
                while (dim.chunkQueue.isNotEmpty() && generated < chunksPerTick) {
                    val pos = dim.chunkQueue.removeFirst()
                    level.getChunk(pos.x, pos.z, ChunkStatus.SURFACE, true)
                    generated++
                }

                val dimProgress = chunksPerDim - dim.chunkQueue.size
                val overallProgress = currentDimIdx * chunksPerDim + dimProgress
                logger.info("Chunk generation progress: $overallProgress/$totalChunks (${dim.dimId}: $dimProgress/$chunksPerDim)")

                if (dim.chunkQueue.isEmpty()) {
                    // Mark any chunks loaded from disk as ready
                    for (pos in sampledPositions) {
                        if (ChunkStageHashStorage.markReady(pos, dim.dimId)) {
                            manuallyMarked++
                        }
                    }
                    logger.info("Finished generating chunks for ${dim.dimId}")
                    currentDimIdx++
                    if (currentDimIdx >= dimWork.size) {
                        if (manuallyMarked > 0) {
                            logger.warn("$manuallyMarked chunks were loaded from disk (no intermediate stage hashes). Delete the world folder for full tracking.")
                        }
                        allGenerationDone = true
                        logger.info("All chunk generation complete, waiting for all stages...")
                    }
                }

                return@register
            }

            // Wait for all chunks to finish all stages
            if (ChunkStageHashStorage.getReadyCount() >= ChunkStageHashStorage.getTrackedCount()) {
                chunkExtractorDone = true
                try {
                    val out = outputDirectory.resolve(chunkStageExtractor.fileName())
                    Files.createDirectories(out.parent)
                    val fileWriter = FileWriter(out.toFile(), StandardCharsets.UTF_8)
                    gson.toJson(chunkStageExtractor.extract(server), fileWriter)
                    fileWriter.close()
                    logger.info("Wrote " + out.toAbsolutePath())
                } catch (e: java.lang.Exception) {
                    logger.error("Extractor for \"${chunkStageExtractor.fileName()}\" failed.", e)
                }
                if (ENABLE_BINARY_DUMP) {
                    try {
                        chunkStageExtractor.writeBinaryBlockData(outputDirectory)
                    } catch (e: java.lang.Exception) {
                        logger.error("Binary block data extraction failed.", e)
                    }
                }
                logger.info("All extractors complete!")
            }
        }
    }

    private fun runExtractor(
        ext: Extractor,
        outputDirectory: Path,
        gson: com.google.gson.Gson,
        server: MinecraftServer
    ) {
        try {
            val out = outputDirectory.resolve(ext.fileName())
            Files.createDirectories(out.parent)
            val fileWriter = FileWriter(out.toFile(), StandardCharsets.UTF_8)
            gson.toJson(ext.extract(server), fileWriter)
            fileWriter.close()
            logger.info("Wrote " + out.toAbsolutePath())
        } catch (e: java.lang.Exception) {
            logger.error("Extractor for \"${ext.fileName()}\" failed.", e)
        }
    }

    interface Extractor {
        fun fileName(): String

        @Throws(Exception::class)
        fun extract(server: MinecraftServer): JsonElement
    }
}
