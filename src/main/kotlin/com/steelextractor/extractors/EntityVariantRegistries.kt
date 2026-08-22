package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.core.Registry
import net.minecraft.core.registries.Registries
import net.minecraft.server.MinecraftServer

private fun <T : Any> extractRegistryEntries(registry: Registry<T>): JsonArray {
    val entries = registry.map { value ->
        val key = registry.getKey(value) ?: error("Entity variant registry entry has no key: $value")
        val id = registry.getId(value)
        check(id >= 0) { "Entity variant registry entry has no ID: $key" }
        id to key.toString()
    }.sortedBy { (id, _) -> id }

    return JsonArray().also { values ->
        for ((id, key) in entries) {
            values.add(JsonObject().apply {
                addProperty("id", id)
                addProperty("key", key)
            })
        }
    }
}

class EntityVariantRegistries : SteelExtractor.Extractor {
    override fun fileName(): String {
        return "steel-registry/build_assets/entity_variant_registries.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val registries = server.registryAccess()
        return JsonObject().apply {
            add("cat_variant", extractRegistryEntries(registries.lookupOrThrow(Registries.CAT_VARIANT)))
            add("cat_sound_variant", extractRegistryEntries(registries.lookupOrThrow(Registries.CAT_SOUND_VARIANT)))
            add("cow_variant", extractRegistryEntries(registries.lookupOrThrow(Registries.COW_VARIANT)))
            add("cow_sound_variant", extractRegistryEntries(registries.lookupOrThrow(Registries.COW_SOUND_VARIANT)))
            add("wolf_variant", extractRegistryEntries(registries.lookupOrThrow(Registries.WOLF_VARIANT)))
            add("wolf_sound_variant", extractRegistryEntries(registries.lookupOrThrow(Registries.WOLF_SOUND_VARIANT)))
            add("frog_variant", extractRegistryEntries(registries.lookupOrThrow(Registries.FROG_VARIANT)))
            add("pig_variant", extractRegistryEntries(registries.lookupOrThrow(Registries.PIG_VARIANT)))
            add("pig_sound_variant", extractRegistryEntries(registries.lookupOrThrow(Registries.PIG_SOUND_VARIANT)))
            add("chicken_variant", extractRegistryEntries(registries.lookupOrThrow(Registries.CHICKEN_VARIANT)))
            add("chicken_sound_variant", extractRegistryEntries(registries.lookupOrThrow(Registries.CHICKEN_SOUND_VARIANT)))
            add(
                "zombie_nautilus_variant",
                extractRegistryEntries(registries.lookupOrThrow(Registries.ZOMBIE_NAUTILUS_VARIANT))
            )
            add("painting_variant", extractRegistryEntries(registries.lookupOrThrow(Registries.PAINTING_VARIANT)))
        }
    }
}
