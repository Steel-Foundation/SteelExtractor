package com.steelextractor

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.steelextractor.extractors.Attributes
import com.steelextractor.extractors.Classes
import com.steelextractor.extractors.BlockEntities
import com.steelextractor.extractors.Blocks
import com.steelextractor.extractors.Entities
import com.steelextractor.extractors.EntityVariantRegistries
import com.steelextractor.extractors.EntityEvents
import com.steelextractor.extractors.DataComponents
import com.steelextractor.extractors.Fluids
import com.steelextractor.extractors.GameRulesExtractor
import com.steelextractor.extractors.Items
import com.steelextractor.extractors.ParticleTypeRegistryExtractor
import com.steelextractor.extractors.PositionSourceTypeRegistryExtractor
import com.steelextractor.extractors.MenuTypes
import com.steelextractor.extractors.MobEffects
import com.steelextractor.extractors.Packets
import com.steelextractor.extractors.LevelEvents
import com.steelextractor.extractors.SoundEvents
import com.steelextractor.extractors.SoundTypes
import com.steelextractor.extractors.MultiNoiseBiomeParameters
import com.steelextractor.extractors.BiomeHashes
import com.steelextractor.extractors.VillagerProfessionRegistryExtractor
import com.steelextractor.extractors.MapDecorationTypeRegistryExtractor
import com.steelextractor.extractors.VillagerTypeRegistryExtractor
import com.steelextractor.extractors.CandleCakes
import com.steelextractor.extractors.ChunkStageHashes
import com.steelextractor.extractors.CustomStatRegistryExtractor
import com.steelextractor.extractors.GameEvents
import com.steelextractor.extractors.Weathering
import com.steelextractor.extractors.Strippables
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.status.ChunkStatus
import com.steelextractor.extractors.PoiTypesExtractor
import com.steelextractor.extractors.Potions
import com.steelextractor.extractors.StatTypeRegistryExtractor
import com.steelextractor.extractors.StructureStarts
import com.steelextractor.extractors.SuspiciousStewEffects
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
import java.util.concurrent.CompletableFuture
import kotlin.random.Random
import kotlin.system.exitProcess
import kotlin.system.measureTimeMillis

object SteelExtractor : ModInitializer {
    private val logger = LoggerFactory.getLogger("steel-extractor")

    /** Set to false to skip chunk generation and chunk stage hash extraction. */
    private val ENABLE_CHUNK_EXTRACTION = envFlag("STEEL_EXTRACTOR_ENABLE_CHUNK_EXTRACTION")

    /** Set to false to skip storing per-chunk block data in memory and writing binary dump files. */
    private val ENABLE_BINARY_DUMP = envFlag("STEEL_EXTRACTOR_ENABLE_BINARY_DUMP")

    /** Sampling parameters: place random CLUSTER_SIZE x CLUSTER_SIZE clusters within a SAMPLE_HALF_RANGE*2 x SAMPLE_HALF_RANGE*2 area. */
    const val CHUNK_SAMPLE_SEED: Long = 123456
    private const val CLUSTER_SIZE: Int = 10 // 10x10 chunks per cluster
    private const val CHUNKS_PER_CLUSTER: Int = CLUSTER_SIZE * CLUSTER_SIZE
    private const val NUM_CLUSTERS: Int = 25 // 25 clusters * 100 = 2,500 chunks
    const val NUM_SAMPLE_CHUNKS: Int = NUM_CLUSTERS * CHUNKS_PER_CLUSTER
    private const val SAMPLE_HALF_RANGE: Int = 500_000 // 1000,000x1000,000 chunk area
    private const val CARVER_CHUNKS_PER_TICK = CHUNKS_PER_CLUSTER
    private const val FEATURE_CHUNKS_PER_TICK = CHUNKS_PER_CLUSTER
    private const val LIGHT_CHUNKS_PER_TICK = CHUNKS_PER_CLUSTER
    private const val LIGHT_FEATURE_CHUNKS_PER_TICK = CHUNKS_PER_CLUSTER
    private const val LIGHT_CAPTURE_READY_PASSES = 2
    private const val MAX_LIGHT_CAPTURE_WAIT_PASSES = 200
    private const val MAX_LIGHT_CAPTURE_PROBE_SAMPLES = 8
    private val CHUNK_POSITION_ORDER: Comparator<ChunkPos> = compareBy({ it.x }, { it.z })
    private const val DEBUG_CLUSTER_ENV = "STEEL_EXTRACTOR_DEBUG_CLUSTER"
    private const val DEBUG_DIMENSION_ENV = "STEEL_EXTRACTOR_DEBUG_DIMENSION"
    private const val DEBUG_SKIP_IMMEDIATE_ENV = "STEEL_EXTRACTOR_SKIP_IMMEDIATE"
    const val LIGHT_DEPENDENCY_RADIUS: Int = 1

    /** Generate the same sampled chunk clusters used by chunk stage hash extraction. */
    fun sampledChunkClusters(): List<List<ChunkPos>> {
        val rng = Random(CHUNK_SAMPLE_SEED)
        val clusters = mutableListOf<List<ChunkPos>>()
        for (i in 0 until NUM_CLUSTERS) {
            val originX = rng.nextInt(-SAMPLE_HALF_RANGE, SAMPLE_HALF_RANGE)
            val originZ = rng.nextInt(-SAMPLE_HALF_RANGE, SAMPLE_HALF_RANGE)
            val positions = mutableListOf<ChunkPos>()
            for (dx in 0 until CLUSTER_SIZE) {
                for (dz in 0 until CLUSTER_SIZE) {
                    positions.add(ChunkPos(originX + dx, originZ + dz))
                }
            }
            clusters.add(positions)
        }
        return clusters
    }

    /** Generate the same sampled chunk positions used by chunk stage hash extraction. */
    fun sampledChunkPositions(): List<ChunkPos> {
        return sampledChunkClusters().flatten()
    }

    private fun focusedChunkCluster(origin: ChunkPos): List<ChunkPos> {
        val positions = mutableListOf<ChunkPos>()
        for (dx in 0 until CLUSTER_SIZE) {
            for (dz in 0 until CLUSTER_SIZE) {
                positions.add(ChunkPos(origin.x + dx, origin.z + dz))
            }
        }
        return positions
    }

    private fun expandPositions(positions: List<ChunkPos>, radius: Int): List<ChunkPos> {
        val expanded = mutableSetOf<ChunkPos>()
        for (pos in positions) {
            for (dx in -radius..radius) {
                for (dz in -radius..radius) {
                    expanded.add(ChunkPos(pos.x + dx, pos.z + dz))
                }
            }
        }
        return expanded.sortedWith(CHUNK_POSITION_ORDER)
    }

    private fun lightDependencyPositions(positions: List<ChunkPos>): List<ChunkPos> {
        return expandPositions(positions, LIGHT_DEPENDENCY_RADIUS)
    }

    private fun lightFeatureDependencyPositions(positions: List<ChunkPos>): List<ChunkPos> {
        return expandPositions(lightDependencyPositions(positions), 1)
    }

    private fun exceptPositions(positions: List<ChunkPos>, excluded: List<ChunkPos>): List<ChunkPos> {
        val excludedSet = excluded.toSet()
        return positions.filter { !excludedSet.contains(it) }.sortedWith(CHUNK_POSITION_ORDER)
    }

    private fun debugClusterOrigin(): ChunkPos? {
        val value = System.getenv(DEBUG_CLUSTER_ENV)?.takeIf { it.isNotBlank() } ?: return null
        val parts = value.split(",", limit = 2)
        require(parts.size == 2) { "$DEBUG_CLUSTER_ENV must be formatted as '<chunk_x>,<chunk_z>'" }
        val x = parts[0].toIntOrNull()
            ?: error("$DEBUG_CLUSTER_ENV chunk_x is not an integer: ${parts[0]}")
        val z = parts[1].toIntOrNull()
            ?: error("$DEBUG_CLUSTER_ENV chunk_z is not an integer: ${parts[1]}")
        return ChunkPos(x, z)
    }

    private fun envFlag(name: String): Boolean {
        val value = System.getenv(name)?.takeIf { it.isNotBlank() } ?: return false
        return value == "1" || value.equals("true", ignoreCase = true) || value.equals("yes", ignoreCase = true)
    }

    private data class LightCaptureProbe(
        val missingChunks: Int,
        val samples: List<String>
    ) {
        val isReady: Boolean
            get() = missingChunks == 0
    }

    private fun probeLightCaptureReadiness(
        level: ServerLevel,
        positions: List<ChunkPos>,
        lightChunks: Map<ChunkPos, ChunkAccess>
    ): LightCaptureProbe {
        var missingChunks = 0
        val samples = mutableListOf<String>()

        for (pos in positions) {
            val chunk = lightChunks[pos] ?: level.getChunk(pos.x, pos.z, ChunkStatus.LIGHT, false)
            if (chunk == null) {
                missingChunks++
                if (samples.size < MAX_LIGHT_CAPTURE_PROBE_SAMPLES) {
                    samples.add("missing LIGHT chunk (${pos.x}, ${pos.z})")
                }
            }
        }

        return LightCaptureProbe(missingChunks, samples)
    }

    override fun onInitialize() {
        logger.info("Hello Fabric world!")

        val test = BuiltInRegistries.BLOCK.byId(5);
        logger.info(test.toString())

        val test2 = BuiltInRegistries.FLUID.byId(2)
        logger.info(test2.toString())

        // Build immediate extractors list conditionally. To disable a particular extractor,
        // set the environment variable STEEL_EXTRACTOR_DISABLE_<NAME>=1 (or true/yes).
        // Example: STEEL_EXTRACTOR_DISABLE_BLOCKS=1
        val immediateExtractors = mutableListOf<Extractor>()
        fun addUnlessDisabled(name: String, supplier: () -> Extractor) {
            if (envFlag("STEEL_EXTRACTOR_DISABLE_$name")) {
                logger.info("Extractor $name disabled via STEEL_EXTRACTOR_DISABLE_$name")
            } else {
                immediateExtractors.add(supplier())
            }
        }

        addUnlessDisabled("BLOCKS") { Blocks() }
        addUnlessDisabled("BLOCK_ENTITIES") { BlockEntities() }
        addUnlessDisabled("DATA_COMPONENTS") { DataComponents() }
        addUnlessDisabled("ITEMS") { Items() }
        addUnlessDisabled("SUSPICIOUS_STEW_EFFECTS") { SuspiciousStewEffects() }
        addUnlessDisabled("PARTICLE_TYPES") { ParticleTypeRegistryExtractor() }
        addUnlessDisabled("POSITION_SOURCE_TYPES") { PositionSourceTypeRegistryExtractor() }
        addUnlessDisabled("VILLAGER_TYPES") { VillagerTypeRegistryExtractor() }
        addUnlessDisabled("VILLAGER_PROFESSIONS") { VillagerProfessionRegistryExtractor() }
        addUnlessDisabled("PACKETS") { Packets() }
        addUnlessDisabled("MENU_TYPES") { MenuTypes() }
        addUnlessDisabled("ENTITIES") { Entities() }
        addUnlessDisabled("ENTITY_VARIANT_REGISTRIES") { EntityVariantRegistries() }
        addUnlessDisabled("ENTITY_EVENTS") { EntityEvents() }
        addUnlessDisabled("FLUIDS") { Fluids() }
        addUnlessDisabled("GAME_RULES") { GameRulesExtractor() }
        addUnlessDisabled("CLASSES") { Classes() }
        addUnlessDisabled("ATTRIBUTES") { Attributes() }
        addUnlessDisabled("MOB_EFFECTS") { MobEffects() }
        addUnlessDisabled("POTIONS") { Potions() }
        addUnlessDisabled("SOUND_TYPES") { SoundTypes() }
        addUnlessDisabled("MAP_DECORATION_TYPES") { MapDecorationTypeRegistryExtractor() }
        addUnlessDisabled("SOUND_EVENTS") { SoundEvents() }
        addUnlessDisabled("MULTI_NOISE_BIOME_PARAMETERS") { MultiNoiseBiomeParameters() }
        addUnlessDisabled("BIOME_HASHES") { BiomeHashes() }
        addUnlessDisabled("LEVEL_EVENTS") { LevelEvents() }
        addUnlessDisabled("TAGS") { Tags() }
        addUnlessDisabled("STRUCTURE_STARTS") { StructureStarts() }
        addUnlessDisabled("STRIPPABLES") { Strippables() }
        addUnlessDisabled("WEATHERING") { Weathering() }
        addUnlessDisabled("CANDLE_CAKES") { CandleCakes() }
        addUnlessDisabled("WAXABLES") { Waxables() }
        addUnlessDisabled("POI_TYPES") { PoiTypesExtractor() }
        addUnlessDisabled("GAME_EVENTS") { GameEvents() }
        addUnlessDisabled("CUSTOM_STATS") { CustomStatRegistryExtractor() }
        addUnlessDisabled("STAT_TYPES") { StatTypeRegistryExtractor() }

        val chunkStageExtractor = ChunkStageHashes()

        val allDimensions = listOf(
            "minecraft:overworld" to Level.OVERWORLD,
            "minecraft:the_nether" to Level.NETHER,
            "minecraft:the_end" to Level.END
        )

        val debugDimension = System.getenv(DEBUG_DIMENSION_ENV)?.takeIf { it.isNotBlank() }
        val dimensions = if (debugDimension == null) {
            allDimensions
        } else {
            val selected = allDimensions.filter { (dimId, _) -> dimId == debugDimension }
            require(selected.isNotEmpty()) {
                "$DEBUG_DIMENSION_ENV must be one of ${allDimensions.joinToString { it.first }}"
            }
            logger.warn("Focused chunk extraction enabled for dimension $debugDimension")
            selected
        }

        val debugClusterOrigin = debugClusterOrigin()
        val sampledClusters = if (debugClusterOrigin == null) {
            sampledChunkClusters()
        } else {
            logger.warn("Focused chunk extraction enabled for cluster origin (${debugClusterOrigin.x}, ${debugClusterOrigin.z})")
            listOf(focusedChunkCluster(debugClusterOrigin))
        }
        val sampledPositions = sampledClusters.flatten()
        val generationClusters = sampledClusters
            .map { cluster -> cluster.sortedWith(CHUNK_POSITION_ORDER) }
            .sortedWith(compareBy({ it.first().x }, { it.first().z }))
        val clusterCount = generationClusters.size

        if (ENABLE_CHUNK_EXTRACTION) {
            ServerLifecycleEvents.SERVER_STARTING.register { _ ->
                logger.info("Setting up chunk stage hash tracking (${sampledPositions.size} sampled chunks from ${SAMPLE_HALF_RANGE * 2}x${SAMPLE_HALF_RANGE * 2} area, ${dimensions.size} dimensions)")
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
            if (envFlag(DEBUG_SKIP_IMMEDIATE_ENV)) {
                logger.warn("Skipping immediate extractors because $DEBUG_SKIP_IMMEDIATE_ENV is enabled")
            } else {
                val timeInMillis = measureTimeMillis {
                    for (ext in immediateExtractors) {
                        runExtractor(ext, outputDirectory, gson, server)
                    }
                }
                logger.info("Immediate extractors done, took ${timeInMillis}ms")
            }


            if (!ENABLE_CHUNK_EXTRACTION) {
                logger.info("All extractors complete! (chunk extraction skipped)")
                if (envFlag("STEEL_EXTRACTOR_EXIT_ON_COMPLETE")) {
                    logger.info("Exiting because STEEL_EXTRACTOR_EXIT_ON_COMPLETE is enabled")
                    ServerLifecycleEvents.SERVER_STOPPING.invoker().onServerStopping(server);
                    server.halt(false); // false means to do a graceful shutdown
                }
            }
        })

        if (!ENABLE_CHUNK_EXTRACTION) return

        // Build per-dimension chunk queues. Features are order-dependent because
        // they can write into neighboring chunks, so keep the vanilla fixture order
        // aligned with the serialized JSON/test order.
        data class ClusterWork(
            val positions: List<ChunkPos>,
            val lightPositions: List<ChunkPos>,
            val lightFeaturePositions: List<ChunkPos>,
            val carverQueue: ArrayDeque<ChunkPos>,
            val featureQueue: ArrayDeque<ChunkPos>,
            val lightFeatureQueue: ArrayDeque<ChunkPos>,
            val lightQueue: ArrayDeque<ChunkPos>,
            val featureChunks: MutableMap<ChunkPos, ChunkAccess> = mutableMapOf(),
            val lightChunks: MutableMap<ChunkPos, ChunkAccess> = mutableMapOf(),
            var featureHashesCaptured: Boolean = false,
            var lightFeaturesGenerated: Boolean = false,
            var lightHashesCaptured: Boolean = false,
            var pendingLightCaptureBarrier: CompletableFuture<Void>? = null,
            var lightCaptureReadyPasses: Int = 0,
            var lightCaptureWaitPasses: Int = 0
        )

        data class DimensionWork(
            val dimensionKey: ResourceKey<Level>,
            val dimId: String,
            val clusters: ArrayDeque<ClusterWork>,
            var carverProgress: Int = 0,
            var featureProgress: Int = 0,
            var lightFeatureProgress: Int = 0,
            var lightProgress: Int = 0
        )

        val dimWork = dimensions.map { (dimId, key) ->
            val clusters = ArrayDeque<ClusterWork>()
            for (positions in generationClusters) {
                val carverQueue = ArrayDeque<ChunkPos>()
                val featureQueue = ArrayDeque<ChunkPos>()
                carverQueue.addAll(positions)
                featureQueue.addAll(positions)
                val lightPositions = lightDependencyPositions(positions)
                val lightQueue = ArrayDeque<ChunkPos>()
                lightQueue.addAll(lightPositions)
                val lightFeaturePositions = lightFeatureDependencyPositions(positions)
                val lightFeatureQueue = ArrayDeque<ChunkPos>()
                lightFeatureQueue.addAll(exceptPositions(lightFeaturePositions, positions))
                clusters.add(ClusterWork(positions, lightPositions, lightFeaturePositions, carverQueue, featureQueue, lightFeatureQueue, lightQueue))
            }
            DimensionWork(key, dimId, clusters)
        }
        val chunksPerDim = sampledPositions.size
        val lightFeatureChunksPerDim = generationClusters.sumOf { exceptPositions(lightFeatureDependencyPositions(it), it).size }
        val lightChunksPerDim = generationClusters.sumOf { lightDependencyPositions(it).size }
        val totalChunks = chunksPerDim * dimWork.size
        val totalChunkSteps = (chunksPerDim * 2 + lightFeatureChunksPerDim + lightChunksPerDim) * dimWork.size

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
                logger.info("Forcing deterministic generation of $totalChunks chunks across ${dimWork.size} dimensions (carvers $CARVER_CHUNKS_PER_TICK/tick, features $FEATURE_CHUNKS_PER_TICK/tick, light features $LIGHT_FEATURE_CHUNKS_PER_TICK/tick, light $LIGHT_CHUNKS_PER_TICK/tick, order x/z ascending)...")
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

                val cluster = dim.clusters.firstOrNull() ?: run {
                    logger.info("Finished generating chunks for ${dim.dimId}")
                    currentDimIdx++
                    if (currentDimIdx >= dimWork.size) {
                        if (manuallyMarked > 0) {
                            logger.warn("$manuallyMarked chunks were loaded from disk (no intermediate stage hashes). Delete the world folder for full tracking.")
                        }
                        allGenerationDone = true
                        logger.info("All chunk generation complete, waiting for all stages...")
                    }
                    return@register
                }

                val runningLightFeatureQueue = cluster.featureQueue.isEmpty() && cluster.lightFeatureQueue.isNotEmpty()
                val (queue, status, batchSize) = when {
                    cluster.carverQueue.isNotEmpty() -> {
                        Triple(cluster.carverQueue, ChunkStatus.CARVERS, CARVER_CHUNKS_PER_TICK)
                    }
                    cluster.featureQueue.isNotEmpty() -> {
                        Triple(cluster.featureQueue, ChunkStatus.FEATURES, FEATURE_CHUNKS_PER_TICK)
                    }
                    cluster.lightFeatureQueue.isNotEmpty() -> {
                        Triple(cluster.lightFeatureQueue, ChunkStatus.FEATURES, LIGHT_FEATURE_CHUNKS_PER_TICK)
                    }
                    else -> {
                        Triple(cluster.lightQueue, ChunkStatus.LIGHT, LIGHT_CHUNKS_PER_TICK)
                    }
                }

                var generatedThisTick = 0
                while (queue.isNotEmpty() && generatedThisTick < batchSize) {
                    val pos = queue.removeFirst()
                    val chunk = level.getChunk(pos.x, pos.z, status, true)
                    if (status == ChunkStatus.FEATURES) {
                        if (chunk != null) {
                            cluster.featureChunks[pos] = chunk
                        }
                        if (runningLightFeatureQueue) {
                            dim.lightFeatureProgress++
                        } else {
                            dim.featureProgress++
                        }
                    } else if (status == ChunkStatus.LIGHT) {
                        if (chunk != null) {
                            cluster.lightChunks[pos] = chunk
                        }
                        dim.lightProgress++
                    } else {
                        dim.carverProgress++
                    }
                    generatedThisTick++
                }

                if (cluster.carverQueue.isEmpty() && cluster.featureQueue.isEmpty() && !cluster.featureHashesCaptured) {
                    // Mark any feature chunks loaded from disk as ready.
                    for (pos in cluster.positions) {
                        if (ChunkStageHashStorage.markReady(pos, dim.dimId)) {
                            manuallyMarked++
                        }
                    }
                    chunkStageExtractor.captureFinalFeatureHashes(
                        server,
                        dim.dimId,
                        cluster.positions,
                        cluster.featureChunks
                    )
                    cluster.featureHashesCaptured = true
                }

                if (cluster.featureHashesCaptured && cluster.lightFeatureQueue.isEmpty() && !cluster.lightFeaturesGenerated) {
                    cluster.lightFeaturesGenerated = true
                    logger.info("Generated deterministic light dependency features for ${cluster.lightFeaturePositions.size} chunks in ${dim.dimId}")
                }

                val dimProgress = dim.carverProgress + dim.featureProgress + dim.lightFeatureProgress + dim.lightProgress
                val overallProgress = currentDimIdx * (chunksPerDim * 2 + lightFeatureChunksPerDim + lightChunksPerDim) + dimProgress
                val clusterNumber = clusterCount - dim.clusters.size + 1
                logger.info("Chunk generation progress: $overallProgress/$totalChunkSteps (${dim.dimId}: cluster $clusterNumber/$clusterCount, carvers ${dim.carverProgress}/$chunksPerDim, features ${dim.featureProgress}/$chunksPerDim, light features ${dim.lightFeatureProgress}/$lightFeatureChunksPerDim, light ${dim.lightProgress}/$lightChunksPerDim)")

                if (
                    cluster.carverQueue.isEmpty() &&
                    cluster.featureQueue.isEmpty() &&
                    cluster.lightFeatureQueue.isEmpty() &&
                    cluster.lightQueue.isEmpty() &&
                    !cluster.lightHashesCaptured
                ) {
                    val barrier = cluster.pendingLightCaptureBarrier
                    if (barrier == null) {
                        val lightEngine = level.chunkSource.lightEngine
                        val pendingTasks = cluster.lightPositions
                            .map { pos -> lightEngine.waitForPendingTasks(pos.x, pos.z) }
                            .toTypedArray()
                        cluster.pendingLightCaptureBarrier = CompletableFuture.allOf(*pendingTasks)
                        logger.info("All tracked LIGHT chunks are ready for ${dim.dimId}; waiting for pending light tasks before capture")
                        return@register
                    }
                    if (!barrier.isDone) {
                        logger.info("Waiting for pending light tasks before light hash capture (${dim.dimId})")
                        return@register
                    }
                    barrier.join()
                    cluster.pendingLightCaptureBarrier = null
                    if (level.chunkSource.lightEngine.hasLightWork()) {
                        cluster.lightCaptureReadyPasses = 0
                        logger.info("Waiting for light engine idle before light hash capture (${dim.dimId})")
                        return@register
                    }

                    val probe = probeLightCaptureReadiness(level, cluster.lightPositions, cluster.lightChunks)
                    if (!probe.isReady) {
                        cluster.lightCaptureReadyPasses = 0
                        cluster.lightCaptureWaitPasses++
                        if (cluster.lightCaptureWaitPasses > MAX_LIGHT_CAPTURE_WAIT_PASSES) {
                            error(
                                "Light capture for ${dim.dimId} did not stabilize after $MAX_LIGHT_CAPTURE_WAIT_PASSES passes: " +
                                    "missing_chunks=${probe.missingChunks}, samples=${probe.samples}"
                            )
                        }
                        logger.info(
                            "Waiting for light chunk availability before light hash capture (${dim.dimId}): " +
                                "missing_chunks=${probe.missingChunks}, samples=${probe.samples}"
                        )
                        return@register
                    }

                    cluster.lightCaptureReadyPasses++
                    if (cluster.lightCaptureReadyPasses < LIGHT_CAPTURE_READY_PASSES) {
                        logger.info(
                            "Light capture readiness pass ${cluster.lightCaptureReadyPasses}/$LIGHT_CAPTURE_READY_PASSES complete for ${dim.dimId}; waiting one more barrier"
                        )
                        return@register
                    }
                    cluster.lightCaptureWaitPasses = 0

                    chunkStageExtractor.captureFinalLightHashes(
                        server,
                        dim.dimId,
                        cluster.positions,
                        cluster.lightChunks
                    )
                    cluster.lightHashesCaptured = true
                }

                if (
                    cluster.carverQueue.isEmpty() &&
                    cluster.featureQueue.isEmpty() &&
                    cluster.lightFeatureQueue.isEmpty() &&
                    cluster.lightQueue.isEmpty()
                ) {
                    dim.clusters.removeFirst()
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
                    try {
                        chunkStageExtractor.writeBinaryLightData(outputDirectory)
                    } catch (e: java.lang.Exception) {
                        logger.error("Binary light data extraction failed.", e)
                    }
                }
                logger.info("All extractors complete!")
                if (envFlag("STEEL_EXTRACTOR_EXIT_ON_COMPLETE")) {
                    logger.info("Exiting because STEEL_EXTRACTOR_EXIT_ON_COMPLETE is enabled")
                    exitProcess(0);
                }
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
