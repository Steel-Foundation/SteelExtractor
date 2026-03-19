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
import kotlin.random.Random

class BiomeHashes : SteelExtractor.Extractor {
    private val logger = LoggerFactory.getLogger("steel-extractor-biome-hashes")

    companion object {
        const val SEED: Long = 13579
        private const val CHUNK_SAMPLE_SEED: Long = 24680
        private const val NUM_CHUNKS: Int = 128
        private const val HALF_RANGE: Int = 625 // 10000 / 16 = 625 chunks per axis half
    }

    override fun fileName(): String {
        return "steel-core/test_assets/biome_hashes.json"
    }

    private data class BiomeKey(val sectionY: Int, val x: Int, val y: Int, val z: Int)

    override fun extract(server: MinecraftServer): JsonElement {
        val json = JsonObject()
        json.addProperty("seed", SEED)
        json.addProperty("chunk_sample_seed", CHUNK_SAMPLE_SEED)
        json.addProperty("num_chunks", NUM_CHUNKS)

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
            RandomState.create(chunkGenerator.generatorSettings().value(), noiseRegistry, SEED)
        } else {
            logger.warn("Chunk generator for $name is not NoiseBasedChunkGenerator, using level's RandomState")
            level.chunkSource.randomState()
        }

        val climateSampler = randomState.sampler()

        val hashesArray = JsonArray()

        val rng = Random(CHUNK_SAMPLE_SEED)
        for (i in 0 until NUM_CHUNKS) {
            val chunkX = rng.nextInt(-HALF_RANGE, HALF_RANGE)
            val chunkZ = rng.nextInt(-HALF_RANGE, HALF_RANGE)
            val hash = chunkBiomeHash(climateSampler, biomeSource, chunkX, chunkZ, minSectionY, maxSectionY)

            val entry = JsonArray()
            entry.add(chunkX)
            entry.add(chunkZ)
            entry.add(hash)
            hashesArray.add(entry)
        }

        dimJson.add("hashes", hashesArray)

        logger.info("Extracted biome hashes for $name: ${hashesArray.size()} chunks (seed=$SEED, sampleSeed=$CHUNK_SAMPLE_SEED)")
        return dimJson
    }

    /**
     * Computes a biome MD5 hash for a chunk.
     *
     * Samples biomes using vanilla's generation iteration order (X,Y,Z) for cache
     * tie-breaking, then hashes in deterministic Y,Z,X order with section_y markers.
     */
    private fun chunkBiomeHash(
        climateSampler: net.minecraft.world.level.biome.Climate.Sampler,
        biomeSource: net.minecraft.world.level.biome.BiomeSource,
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

                        val biome = biomeSource.getNoiseBiome(quartX, quartY, quartZ, climateSampler)
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
