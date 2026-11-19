package com.steelextractor;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.steelextractor.mixin.LegacyRandomSourceAccessor;
import com.steelextractor.mixin.WorldgenRandomAccessor;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.attribute.EnvironmentAttributes;

public final class ChunkSpawnTraces {
    public static final String OUTPUT_PATH = "steel-core/test_assets/chunk_spawn_hashes.json";
    private static final HexFormat HEX = HexFormat.of();
    private static final ThreadLocal<Trace> ACTIVE = new ThreadLocal<>();
    private static final Map<DimChunkPos, JsonObject> TRACES = new ConcurrentHashMap<>();
    private static volatile boolean enabled;

    private ChunkSpawnTraces() {}

    public static void enable() {
        enabled = true;
    }

    public static boolean contains(ChunkPos chunk, String dimension) {
        return TRACES.containsKey(new DimChunkPos(chunk, dimension));
    }

    public static void begin(ServerLevel level, ChunkPos chunk) {
        if (!enabled) return;
        String dimension = level.dimension().identifier().toString();
        if (ChunkStageHashStorage.INSTANCE.isTracking(chunk, dimension)) {
            ACTIVE.set(new Trace(new DimChunkPos(chunk, dimension), level));
        }
    }

    public static void recordEntity(Entity entity) {
        Trace trace = ACTIVE.get();
        if (trace == null) return;
        for (Entity passengerOrRoot : entity.getSelfAndPassengers().toList()) {
            trace.spawns.add(Spawn.of(passengerOrRoot));
        }
    }

    public static void recordOutcome(ServerLevelAccessor level, BlockPos sourcePos, RandomSource selectionRandom) {
        Trace trace = ACTIVE.get();
        if (trace == null) return;

        trace.selectionState = randomState(selectionRandom);
        trace.regionState = randomState(level.getRandom());
        if (!trace.spawnMobs) return;

        // Resolve the diagnostic biome IDs after the spawn loop so the reads cannot affect it.
        var biomes = level.getBiomeManager();
        trace.sourceBiome = biomes.getBiome(sourcePos).unwrapKey().orElseThrow().identifier().toString();
        trace.probabilityBiome = biomes.getNoiseBiomeAtPosition(sourcePos).unwrapKey().orElseThrow().identifier().toString();
        float probability = level.environmentAttributes().getValue(EnvironmentAttributes.CREATURE_WORLD_GEN_SPAWN_PROBABILITY, sourcePos);
        trace.creatureProbabilityBits = HEX.toHexDigits(Float.floatToRawIntBits(probability));
    }

    public static void finish() {
        Trace trace = ACTIVE.get();
        if (trace == null) return;
        ACTIVE.remove();
        if (TRACES.putIfAbsent(trace.key, trace.toJson()) != null) {
            throw new IllegalStateException("Duplicate spawn-stage trace for " + trace.key);
        }
    }

    public static JsonObject extract(long worldSeed) {
        var json = new JsonObject();
        json.addProperty("seed", worldSeed);
        json.addProperty("minecraft_version", SharedConstants.getCurrentVersion().name());
        var dimensions = new JsonObject();
        var grouped = TRACES.entrySet().stream().collect(Collectors.groupingBy(entry -> entry.getKey().getDimension()));
        grouped.keySet().stream().sorted().forEach(dimension -> {
            var chunks = new JsonArray();
            Comparator<Map.Entry<DimChunkPos, JsonObject>> byPosition = Comparator
                .comparingInt((Map.Entry<DimChunkPos, JsonObject> entry) -> entry.getKey().getPos().x())
                .thenComparingInt(entry -> entry.getKey().getPos().z());
            grouped.get(dimension).stream().sorted(byPosition).forEach(entry -> chunks.add(entry.getValue()));
            var dimensionJson = new JsonObject();
            dimensionJson.add("chunks", chunks);
            dimensions.add(dimension, dimensionJson);
        });
        json.add("dimensions", dimensions);
        return json;
    }

    private static JsonObject randomState(RandomSource random) {
        if (random instanceof WorldgenRandom) {
            random = ((WorldgenRandomAccessor) random).steelExtractorRandomSource();
        }
        var state = new JsonObject();
        if (random instanceof LegacyRandomSource) {
            state.addProperty("kind", "legacy");
            state.addProperty("seed", HEX.toHexDigits(((LegacyRandomSourceAccessor) random).steelExtractorSeed().get()));
        } else if (random instanceof XoroshiroRandomSource xoroshiro) {
            var seeds = XoroshiroRandomSource.CODEC.encodeStart(JsonOps.INSTANCE, xoroshiro).getOrThrow().getAsJsonArray();
            state.addProperty("kind", "xoroshiro");
            state.addProperty("seed_lo", HEX.toHexDigits(seeds.get(0).getAsLong()));
            state.addProperty("seed_hi", HEX.toHexDigits(seeds.get(1).getAsLong()));
        } else {
            throw new IllegalArgumentException("Unsupported spawn random source: " + random.getClass().getName());
        }
        return state;
    }

    private static final class Trace {
        private final DimChunkPos key;
        private final long clockTime;
        private final String difficulty;
        private final boolean spawnMobs;
        private final List<Spawn> spawns = new ArrayList<>();
        private String sourceBiome;
        private String probabilityBiome;
        private String creatureProbabilityBits;
        private JsonObject selectionState;
        private JsonObject regionState;

        private Trace(DimChunkPos key, ServerLevel level) {
            this.key = key;
            this.clockTime = level.getOverworldClockTime();
            this.difficulty = level.getDifficulty().getSerializedName();
            this.spawnMobs = level.getGameRules().get(GameRules.SPAWN_MOBS);
        }

        private JsonObject toJson() {
            var json = new JsonObject();
            json.addProperty("x", key.getPos().x());
            json.addProperty("z", key.getPos().z());
            json.addProperty("clock_time", clockTime);
            json.addProperty("difficulty", difficulty);
            json.addProperty("spawn_mobs", spawnMobs);
            json.addProperty("source_biome", sourceBiome);
            json.addProperty("probability_biome", probabilityBiome);
            json.addProperty("creature_probability_bits", creatureProbabilityBits);
            json.add("selection_random_state", Objects.requireNonNullElse(selectionState, JsonNull.INSTANCE));
            json.add("region_random_state", Objects.requireNonNullElse(regionState, JsonNull.INSTANCE));
            var spawnJson = new JsonArray();
            for (Spawn spawn : spawns) spawnJson.add(spawn.toJson());
            json.add("spawns", spawnJson);
            json.addProperty("hash", hashSpawns());
            return json;
        }

        // Big-endian spawn count, followed by UTF-8 ID byte length, ID bytes, xyz f64 bits and yaw f32 bits.
        private String hashSpawns() {
            final MessageDigest hash;
            try {
                hash = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException exception) {
                throw new AssertionError("Required MD5 algorithm is unavailable", exception);
            }
            hash.update(ByteBuffer.allocate(Integer.BYTES).putInt(spawns.size()).array());
            for (Spawn spawn : spawns) {
                byte[] entity = spawn.entity.getBytes(StandardCharsets.UTF_8);
                hash.update(ByteBuffer.allocate(Integer.BYTES).putInt(entity.length).array());
                hash.update(entity);
                hash.update(ByteBuffer.allocate(Long.BYTES * 3 + Integer.BYTES)
                    .putLong(spawn.xBits).putLong(spawn.yBits).putLong(spawn.zBits).putInt(spawn.yawBits).array());
            }
            return HEX.formatHex(hash.digest());
        }
    }

    private record Spawn(String entity, long xBits, long yBits, long zBits, int yawBits) {
        private static Spawn of(Entity entity) {
            return new Spawn(
                BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                Double.doubleToRawLongBits(entity.getX()),
                Double.doubleToRawLongBits(entity.getY()),
                Double.doubleToRawLongBits(entity.getZ()),
                Float.floatToRawIntBits(entity.getYRot())
            );
        }

        private JsonObject toJson() {
            var json = new JsonObject();
            json.addProperty("entity", entity);
            json.addProperty("x_bits", HEX.toHexDigits(xBits));
            json.addProperty("y_bits", HEX.toHexDigits(yBits));
            json.addProperty("z_bits", HEX.toHexDigits(zBits));
            json.addProperty("yaw_bits", HEX.toHexDigits(yawBits));
            return json;
        }
    }
}
