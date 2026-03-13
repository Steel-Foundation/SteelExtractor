package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mojang.serialization.JsonOps
import com.steelextractor.SteelExtractor
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.RegistryOps
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument
import net.minecraft.world.level.block.state.properties.Property
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.material.PushReaction
import net.minecraft.world.level.storage.loot.LootTable
import net.minecraft.world.phys.AABB
import org.slf4j.LoggerFactory
import java.lang.reflect.Field
import java.util.*

class Blocks : SteelExtractor.Extractor {
    private val logger = LoggerFactory.getLogger("steel-extractor-blocks")
    private val shapes: LinkedHashMap<AABB, Int> = LinkedHashMap()


    override fun fileName(): String {
        return "steel-registry/build_assets/blocks.json"
    }

    fun getConstantName(clazz: Class<*>, value: Any?): String? {
        for (f in clazz.getFields()) {          // only public fields
            try {
                // we expect a static final constant, so no instance needed
                val fieldValue = f.get(null)
                if (fieldValue === value) {           // reference equality is what we want
                    return f.getName()
                }
            } catch (e: IllegalAccessException) {
                // shouldn't happen with getFields(), but ignore it just in case
            }
        }
        return null // no match found
    }

    /**
     * Reads the value of a private field from an object using Java Reflection.
     *
     * @param obj The object instance from which to read the private field.
     * @param fieldName The name of the private field to read.
     * @return The value of the private field, or null if the field is not found
     *         or an access error occurs.
     * @throws IllegalArgumentException if the provided object or fieldName is null or empty.
     */
    inline fun <reified T : Any> getPrivateFieldValue(obj: Any, fieldName: String): T? {
        require(fieldName.isNotBlank()) { "Field name cannot be blank." }

        return try {
            val field: Field = obj.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true // Make the private field accessible
            field.get(obj) as T? // Cast to the expected type T
        } catch (e: NoSuchFieldException) {
            println("Error: Private field '$fieldName' not found in class ${obj.javaClass.simpleName}. ${e.message}")
            null
        } catch (e: IllegalAccessException) {
            println("Error: Cannot access private field '$fieldName' in class ${obj.javaClass.simpleName}. ${e.message}")
            null
        } catch (e: ClassCastException) {
            println("Error: Cannot cast private field '$fieldName' to expected type ${T::class.simpleName}. ${e.message}")
            null
        }
    }

    /**
     * Computes shape data (default + overwrites) for a given shape extractor function.
     * Returns a Pair of (defaultShapeAabbs, shapeMap) for use in building overwrites.
     */
    private fun computeShapeData(
        possibleStates: List<net.minecraft.world.level.block.state.BlockState>,
        shapeExtractor: (net.minecraft.world.level.block.state.BlockState) -> List<AABB>
    ): Triple<List<AABB>, JsonArray, LinkedHashMap<List<AABB>, JsonArray>> {
        val shapeCounts = LinkedHashMap<List<AABB>, Int>()
        val shapeMap = LinkedHashMap<List<AABB>, JsonArray>()

        for (state in possibleStates) {
            val shapeAabbs = shapeExtractor(state)
            val currentShapeJsonArray = JsonArray()
            for (box in shapeAabbs) {
                val idx = shapes.putIfAbsent(box, shapes.size)
                currentShapeJsonArray.add(Objects.requireNonNullElseGet(idx) { shapes.size - 1 })
            }

            shapeCounts.merge(shapeAabbs, 1, Int::plus)
            shapeMap.putIfAbsent(shapeAabbs, currentShapeJsonArray)
        }

        val mostFrequentEntry = shapeCounts.maxByOrNull { it.value }

        return if (mostFrequentEntry != null) {
            Triple(mostFrequentEntry.key, shapeMap[mostFrequentEntry.key]!!, shapeMap)
        } else {
            Triple(emptyList(), JsonArray(), shapeMap)
        }
    }

    /**
     * Checks if two AABB lists differ.
     */
    private fun shapesDiffer(current: List<AABB>, default: List<AABB>): Boolean {
        if (current.size != default.size) return true
        return !current.zip(default).all { (c, d) -> c == d }
    }

    fun createBlockShapesJson(block: Block): JsonObject {
        val resultJson = JsonObject()
        val possibleStates = block.stateDefinition.possibleStates

        if (possibleStates.isEmpty()) {
            val emptyCollisions = JsonObject()
            emptyCollisions.add("default", JsonArray())
            emptyCollisions.add("overwrites", JsonArray())
            resultJson.add("collision_shapes", emptyCollisions)

            val emptyOutlines = JsonObject()
            emptyOutlines.add("default", JsonArray())
            emptyOutlines.add("overwrites", JsonArray())
            resultJson.add("outline_shapes", emptyOutlines)

            return resultJson
        }

        // Compute collision shapes
        val (defaultCollisionAabbs, defaultCollisionIdxs, collisionMap) = computeShapeData(possibleStates) { state ->
            state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs()
        }

        // Compute outline shapes
        val (defaultOutlineAabbs, defaultOutlineIdxs, outlineMap) = computeShapeData(possibleStates) { state ->
            state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs()
        }

        // Build collision_shapes object
        val collisionShapesJson = JsonObject()
        collisionShapesJson.add("default", defaultCollisionIdxs)

        val collisionOverwrites = JsonArray()
        for (i in possibleStates.indices) {
            val state = possibleStates[i]
            val currentAabbs = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs()

            if (shapesDiffer(currentAabbs, defaultCollisionAabbs)) {
                val overwrite = JsonObject()
                val shapeIdxs = collisionMap[currentAabbs] ?: run {
                    logger.error("Collision shape not found in map for state offset $i. Recalculating.")
                    val tempArray = JsonArray()
                    for (box in currentAabbs) {
                        val idx = shapes.putIfAbsent(box, shapes.size)
                        tempArray.add(Objects.requireNonNullElseGet(idx) { shapes.size - 1 })
                    }
                    tempArray
                }
                overwrite.addProperty("offset", i)
                overwrite.add("shapes", shapeIdxs)
                collisionOverwrites.add(overwrite)
            }
        }
        collisionShapesJson.add("overwrites", collisionOverwrites)
        resultJson.add("collision_shapes", collisionShapesJson)

        // Build outline_shapes object
        val outlineShapesJson = JsonObject()
        outlineShapesJson.add("default", defaultOutlineIdxs)

        val outlineOverwrites = JsonArray()
        for (i in possibleStates.indices) {
            val state = possibleStates[i]
            val currentAabbs = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs()

            if (shapesDiffer(currentAabbs, defaultOutlineAabbs)) {
                val overwrite = JsonObject()
                val shapeIdxs = outlineMap[currentAabbs] ?: run {
                    logger.error("Outline shape not found in map for state offset $i. Recalculating.")
                    val tempArray = JsonArray()
                    for (box in currentAabbs) {
                        val idx = shapes.putIfAbsent(box, shapes.size)
                        tempArray.add(Objects.requireNonNullElseGet(idx) { shapes.size - 1 })
                    }
                    tempArray
                }
                overwrite.addProperty("offset", i)
                overwrite.add("shapes", shapeIdxs)
                outlineOverwrites.add(overwrite)
            }
        }
        outlineShapesJson.add("overwrites", outlineOverwrites)
        resultJson.add("outline_shapes", outlineShapesJson)

        return resultJson
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val topLevelJson = JsonObject()

        val blocksJson = JsonArray()


        for (block in BuiltInRegistries.BLOCK) {
            val blockJson = JsonObject()
            blockJson.addProperty("id", BuiltInRegistries.BLOCK.getId(block))
            blockJson.addProperty("name", BuiltInRegistries.BLOCK.getKey(block).path)


            val behaviourProps = (block as BlockBehaviour).properties()


            // Add the differing BlockBehaviour.Properties to blockJson
            val behaviourJson = JsonObject()
            behaviourJson.addProperty("hasCollision", getPrivateFieldValue<Boolean>(behaviourProps, "hasCollision"))
            behaviourJson.addProperty("canOcclude", getPrivateFieldValue<Boolean>(behaviourProps, "canOcclude"))

            behaviourJson.addProperty(
                "explosionResistance",
                getPrivateFieldValue<Float>(behaviourProps, "explosionResistance")
            )
            behaviourJson.addProperty(
                "isRandomlyTicking",
                getPrivateFieldValue<Boolean>(behaviourProps, "isRandomlyTicking")
            )

            behaviourJson.addProperty("forceSolidOff", getPrivateFieldValue<Boolean>(behaviourProps, "forceSolidOff"))
            behaviourJson.addProperty("forceSolidOn", getPrivateFieldValue<Boolean>(behaviourProps, "forceSolidOn"))

            behaviourJson.addProperty(
                "pushReaction",
                getPrivateFieldValue<PushReaction>(behaviourProps, "pushReaction").toString()
            )


            val soundType = getPrivateFieldValue<SoundType>(behaviourProps, "soundType")
            if (soundType != null) {
                val soundTypeName = getConstantName(SoundType::class.java, soundType)
                if (soundTypeName != null) {
                    behaviourJson.addProperty("sound_type", soundTypeName)
                }
            }

            behaviourJson.addProperty("friction", getPrivateFieldValue<Float>(behaviourProps, "friction"))
            behaviourJson.addProperty("speedFactor", getPrivateFieldValue<Float>(behaviourProps, "speedFactor"))
            behaviourJson.addProperty("jumpFactor", getPrivateFieldValue<Float>(behaviourProps, "jumpFactor"))
            behaviourJson.addProperty("dynamicShape", getPrivateFieldValue<Boolean>(behaviourProps, "dynamicShape"))

            behaviourJson.addProperty("destroyTime", getPrivateFieldValue<Float>(behaviourProps, "destroyTime"))
            behaviourJson.addProperty(
                "explosionResistance",
                getPrivateFieldValue<Float>(behaviourProps, "explosionResistance")
            )
            behaviourJson.addProperty("ignitedByLava", getPrivateFieldValue<Boolean>(behaviourProps, "ignitedByLava"))

            behaviourJson.addProperty("liquid", getPrivateFieldValue<Boolean>(behaviourProps, "liquid"))
            behaviourJson.addProperty("isAir", getPrivateFieldValue<Boolean>(behaviourProps, "isAir"))
            //behaviourJson.addProperty("isRedstoneConductor", getPrivateFieldValue<Boolean>(behaviourProps, "isRedstoneConductor"))
            //behaviourJson.addProperty("isSuffocating", getPrivateFieldValue<Boolean>(behaviourProps, "isSuffocating"))
            behaviourJson.addProperty(
                "requiresCorrectToolForDrops",
                getPrivateFieldValue<Boolean>(behaviourProps, "requiresCorrectToolForDrops")
            )
            behaviourJson.addProperty(
                "instrument",
                getPrivateFieldValue<NoteBlockInstrument>(behaviourProps, "instrument").toString()
            )
            behaviourJson.addProperty("replaceable", getPrivateFieldValue<Boolean>(behaviourProps, "replaceable"))

            if (block.lootTable.isPresent) {
                val tableKey = block.lootTable.get();
                behaviourJson.addProperty(
                    "lootTable", tableKey.identifier().toString()
                )
            }


            val shapesStructureJson = createBlockShapesJson(block)
            blockJson.add("collision_shapes", shapesStructureJson.getAsJsonObject("collision_shapes"))
            blockJson.add("outline_shapes", shapesStructureJson.getAsJsonObject("outline_shapes"))

            // Only add if there are actual differences
            if (behaviourJson.size() > 0) {
                blockJson.add("behavior_properties", behaviourJson)
            }

            val propsJson = JsonArray()
            for (prop in block.stateDefinition.properties) {
                propsJson.add(getConstantName(BlockStateProperties::class.java, prop))
            }
            blockJson.add("properties", propsJson)

            val defaultProps = JsonArray()

            val state = block.defaultBlockState();
            for (prop in block.stateDefinition.properties) {
                val comparableValue = state.getValue(prop)
                val valueString = (prop as Property<Comparable<*>>).getName(comparableValue as Comparable<*>)

                val prefixedValueString = when (comparableValue) {
                    is Boolean -> "bool_$valueString"
                    is Enum<*> -> {
                        val fullClassName =
                            comparableValue.javaClass.name // e.g., "net.minecraft.core.Direction$Axis$2"

                        // 1. Get substring after the last dot (package name)
                        //    Result: "Direction$Axis$2"
                        var classNamePart = fullClassName.substringAfterLast('.', "")

                        // 2. Remove any trailing anonymous class identifiers (e.g., "$2", "$1")
                        //    Result for "Direction$Axis$2": "Direction$Axis"
                        //    Result for "RedstoneSide": "RedstoneSide"
                        val anonymousClassRegex = "\\$\\d+$".toRegex() // Matches "$1", "$2", etc. at the end
                        classNamePart = classNamePart.replace(anonymousClassRegex, "")

                        // 3. If a '$' remains, take the part after the last '$'
                        //    Result for "Direction$Axis": "Axis"
                        //    Result for "RedstoneSide": "RedstoneSide"
                        val finalClassName = classNamePart.substringAfterLast(
                            '$',
                            classNamePart
                        ) // Second 'classNamePart' is default if no '$'

                        "enum_${finalClassName}_$valueString"
                    }

                    is Number -> "int_$valueString"   // Catches Integer, Long, etc.
                    else -> "unknown_$valueString"    // Fallback for any other types
                }
                defaultProps.add(prefixedValueString)
            }

            blockJson.add("default_properties", defaultProps)

            blocksJson.add(blockJson)
        }

        val shapesJson = JsonArray()
        for (shape in shapes.keys) {
            val shapeJson = JsonObject()
            val min = JsonArray()
            min.add(shape.minX)
            min.add(shape.minY)
            min.add(shape.minZ)
            val max = JsonArray()
            max.add(shape.maxX)
            max.add(shape.maxY)
            max.add(shape.maxZ)
            shapeJson.add("min", min)
            shapeJson.add("max", max)
            shapesJson.add(shapeJson)
        }

        topLevelJson.add("shapes", shapesJson)
        topLevelJson.add("blocks", blocksJson)

        return topLevelJson
    }
}
