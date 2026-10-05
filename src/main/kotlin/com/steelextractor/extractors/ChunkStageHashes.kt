package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.ChunkStageHashStorage
import com.steelextractor.LightSectionData
import com.steelextractor.SteelExtractor
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.status.ChunkStatus
import org.slf4j.LoggerFactory
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream

class ChunkStageHashes : SteelExtractor.Extractor {
    private val logger = LoggerFactory.getLogger("steel-extractor-chunk-stage-hashes")

    override fun fileName(): String {
        return "steel-core/test_assets/chunk_stage_hashes.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val json = JsonObject()

        val worldSeed = server.overworld().seed
        json.addProperty("seed", worldSeed)
        json.addProperty("chunk_sample_seed", SteelExtractor.CHUNK_SAMPLE_SEED)
        json.addProperty("num_chunks", SteelExtractor.NUM_SAMPLE_CHUNKS)
        json.addProperty("chunk_generation_order", "x_z_ascending")
        json.addProperty("feature_hash_capture", "after_all_tracked_features_ready")
        json.addProperty("biome_tie_breaker", "vanilla_thread_local_rtree")
        json.addProperty("hashset_iteration_order", "vanilla")
        json.addProperty("light_hash_capture", "after_all_tracked_light_ready_pending_tasks_drained_and_light_engine_idle")
        json.addProperty("light_dependency_radius", SteelExtractor.LIGHT_DEPENDENCY_RADIUS)
        json.addProperty("light_feature_dependency_capture", "after_tracked_features_before_light_x_z_ascending")
        json.addProperty("light_binary_format", "packet_data_layers_and_sky_sources_binary_v1")
        json.addProperty("light_hash_format", "packet_data_layers_v1")

        if (worldSeed != 13579L) {
            logger.warn("World seed is $worldSeed, not 13579! Set level-seed=13579 in server.properties and delete the world folder.")
        }

        val allHashes = ChunkStageHashStorage.getAllHashes()

        val dimensionsJson = JsonObject()
        val dimGroups = allHashes.entries.groupBy { it.key.first.dimension }

        for ((dimension, dimEntries) in dimGroups.toSortedMap()) {
            val dimJson = JsonObject()
            val chunkGroups = dimEntries.groupBy { it.key.first.pos }

            val chunksArray = JsonArray()
            for ((pos, entries) in chunkGroups.toSortedMap(compareBy({ it.x }, { it.z }))) {
                val chunkJson = JsonObject()
                chunkJson.addProperty("x", pos.x)
                chunkJson.addProperty("z", pos.z)

                val stagesJson = JsonObject()
                for ((key, hash) in entries.sortedBy { it.key.second }) {
                    val stageName = key.second
                    stagesJson.addProperty(stageName, hash)
                }
                chunkJson.add("stages", stagesJson)

                chunksArray.add(chunkJson)
            }

            dimJson.add("chunks", chunksArray)
            dimJson.addProperty("chunk_count", chunkGroups.size)
            dimensionsJson.add(dimension, dimJson)

            logger.info("Extracted chunk stage hashes for ${chunkGroups.size} chunks in $dimension")
        }

        json.add("dimensions", dimensionsJson)
        return json
    }

    fun captureFinalFeatureHashes(
        server: MinecraftServer,
        dimension: String,
        positions: Collection<ChunkPos>,
        featureChunks: Map<ChunkPos, ChunkAccess>
    ) {
        val positionSet = positions.toSet()
        val trackedChunks = ChunkStageHashStorage.getTrackedChunks()
            .filter { it.dimension == dimension && positionSet.contains(it.pos) }
            .sortedWith(compareBy({ it.pos.x }, { it.pos.z }))
        if (trackedChunks.isEmpty()) {
            return
        }

        val levelKey = when (dimension) {
            "minecraft:overworld" -> Level.OVERWORLD
            "minecraft:the_nether" -> Level.NETHER
            "minecraft:the_end" -> Level.END
            else -> {
                logger.warn("Cannot capture final feature hashes for unknown dimension $dimension")
                return
            }
        }
        val level = server.getLevel(levelKey) ?: run {
            logger.warn("Cannot capture final feature hashes for missing level $dimension")
            return
        }

        var captured = 0
        for (tracked in trackedChunks) {
            val chunk = featureChunks[tracked.pos] ?: level.getChunk(tracked.pos.x, tracked.pos.z, ChunkStatus.FEATURES, false)
            if (chunk == null) {
                logger.warn("Tracked chunk ${tracked.pos} in $dimension is not loaded at FEATURES; skipping final feature hash")
                continue
            }

            if (ChunkStageHashStorage.enableBinaryDump) {
                val result = ChunkStageHashStorage.computeBlockHashWithData(chunk.sections.asIterable())
                ChunkStageHashStorage.storeHash(tracked.pos, dimension, ChunkStatus.FEATURES.toString(), result.hash)
                ChunkStageHashStorage.storeBlockData(tracked.pos, dimension, ChunkStatus.FEATURES.toString(), result.sectionData)
            } else {
                val hash = ChunkStageHashStorage.computeBlockHash(chunk.sections.asIterable())
                ChunkStageHashStorage.storeHash(tracked.pos, dimension, ChunkStatus.FEATURES.toString(), hash)
            }
            captured++
        }
        logger.info("Captured final feature hashes for $captured/${trackedChunks.size} chunks in $dimension")
    }

    fun captureFinalLightHashes(
        server: MinecraftServer,
        dimension: String,
        positions: Collection<ChunkPos>,
        lightChunks: Map<ChunkPos, ChunkAccess>
    ) {
        val positionSet = positions.toSet()
        val trackedChunks = ChunkStageHashStorage.getTrackedChunks()
            .filter { it.dimension == dimension && positionSet.contains(it.pos) }
            .sortedWith(compareBy({ it.pos.x }, { it.pos.z }))
        if (trackedChunks.isEmpty()) {
            return
        }

        val levelKey = when (dimension) {
            "minecraft:overworld" -> Level.OVERWORLD
            "minecraft:the_nether" -> Level.NETHER
            "minecraft:the_end" -> Level.END
            else -> {
                logger.warn("Cannot capture final light hashes for unknown dimension $dimension")
                return
            }
        }
        val level = server.getLevel(levelKey) ?: run {
            logger.warn("Cannot capture final light hashes for missing level $dimension")
            return
        }

        var captured = 0
        for (tracked in trackedChunks) {
            val chunk = lightChunks[tracked.pos] ?: level.getChunk(tracked.pos.x, tracked.pos.z, ChunkStatus.LIGHT, false)
            if (chunk == null) {
                logger.warn("Tracked chunk ${tracked.pos} in $dimension is not loaded at LIGHT; skipping final light hash")
                continue
            }

            if (ChunkStageHashStorage.enableBinaryDump) {
                val result = ChunkStageHashStorage.computeLightHashWithData(level.lightEngine, chunk)
                ChunkStageHashStorage.storeHash(tracked.pos, dimension, ChunkStatus.LIGHT.toString(), result.hash)
                ChunkStageHashStorage.storeLightData(tracked.pos, dimension, result.data)
            } else {
                val hash = ChunkStageHashStorage.computeLightHash(level.lightEngine, chunk)
                ChunkStageHashStorage.storeHash(tracked.pos, dimension, ChunkStatus.LIGHT.toString(), hash)
            }
            captured++
        }
        logger.info("Captured final light hashes for $captured/${trackedChunks.size} chunks in $dimension")
    }

    /**
     * Write per-dimension per-stage gzip-compressed binary files containing raw block state IDs.
     *
     * Format (all integers big-endian):
     *   chunk_count: i32
     *   For each chunk (sorted by x, z):
     *     chunk_x: i32
     *     chunk_z: i32
     *     section_count: i32
     *     For each section (bottom to top):
     *       has_data: u8 (0 = all air, 1 = has block data)
     *       if has_data == 1:
     *         state_ids: [i32; 4096] in YZX order
     */
    fun writeBinaryBlockData(outputDir: Path) {
        val allData = ChunkStageHashStorage.getAllBlockData()
        if (allData.isEmpty()) {
            logger.warn("No block data stored, skipping binary output")
            return
        }

        val dimGroups = allData.entries.groupBy { it.key.first.dimension }

        for ((dimension, dimEntries) in dimGroups) {
            val dimShort = dimension.removePrefix("minecraft:")
            val stageGroups = dimEntries.groupBy { it.key.second }

            for ((stageName, entries) in stageGroups) {
                val shortName = stageName.removePrefix("minecraft:")
                val fileName = "chunk_stage_${dimShort}_${shortName}_blocks.bin.gz"
                val outputPath = outputDir.resolve("steel-core/test_assets/$fileName")
                Files.createDirectories(outputPath.parent)

                val chunksByPos = entries
                    .map { it.key.first.pos to it.value }
                    .sortedWith(compareBy({ it.first.x }, { it.first.z }))

                GZIPOutputStream(Files.newOutputStream(outputPath)).use { gzip ->
                    DataOutputStream(gzip).use { dos ->
                        dos.writeInt(chunksByPos.size)
                        for ((pos, sectionData) in chunksByPos) {
                            dos.writeInt(pos.x)
                            dos.writeInt(pos.z)
                            dos.writeInt(sectionData.size)
                            for (section in sectionData) {
                                if (section == null) {
                                    dos.writeByte(0)
                                } else {
                                    dos.writeByte(1)
                                    for (stateId in section) {
                                        dos.writeInt(stateId)
                                    }
                                }
                            }
                        }
                    }
                }

                logger.info("Wrote binary block data for $dimension stage '$stageName': ${chunksByPos.size} chunks -> $outputPath")
            }
        }
    }

    /**
     * Write per-dimension gzip-compressed binary files containing raw light packet bytes.
     *
     * Format (all integers big-endian):
     *   chunk_count: i32
     *   For each chunk (sorted by x, z):
     *     chunk_x: i32
     *     chunk_z: i32
     *     min_section_y: i32
     *     section_count: i32
     *     sky_source_count: i32
     *     sky_sources: [i32; sky_source_count] in x + z * 16 order
     *     For sky, then block:
     *       For each light section:
     *         state: u8 (0 = null, 1 = empty, 2 = data)
     *         if state == 2:
     *           bytes: [u8; 2048] in vanilla DataLayer order
     */
    fun writeBinaryLightData(outputDir: Path) {
        val allData = ChunkStageHashStorage.getAllLightData()
        if (allData.isEmpty()) {
            logger.warn("No light data stored, skipping binary light output")
            return
        }

        val dimGroups = allData.entries.groupBy { it.key.dimension }

        for ((dimension, entries) in dimGroups) {
            val dimShort = dimension.removePrefix("minecraft:")
            val fileName = "chunk_stage_${dimShort}_light_layers.bin.gz"
            val outputPath = outputDir.resolve("steel-core/test_assets/$fileName")
            Files.createDirectories(outputPath.parent)

            val chunksByPos = entries
                .map { it.key.pos to it.value }
                .sortedWith(compareBy({ it.first.x }, { it.first.z }))

            GZIPOutputStream(Files.newOutputStream(outputPath)).use { gzip ->
                DataOutputStream(gzip).use { dos ->
                    dos.writeInt(chunksByPos.size)
                    for ((pos, data) in chunksByPos) {
                        dos.writeInt(pos.x)
                        dos.writeInt(pos.z)
                        dos.writeInt(data.minSection)
                        dos.writeInt(data.sectionCount)
                        dos.writeInt(data.skySources.size)
                        for (sourceY in data.skySources) {
                            dos.writeInt(sourceY)
                        }
                        writeLightLayer(dos, data.sky)
                        writeLightLayer(dos, data.block)
                    }
                }
            }

            logger.info("Wrote binary light data for $dimension: ${chunksByPos.size} chunks -> $outputPath")
        }
    }

    private fun writeLightLayer(dos: DataOutputStream, sections: List<LightSectionData>) {
        for (section in sections) {
            dos.writeByte(section.state)
            if (section.state == 2) {
                val bytes = section.bytes ?: error("light section marked data without bytes")
                require(bytes.size == 2048) { "light section byte length was ${bytes.size}, expected 2048" }
                dos.write(bytes)
            }
        }
    }
}
