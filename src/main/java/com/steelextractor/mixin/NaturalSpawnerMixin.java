package com.steelextractor.mixin;

import com.steelextractor.ChunkSpawnTraces;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NaturalSpawner.class)
public class NaturalSpawnerMixin {
    @Redirect(
        method = "spawnMobsForChunkGeneration",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/ServerLevelAccessor;addFreshEntityWithPassengers(Lnet/minecraft/world/entity/Entity;)V")
    )
    private static void recordEntity(ServerLevelAccessor level, Entity entity) {
        level.addFreshEntityWithPassengers(entity);
        ChunkSpawnTraces.recordEntity(entity);
    }

    @Inject(method = "spawnMobsForChunkGeneration", at = @At("RETURN"))
    private static void recordSpawnRandomState(
        ServerLevelAccessor level, BlockPos sourcePos, ChunkPos chunkPos, RandomSource random, CallbackInfo callback
    ) {
        ChunkSpawnTraces.recordOutcome(level, sourcePos, random);
    }
}
