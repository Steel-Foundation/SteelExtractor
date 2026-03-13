package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.Property
import net.minecraft.world.level.material.FlowingFluid
import org.slf4j.LoggerFactory
import java.lang.reflect.Field
import java.lang.reflect.Method

class Fluids : SteelExtractor.Extractor {
    private val logger = LoggerFactory.getLogger("steel-extractor-fluids")

    override fun fileName(): String {
        return "steel-registry/build_assets/fluids.json"
    }

    fun getConstantName(clazz: Class<*>, value: Any?): String? {
        for (f in clazz.fields) {
            try {
                val fieldValue = f.get(null)
                if (fieldValue === value) {
                    return f.name
                }
            } catch (_: IllegalAccessException) {
                // Ignore
            }
        }
        return null
    }

    inline fun <reified T : Any> getPrivateFieldValue(obj: Any, fieldName: String): T? {
        require(fieldName.isNotBlank()) { "Field name cannot be blank." }

        return try {
            val field: Field = obj.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.get(obj) as T?
        } catch (_: NoSuchFieldException) {
            null
        } catch (_: IllegalAccessException) {
            null
        } catch (_: ClassCastException) {
            null
        }
    }

    private fun getProtectedMethod(obj: Any, methodName: String, vararg paramTypes: Class<*>): Method? {
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            try {
                val method = clazz.getDeclaredMethod(methodName, *paramTypes)
                method.isAccessible = true
                return method
            } catch (_: NoSuchMethodException) {
                clazz = clazz.superclass
            }
        }
        return null
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val topLevelJson = JsonObject()
        val fluidsJson = JsonArray()
        val world = server.overworld()

        for (fluid in BuiltInRegistries.FLUID) {
            val key = BuiltInRegistries.FLUID.getKey(fluid)
            val name = key?.path ?: "unknown"

            val fluidJson = JsonObject()
            val id = BuiltInRegistries.FLUID.getId(fluid)

            fluidJson.addProperty("id", id)
            fluidJson.addProperty("name", name)

            try {
                val fluidState = fluid.defaultFluidState()

                // Block form
                val blockState = fluidState.createLegacyBlock()
                val blockKey = BuiltInRegistries.BLOCK.getKey(blockState.block)
                fluidJson.addProperty("block", blockKey?.path)

                // Bucket item
                val bucketItem = fluid.bucket
                val bucketKey = BuiltInRegistries.ITEM.getKey(bucketItem)
                fluidJson.addProperty("bucket_item", bucketKey?.path)

                // Behavior properties
                val behaviorJson = JsonObject()

                behaviorJson.addProperty("is_empty", fluidState.isEmpty)

                // Explosion resistance (protected method)
                val explosionResistanceMethod = getProtectedMethod(fluid, "getExplosionResistance")
                if (explosionResistanceMethod != null) {
                    val explosionResistance = explosionResistanceMethod.invoke(fluid) as Float
                    behaviorJson.addProperty("explosion_resistance", explosionResistance)
                }

                // FlowingFluid specific properties
                if (fluid is FlowingFluid) {
                    behaviorJson.addProperty("is_flowing_fluid", true)

                    // Source and flowing fluid references
                    val sourceFluid = fluid.source
                    val sourceKey = BuiltInRegistries.FLUID.getKey(sourceFluid)
                    behaviorJson.addProperty("source_fluid", sourceKey?.path)

                    val flowingFluid = fluid.flowing
                    val flowingKey = BuiltInRegistries.FLUID.getKey(flowingFluid)
                    behaviorJson.addProperty("flowing_fluid", flowingKey?.path)

                    // Tick delay
                    behaviorJson.addProperty("tick_delay", fluid.getTickDelay(world))

                    // Drop off (protected method)
                    val dropOffMethod =
                        getProtectedMethod(fluid, "getDropOff", net.minecraft.world.level.LevelReader::class.java)
                    if (dropOffMethod != null) {
                        val dropOff = dropOffMethod.invoke(fluid, world) as Int
                        behaviorJson.addProperty("drop_off", dropOff)
                    }

                    // Slope find distance (protected method)
                    val slopeFindDistanceMethod = getProtectedMethod(
                        fluid,
                        "getSlopeFindDistance",
                        net.minecraft.world.level.LevelReader::class.java
                    )
                    if (slopeFindDistanceMethod != null) {
                        val slopeFindDistance = slopeFindDistanceMethod.invoke(fluid, world) as Int
                        behaviorJson.addProperty("slope_find_distance", slopeFindDistance)
                    }
                } else {
                    behaviorJson.addProperty("is_flowing_fluid", false)
                }

                fluidJson.add("behavior_properties", behaviorJson)

                // Properties array (like blocks)
                val propsJson = JsonArray()
                for (prop in fluid.stateDefinition.properties) {
                    propsJson.add(getConstantName(BlockStateProperties::class.java, prop))
                }
                fluidJson.add("properties", propsJson)

                // Default properties array (like blocks)
                val defaultProps = JsonArray()
                for (prop in fluid.stateDefinition.properties) {
                    val comparableValue = fluidState.getValue(prop)
                    val valueString = (prop as Property<Comparable<*>>).getName(comparableValue as Comparable<*>)

                    val prefixedValueString = when (comparableValue) {
                        is Boolean -> "bool_$valueString"
                        is Enum<*> -> {
                            val fullClassName = comparableValue.javaClass.name
                            var classNamePart = fullClassName.substringAfterLast('.', "")
                            val anonymousClassRegex = "\\$\\d+$".toRegex()
                            classNamePart = classNamePart.replace(anonymousClassRegex, "")
                            val finalClassName = classNamePart.substringAfterLast('$', classNamePart)
                            "enum_${finalClassName}_$valueString"
                        }

                        is Number -> "int_$valueString"
                        else -> "unknown_$valueString"
                    }
                    defaultProps.add(prefixedValueString)
                }
                fluidJson.add("default_properties", defaultProps)

            } catch (e: Exception) {
                logger.warn("Failed to get info for $name: ${e.message}")
            }

            fluidsJson.add(fluidJson)
        }

        topLevelJson.add("fluids", fluidsJson)

        return topLevelJson
    }
}
