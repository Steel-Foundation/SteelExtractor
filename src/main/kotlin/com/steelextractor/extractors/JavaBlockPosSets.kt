package com.steelextractor.extractors

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HashMap
import java.util.HashSet
import java.util.HexFormat
import java.util.Random

/** Run with the extractJavaBlockPosSets Gradle task, which supplies the required JVM flags. */
object JavaBlockPosSets {
    private val mapField = HashSet::class.java.getDeclaredField("map").apply { isAccessible = true }
    private val tableField = HashMap::class.java.getDeclaredField("table").apply { isAccessible = true }
    private val keyField = Class.forName("java.util.HashMap\$Node")
        .getDeclaredField("key").apply { isAccessible = true }
    private val treeNodeClass = Class.forName("java.util.HashMap\$TreeNode")
    private val leftField = treeNodeClass.getDeclaredField("left").apply { isAccessible = true }
    private val rightField = treeNodeClass.getDeclaredField("right").apply { isAccessible = true }
    private val redField = treeNodeClass.getDeclaredField("red").apply { isAccessible = true }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty()) { "Use STEEL_EXTRACTOR_OUTPUT_DIRECTORY to select an output directory" }
        check(Runtime.version().feature() == 25) { "Collection fixtures require OpenJDK 25" }
        check("-XX:hashCode=3" in ManagementFactory.getRuntimeMXBean().inputArguments) {
            "Use extractJavaBlockPosSets so identity hashes increase with insertion order"
        }
        SharedConstants.tryDetectVersion()
        val root = JsonObject().apply {
            addProperty("minecraft_version", SharedConstants.getCurrentVersion().id())
            addProperty("java_feature_version", Runtime.version().feature())
            addProperty("java_runtime_version", Runtime.version().toString())
            addProperty("identity_hash_code_mode", "sequential")
            add("cases", cases())
        }
        val directory = Path.of(System.getenv("STEEL_EXTRACTOR_OUTPUT_DIRECTORY") ?: "steel_extractor_output")
        val output = directory.resolve("steel-core/test_assets/java_block_pos_sets.json")
        Files.createDirectories(output.parent)
        Files.newBufferedWriter(output, StandardCharsets.UTF_8).use { writer ->
            GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(root, writer)
            writer.appendLine()
        }
        println("Wrote ${output.toAbsolutePath()}")
    }

    private fun cases(): JsonArray = JsonArray().apply {
        fun capture(name: String, run: Capture.() -> Unit) {
            add(Capture().apply(run).json(name))
        }
        capture("control-initial-sixteen") {
            for (hash in 0 until 12) add(hash)
            add(3)
        }
        capture("control-resize-without-trees") {
            for (hash in 0 until 130) add(hash)
        }
        capture("collision-resizes-before-treeification") {
            for (hash in 0 until 15) add(hash * 64)
            add(128)
        }
        capture("split-to-lists-and-treeify-again") {
            warmup()
            for (hash in 0 until 9) add(hash * 64)
            fillUntil(49)
            for (hash in 9 until 21) add(hash * 64)
            add(640)
        }
        capture("split-tree-and-list") {
            warmup()
            for (hash in 0 until 13) add(hash * 128)
            for (hash in 0 until 3) add(hash * 128 + 64)
            fillUntil(49)
            add(13 * 128)
            add(64)
        }
        capture("split-two-trees-then-untreeify") {
            warmup()
            for (hash in 0 until 16) add(hash * 64)
            fillUntil(49)
            add(1024)
            add(1088)
            fillUntil(97)
            add(1152)
        }
        capture("unsplit-tree-retains-shape") {
            warmup()
            for (hash in 0 until 14) add(hash * 256)
            fillUntil(49)
            add(14 * 256)
            fillUntil(97)
            add(15 * 256)
            fillUntil(193)
            add(16 * 256)
        }
        capture("overflowing-three-dimensional-hashes") {
            warmup()
            val coordinates = Random(428)
            for (index in 0 until 25) {
                val spread = (if (index % 2 == 0) Int.MIN_VALUE else 0) + (index / 2) * 64
                val raw = spread xor (spread ushr 16)
                val y = coordinates.nextInt()
                val z = coordinates.nextInt()
                add(BlockPos(raw - (y + z * 31) * 31, y, z))
            }
        }
        capture("signed-hash-comparison") {
            warmup()
            for (hash in intArrayOf(0, Int.MIN_VALUE, 64, Int.MIN_VALUE + 64, -64, 128, -128, Int.MIN_VALUE + 128, 192, -192, Int.MAX_VALUE - 63)) {
                add(hash)
            }
            add(Int.MIN_VALUE)
        }
        capture("equal-hash-sequential-identities") {
            repeat(2) {
                for (z in 0 until 40) add(BlockPos(-961 * z, 0, z))
            }
        }
        capture("equal-hash-rebuild-after-split") {
            warmup()
            for (z in 0 until 12) add(BlockPos(-961 * z, 0, z))
            for (index in 0 until 8) add(64 + index * 128)
            fillUntil(49)
            add(BlockPos(-961 * 12, 0, 12))
            fillUntil(97)
            add(BlockPos(-961 * 13, 0, 13))
        }
    }

    private class Capture {
        private val set = HashSet<BlockPos>()
        private val insertions = JsonArray()
        private var previousIdentity = 0

        fun add(spread: Int) {
            // Invert HashMap's spread so each case can target specific buckets.
            add(BlockPos(spread xor (spread ushr 16), 0, 0))
        }

        fun add(pos: BlockPos) {
            val identity = System.identityHashCode(pos)
            check(identity > previousIdentity) { "JVM identity hashes must increase for every fixture key" }
            previousIdentity = identity
            val inserted = set.add(pos)
            val table = tableField.get(mapField.get(set)) as Array<*>
            val order = buildString {
                for (entry in set) append("${entry.x} ${entry.y} ${entry.z}\n")
            }
            val shape = buildString {
                for ((index, node) in table.withIndex()) {
                    if (treeNodeClass.isInstance(node)) {
                        append("$index:")
                        appendTree(node, this)
                    }
                }
            }
            insertions.add(JsonObject().apply {
                add("position", JsonArray().apply { add(pos.x); add(pos.y); add(pos.z) })
                addProperty("inserted", inserted)
                addProperty("capacity", table.size)
                addProperty("tree_bins", table.count { treeNodeClass.isInstance(it) })
                addProperty("order_sha256", sha256(order))
                addProperty("tree_shape_sha256", sha256(shape))
            })
        }

        fun warmup() {
            for (hash in 1..25) add(hash)
        }

        fun fillUntil(size: Int) {
            var hash = 33
            while (set.size < size) {
                if ((hash and 63) != 0) add(hash)
                hash++
            }
        }

        fun json(name: String): JsonObject = JsonObject().apply {
            addProperty("name", name)
            add("insertions", insertions)
        }
    }

    private fun appendTree(node: Any?, output: StringBuilder) {
        if (node == null) {
            output.append('.')
            return
        }
        val pos = keyField.get(node) as BlockPos
        output.append("(${pos.x},${pos.y},${pos.z},${if (redField.getBoolean(node)) 'r' else 'b'}")
        appendTree(leftField.get(node), output)
        appendTree(rightField.get(node), output)
        output.append(')')
    }

    private fun sha256(value: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
    )
}
