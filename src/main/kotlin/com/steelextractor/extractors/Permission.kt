package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mojang.brigadier.tree.LiteralCommandNode
import com.steelextractor.SteelExtractor
import net.minecraft.server.MinecraftServer
import net.minecraft.server.commands.OpCommand
import net.minecraft.server.permissions.LevelBasedPermissionSet
import net.minecraft.server.permissions.PermissionLevel
import net.minecraft.server.permissions.Permissions


class Permission : SteelExtractor.Extractor {
    override fun fileName(): String {
        return "steel-core/build/permissions_group.json";
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val dispatcher = server.commands.dispatcher
        val levels = arrayOf(
            LevelBasedPermissionSet.NO_PERMISSIONS,
            LevelBasedPermissionSet.MODERATOR,
            LevelBasedPermissionSet.GAMEMASTER,
            LevelBasedPermissionSet.ADMIN,
            LevelBasedPermissionSet.ALL_PERMISSIONS
        )
        val perms = Array(5) { mutableSetOf<String>() }
        for (command in dispatcher.root.children) {
            for (i in 0..4) {
                val source = server.createCommandSourceStack().withPermission(
                    levels[i]
                )
                if (command.requirement.test(source)
                ) {
                    // Steel derives a command's root permission as `<namespace>.command.<literal>`.
                    // A trailing `.*` would not match the bare root key, so emit the root key itself.
                    perms[i].add("minecraft.command.${(command as LiteralCommandNode).literal}")
                    break
                }
            }
        }
        // Vanilla `LevelBasedPermissionSet` grants entity selectors from GAMEMASTERS up. Steel
        // splits that single vanilla permission into a basic and an advanced selector key, so
        // grant both at the levels that pass the vanilla check.
        for (i in 0..4) {
            if (levels[i].hasPermission(Permissions.COMMANDS_ENTITY_SELECTORS)) {
                perms[i].add("minecraft.selector")
                perms[i].add("minecraft.selector.advanced")
            }
        }
        return JsonObject().apply {
            addProperty("default", PermissionLevel.ALL.serializedName)
            addProperty("op", PermissionLevel.OWNERS.serializedName) // TODO extract the default from the minecraft code
            addProperty("command_block", LevelBasedPermissionSet.GAMEMASTER.level().serializedName)
            add("groups", JsonObject().apply {
                val entries = PermissionLevel.entries;
                for (i in entries.indices) {
                    add(entries[i].serializedName, JsonObject().apply {
                        add("allow", JsonArray().apply {
                            for (perm in perms[i]) {
                                add(perm)
                            }
                        })
                        if (i > 0) {
                            addProperty("inherits", entries[i - 1].serializedName)
                        }
                    })
                }
            })
        }
    }
}
