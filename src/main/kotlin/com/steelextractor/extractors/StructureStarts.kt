package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.level.levelgen.structure.Structure
import org.slf4j.LoggerFactory

/**
 * Extracts structure starts from generated chunks.
 *
 * Uses placement math (pure RNG, no terrain gen) to find which chunks should
 * have structures, then generates only those chunks to STRUCTURE_STARTS status
 * (very cheap — no noise/terrain).
 *
 * Output is used to verify Steel's structure generation matches vanilla.
 */
class StructureStarts : SteelExtractor.Extractor {
    private val logger = LoggerFactory.getLogger("steel-extractor-structure-starts")

    companion object {
        const val SEED: Long = 13579
        // Scan range in chunks (half-width). 100 = 200x200 chunk area = 3200x3200 blocks
        private const val HALF_RANGE: Int = 100
        // Max chunks to actually generate per dimension (safety cap for watchdog)
        private const val MAX_GENERATE: Int = 2000
    }

    override fun fileName(): String = "steel-core/test_assets/structure_starts.json"

    override fun extract(server: MinecraftServer): JsonElement {
        val json = JsonObject()
        json.addProperty("seed", SEED)
        json.addProperty("scan_range", HALF_RANGE)
        json.addProperty("max_generate", MAX_GENERATE)

        val structureRegistry = server.registryAccess().lookupOrThrow(Registries.STRUCTURE)
        val structureSetRegistry = server.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET)

        val dimensions = mapOf(
            "overworld" to server.overworld(),
            "the_nether" to server.getLevel(Level.NETHER),
            "the_end" to server.getLevel(Level.END)
        )

        for ((name, level) in dimensions) {
            if (level == null) {
                logger.warn("Dimension $name not available, skipping")
                continue
            }
            json.add(name, extractDimension(level, name, structureRegistry, structureSetRegistry))
        }

        return json
    }

    private fun extractDimension(
        level: ServerLevel,
        name: String,
        structureRegistry: net.minecraft.core.Registry<Structure>,
        structureSetRegistry: net.minecraft.core.Registry<net.minecraft.world.level.levelgen.structure.StructureSet>
    ): JsonObject {
        val dimJson = JsonObject()

        val generatorState = level.chunkSource.generatorState

        // Phase 1: Use placement math to find candidate chunks (no chunk gen needed)
        val candidateChunks = mutableSetOf<ChunkPos>()

        for (set in structureSetRegistry) {
            val placement = set.placement()

            for (x in -HALF_RANGE until HALF_RANGE) {
                for (z in -HALF_RANGE until HALF_RANGE) {
                    if (placement.isStructureChunk(generatorState, x, z)) {
                        candidateChunks.add(ChunkPos(x, z))
                    }
                }
            }
        }

        logger.info("$name: Found ${candidateChunks.size} candidate structure chunks in ${HALF_RANGE * 2}x${HALF_RANGE * 2} area")

        // Phase 2: Generate candidate chunks to STRUCTURE_STARTS (no terrain gen).
        // Sort deterministically and cap to avoid watchdog timeout.
        val sorted = candidateChunks.sortedWith(compareBy({ it.x }, { it.z }))
        val toGenerate = if (sorted.size > MAX_GENERATE) {
            logger.warn("$name: Capping from ${sorted.size} to $MAX_GENERATE candidates")
            sorted.take(MAX_GENERATE)
        } else {
            sorted
        }

        val chunksWithStarts = JsonArray()
        var totalStarts = 0
        var totalPieces = 0

        for ((idx, pos) in toGenerate.withIndex()) {
            if (idx > 0 && idx % 500 == 0) {
                logger.info("$name: Generated $idx/${toGenerate.size} chunks, found $totalStarts starts so far...")
            }

            val chunk = try {
                level.getChunk(pos.x, pos.z, ChunkStatus.STRUCTURE_STARTS, true) ?: continue
            } catch (e: Exception) {
                logger.warn("Failed to generate chunk ${pos.x},${pos.z}: ${e.message}")
                continue
            }

            val starts = chunk.allStarts
            val validStarts = starts.filter { (_, start) -> start.isValid }
            if (validStarts.isEmpty()) continue

            val chunkJson = JsonObject()
            chunkJson.addProperty("x", pos.x)
            chunkJson.addProperty("z", pos.z)

            val startsArray = JsonArray()
            for ((structure, start) in validStarts) {
                val startJson = JsonObject()
                val structureKey = structureRegistry.getKey(structure)
                startJson.addProperty("structure", structureKey?.toString() ?: "unknown")
                startJson.addProperty("chunk_x", start.chunkPos.x)
                startJson.addProperty("chunk_z", start.chunkPos.z)
                startJson.addProperty("references", start.references)

                val bb = start.boundingBox
                startJson.add("bounding_box", serializeBoundingBox(bb))

                val piecesArray = JsonArray()
                for (piece in start.pieces) {
                    val pieceJson = JsonObject()

                    val pieceTypeKey = BuiltInRegistries.STRUCTURE_PIECE.getKey(piece.type)
                    pieceJson.addProperty("type", pieceTypeKey?.toString() ?: "unknown")
                    pieceJson.addProperty("gen_depth", piece.genDepth)

                    val orientation = piece.orientation
                    pieceJson.addProperty("orientation", orientation?.get2DDataValue() ?: -1)

                    pieceJson.add("bounding_box", serializeBoundingBox(piece.boundingBox))

                    piecesArray.add(pieceJson)
                    totalPieces++
                }
                startJson.add("pieces", piecesArray)

                startsArray.add(startJson)
                totalStarts++
            }
            chunkJson.add("starts", startsArray)

            chunksWithStarts.add(chunkJson)
        }

        dimJson.addProperty("candidate_chunks", candidateChunks.size)
        dimJson.addProperty("generated_chunks", toGenerate.size)
        dimJson.addProperty("chunks_with_starts", chunksWithStarts.size())
        dimJson.addProperty("total_starts", totalStarts)
        dimJson.addProperty("total_pieces", totalPieces)
        dimJson.add("chunks", chunksWithStarts)

        logger.info("$name: Extracted $totalStarts structure starts ($totalPieces pieces) from ${chunksWithStarts.size()} chunks")
        return dimJson
    }

    private fun serializeBoundingBox(bb: net.minecraft.world.level.levelgen.structure.BoundingBox): JsonObject {
        val json = JsonObject()
        json.addProperty("min_x", bb.minX())
        json.addProperty("min_y", bb.minY())
        json.addProperty("min_z", bb.minZ())
        json.addProperty("max_x", bb.maxX())
        json.addProperty("max_y", bb.maxY())
        json.addProperty("max_z", bb.maxZ())
        return json
    }
}
