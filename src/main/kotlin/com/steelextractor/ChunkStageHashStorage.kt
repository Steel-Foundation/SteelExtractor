package com.steelextractor

import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.status.ChunkStatus
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class DimChunkPos(val pos: ChunkPos, val dimension: String)

data class BlockHashResult(
    val hash: String,
    val sectionData: List<IntArray?>
)

object ChunkStageHashStorage {
    private val hashes = ConcurrentHashMap<Pair<DimChunkPos, String>, String>()
    private val blockData = ConcurrentHashMap<Pair<DimChunkPos, String>, List<IntArray?>>()
    private val trackedChunks = ConcurrentHashMap.newKeySet<DimChunkPos>()
    private val readyChunks = ConcurrentHashMap.newKeySet<DimChunkPos>()

    @Volatile
    private var readyLatch: CountDownLatch? = null

    /** Set by SteelExtractor before generating each dimension's chunks. Read by the mixin. */
    @Volatile
    var currentDimension: String = ""

    /** When false, block data is not stored in memory and binary dump is skipped. */
    @Volatile
    var enableBinaryDump: Boolean = false

    fun startTracking(chunks: Set<DimChunkPos>) {
        trackedChunks.addAll(chunks)
        readyLatch = CountDownLatch(chunks.size)
    }

    fun isTracking(pos: ChunkPos, dimension: String): Boolean {
        return trackedChunks.contains(DimChunkPos(pos, dimension))
    }

    fun markReady(pos: ChunkPos, dimension: String): Boolean {
        val key = DimChunkPos(pos, dimension)
        if (trackedChunks.contains(key) && readyChunks.add(key)) {
            readyLatch?.countDown()
            return true
        }
        return false
    }

    fun waitForAllReady(timeoutSeconds: Long): Boolean {
        return readyLatch?.await(timeoutSeconds, TimeUnit.SECONDS) ?: true
    }

    fun storeHash(pos: ChunkPos, dimension: String, stageName: String, hash: String) {
        hashes[Pair(DimChunkPos(pos, dimension), stageName)] = hash
    }

    fun storeBlockData(pos: ChunkPos, dimension: String, stageName: String, data: List<IntArray?>) {
        blockData[Pair(DimChunkPos(pos, dimension), stageName)] = data
    }

    fun getAllHashes(): Map<Pair<DimChunkPos, String>, String> {
        return hashes.toMap()
    }

    fun getAllBlockData(): Map<Pair<DimChunkPos, String>, List<IntArray?>> {
        return blockData.toMap()
    }

    fun getReadyCount(): Int = readyChunks.size
    fun getTrackedCount(): Int = trackedChunks.size

    fun clear() {
        hashes.clear()
        blockData.clear()
        trackedChunks.clear()
        readyChunks.clear()
        readyLatch = null
    }

    fun computeBlockHash(sections: Iterable<net.minecraft.world.level.chunk.LevelChunkSection>): String {
        val md = MessageDigest.getInstance("MD5")
        for (section in sections) {
            if (section.hasOnlyAir()) {
                md.update(0.toByte())
            } else {
                val states = section.states
                for (y in 0 until 16) {
                    for (z in 0 until 16) {
                        for (x in 0 until 16) {
                            val stateId = net.minecraft.world.level.block.Block.getId(states.get(x, y, z))
                            md.update((stateId shr 24).toByte())
                            md.update((stateId shr 16).toByte())
                            md.update((stateId shr 8).toByte())
                            md.update(stateId.toByte())
                        }
                    }
                }
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun computeBlockHashWithData(sections: Iterable<net.minecraft.world.level.chunk.LevelChunkSection>): BlockHashResult {
        val md = MessageDigest.getInstance("MD5")
        val sectionDataList = mutableListOf<IntArray?>()

        for (section in sections) {
            if (section.hasOnlyAir()) {
                md.update(0.toByte())
                sectionDataList.add(null)
            } else {
                val stateIds = IntArray(4096)
                var idx = 0
                val states = section.states
                for (y in 0 until 16) {
                    for (z in 0 until 16) {
                        for (x in 0 until 16) {
                            val state = states.get(x, y, z)
                            val stateId = net.minecraft.world.level.block.Block.getId(state)
                            stateIds[idx] = stateId
                            idx++
                            md.update((stateId shr 24).toByte())
                            md.update((stateId shr 16).toByte())
                            md.update((stateId shr 8).toByte())
                            md.update(stateId.toByte())
                        }
                    }
                }
                sectionDataList.add(stateIds)
            }
        }

        val hash = md.digest().joinToString("") { "%02x".format(it) }
        return BlockHashResult(hash, sectionDataList)
    }
}
