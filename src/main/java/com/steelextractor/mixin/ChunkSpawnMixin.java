package com.steelextractor.mixin;

import com.steelextractor.ChunkSpawnTraces;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkStatusTasks.class)
public class ChunkSpawnMixin {
    @Inject(method = "generateSpawn", at = @At("HEAD"))
    private static void startSpawn(
        WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk,
        CallbackInfoReturnable<CompletableFuture<ChunkAccess>> callback
    ) {
        ChunkSpawnTraces.begin(context.level(), chunk.getPos());
    }

    @Inject(method = "generateSpawn", at = @At("RETURN"))
    private static void finishSpawn(
        WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk,
        CallbackInfoReturnable<CompletableFuture<ChunkAccess>> callback
    ) {
        ChunkSpawnTraces.finish();
    }
}
