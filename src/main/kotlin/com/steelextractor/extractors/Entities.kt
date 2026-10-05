package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.steelextractor.SteelExtractor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.Holder
import net.minecraft.core.particles.ColorParticleOption
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ClientInformation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityAttachment
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.monster.zombie.ZombieVillager
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.entity.npc.villager.VillagerData
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.attributes.DefaultAttributes
import net.minecraft.world.entity.boss.enderdragon.EnderDragon
import org.slf4j.LoggerFactory
import java.lang.reflect.Field
import java.util.UUID
import com.mojang.authlib.GameProfile
import net.minecraft.world.entity.EntityTypes

class Entities : SteelExtractor.Extractor {
    private val logger = LoggerFactory.getLogger("steel-extractor-entities")

    private data class SynchedAccessorInfo(
        val accessor: EntityDataAccessor<*>,
        val fieldName: String,
        val declaringClass: Class<*>,
        val rawFieldName: String
    )

    // Cache reflection fields
    private val entityDataField: Field = Entity::class.java.getDeclaredField("entityData").apply { isAccessible = true }
    private val itemsByIdField: Field =
        SynchedEntityData::class.java.getDeclaredField("itemsById").apply { isAccessible = true }
    private val dataItemClass = Class.forName("net.minecraft.network.syncher.SynchedEntityData\$DataItem")
    private val accessorField: Field = dataItemClass.getDeclaredField("accessor").apply { isAccessible = true }
    private val initialValueField: Field = dataItemClass.getDeclaredField("initialValue").apply { isAccessible = true }
    private val colorParticleColorField: Field =
        ColorParticleOption::class.java.getDeclaredField("color").apply { isAccessible = true }

    // Build serializer name lookup
    private val serializerNames: Map<Int, String> by lazy {
        val names = mutableMapOf<Int, String>()
        for (field in EntityDataSerializers::class.java.declaredFields) {
            if (net.minecraft.network.syncher.EntityDataSerializer::class.java.isAssignableFrom(field.type)) {
                field.isAccessible = true
                val serializer = field.get(null) as? net.minecraft.network.syncher.EntityDataSerializer<*>
                if (serializer != null) {
                    val id = EntityDataSerializers.getSerializedId(serializer)
                    if (id != -1) {
                        names[id] = field.name.lowercase()
                    }
                }
            }
        }
        names
    }

    override fun fileName(): String {
        return "steel-registry/build_assets/entities.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val entityTypesArray = JsonArray()
        val world = server.overworld()

        for (entityType in BuiltInRegistries.ENTITY_TYPE) {
            val key = BuiltInRegistries.ENTITY_TYPE.getKey(entityType)
            val name = key?.path ?: "unknown"

            val entityTypeJson = JsonObject()
            val id = BuiltInRegistries.ENTITY_TYPE.getId(entityType)

            entityTypeJson.addProperty("id", id)
            entityTypeJson.addProperty("name", name)

            try {
                // Default dimensions from EntityType
                val defaultDimensions = entityType.dimensions
                entityTypeJson.addProperty("width", defaultDimensions.width())
                entityTypeJson.addProperty("height", defaultDimensions.height())
                entityTypeJson.addProperty("eye_height", defaultDimensions.eyeHeight())
                entityTypeJson.addProperty("fixed", defaultDimensions.fixed())

                // Extract default attachments
                entityTypeJson.add("attachments", extractAttachments(defaultDimensions))

                // Try to create entity instance to get pose-specific dimensions and other data
                val entity = try {
                    @Suppress("UNCHECKED_CAST")
                    (entityType as EntityType<Entity>).create(world, net.minecraft.world.entity.EntitySpawnReason.LOAD)
                } catch (e: Exception) {
                    logger.warn("Failed to create entity instance for $name: ${e.message}")
                    null
                }

                val entityClass = entityJavaClass(entityType, entity)
                if (entityClass != null) {
                    entityTypeJson.addProperty("java_class", entityClass.name)
                    entityTypeJson.add("class_hierarchy", extractClassHierarchy(entityClass))
                }

                // Extract baby dimensions for ageable entities
                if (entity is LivingEntity) {
                    entityTypeJson.add("baby_dimensions", extractBabyDimensions(entity))
                }

                // Extract multi-part hitboxes (e.g., EnderDragon)
                if (entity is EnderDragon) {
                    logger.info("Extracting dragon parts for ender_dragon")
                    entityTypeJson.add("parts", extractDragonParts(entity))
                }

                entity?.discard()

                // Category
                entityTypeJson.addProperty("mob_category", entityType.category.name)

                // Tracking info
                entityTypeJson.addProperty("client_tracking_range", entityType.clientTrackingRange())
                entityTypeJson.addProperty("update_interval", entityType.updateInterval())

                // Flags
                entityTypeJson.addProperty("fire_immune", entityType.fireImmune())
                entityTypeJson.addProperty("summonable", entityType.canSummon())
                entityTypeJson.addProperty("allowed_in_peaceful", entityType.isAllowedInPeaceful())
                entityTypeJson.addProperty("can_serialize", entityType.canSerialize())
                entityTypeJson.addProperty("only_op_can_set_nbt", entityType.onlyOpCanSetNbt())
                entityTypeJson.addProperty("can_spawn_far_from_player", entityType.canSpawnFarFromPlayer())

                // Synched data
                entityTypeJson.add("synched_data", extractSynchedData(entityType, world))

                // Attributes (for LivingEntities)
                entityTypeJson.add("attributes", extractAttributes(entityType))

                // Behavior flags
                if (entity != null) {
                    entityTypeJson.add("flags", extractBehaviorFlags(entity))
                }

            } catch (e: Exception) {
                logger.warn("Failed to get info for ${key?.path}: ${e.message}")
            }

            entityTypesArray.add(entityTypeJson)
        }

        return entityTypesArray
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractSynchedData(
        entityType: EntityType<*>,
        world: net.minecraft.server.level.ServerLevel
    ): JsonObject {
        val synchedDataJson = JsonObject()

        try {
            val entity =
                (entityType as EntityType<Entity>).create(world, net.minecraft.world.entity.EntitySpawnReason.LOAD)

            if (entity != null) {
                writeSynchedDataFromEntity(entity, entity.javaClass, synchedDataJson)
                entity.discard()
            } else {
                // Entity can't be instantiated (e.g., Player).
                if (entityType == EntityTypes.PLAYER) {
                    val fakePlayer = createFakeServerPlayer(server = world.server)
                    if (fakePlayer != null) {
                        writeSynchedDataFromEntity(fakePlayer, Player::class.java, synchedDataJson)
                        fakePlayer.discard()
                    }
                } else {
                    // Fall back to static extraction of EntityDataAccessor fields.
                    val entityClass = entityType.baseClass
                    writeStaticSynchedData(entityClass, synchedDataJson)
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to extract synched data: ${e.message}")
        }

        return synchedDataJson
    }

    private fun writeSynchedDataFromEntity(
        entity: Entity,
        dataClass: Class<out Entity>,
        output: JsonObject
    ) {
        val entityData = entityDataField.get(entity) as SynchedEntityData
        val itemsById = itemsByIdField.get(entityData) as Array<*>
        val accessorNames = getAccessorNames(dataClass)
        val fieldsByClass = linkedMapOf<Class<*>, MutableList<JsonObject>>()
        val unresolvedFields = JsonArray()

        for (dataItem in itemsById) {
            if (dataItem == null) continue

            val accessor = accessorField.get(dataItem) as EntityDataAccessor<*>
            val serializerId = EntityDataSerializers.getSerializedId(accessor.serializer())
            val index = accessor.id()
            val accessorInfo = accessorNames[index]
            val runtimeInitializer = if (
                entity is ZombieVillager && accessorInfo?.rawFieldName == "DATA_VILLAGER_DATA"
            ) "random_villager_profession" else null
            val defaultValue = if (runtimeInitializer != null) {
                Villager.createDefaultVillagerData()
            } else {
                initialValueField.get(dataItem)
            }
            val fieldJson = synchedFieldJson(
                index,
                accessorInfo?.fieldName ?: "unknown",
                accessorInfo?.rawFieldName,
                serializerId,
                defaultValue
            )
            if (runtimeInitializer != null) {
                fieldJson.addProperty("runtime_initializer", runtimeInitializer)
            }

            if (accessorInfo == null) {
                unresolvedFields.add(fieldJson)
            } else {
                fieldsByClass.getOrPut(accessorInfo.declaringClass) { mutableListOf() }.add(fieldJson)
            }
        }

        writeSynchedDataLayers(output, dataClass, fieldsByClass, unresolvedFields)
    }

    private fun writeStaticSynchedData(dataClass: Class<out Entity>, output: JsonObject) {
        val fieldsByClass = linkedMapOf<Class<*>, MutableList<JsonObject>>()
        for ((index, accessorInfo) in getAccessorNames(dataClass).toSortedMap()) {
            val serializerId = EntityDataSerializers.getSerializedId(accessorInfo.accessor.serializer())
            val fieldJson = synchedFieldJson(
                index,
                accessorInfo.fieldName,
                accessorInfo.rawFieldName,
                serializerId,
                null
            )
            annotateRuntimeInitializer(accessorInfo, fieldJson)
            fieldsByClass.getOrPut(accessorInfo.declaringClass) { mutableListOf() }.add(fieldJson)
        }

        writeSynchedDataLayers(output, dataClass, fieldsByClass, JsonArray())
    }

    private fun writeSynchedDataLayers(
        output: JsonObject,
        dataClass: Class<out Entity>,
        fieldsByClass: Map<Class<*>, List<JsonObject>>,
        unresolvedFields: JsonArray
    ) {
        output.addProperty("java_class", dataClass.name)
        output.add("class_hierarchy", extractClassHierarchy(dataClass))

        val layers = JsonArray()
        for (clazz in entityClassHierarchy(dataClass)) {
            val fields = fieldsByClass[clazz] ?: continue
            val layerJson = JsonObject()
            layerJson.addProperty("java_class", clazz.name)
            layerJson.addProperty("simple_name", clazz.simpleName)

            val fieldsJson = JsonArray()
            for (field in fields.sortedBy { it.get("index").asInt }) {
                fieldsJson.add(field)
            }
            layerJson.add("fields", fieldsJson)
            layers.add(layerJson)
        }
        output.add("layers", layers)

        if (unresolvedFields.size() > 0) {
            output.add("unresolved_fields", unresolvedFields)
        }
    }

    private fun synchedFieldJson(
        index: Int,
        fieldName: String,
        accessorField: String?,
        serializerId: Int,
        defaultValue: Any?
    ): JsonObject {
        val fieldJson = JsonObject()
        fieldJson.addProperty("index", index)
        fieldJson.addProperty("name", fieldName)
        if (accessorField != null) {
            fieldJson.addProperty("accessor_field", accessorField)
        }
        fieldJson.addProperty("serializer_id", serializerId)
        fieldJson.addProperty("serializer", serializerNames[serializerId] ?: "unknown")
        fieldJson.add("default_value", serializeDefaultValue(defaultValue))
        return fieldJson
    }

    private fun annotateRuntimeInitializer(accessor: SynchedAccessorInfo, fieldJson: JsonObject) {
        // ZombieVillager defines this synced value through
        // initializeZombieVillagerData(this.random), rather than a static default.
        if (
            accessor.declaringClass == ZombieVillager::class.java &&
            accessor.rawFieldName == "DATA_VILLAGER_DATA"
        ) {
            fieldJson.addProperty("runtime_initializer", "random_villager_profession")
        }
    }

    private fun entityJavaClass(entityType: EntityType<*>, entity: Entity?): Class<out Entity>? {
        if (entity != null) {
            return entity.javaClass
        }
        if (entityType == EntityTypes.PLAYER) {
            return Player::class.java
        }
        return null
    }

    private fun entityClassHierarchy(entityClass: Class<*>): List<Class<*>> {
        val classes = mutableListOf<Class<*>>()
        var clazz: Class<*>? = entityClass
        while (clazz != null && Entity::class.java.isAssignableFrom(clazz)) {
            classes.add(clazz)
            clazz = clazz.superclass
        }
        return classes.asReversed()
    }

    private fun extractClassHierarchy(entityClass: Class<*>): JsonArray {
        val hierarchy = JsonArray()
        for (entry in entityClassHierarchy(entityClass)) {
            val classJson = JsonObject()
            classJson.addProperty("java_class", entry.name)
            classJson.addProperty("simple_name", entry.simpleName)
            hierarchy.add(classJson)
        }
        return hierarchy
    }

    private fun getAccessorNames(entityClass: Class<*>): Map<Int, SynchedAccessorInfo> {
        val accessors = mutableMapOf<Int, SynchedAccessorInfo>()
        var clazz: Class<*>? = entityClass
        while (clazz != null && Entity::class.java.isAssignableFrom(clazz)) {
            for (field in clazz.declaredFields) {
                if (EntityDataAccessor::class.java.isAssignableFrom(field.type)) {
                    try {
                        field.isAccessible = true
                        val accessor = field.get(null) as? EntityDataAccessor<*>
                        if (accessor != null) {
                            val name = field.name.lowercase().removePrefix("data_")
                            accessors[accessor.id()] = SynchedAccessorInfo(accessor, name, clazz, field.name)
                        }
                    } catch (_: Exception) {
                        // Skip non-static or inaccessible fields
                    }
                }
            }
            clazz = clazz.superclass
        }
        return accessors
    }

    private fun createFakeServerPlayer(server: MinecraftServer): ServerPlayer? {
        return try {
            val world = server.overworld()
            val profile = GameProfile(UUID.randomUUID(), "FakePlayer")
            ServerPlayer(server, world, profile, ClientInformation.createDefault())
        } catch (e: Exception) {
            logger.warn("Failed to create fake ServerPlayer: ${e.message}")
            null
        }
    }

    private fun extractAttachments(dimensions: net.minecraft.world.entity.EntityDimensions): JsonObject {
        val attachmentsJson = JsonObject()
        val attachmentsObj = dimensions.attachments()

        for (attachmentType in EntityAttachment.entries) {
            val pointsArray = JsonArray()
            var index = 0
            while (true) {
                val point = attachmentsObj.getNullable(attachmentType, index, 0f)
                if (point == null) break
                val pointJson = JsonObject()
                pointJson.addProperty("x", point.x)
                pointJson.addProperty("y", point.y)
                pointJson.addProperty("z", point.z)
                pointsArray.add(pointJson)
                index++
            }
            if (pointsArray.size() > 0) {
                attachmentsJson.add(attachmentType.name.lowercase(), pointsArray)
            }
        }

        return attachmentsJson
    }

    private fun extractBabyDimensions(entity: LivingEntity): JsonObject? {
        try {
            // Get the age scale method via reflection to check if this entity supports babies
            val getAgeScaleMethod = LivingEntity::class.java.getDeclaredMethod("getAgeScale")
            getAgeScaleMethod.isAccessible = true
            val currentAgeScale = getAgeScaleMethod.invoke(entity) as Float

            // If age scale is 1.0, the entity is adult - try to get baby scale
            // Most baby mobs have 0.5 scale
            if (currentAgeScale == 1.0f) {
                // Check if entity has setBaby method (AgeableMob)
                val setBabyMethod = try {
                    entity::class.java.getMethod("setBaby", Boolean::class.java)
                } catch (_: NoSuchMethodException) {
                    null
                }

                if (setBabyMethod != null) {
                    // Set to baby, get dimensions, then restore
                    setBabyMethod.invoke(entity, true)
                    entity.refreshDimensions()

                    val babyDimensions = entity.getDimensions(Pose.STANDING)
                    val babyJson = JsonObject()
                    babyJson.addProperty("width", babyDimensions.width())
                    babyJson.addProperty("height", babyDimensions.height())
                    babyJson.addProperty("eye_height", babyDimensions.eyeHeight())

                    // Restore to adult
                    setBabyMethod.invoke(entity, false)
                    entity.refreshDimensions()

                    return babyJson
                }
            }
        } catch (_: Exception) {
            // Entity doesn't support baby dimensions
        }
        return null
    }

    private fun extractDragonParts(dragon: EnderDragon): JsonArray {
        val partsArray = JsonArray()
        val subEntities = dragon.getSubEntities()
        logger.info("Dragon has ${subEntities.size} sub-entities")

        for (part in subEntities) {
            val partJson = JsonObject()
            partJson.addProperty("name", part.name)
            val partDimensions = part.getDimensions(Pose.STANDING)
            partJson.addProperty("width", partDimensions.width())
            partJson.addProperty("height", partDimensions.height())
            partsArray.add(partJson)
        }

        return partsArray
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractAttributes(entityType: EntityType<*>): JsonObject? {
        try {
            // Only LivingEntities have attributes
            val supplier = DefaultAttributes.getSupplier(entityType as EntityType<out LivingEntity>)
            val attributesJson = JsonObject()

            // List of all known attributes to check
            val attributeHolders = listOf(
                Attributes.MAX_HEALTH,
                Attributes.FOLLOW_RANGE,
                Attributes.KNOCKBACK_RESISTANCE,
                Attributes.MOVEMENT_SPEED,
                Attributes.FLYING_SPEED,
                Attributes.ATTACK_DAMAGE,
                Attributes.ATTACK_KNOCKBACK,
                Attributes.ATTACK_SPEED,
                Attributes.ARMOR,
                Attributes.ARMOR_TOUGHNESS,
                Attributes.MAX_ABSORPTION,
                Attributes.LUCK,
                Attributes.SPAWN_REINFORCEMENTS_CHANCE,
                Attributes.JUMP_STRENGTH,
                Attributes.GRAVITY,
                Attributes.SAFE_FALL_DISTANCE,
                Attributes.FALL_DAMAGE_MULTIPLIER,
                Attributes.SCALE,
                Attributes.STEP_HEIGHT,
                Attributes.BLOCK_INTERACTION_RANGE,
                Attributes.ENTITY_INTERACTION_RANGE,
                Attributes.BLOCK_BREAK_SPEED,
                Attributes.MINING_EFFICIENCY,
                Attributes.SNEAKING_SPEED,
                Attributes.SUBMERGED_MINING_SPEED,
                Attributes.SWEEPING_DAMAGE_RATIO,
                Attributes.OXYGEN_BONUS,
                Attributes.WATER_MOVEMENT_EFFICIENCY,
                Attributes.BURNING_TIME,
                Attributes.EXPLOSION_KNOCKBACK_RESISTANCE,
                Attributes.MOVEMENT_EFFICIENCY,
                Attributes.CAMERA_DISTANCE,
                Attributes.WAYPOINT_TRANSMIT_RANGE,
                Attributes.WAYPOINT_RECEIVE_RANGE,
                Attributes.BOUNCINESS,
                Attributes.AIR_DRAG_MODIFIER,
                Attributes.FRICTION_MODIFIER,
                Attributes.NAME_TAG_DISTANCE,
                Attributes.BELOW_NAME_DISTANCE,
                Attributes.TEMPT_RANGE
            )

            for (holder in attributeHolders) {
                try {
                    if (supplier.hasAttribute(holder)) {
                        val baseValue = supplier.getBaseValue(holder)
                        val key = holder.unwrapKey().orElse(null)?.identifier()?.path ?: continue
                        attributesJson.addProperty(key, baseValue)
                    }
                } catch (_: Exception) {
                    // Attribute not present for this entity type
                }
            }

            return if (attributesJson.size() > 0) attributesJson else null
        } catch (_: Exception) {
            // Not a LivingEntity or doesn't have attributes
            return null
        }
    }

    private fun extractBehaviorFlags(entity: Entity): JsonObject {
        val flagsJson = JsonObject()

        flagsJson.addProperty("is_pushable", entity.isPushable)
        flagsJson.addProperty("is_attackable", entity.isAttackable)
        flagsJson.addProperty("is_pickable", entity.isPickable)
        flagsJson.addProperty("can_be_collided_with", entity.canBeCollidedWith(null))
        flagsJson.addProperty("is_pushed_by_fluid", entity.isPushedByFluid())
        flagsJson.addProperty("can_freeze", entity.canFreeze())
        flagsJson.addProperty("can_be_hit_by_projectile", entity.canBeHitByProjectile())
        flagsJson.addProperty("piston_push_reaction", entity.getPistonPushReaction().name)

        if (entity is LivingEntity) {
            flagsJson.addProperty("is_sensitive_to_water", entity.isSensitiveToWater)
            flagsJson.addProperty("can_breathe_underwater", entity.canBreatheUnderwater())
            flagsJson.addProperty("can_be_seen_as_enemy", entity.canBeSeenAsEnemy())
        }

        return flagsJson
    }

    private fun serializeDefaultValue(value: Any?): JsonElement {
        return when (value) {
            null -> JsonNull.INSTANCE
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            is java.util.Optional<*> -> {
                val obj = JsonObject()
                obj.addProperty("present", value.isPresent)
                if (value.isPresent) {
                    obj.add("value", serializeDefaultValue(value.get()))
                }
                obj
            }

            is java.util.OptionalInt -> {
                val obj = JsonObject()
                obj.addProperty("present", value.isPresent)
                if (value.isPresent) {
                    obj.addProperty("value", value.asInt)
                }
                obj
            }

            is net.minecraft.world.entity.Pose -> JsonPrimitive(value.name)
            is net.minecraft.core.Direction -> JsonPrimitive(value.name)
            is net.minecraft.world.item.ItemStack -> {
                if (value.isEmpty) {
                    JsonPrimitive("empty")
                } else {
                    val obj = JsonObject()
                    val itemKey = BuiltInRegistries.ITEM.getKey(value.item)
                    obj.addProperty("item", itemKey?.toString() ?: "unknown")
                    obj.addProperty("count", value.count)
                    obj
                }
            }

            is net.minecraft.core.BlockPos -> {
                val obj = JsonObject()
                obj.addProperty("x", value.x)
                obj.addProperty("y", value.y)
                obj.addProperty("z", value.z)
                obj
            }

            is net.minecraft.world.level.block.state.BlockState -> {
                val blockKey = BuiltInRegistries.BLOCK.getKey(value.block)
                JsonPrimitive(blockKey?.toString() ?: "unknown")
            }

            is net.minecraft.core.Rotations -> {
                val obj = JsonObject()
                obj.addProperty("x", value.x)
                obj.addProperty("y", value.y)
                obj.addProperty("z", value.z)
                obj
            }

            is net.minecraft.network.chat.Component -> {
                JsonPrimitive(value.string)
            }

            is ParticleOptions -> serializeParticleOptions(value)
            is VillagerData -> serializeVillagerData(value)

            is Holder<*> -> {
                val key = value.unwrapKey()
                if (key.isPresent) {
                    JsonPrimitive(key.get().identifier().toString())
                } else {
                    JsonPrimitive("unknown_holder")
                }
            }

            is List<*> -> {
                val arr = JsonArray()
                for (item in value) {
                    arr.add(serializeDefaultValue(item))
                }
                arr
            }

            is org.joml.Vector3f -> {
                val obj = JsonObject()
                obj.addProperty("x", value.x)
                obj.addProperty("y", value.y)
                obj.addProperty("z", value.z)
                obj
            }

            is org.joml.Quaternionf -> {
                val obj = JsonObject()
                obj.addProperty("x", value.x)
                obj.addProperty("y", value.y)
                obj.addProperty("z", value.z)
                obj.addProperty("w", value.w)
                obj
            }

            is Enum<*> -> JsonPrimitive(value.name)
            else -> JsonPrimitive(value::class.java.simpleName)
        }
    }

    private fun serializeParticleOptions(value: ParticleOptions): JsonElement {
        val typeKey = BuiltInRegistries.PARTICLE_TYPE.getKey(value.type)
            ?: error("Particle options default has unregistered type: ${value.type}")
        val obj = JsonObject()
        obj.addProperty("type", typeKey.toString())

        val options = JsonObject()
        when (value) {
            is ColorParticleOption -> {
                options.addProperty("kind", "color")
                options.addProperty("color", colorParticleColorField.getInt(value))
            }

            else -> {
                options.addProperty("kind", "none")
            }
        }
        obj.add("options", options)
        return obj
    }

    private fun serializeVillagerData(value: VillagerData): JsonElement {
        val obj = JsonObject()
        obj.addProperty("type", holderKey(value.type()))
        obj.addProperty("profession", holderKey(value.profession()))
        obj.addProperty("level", value.level())
        return obj
    }

    private fun holderKey(holder: Holder<*>): String {
        return holder.unwrapKey()
            .map { key -> key.identifier().toString() }
            .orElseThrow { IllegalStateException("Default holder has no registry key: $holder") }
    }
}
