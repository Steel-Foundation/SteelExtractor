package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mojang.serialization.JsonOps
import com.steelextractor.SteelExtractor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.RegistryOps
import net.minecraft.server.MinecraftServer
import net.minecraft.world.item.component.SuspiciousStewEffects as VanillaSuspiciousStewEffects
import net.minecraft.world.level.block.SuspiciousEffectHolder

/** Extracts the effect lists returned by vanilla `SuspiciousEffectHolder` items. */
class SuspiciousStewEffects : SteelExtractor.Extractor {
    override fun fileName(): String {
        return "steel-registry/build_assets/suspicious_stew_effects.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val registryOps = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess())
        val holders = JsonArray()

        for (item in BuiltInRegistries.ITEM) {
            val effectHolder = SuspiciousEffectHolder.tryGet(item) ?: continue
            val itemKey = BuiltInRegistries.ITEM.getKey(item)
            val holderJson = JsonObject()
            holderJson.addProperty("item", itemKey.toString())

            val effectsJson = VanillaSuspiciousStewEffects.CODEC
                .encodeStart(registryOps, effectHolder.suspiciousEffects)
                .getOrThrow()

            holderJson.add("effects", effectsJson)
            holders.add(holderJson)
        }

        return holders
    }
}
