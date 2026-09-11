package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.core.Registry
import net.minecraft.core.particles.ParticleType
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.sounds.SoundEvent
import net.minecraft.stats.StatType
import net.minecraft.world.entity.npc.villager.VillagerProfession
import net.minecraft.world.entity.npc.villager.VillagerType
import net.minecraft.world.level.gameevent.PositionSourceType
import net.minecraft.world.level.saveddata.maps.MapDecorationType

internal fun <T : Any> extractBuiltInRegistry(
    registry: Registry<T>,
    sortById: Boolean = false,
    addFields: (T, JsonObject) -> Unit = { _, _ -> }
): JsonArray {
    val values = JsonArray()
    val entries = if (sortById) {
        registry.toList().sortedBy { registry.getId(it) }
    } else {
        registry.toList()
    }

    val ids = HashSet<Int>()
    val keys = HashSet<String>()
    var expectedId = 0

    for (entry in entries) {
        val id = registry.getId(entry)
        val key = registry.getKey(entry) ?: error("Built-in registry entry has no key: $entry")
        if (sortById) {
            require(id == expectedId) {
                "Built-in registry IDs must be contiguous: expected $expectedId, got $id for $key"
            }
            require(ids.add(id)) { "Duplicate built-in registry ID $id for $key" }
            require(keys.add(key.toString())) { "Duplicate built-in registry key $key" }
            expectedId++
        }

        val entryJson = JsonObject()
        entryJson.addProperty("id", id)
        entryJson.addProperty("key", key.toString())
        addFields(entry, entryJson)
        values.add(entryJson)
    }
    return values
}

class ParticleTypeRegistryExtractor : SteelExtractor.Extractor {
    override fun fileName(): String {
        return "steel-registry/build_assets/particle_types.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        return extractBuiltInRegistry(BuiltInRegistries.PARTICLE_TYPE) { particleType: ParticleType<*>, json ->
            json.addProperty("override_limiter", particleType.overrideLimiter)
            json.addProperty("options_type", optionsType(particleType))
        }
    }

    private fun optionsType(particleType: ParticleType<*>): String {
        if (particleType is SimpleParticleType) {
            return "simple"
        }

        return when (particleType) {
            ParticleTypes.BLOCK,
            ParticleTypes.BLOCK_MARKER,
            ParticleTypes.FALLING_DUST,
            ParticleTypes.DUST_PILLAR,
            ParticleTypes.BLOCK_CRUMBLE -> "block"

            ParticleTypes.ENTITY_EFFECT,
            ParticleTypes.TINTED_LEAVES,
            ParticleTypes.FLASH -> "color"

            ParticleTypes.DUST -> "dust"
            ParticleTypes.DUST_COLOR_TRANSITION -> "dust_color_transition"
            ParticleTypes.GEYSER,
            ParticleTypes.GEYSER_PLUME -> "geyser"

            ParticleTypes.GEYSER_BASE,
            ParticleTypes.GEYSER_POOF -> "geyser_base"

            ParticleTypes.DRAGON_BREATH -> "power"
            ParticleTypes.EFFECT,
            ParticleTypes.INSTANT_EFFECT -> "spell"

            ParticleTypes.ITEM -> "item"
            ParticleTypes.SCULK_CHARGE -> "sculk_charge"
            ParticleTypes.SHRIEK -> "shriek"
            ParticleTypes.TRAIL -> "trail"
            ParticleTypes.VIBRATION -> "vibration"
            else -> error("Unknown parameterized particle type: ${BuiltInRegistries.PARTICLE_TYPE.getKey(particleType)}")
        }
    }
}

class PositionSourceTypeRegistryExtractor : SteelExtractor.Extractor {
    override fun fileName(): String {
        return "steel-registry/build_assets/position_source_types.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        return extractBuiltInRegistry(BuiltInRegistries.POSITION_SOURCE_TYPE) { positionSourceType: PositionSourceType<*>, _ ->
            when (positionSourceType) {
                PositionSourceType.BLOCK,
                PositionSourceType.ENTITY -> Unit

                else -> error(
                    "Unknown position source type: ${BuiltInRegistries.POSITION_SOURCE_TYPE.getKey(positionSourceType)}"
                )
            }
        }
    }
}

class MapDecorationTypeRegistryExtractor : SteelExtractor.Extractor {
    override fun fileName(): String {
        return "steel-registry/build_assets/map_decoration_types.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        return extractBuiltInRegistry(BuiltInRegistries.MAP_DECORATION_TYPE) { type: MapDecorationType, json ->
            json.addProperty("asset_id", type.assetId().toString())
            json.addProperty("show_on_item_frame", type.showOnItemFrame())
            json.addProperty("map_color", type.mapColor())
            json.addProperty("exploration_map_element", type.explorationMapElement())
            json.addProperty("track_count", type.trackCount())
        }
    }
}

class VillagerTypeRegistryExtractor : SteelExtractor.Extractor {
    override fun fileName(): String {
        return "steel-registry/build_assets/villager_types.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        return extractBuiltInRegistry(BuiltInRegistries.VILLAGER_TYPE) { _: VillagerType, _ -> }
    }
}

class VillagerProfessionRegistryExtractor : SteelExtractor.Extractor {
    override fun fileName(): String {
        return "steel-registry/build_assets/villager_professions.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        return extractBuiltInRegistry(BuiltInRegistries.VILLAGER_PROFESSION) { profession: VillagerProfession, json ->
            val workSound = profession.workSound()
            if (workSound != null) {
                json.addProperty("work_sound", soundKey(workSound))
            }
        }
    }

    private fun soundKey(sound: SoundEvent): String {
        val key = BuiltInRegistries.SOUND_EVENT.getKey(sound)
            ?: error("Villager profession work sound has no key: $sound")
        return key.toString()
    }
}

class CustomStatRegistryExtractor : SteelExtractor.Extractor {
    override fun fileName(): String {
        return "steel-registry/build_assets/custom_stats.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        return extractBuiltInRegistry(BuiltInRegistries.CUSTOM_STAT) { _: Identifier, _ -> }
    }
}

class StatTypeRegistryExtractor : SteelExtractor.Extractor {
    override fun fileName(): String {
        return "steel-registry/build_assets/stat_types.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        return extractBuiltInRegistry(BuiltInRegistries.STAT_TYPE) { _: StatType<*>, _ -> }
    }
}