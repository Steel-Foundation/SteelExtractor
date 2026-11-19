package com.steelextractor.mixin;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(WorldgenRandom.class)
public interface WorldgenRandomAccessor {
    @Accessor("randomSource")
    RandomSource steelExtractorRandomSource();
}
