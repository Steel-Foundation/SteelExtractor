# SteelExtractor

## Java BlockPos set test fixtures

Run `./gradlew extractJavaBlockPosSets` with Java 25. This dedicated task supplies
its reflection and sequential identity-hash JVM flags without changing the flags
used by server/worldgen extraction.

The output is `run/steel_extractor_output/steel-core/test_assets/java_block_pos_sets.json`.
`STEEL_EXTRACTOR_OUTPUT_DIRECTORY` overrides the output root. Copy the produced file
into Steel's matching `steel-core/test_assets/` directory and run
`cargo test -p steel-core --lib java_block_pos_set` in Steel.

The fixture records each insertion's result, table capacity, tree-bin count,
iteration-order SHA-256, and tree-shape SHA-256. It includes distinct-hash resizes,
tree splits, duplicate keys, signed hashes, overflowing coordinates, and equal-hash
keys whose JVM identity order matches insertion order.

## TNT explosion-order test fixture

Run `./gradlew extractTntExplosionOrder` with Java 25. It creates a fresh vanilla
flat GameTest level, places the obsidian-channel/TNT fixture, and captures a
radius-four explosion with the level RNG seeded to 1. The capture runs without
advancing entity ticks; the temporary world is closed and deleted afterward.

The output is `run/steel_extractor_output/steel-core/test_assets/vanilla_tnt_order.json`.
`STEEL_EXTRACTOR_OUTPUT_DIRECTORY` overrides the output root. Copy the produced
file into Steel's matching `steel-core/test_assets/` directory and run
`cargo test -p steel-core --lib radius_four_tnt_matches_vanilla_order_and_fuse_bounds`.

The JSON records the ray-insertion digest, set iteration order, shuffled block
callback order, primed TNT positions/fuses, and surviving TNT. Steel checks fuse
bounds and priming order because its live gameplay RNG is unseeded.
