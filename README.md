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
