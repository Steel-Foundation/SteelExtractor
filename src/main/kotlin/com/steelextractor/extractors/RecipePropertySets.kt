package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.world.item.ItemStack

class RecipePropertySets : SteelExtractor.Extractor {
    override val required: Boolean = true

    override fun fileName(): String {
        return "steel-registry/build_assets/recipe_property_sets.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val propertySets = server.getRecipeManager().getSynchronizedItemProperties()
        return JsonObject().apply {
            propertySets.entries
                .sortedBy { it.key.identifier().getPath() }
                .forEach { (key, propertySet) ->
                    add(key.identifier().getPath(), JsonArray().apply {
                        BuiltInRegistries.ITEM.toList()
                            .asSequence()
                            .filter { item -> propertySet.test(ItemStack(item)) }
                            .map { item -> BuiltInRegistries.ITEM.getKey(item).toString() }
                            .sorted()
                            .forEach(::add)
                    })
                }
        }
    }
}
