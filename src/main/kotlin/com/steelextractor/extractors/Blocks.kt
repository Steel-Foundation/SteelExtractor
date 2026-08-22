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
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument
import net.minecraft.world.level.block.state.properties.Property
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.material.PushReaction
import net.minecraft.world.level.storage.loot.LootTable
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.shapes.CollisionContext
import org.slf4j.LoggerFactory
import java.lang.reflect.Field
import java.util.*
import kotlin.math.abs

class Blocks : SteelExtractor.Extractor {
    private val logger = LoggerFactory.getLogger("steel-extractor-blocks")
    private val shapes: LinkedHashMap<AABB, Int> = LinkedHashMap()

    private enum class OffsetType {
        NONE,
        XZ,
        XYZ,
    }

    private data class StateLightProperties(
        val lightEmission: Int,
        val lightDampening: Int,
        val useShapeForLightOcclusion: Boolean,
    )

    private data class StateFluidProperties(
        val fluid: String,
        val amount: Int,
        val falling: Boolean,
    )

    private data class ShapeData(
        val defaultAabbs: List<AABB>,
        val defaultIdxs: JsonArray,
        val shapeMap: LinkedHashMap<List<AABB>, JsonArray>,
    )

    private data class ShapeChannel(
        val jsonName: String,
        val displayName: String,
        val shapeExtractor: (BlockState, BlockPos) -> List<AABB>,
    )

    companion object {
        private const val AABB_EPSILON = 1.0e-9
        private const val OFFSET_EPSILON = 1.0e-9

        private val OFFSET_PROBE_POSITIONS = listOf(
            BlockPos.ZERO,
            BlockPos(1, 0, 0),
            BlockPos(0, 0, 1),
            BlockPos(7, 0, 11),
            BlockPos(-13, 0, 5),
        )
    }


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

    private fun getProtectedFloatMethodValue(obj: Any, methodName: String): Float {
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            try {
                val method = clazz.getDeclaredMethod(methodName)
                method.isAccessible = true
                return method.invoke(obj) as Float
            } catch (_: NoSuchMethodException) {
                clazz = clazz.superclass
            }
        }
        throw IllegalArgumentException("Method '$methodName' not found in ${obj.javaClass.simpleName}")
    }

    private fun getOffsetType(block: Block): OffsetType {
        val state = block.defaultBlockState()
        if (!state.hasOffsetFunction()) {
            return OffsetType.NONE
        }

        for (pos in OFFSET_PROBE_POSITIONS) {
            if (abs(state.getOffset(pos).y) > OFFSET_EPSILON) {
                return OffsetType.XYZ
            }
        }

        return OffsetType.XZ
    }

    private fun movedAabbEquals(actual: AABB, base: AABB, dx: Double, dy: Double, dz: Double): Boolean {
        return abs(actual.minX - (base.minX + dx)) <= AABB_EPSILON
                && abs(actual.minY - (base.minY + dy)) <= AABB_EPSILON
                && abs(actual.minZ - (base.minZ + dz)) <= AABB_EPSILON
                && abs(actual.maxX - (base.maxX + dx)) <= AABB_EPSILON
                && abs(actual.maxY - (base.maxY + dy)) <= AABB_EPSILON
                && abs(actual.maxZ - (base.maxZ + dz)) <= AABB_EPSILON
    }

    private fun shapesEqualAfterMove(base: List<AABB>, actual: List<AABB>, dx: Double, dy: Double, dz: Double): Boolean {
        if (base.size != actual.size) {
            return false
        }

        return base.zip(actual).all { (baseBox, actualBox) ->
            movedAabbEquals(actualBox, baseBox, dx, dy, dz)
        }
    }

    private fun shapeChannelUsesOffset(
        possibleStates: List<BlockState>,
        shapeExtractor: (BlockState, BlockPos) -> List<AABB>,
    ): Boolean {
        for (state in possibleStates) {
            if (!state.hasOffsetFunction()) {
                continue
            }

            val basePos = BlockPos.ZERO
            val baseOffset = state.getOffset(basePos)
            val baseShape = shapeExtractor(state, basePos)

            for (probePos in OFFSET_PROBE_POSITIONS) {
                val probeOffset = state.getOffset(probePos)
                val dx = probeOffset.x - baseOffset.x
                val dy = probeOffset.y - baseOffset.y
                val dz = probeOffset.z - baseOffset.z
                if (abs(dx) <= OFFSET_EPSILON && abs(dy) <= OFFSET_EPSILON && abs(dz) <= OFFSET_EPSILON) {
                    continue
                }

                val probeShape = shapeExtractor(state, probePos)
                if (baseShape.isEmpty() && probeShape.isEmpty()) {
                    continue
                }

                if (shapesEqualAfterMove(baseShape, probeShape, dx, dy, dz)) {
                    return true
                }
            }
        }

        return false
    }

    private fun normalizeShapeAabbs(
        state: BlockState,
        pos: BlockPos,
        usesOffset: Boolean,
        shapeExtractor: (BlockState, BlockPos) -> List<AABB>,
    ): List<AABB> {
        val shapeAabbs = shapeExtractor(state, pos)
        if (!usesOffset || shapeAabbs.isEmpty()) {
            return shapeAabbs
        }

        val offset = state.getOffset(pos)
        return shapeAabbs.map { box -> box.move(-offset.x, -offset.y, -offset.z) }
    }

    /**
     * Computes shape data (default + overwrites) for a given shape extractor function.
     * Returns the default AABBs, their top-level shape indices, and the deduplicated
     * shape map used to build overwrites.
     */
    private fun computeShapeData(
        possibleStates: List<BlockState>,
        usesOffset: Boolean,
        shapeExtractor: (BlockState, BlockPos) -> List<AABB>,
    ): ShapeData {
        val shapeCounts = LinkedHashMap<List<AABB>, Int>()
        val shapeMap = LinkedHashMap<List<AABB>, JsonArray>()

        for (state in possibleStates) {
            val shapeAabbs = normalizeShapeAabbs(state, BlockPos.ZERO, usesOffset, shapeExtractor)
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
            ShapeData(mostFrequentEntry.key, shapeMap[mostFrequentEntry.key]!!, shapeMap)
        } else {
            ShapeData(emptyList(), JsonArray(), shapeMap)
        }
    }

    /**
     * Checks if two AABB lists differ.
     */
    private fun shapesDiffer(current: List<AABB>, default: List<AABB>): Boolean {
        if (current.size != default.size) return true
        return !current.zip(default).all { (c, d) -> c == d }
    }

    private fun emptyShapeDataJson(usesOffset: Boolean = false): JsonObject {
        val shapeJson = JsonObject()
        shapeJson.addProperty("usesOffset", usesOffset)
        shapeJson.add("default", JsonArray())
        shapeJson.add("overwrites", JsonArray())
        return shapeJson
    }

    private fun buildShapeDataJson(
        possibleStates: List<BlockState>,
        shapeData: ShapeData,
        usesOffset: Boolean,
        shapeName: String,
        shapeExtractor: (BlockState, BlockPos) -> List<AABB>,
    ): JsonObject {
        val shapeJson = JsonObject()
        shapeJson.addProperty("usesOffset", usesOffset)
        shapeJson.add("default", shapeData.defaultIdxs)

        val overwrites = JsonArray()
        for (i in possibleStates.indices) {
            val state = possibleStates[i]
            val currentAabbs = normalizeShapeAabbs(state, BlockPos.ZERO, usesOffset, shapeExtractor)

            if (shapesDiffer(currentAabbs, shapeData.defaultAabbs)) {
                val overwrite = JsonObject()
                val shapeIdxs = shapeData.shapeMap[currentAabbs] ?: run {
                    logger.error("$shapeName shape not found in map for state offset $i. Recalculating.")
                    val tempArray = JsonArray()
                    for (box in currentAabbs) {
                        val idx = shapes.putIfAbsent(box, shapes.size)
                        tempArray.add(Objects.requireNonNullElseGet(idx) { shapes.size - 1 })
                    }
                    tempArray
                }
                overwrite.addProperty("offset", i)
                overwrite.add("shapes", shapeIdxs)
                overwrites.add(overwrite)
            }
        }
        shapeJson.add("overwrites", overwrites)
        return shapeJson
    }

    fun createBlockShapesJson(block: Block): JsonObject {
        val resultJson = JsonObject()
        val possibleStates = block.stateDefinition.possibleStates

        if (possibleStates.isEmpty()) {
            resultJson.add("collision_shapes", emptyShapeDataJson())
            resultJson.add("support_shapes", emptyShapeDataJson())
            resultJson.add("outline_shapes", emptyShapeDataJson())
            resultJson.add("occlusion_shapes", emptyShapeDataJson())
            resultJson.add("interaction_shapes", emptyShapeDataJson())
            resultJson.add("visual_shapes", emptyShapeDataJson())
            return resultJson
        }

        val channels = listOf(
            ShapeChannel("collision_shapes", "Collision") { state, pos ->
                state.getCollisionShape(EmptyBlockGetter.INSTANCE, pos).toAabbs()
            },
            ShapeChannel("support_shapes", "Support") { state, pos ->
                state.getBlockSupportShape(EmptyBlockGetter.INSTANCE, pos).toAabbs()
            },
            ShapeChannel("outline_shapes", "Outline") { state, pos ->
                state.getShape(EmptyBlockGetter.INSTANCE, pos).toAabbs()
            },
            ShapeChannel("occlusion_shapes", "Occlusion") { state, _ ->
                state.getOcclusionShape().toAabbs()
            },
            ShapeChannel("interaction_shapes", "Interaction") { state, pos ->
                state.getInteractionShape(EmptyBlockGetter.INSTANCE, pos).toAabbs()
            },
            ShapeChannel("visual_shapes", "Visual") { state, pos ->
                state.getVisualShape(EmptyBlockGetter.INSTANCE, pos, CollisionContext.empty()).toAabbs()
            },
        )

        for (channel in channels) {
            val usesOffset = shapeChannelUsesOffset(possibleStates, channel.shapeExtractor)
            val shapeData = computeShapeData(possibleStates, usesOffset, channel.shapeExtractor)
            resultJson.add(
                channel.jsonName,
                buildShapeDataJson(
                    possibleStates,
                    shapeData,
                    usesOffset,
                    channel.displayName,
                    channel.shapeExtractor,
                )
            )
        }

        return resultJson
    }

    private fun stateLightProperties(state: BlockState): StateLightProperties {
        return StateLightProperties(
            state.lightEmission,
            state.lightDampening,
            state.useShapeForLightOcclusion()
        )
    }

    private fun stateLightPropertiesJson(properties: StateLightProperties): JsonObject {
        val json = JsonObject()
        json.addProperty("lightEmission", properties.lightEmission)
        json.addProperty("lightDampening", properties.lightDampening)
        json.addProperty("useShapeForLightOcclusion", properties.useShapeForLightOcclusion)
        return json
    }

    private fun createLightPropertiesJson(block: Block): JsonObject {
        val resultJson = JsonObject()
        val possibleStates = block.stateDefinition.possibleStates
        if (possibleStates.isEmpty()) {
            resultJson.add("default", stateLightPropertiesJson(StateLightProperties(0, 0, false)))
            resultJson.add("overwrites", JsonArray())
            return resultJson
        }

        val propertyCounts = LinkedHashMap<StateLightProperties, Int>()
        for (state in possibleStates) {
            propertyCounts.merge(stateLightProperties(state), 1, Int::plus)
        }

        var defaultProperties = stateLightProperties(possibleStates[0])
        var defaultCount = 0
        for ((properties, count) in propertyCounts) {
            if (count > defaultCount) {
                defaultProperties = properties
                defaultCount = count
            }
        }
        resultJson.add("default", stateLightPropertiesJson(defaultProperties))

        val overwrites = JsonArray()
        for (i in possibleStates.indices) {
            val currentProperties = stateLightProperties(possibleStates[i])
            if (currentProperties != defaultProperties) {
                val overwrite = stateLightPropertiesJson(currentProperties)
                overwrite.addProperty("offset", i)
                overwrites.add(overwrite)
            }
        }
        resultJson.add("overwrites", overwrites)
        return resultJson
    }

    private fun stateFluidProperties(state: BlockState): StateFluidProperties {
        val fluidState = state.fluidState
        return StateFluidProperties(
            BuiltInRegistries.FLUID.getKey(fluidState.type).path,
            fluidState.amount,
            fluidState.getOptionalValue(BlockStateProperties.FALLING).orElse(false),
        )
    }

    private fun stateFluidPropertiesJson(properties: StateFluidProperties): JsonObject {
        val json = JsonObject()
        json.addProperty("fluid", properties.fluid)
        json.addProperty("amount", properties.amount)
        json.addProperty("falling", properties.falling)
        return json
    }

    private fun createStateFluidPropertiesJson(block: Block): JsonObject {
        val resultJson = JsonObject()
        val possibleStates = block.stateDefinition.possibleStates
        if (possibleStates.isEmpty()) {
            resultJson.add("default", stateFluidPropertiesJson(StateFluidProperties("empty", 0, false)))
            resultJson.add("overwrites", JsonArray())
            return resultJson
        }

        val propertyCounts = LinkedHashMap<StateFluidProperties, Int>()
        for (state in possibleStates) {
            propertyCounts.merge(stateFluidProperties(state), 1, Int::plus)
        }

        var defaultProperties = stateFluidProperties(possibleStates[0])
        var defaultCount = 0
        for ((properties, count) in propertyCounts) {
            if (count > defaultCount) {
                defaultProperties = properties
                defaultCount = count
            }
        }
        resultJson.add("default", stateFluidPropertiesJson(defaultProperties))

        val overwrites = JsonArray()
        for (i in possibleStates.indices) {
            val currentProperties = stateFluidProperties(possibleStates[i])
            if (currentProperties != defaultProperties) {
                val overwrite = stateFluidPropertiesJson(currentProperties)
                overwrite.addProperty("offset", i)
                overwrites.add(overwrite)
            }
        }
        resultJson.add("overwrites", overwrites)
        return resultJson
    }

    private fun createStateBooleanPropertiesJson(
        block: Block,
        getValue: (BlockState) -> Boolean,
    ): JsonObject {
        val resultJson = JsonObject()
        val possibleStates = block.stateDefinition.possibleStates
        if (possibleStates.isEmpty()) {
            resultJson.addProperty("default", false)
            resultJson.add("overwrites", JsonArray())
            return resultJson
        }

        val propertyCounts = LinkedHashMap<Boolean, Int>()
        for (state in possibleStates) {
            propertyCounts.merge(getValue(state), 1, Int::plus)
        }

        var defaultValue = getValue(possibleStates[0])
        var defaultCount = 0
        for ((value, count) in propertyCounts) {
            if (count > defaultCount) {
                defaultValue = value
                defaultCount = count
            }
        }
        resultJson.addProperty("default", defaultValue)

        val overwrites = JsonArray()
        for (i in possibleStates.indices) {
            val currentValue = getValue(possibleStates[i])
            if (currentValue != defaultValue) {
                val overwrite = JsonObject()
                overwrite.addProperty("offset", i)
                overwrite.addProperty("value", currentValue)
                overwrites.add(overwrite)
            }
        }
        resultJson.add("overwrites", overwrites)
        return resultJson
    }

    private fun createStateMapColorJson(block: Block): JsonObject {
        val resultJson = JsonObject()
        val possibleStates = block.stateDefinition.possibleStates
        if (possibleStates.isEmpty()) {
            resultJson.addProperty("default", 0)
            resultJson.add("overwrites", JsonArray())
            return resultJson
        }

        val colorCounts = LinkedHashMap<Int, Int>()
        for (state in possibleStates) {
            val color = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).id
            colorCounts.merge(color, 1, Int::plus)
        }

        var defaultColor = possibleStates[0]
            .getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
            .id
        var defaultCount = 0
        for ((color, count) in colorCounts) {
            if (count > defaultCount) {
                defaultColor = color
                defaultCount = count
            }
        }
        resultJson.addProperty("default", defaultColor)

        val overwrites = JsonArray()
        for (i in possibleStates.indices) {
            val color = possibleStates[i]
                .getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
                .id
            if (color != defaultColor) {
                val overwrite = JsonObject()
                overwrite.addProperty("offset", i)
                overwrite.addProperty("value", color)
                overwrites.add(overwrite)
            }
        }
        resultJson.add("overwrites", overwrites)
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
            behaviourJson.addProperty("offsetType", getOffsetType(block).name)
            behaviourJson.addProperty("maxHorizontalOffset", getProtectedFloatMethodValue(block, "getMaxHorizontalOffset"))
            behaviourJson.addProperty("maxVerticalOffset", getProtectedFloatMethodValue(block, "getMaxVerticalOffset"))

            behaviourJson.addProperty("destroyTime", getPrivateFieldValue<Float>(behaviourProps, "destroyTime"))
            behaviourJson.addProperty(
                "explosionResistance",
                getPrivateFieldValue<Float>(behaviourProps, "explosionResistance")
            )
            behaviourJson.addProperty("ignitedByLava", getPrivateFieldValue<Boolean>(behaviourProps, "ignitedByLava"))

            behaviourJson.addProperty("liquid", getPrivateFieldValue<Boolean>(behaviourProps, "liquid"))
            behaviourJson.addProperty("isAir", getPrivateFieldValue<Boolean>(behaviourProps, "isAir"))
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
            blockJson.add("support_shapes", shapesStructureJson.getAsJsonObject("support_shapes"))
            blockJson.add("outline_shapes", shapesStructureJson.getAsJsonObject("outline_shapes"))
            blockJson.add("occlusion_shapes", shapesStructureJson.getAsJsonObject("occlusion_shapes"))
            blockJson.add("interaction_shapes", shapesStructureJson.getAsJsonObject("interaction_shapes"))
            blockJson.add("visual_shapes", shapesStructureJson.getAsJsonObject("visual_shapes"))
            blockJson.add("light_properties", createLightPropertiesJson(block))
            blockJson.add("fluid_state", createStateFluidPropertiesJson(block))
            blockJson.add("map_color", createStateMapColorJson(block))
            blockJson.add(
                "randomly_ticking",
                createStateBooleanPropertiesJson(block) { state -> state.isRandomlyTicking },
            )
            blockJson.add(
                "suffocating",
                createStateBooleanPropertiesJson(block) { state ->
                    state.isSuffocating(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
                },
            )
            blockJson.add(
                "redstone_conductor",
                createStateBooleanPropertiesJson(block) { state ->
                    state.isRedstoneConductor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
                },
            )

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
