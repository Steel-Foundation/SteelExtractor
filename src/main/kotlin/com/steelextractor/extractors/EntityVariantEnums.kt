package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.server.MinecraftServer
import net.minecraft.util.StringRepresentable
import net.minecraft.world.entity.animal.equine.Llama
import net.minecraft.world.entity.animal.equine.Markings
import net.minecraft.world.entity.animal.panda.Panda
import net.minecraft.world.entity.animal.parrot.Parrot
import net.minecraft.world.entity.animal.equine.Variant as HorseVariant

class EntityVariantEnums : SteelExtractor.Extractor {
    override fun fileName(): String = "steel-registry/build_assets/entity_variant_enums.json"

    override fun extract(server: MinecraftServer): JsonObject = extractEntityVariantEnums()
}

internal fun extractEntityVariantEnums(): JsonObject = JsonObject().apply {
    add("horse_variant", extractEntries(HorseVariant.entries, HorseVariant::getId))
    add("horse_markings", extractEntries(Markings.entries, Markings::getId))
    add("llama_variant", extractEntries(Llama.Variant.entries, Llama.Variant::getId))
    add("parrot_variant", extractEntries(Parrot.Variant.entries, Parrot.Variant::getId))
    add("panda_gene", extractEntries(Panda.Gene.entries, Panda.Gene::getId))
}

private fun <T : Enum<T>> extractEntries(entries: List<T>, id: (T) -> Int): JsonArray =
    JsonArray().apply {
        for (value in entries) {
            add(JsonObject().apply {
                addProperty("name", value.name)
                addProperty("id", id(value))
                if (value is StringRepresentable) {
                    addProperty("serialized_name", value.serializedName)
                }
            })
        }
    }
