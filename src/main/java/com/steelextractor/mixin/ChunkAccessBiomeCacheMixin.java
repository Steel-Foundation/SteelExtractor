package com.steelextractor.mixin;

import com.steelextractor.BiomeCacheReset;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkAccess.class)
public abstract class ChunkAccessBiomeCacheMixin {

    /**
     * Match Steel's deterministic per-chunk biome sampler: vanilla still uses
     * its R-tree warm-start within the chunk, but not across chunk boundaries.
     */
    @Inject(method = "fillBiomesFromNoise", at = @At("HEAD"))
    private void resetBiomeCacheForChunk(BiomeResolver biomeResolver, CallbackInfo ci) {
        if (biomeResolver instanceof MultiNoiseBiomeSource) {
            BiomeCacheReset.markNeeded();
        }
    }
}
