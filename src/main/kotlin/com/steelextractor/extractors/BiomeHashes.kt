package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.core.registries.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator
import net.minecraft.world.level.levelgen.RandomState
import org.slf4j.LoggerFactory
import java.security.MessageDigest

class BiomeHashes : SteelExtractor.Extractor {
    private val logger = LoggerFactory.getLogger("steel-extractor-biome-hashes")

    companion object {
        const val SEED: Long = 13579
    }

    override fun fileName(): String {
        return "steel-core/test_assets/biome_hashes.json"
    }

    private data class BiomeKey(val sectionY: Int, val x: Int, val y: Int, val z: Int)

    override fun extract(server: MinecraftServer): JsonElement {
        val json = JsonObject()
        json.addProperty("seed", SEED)
        json.addProperty("chunk_sample_seed", SteelExtractor.CHUNK_SAMPLE_SEED)
        json.addProperty("num_chunks", SteelExtractor.NUM_SAMPLE_CHUNKS)

        val worldSeed = server.overworld().seed
        if (worldSeed != SEED) {
            logger.warn("World seed is $worldSeed, not $SEED! The End biome hashes will be based on the world seed, not SEED. Set level-seed=$SEED in server.properties and delete the world folder.")
        }

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
            json.add(name, extractDimension(server, level, name))
        }

        return json
    }

    private fun extractDimension(server: MinecraftServer, level: ServerLevel, name: String): JsonObject {
        val dimJson = JsonObject()

        val minSectionY = level.minSectionY
        val maxSectionY = level.maxSectionY

        dimJson.addProperty("min_section_y", minSectionY)
        dimJson.addProperty("max_section_y", maxSectionY)

        val chunkGenerator = level.chunkSource.generator
        val biomeSource = chunkGenerator.biomeSource

        val noiseRegistry = server.registryAccess().lookupOrThrow(Registries.NOISE)

        val randomState = if (chunkGenerator is NoiseBasedChunkGenerator) {
            RandomState.create(noiseRegistry, SEED, chunkGenerator.generatorSettings().value())
        } else {
            logger.warn("Chunk generator for $name is not NoiseBasedChunkGenerator, using level's RandomState")
            level.chunkSource.randomState()
        }

        val biomeResolver = biomeSource.createUncachedResolver(randomState)

        val hashesArray = JsonArray()

        val sampledPositions = SteelExtractor.sampledChunkPositions()
        for (pos in sampledPositions) {
            val hash = chunkBiomeHash(biomeResolver, pos.x, pos.z, minSectionY, maxSectionY)

            val entry = JsonArray()
            entry.add(pos.x)
            entry.add(pos.z)
            entry.add(hash)
            hashesArray.add(entry)
        }

        dimJson.add("hashes", hashesArray)

        logger.info("Extracted biome hashes for $name: ${hashesArray.size()} chunks (seed=$SEED, sampleSeed=${SteelExtractor.CHUNK_SAMPLE_SEED})")
        return dimJson
    }

    /**
     * Computes a biome MD5 hash for a chunk.
     *
     * Samples biomes using vanilla's generation iteration order (X,Y,Z) for cache
     * tie-breaking, then hashes in deterministic Y,Z,X order with section_y markers.
     */
    private fun chunkBiomeHash(
        biomeResolver: net.minecraft.world.level.biome.BiomeResolver,
        chunkX: Int,
        chunkZ: Int,
        minSectionY: Int,
        maxSectionY: Int
    ): String {
        // Step 1: Sample biomes in generation order (X outer, Y middle, Z inner)
        // to match vanilla/Steel world generation cache behavior.
        val biomes = HashMap<BiomeKey, String>()

        for (sectionY in minSectionY..maxSectionY) {
            for (x in 0 until 4) {
                for (y in 0 until 4) {
                    for (z in 0 until 4) {
                        val quartX = chunkX * 4 + x
                        val quartY = sectionY * 4 + y
                        val quartZ = chunkZ * 4 + z

                        val biome = biomeResolver.getNoiseBiome(quartX, quartY, quartZ)
                        val biomeName = biome.unwrapKey()
                            .map { it.identifier().toString() }
                            .orElse("unknown")

                        biomes[BiomeKey(sectionY, x, y, z)] = biomeName
                    }
                }
            }
        }

        // Step 2: Hash in deterministic Y,Z,X order with section markers.
        val md = MessageDigest.getInstance("MD5")

        for (sectionY in minSectionY..maxSectionY) {
            md.update(sectionY.toByte())
            for (y in 0 until 4) {
                for (z in 0 until 4) {
                    for (x in 0 until 4) {
                        val biome = biomes[BiomeKey(sectionY, x, y, z)]!!
                        // Strip "minecraft:" prefix if present
                        val name = if (biome.startsWith("minecraft:")) {
                            biome.substring("minecraft:".length)
                        } else {
                            biome
                        }
                        md.update(name.toByteArray(Charsets.UTF_8))
                    }
                }
            }
        }

        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
