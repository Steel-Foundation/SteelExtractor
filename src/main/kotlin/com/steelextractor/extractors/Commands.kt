package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mojang.brigadier.tree.LiteralCommandNode
import com.steelextractor.SteelExtractor
import net.minecraft.server.MinecraftServer

class Commands : SteelExtractor.Extractor {
    override fun fileName(): String {
        // This should be placed in the Steel-Docs repository.
        return "commands.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val commands = JsonArray()

        for (node in server.commands.dispatcher.root.children) {
            if (node !is LiteralCommandNode) continue
            val element = JsonObject()
            element.addProperty("name", "/" + node.name)
            val redirect = (node.redirect as? LiteralCommandNode)?.literal
            element.addProperty("class", "/" + (redirect ?: node.literal))
            commands.add(element)
        }

        val output = JsonObject()
        output.add("commands", commands)
        return output
    }
}
