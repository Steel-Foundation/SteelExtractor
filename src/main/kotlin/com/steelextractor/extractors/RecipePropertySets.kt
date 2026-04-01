package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.core.Holder
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.world.item.Item
import net.minecraft.world.item.crafting.RecipeManager
import net.minecraft.world.item.crafting.RecipePropertySet

class RecipePropertySets : SteelExtractor.Extractor {
    override fun fileName(): String {
        return "steel-registry/build_assets/recipe_property_sets.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val topLevel = JsonObject()

        val field = RecipeManager::class.java.getDeclaredField("propertySets")
        field.isAccessible = true

        @Suppress("UNCHECKED_CAST")
        val propertySets = field.get(server.recipeManager) as Map<ResourceKey<RecipePropertySet>, RecipePropertySet>

        val itemsField = RecipePropertySet::class.java.getDeclaredField("items")
        itemsField.isAccessible = true

        for ((key, propertySet) in propertySets) {

            @Suppress("UNCHECKED_CAST")
            val items = itemsField.get(propertySet) as Set<Holder<Item>>
            val obj = JsonArray()

            for (item in items) {
                obj.add(item.registeredName)
            }

            topLevel.add(key.identifier().path, obj)
        }


        return topLevel
    }
}