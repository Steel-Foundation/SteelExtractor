package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.core.Holder
import net.minecraft.core.particles.ColorParticleOption
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.ai.attributes.Attribute
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import java.lang.reflect.Field

class MobEffects : SteelExtractor.Extractor {
    private val attributeModifiersField: Field = net.minecraft.world.effect.MobEffect::class.java
        .getDeclaredField("attributeModifiers")
        .apply { isAccessible = true }
    private val attributeTemplateClass = Class.forName("net.minecraft.world.effect.MobEffect\$AttributeTemplate")
    private val attributeTemplateIdField: Field = attributeTemplateClass
        .getDeclaredField("id")
        .apply { isAccessible = true }
    private val attributeTemplateAmountField: Field = attributeTemplateClass
        .getDeclaredField("amount")
        .apply { isAccessible = true }
    private val attributeTemplateOperationField: Field = attributeTemplateClass
        .getDeclaredField("operation")
        .apply { isAccessible = true }
    private val colorParticleColorField: Field = ColorParticleOption::class.java
        .getDeclaredField("color")
        .apply { isAccessible = true }

    override fun fileName(): String {
        return "steel-registry/build_assets/mob_effects.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val effectsArray = JsonArray()

        for (effect in BuiltInRegistries.MOB_EFFECT) {
            val key = BuiltInRegistries.MOB_EFFECT.getKey(effect)
            val name = key?.path ?: "unknown"

            val effectJson = JsonObject()
            val id = BuiltInRegistries.MOB_EFFECT.getId(effect)

            effectJson.addProperty("id", id)
            effectJson.addProperty("name", name)

            effectJson.addProperty("category", effect.category.name)
            effectJson.addProperty("color", effect.color)
            effectJson.addProperty("instantaneous", effect.isInstantaneous)
            effectJson.add("particle", extractParticle(effect))
            val attributeModifiers = extractAttributeModifiers(effect)
            if (attributeModifiers.size() > 0) {
                effectJson.add("attribute_modifiers", attributeModifiers)
            }

            effectsArray.add(effectJson)
        }

        return effectsArray
    }

    private fun extractParticle(effect: net.minecraft.world.effect.MobEffect): JsonObject {
        val holder = BuiltInRegistries.MOB_EFFECT.wrapAsHolder(effect)
        val regular = MobEffectInstance(holder, 1, 0, false, true).particleOptions
        val ambient = MobEffectInstance(holder, 1, 0, true, true).particleOptions
        val amplified = MobEffectInstance(holder, 1, 1, false, true).particleOptions
        val typeKey = BuiltInRegistries.PARTICLE_TYPE.getKey(regular.type)
            ?: error("Mob effect particle has an unregistered type: ${regular.type}")

        check(ambient.type === regular.type && amplified.type === regular.type) {
            "Mob effect particle type depends on instance state: ${BuiltInRegistries.MOB_EFFECT.getKey(effect)}"
        }

        val particleJson = JsonObject()
        particleJson.addProperty("type", typeKey.toString())

        when (regular) {
            is SimpleParticleType -> {
                check(ambient is SimpleParticleType && amplified is SimpleParticleType)
                particleJson.addProperty("options_type", "simple")
            }

            is ColorParticleOption -> extractColorParticle(
                effect,
                regular,
                ambient as? ColorParticleOption
                    ?: error("Ambient mob effect particle options changed type"),
                amplified as? ColorParticleOption
                    ?: error("Amplified mob effect particle options changed type"),
                particleJson
            )

            else -> error(
                "Unsupported mob effect particle options ${regular::class.java.name} for " +
                    BuiltInRegistries.MOB_EFFECT.getKey(effect)
            )
        }

        return particleJson
    }

    private fun extractColorParticle(
        effect: net.minecraft.world.effect.MobEffect,
        regular: ColorParticleOption,
        ambient: ColorParticleOption,
        amplified: ColorParticleOption,
        particleJson: JsonObject
    ) {
        val regularColor = colorParticleColorField.getInt(regular)
        val ambientColor = colorParticleColorField.getInt(ambient)
        val amplifiedColor = colorParticleColorField.getInt(amplified)
        val effectRgb = effect.color and 0x00ff_ffff

        if (
            regularColor and 0x00ff_ffff == effectRgb &&
            ambientColor and 0x00ff_ffff == effectRgb &&
            amplifiedColor == regularColor
        ) {
            particleJson.addProperty("options_type", "mob_effect_color")
            particleJson.addProperty("regular_alpha", regularColor ushr 24)
            particleJson.addProperty("ambient_alpha", ambientColor ushr 24)
            return
        }

        check(regularColor == ambientColor && regularColor == amplifiedColor) {
            "Mob effect color particle depends on unsupported instance state: " +
                BuiltInRegistries.MOB_EFFECT.getKey(effect)
        }
        particleJson.addProperty("options_type", "fixed_color")
        particleJson.addProperty("color", regularColor)
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractAttributeModifiers(effect: net.minecraft.world.effect.MobEffect): JsonArray {
        val modifiersJson = JsonArray()
        val modifiers = attributeModifiersField.get(effect) as Map<Holder<Attribute>, Any>

        for ((attributeHolder, template) in modifiers.entries) {
            val attribute = attributeHolder.value()
            val attributeKey = BuiltInRegistries.ATTRIBUTE.getKey(attribute) ?: continue
            val modifierId = attributeTemplateIdField.get(template)
            val amount = attributeTemplateAmountField.get(template) as Double
            val operation = attributeTemplateOperationField.get(template) as AttributeModifier.Operation

            val modifierJson = JsonObject()
            modifierJson.addProperty("attribute", attributeKey.path)
            modifierJson.addProperty("id", modifierId.toString().removePrefix("minecraft:"))
            modifierJson.addProperty("amount", amount)
            modifierJson.addProperty("operation", operation.name)
            modifiersJson.add(modifierJson)
        }

        return modifiersJson
    }
}
