package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.BucketItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.StandingAndWallBlockItem
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.CropBlock
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.material.Fluid
import org.slf4j.LoggerFactory

class Classes : SteelExtractor.Extractor {
    private val logger = LoggerFactory.getLogger("steel-extractor-block-classes")

    override fun fileName(): String {
        return "steel-core/build/classes.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val topLevelJson = JsonObject()

        val blocksJson = JsonArray()
        for (block in BuiltInRegistries.BLOCK) {
            val blockJson = JsonObject()
            val name = BuiltInRegistries.BLOCK.getKey(block)?.path ?: "unknown"

            blockJson.addProperty("name", name)
            blockJson.addProperty("class", block.javaClass.simpleName)

            if (block is LiquidBlock) {
                val fluidField = LiquidBlock::class.java.getDeclaredField("fluid")
                fluidField.isAccessible = true
                val fluid = fluidField.get(block) as net.minecraft.world.level.material.FlowingFluid
                blockJson.addProperty("fluid", BuiltInRegistries.FLUID.getKey(fluid)?.path)
            }

            if (block is ButtonBlock) {
                val ticksField = ButtonBlock::class.java.getDeclaredField("ticksToStayPressed")
                ticksField.isAccessible = true
                blockJson.addProperty("ticks_to_stay_pressed", ticksField.getInt(block))

                val typeField = ButtonBlock::class.java.getDeclaredField("type")
                typeField.isAccessible = true
                val blockSetType = typeField.get(block) as net.minecraft.world.level.block.state.properties.BlockSetType
                blockJson.addProperty("button_click_on",
                    BuiltInRegistries.SOUND_EVENT.getKey(blockSetType.buttonClickOn())?.path?.replace(".", "_")
                        ?.uppercase()
                )
                blockJson.addProperty("button_click_off",
                    BuiltInRegistries.SOUND_EVENT.getKey(blockSetType.buttonClickOff())?.path?.replace(".", "_")
                        ?.uppercase()
                )
            }

            if (block is CropBlock) {
                blockJson.addProperty("max_age", block.maxAge)
                val method = block::class.java.getDeclaredMethod("getBaseSeedId")
                method.isAccessible = true
                val item = method.invoke(block)
                val path = BuiltInRegistries.ITEM.getKey(item as Item).path
                blockJson.addProperty("clone_item_stack", path)
            }

            blocksJson.add(blockJson)
        }
        topLevelJson.add("blocks", blocksJson)

        val itemsJson = JsonArray()
        for (item in BuiltInRegistries.ITEM) {
            val itemJson = JsonObject()
            val name = BuiltInRegistries.ITEM.getKey(item)?.path ?: "unknown"

            itemJson.addProperty("name", name)
            itemJson.addProperty("class", item.javaClass.simpleName)

            if (item is BlockItem) {
                itemJson.addProperty("block", BuiltInRegistries.BLOCK.getKey(item.block)?.path)
            }
            if (item is StandingAndWallBlockItem) {
                itemJson.addProperty(
                    "wallBlock",
                    BuiltInRegistries.BLOCK.getKey(item.javaClass.getField("wallBlock").get(item) as Block)?.path
                )
            }
            if (item is BucketItem) {
                var fluidField = BucketItem::class.java.getDeclaredField("content")
                fluidField.isAccessible = true
                itemJson.addProperty(
                    "fluid",
                    BuiltInRegistries.FLUID.getKey(fluidField.get(item) as Fluid)?.path
                )
            }

            itemsJson.add(itemJson)
        }
        topLevelJson.add("items", itemsJson)

        return topLevelJson
    }
}
