package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.pathfinder.PathComputationType

class BlockSpawnRules : SteelExtractor.Extractor {
    override fun fileName(): String = "steel-registry/build_assets/block_spawn_rules.json"

    override fun extract(server: MinecraftServer): JsonObject {
        val predicateField = BlockBehaviour.Properties::class.java
            .getDeclaredField("isValidSpawn")
            .apply { isAccessible = true }
        val defaultPredicate = predicateField.get(BlockBehaviour.Properties.of())
        val overrides = JsonObject()
        val signalSources = JsonArray()
        val landPathfinding = JsonObject()

        for (block in BuiltInRegistries.BLOCK) {
            val key = BuiltInRegistries.BLOCK.getKey(block).toString()
            val states = block.stateDefinition.possibleStates
            val signalSource = block.defaultBlockState().isSignalSource
            require(states.all { it.isSignalSource == signalSource }) {
                "Signal source flag for $key depends on its state"
            }
            if (signalSource) signalSources.add(key)

            val byState = states.map { it.isPathfindable(PathComputationType.LAND) }
            if (states.indices.any { offset ->
                val fullShape = Block.isShapeFullBlock(states[offset].getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO))
                byState[offset] == fullShape
            }) {
                val default = byState.count { it } > byState.size / 2
                val overwrites = JsonArray()
                for ((offset, value) in byState.withIndex()) {
                    if (value != default) {
                        overwrites.add(JsonObject().apply {
                            addProperty("offset", offset)
                            addProperty("value", value)
                        })
                    }
                }
                landPathfinding.add(key, JsonObject().apply {
                    addProperty("default", default)
                    add("overwrites", overwrites)
                })
            }

            if (predicateField.get(block.properties()) === defaultPredicate) continue

            val allowed = JsonArray()
            for (type in BuiltInRegistries.ENTITY_TYPE) {
                val valid = block.defaultBlockState().isValidSpawn(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, type)
                require(states.all { state ->
                    state.isValidSpawn(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, type) == valid
                }) { "Block spawn rule for $key depends on its state" }
                if (valid) allowed.add(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString())
            }

            if (allowed.size() == BuiltInRegistries.ENTITY_TYPE.size()) {
                overrides.addProperty(key, true)
            } else {
                overrides.add(key, allowed)
            }
        }

        return JsonObject().apply {
            add("overrides", overrides)
            add("signal_sources", signalSources)
            add("land_pathfinding", landPathfinding)
        }
    }
}
