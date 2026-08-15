package com.steelextractor.extractors

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.SharedConstants
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer

/**
 * Extracts the built-in registries used by recipe-book packet dispatch.
 *
 * These registries are known by an unmodified vanilla client, so Steel consumes their exact
 * numeric IDs rather than assigning IDs independently during Rust code generation.
 */
class RecipeBookRegistries : SteelExtractor.Extractor {
    override val required: Boolean = true

    override fun fileName(): String {
        return "steel-registry/build_assets/recipe_book_registries.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val version = SharedConstants.getCurrentVersion()
        val registries = JsonObject()
        registries.add(
            "recipe_book_category",
            extractBuiltInRegistry(BuiltInRegistries.RECIPE_BOOK_CATEGORY, sortById = true)
        )
        registries.add(
            "recipe_display",
            extractBuiltInRegistry(BuiltInRegistries.RECIPE_DISPLAY, sortById = true)
        )
        registries.add(
            "slot_display",
            extractBuiltInRegistry(BuiltInRegistries.SLOT_DISPLAY, sortById = true)
        )

        return JsonObject().apply {
            addProperty("schema_version", 1)
            addProperty("minecraft_version", version.name())
            addProperty("protocol_version", SharedConstants.getProtocolVersion())
            add("registries", registries)
        }
    }
}
