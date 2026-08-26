package com.steelextractor.extractors

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.core.HolderSet
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.world.item.component.BlockTransformers
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.levelgen.blockpredicates.MatchingBlocksPredicate
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider
import net.minecraft.world.level.levelgen.feature.stateproviders.CopyPropertiesProvider
import net.minecraft.world.level.levelgen.feature.stateproviders.RuleBasedStateProvider
import net.minecraft.world.level.levelgen.feature.stateproviders.SimpleStateProvider

class Strippables : SteelExtractor.Extractor {
    override fun fileName(): String {
        return "steel-core/build/strippables.json"
    }

    // MatchingBlocksPredicate exposes no public getter for its source block set.
    private val blocksField = MatchingBlocksPredicate::class.java.getDeclaredField("blocks").apply { isAccessible = true }

    private fun resultBlock(provider: BlockStateProvider): Block? {
        return when (provider) {
            is SimpleStateProvider -> provider.state().block
            is CopyPropertiesProvider -> resultBlock(provider.source())
            else -> null
        }
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val topLevelJson = JsonObject()

        val blockTransformerRegistry = server.registryAccess().lookupOrThrow(Registries.BLOCK_TRANSFORMER)
        val axeTransformer = blockTransformerRegistry.getValueOrThrow(BlockTransformers.AXE)

        for (data in axeTransformer.transforms()) {
            val provider = data.blockStateProvider()
            if (provider !is RuleBasedStateProvider) continue

            for (rule in provider.rules()) {
                val predicate = rule.ifTrue()
                if (predicate !is MatchingBlocksPredicate) continue
                val stripped = resultBlock(rule.then()) ?: continue

                @Suppress("UNCHECKED_CAST")
                val blocks = blocksField.get(predicate) as HolderSet<Block>
                for (holder in blocks) {
                    topLevelJson.addProperty(
                        BuiltInRegistries.BLOCK.getKey(holder.value()).path,
                        BuiltInRegistries.BLOCK.getKey(stripped).path
                    )
                }
            }
        }

        return topLevelJson
    }
}
