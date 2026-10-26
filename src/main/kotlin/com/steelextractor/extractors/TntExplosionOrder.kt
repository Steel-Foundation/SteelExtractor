package com.steelextractor.extractors

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestServer
import net.minecraft.server.Bootstrap
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.packs.repository.ServerPacksSource
import net.minecraft.util.Util
import net.minecraft.world.entity.item.PrimedTnt
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Explosion
import net.minecraft.world.level.ExplosionDamageCalculator
import net.minecraft.world.level.ServerExplosion
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.gamerules.GameRules
import net.minecraft.world.level.storage.LevelStorageSource
import net.minecraft.world.phys.Vec3
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Optional

/** Captures the radius-four TNT fixture in a fresh vanilla flat GameTest level. */
object TntExplosionOrder {
    private const val SEED = 1L
    private const val RADIUS = 4.0F
    private val TNT_POSITIONS = listOf(BlockPos(6, 65, 9), BlockPos(10, 65, 5), BlockPos(8, 65, 7))
    private val calculatePositions = ServerExplosion::class.java
        .getDeclaredMethod("calculateExplodedPositions").apply { isAccessible = true }
    private val interactWithBlocks = ServerExplosion::class.java
        .getDeclaredMethod("interactWithBlocks", List::class.java).apply { isAccessible = true }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty()) { "Use STEEL_EXTRACTOR_OUTPUT_DIRECTORY to select an output directory" }
        check(Runtime.version().feature() == 25) { "TNT fixture capture requires Java 25" }
        SharedConstants.tryDetectVersion()
        val worldDirectory = Files.createTempDirectory(Path.of("."), "tnt-explosion-")
        try {
            Bootstrap.bootStrap()
            val storage = LevelStorageSource.createDefault(worldDirectory).createAccess("capture")
            val packs = ServerPacksSource.createPackRepository(storage)
            val result = GameTestServer.create(Thread.currentThread(), storage, packs, Optional.empty(), false, 1).use { server ->
                // Initialize vanilla's real flat levels without advancing entity ticks or running GameTests.
                val initialize = GameTestServer::class.java.getDeclaredMethod("initServer").apply { isAccessible = true }
                check(initialize.invoke(server) as Boolean) { "Vanilla GameTest server initialization failed" }
                capture(server.overworld())
            }
            val directory = Path.of(System.getenv("STEEL_EXTRACTOR_OUTPUT_DIRECTORY") ?: "steel_extractor_output")
            val output = directory.resolve("steel-core/test_assets/vanilla_tnt_order.json")
            Files.createDirectories(output.parent)
            Files.newBufferedWriter(output, StandardCharsets.UTF_8).use { writer ->
                GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(result, writer)
                writer.appendLine()
            }
            println("Wrote ${output.toAbsolutePath()}")
        } finally {
            Util.shutdownExecutors()
            Files.walk(worldDirectory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
            }
        }
    }

    private fun capture(level: ServerLevel): JsonObject {
        check(level.gameRules.get(GameRules.TNT_EXPLODES)) { "The fixture requires TNT explosions to be enabled" }
        for (x in -1..1) {
            for (z in -1..1) level.getChunk(x, z)
        }
        for (x in -4..20) {
            for (z in -4..20) {
                for (y in 52..80) {
                    val state = if (y !in 65..67 || kotlin.math.abs(x + z - 16) > 1) {
                        Blocks.OBSIDIAN.defaultBlockState()
                    } else {
                        Blocks.AIR.defaultBlockState()
                    }
                    val pos = BlockPos(x, y, z)
                    if (level.getBlockState(pos) != state) {
                        check(level.setBlock(pos, state, Block.UPDATE_NONE)) { "Failed to place fixture block at $pos" }
                    }
                }
            }
        }
        for (pos in TNT_POSITIONS) {
            check(level.setBlock(pos, Blocks.TNT.defaultBlockState(), Block.UPDATE_NONE)) {
                "Failed to place fixture TNT at $pos"
            }
        }
        val source = PrimedTnt(level, 8.5, 65.0, 8.5, null)
        val center = Vec3(source.x, source.getY(0.0625), source.z)
        val calculator = RecordingCalculator()
        val explosion = ServerExplosion(level, source, null, calculator, center, RADIUS, false, Explosion.BlockInteraction.DESTROY_WITH_DECAY)
        level.getRandom().setSeed(SEED)
        val affected = calculatePositions.invoke(explosion) as List<*>
        val iteration = affected.map { it as BlockPos }
        check(iteration.map { it.hashCode() }.toSet().size == iteration.size) {
            "The TNT fixture must avoid JVM identity-dependent full-hash ties"
        }
        interactWithBlocks.invoke(explosion, affected)
        val callbacks = affected.map { it as BlockPos }
        val primed = level.allEntities.filterIsInstance<PrimedTnt>().sortedBy { it.id }
        val survivors = TNT_POSITIONS.filter { level.getBlockState(it).`is`(Blocks.TNT) }
        check(primed.map { it.blockPosition() } == callbacks.filter { it in TNT_POSITIONS }) {
            "TNT entities must be created in callback order"
        }
        val insertions = buildString {
            for (pos in calculator.insertions) append("${pos.x} ${pos.y} ${pos.z}\n")
        }
        return JsonObject().apply {
            addProperty("minecraft_version", SharedConstants.getCurrentVersion().id())
            addProperty("java_feature_version", Runtime.version().feature())
            addProperty("java_runtime_version", Runtime.version().toString())
            addProperty("random_seed", SEED)
            addProperty("explosion_radius", RADIUS)
            add("source_position", JsonArray().apply { add(source.x); add(source.y); add(source.z) })
            add("explosion_center", JsonArray().apply { add(center.x); add(center.y); add(center.z) })
            add("tnt_positions", positions(TNT_POSITIONS))
            addProperty("insertions_sha256", HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(insertions.toByteArray(StandardCharsets.UTF_8))
            ))
            add("iteration", positions(iteration))
            add("callbacks", positions(callbacks))
            add("primed_tnt", JsonArray().apply {
                for (entity in primed) add(JsonObject().apply {
                    add("position", position(entity.blockPosition()))
                    addProperty("fuse", entity.fuse)
                })
            })
            add("surviving_tnt", positions(survivors))
        }
    }

    private class RecordingCalculator : ExplosionDamageCalculator() {
        val insertions = mutableListOf<BlockPos>()

        override fun shouldBlockExplode(explosion: Explosion, level: BlockGetter, pos: BlockPos, state: BlockState, power: Float): Boolean {
            insertions.add(pos.immutable())
            return super.shouldBlockExplode(explosion, level, pos, state, power)
        }
    }

    private fun position(pos: BlockPos): JsonArray = JsonArray().apply { add(pos.x); add(pos.y); add(pos.z) }

    private fun positions(values: List<BlockPos>): JsonArray = JsonArray().apply {
        for (pos in values) add(position(pos))
    }
}
