package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.steelextractor.SteelExtractor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory

class GameRulesExtractor : SteelExtractor.Extractor {
    private val logger = LoggerFactory.getLogger("steel-extractor-gamerules")

    override fun fileName(): String {
        return "steel-registry/build_assets/game_rules.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val topLevelJson = JsonObject()
        val gameRulesJson = JsonArray()

        for (gamerule in BuiltInRegistries.GAME_RULE) {
            val entry = JsonObject()
            entry.addProperty("name", gamerule.toString())
            entry.addProperty("category", gamerule.category().id.path)

            val argument = gamerule.argument()
            when (argument) {
                is BoolArgumentType -> {
                    entry.addProperty("type", "bool")
                    entry.addProperty("default", gamerule.defaultValue() as Boolean)
                }

                is IntegerArgumentType -> {
                    entry.addProperty("type", "int")
                    entry.addProperty("default", gamerule.defaultValue() as Int)

                    val min = argument.minimum
                    val max = argument.maximum

                    // Only include if not the default bounds
                    if (min != Int.MIN_VALUE) {
                        entry.addProperty("min", min)
                    }
                    if (max != Int.MAX_VALUE) {
                        entry.addProperty("max", max)
                    }
                }

                else -> {
                    // Fallback for unknown types
                    entry.addProperty("type", gamerule.gameRuleType().toString())
                    entry.addProperty("default", gamerule.defaultValue().toString())
                }
            }

            gameRulesJson.add(entry)
        }

        topLevelJson.add("game_rules", gameRulesJson)

        return topLevelJson
    }
}
