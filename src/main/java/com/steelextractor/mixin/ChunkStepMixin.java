package com.steelextractor.mixin;

import com.steelextractor.BlockHashResult;
import com.steelextractor.ChunkStageHashStorage;
import net.minecraft.util.profiling.jfr.callback.ProfiledDuration;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

@Mixin(ChunkStep.class)
public class ChunkStepMixin {

    private static final Set<ChunkStatus> BLOCK_MODIFYING_STAGES = Set.of(
        ChunkStatus.NOISE,
        ChunkStatus.SURFACE,
        ChunkStatus.CARVERS,
        ChunkStatus.FEATURES
    );

    @Shadow
    @Final
    private ChunkStatus targetStatus;

    @Inject(method = "completeChunkGeneration", at = @At("RETURN"))
    private void onChunkGenerationComplete(ChunkAccess chunk, ProfiledDuration profiledDuration, CallbackInfoReturnable<ChunkAccess> cir) {
        String dimension = ChunkStageHashStorage.INSTANCE.getCurrentDimension();

        if (!ChunkStageHashStorage.INSTANCE.isTracking(chunk.getPos(), dimension)) {
            return;
        }

        if (BLOCK_MODIFYING_STAGES.contains(this.targetStatus)) {
            LevelChunkSection[] sections = chunk.getSections();
            if (ChunkStageHashStorage.INSTANCE.getEnableBinaryDump()) {
                BlockHashResult result = ChunkStageHashStorage.INSTANCE.computeBlockHashWithData(java.util.Arrays.asList(sections));
                ChunkStageHashStorage.INSTANCE.storeHash(chunk.getPos(), dimension, this.targetStatus.toString(), result.getHash());
                ChunkStageHashStorage.INSTANCE.storeBlockData(chunk.getPos(), dimension, this.targetStatus.toString(), result.getSectionData());
            } else {
                String hash = ChunkStageHashStorage.INSTANCE.computeBlockHash(java.util.Arrays.asList(sections));
                ChunkStageHashStorage.INSTANCE.storeHash(chunk.getPos(), dimension, this.targetStatus.toString(), hash);
            }
        }

        if (this.targetStatus == ChunkStatus.SURFACE) {
            ChunkStageHashStorage.INSTANCE.markReady(chunk.getPos(), dimension);
        }
    }
}
