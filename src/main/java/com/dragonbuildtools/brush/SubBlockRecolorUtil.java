package com.dragonbuildtools.brush;

import com.dragonbuildtools.util.BlockColorMapper;
import com.dragonbuildtools.util.BlockColorMapper.PaletteEntry;
import com.dragonbuildtools.util.BlockColorMapper.SubBlockFamily;
import com.dragonbuildtools.util.VanillaBlockAppearanceResolver;
import com.dragonbuildtools.util.VanillaBlockAppearanceResolver.BlockColorRecord;
import com.dragonbuildtools.util.VanillaBlockAppearanceResolver.ShapeKind;
import com.fastasyncworldedit.core.registry.state.PropertyKey;
import com.sk89q.worldedit.registry.state.Property;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Recolors any Minecraft {@link BlockState} (full blocks AND sub-blocks: stairs, slabs, walls,
 * fences, fence gates, trapdoors, carpets, glass panes, buttons, pressure plates, leaves, and
 * stained glass) to a target sRGB color while preserving the exact original sub-block geometry
 * and all blockstate properties (facing, half, corner shape, connections, open/closed, waterlogged).
 */
public final class SubBlockRecolorUtil {

    public enum RecolorBucket {
        CUBE,
        TRANSLUCENT_CUBE,
        LEAVES,
        STAIRS,
        SLAB,
        WALL,
        FENCE,
        FENCE_GATE,
        TRAPDOOR,
        CARPET,
        PANE,
        BUTTON,
        PLATE,
        FIXED
    }

    public static final class CandidateColorEntry {
        public final String blockName;
        public final int r;
        public final int g;
        public final int b;
        public final RecolorBucket bucket;

        public CandidateColorEntry(String blockName, int r, int g, int b, RecolorBucket bucket) {
            this.blockName = blockName;
            this.r = r;
            this.g = g;
            this.b = b;
            this.bucket = bucket;
        }
    }

    /** Minimum squared perceptual distance before replacing an existing block with a new color. */
    private static final double MIN_CHANGE_DIST_SQ = 8.0;

    private static volatile Map<RecolorBucket, List<CandidateColorEntry>> cachedBuckets;

    private SubBlockRecolorUtil() {
    }

    /**
     * Classifies a block ID into its exact {@link RecolorBucket} so sub-blocks only ever map to
     * matching sub-blocks of the same structural class.
     */
    public static RecolorBucket classifyBucketByName(String rawBlockId) {
        if (rawBlockId == null || rawBlockId.isEmpty()) {
            return RecolorBucket.FIXED;
        }
        String name = VanillaBlockAppearanceResolver.cleanBlockName(rawBlockId);
        if (name.equals("air") || name.equals("cave_air") || name.equals("void_air")
                || name.equals("water") || name.equals("lava") || name.equals("bubble_column")
                || name.equals("barrier") || name.equals("light") || name.equals("structure_void")
                || name.equals("bedrock") || name.equals("snow")) {
            // Note: snow[layers=1..7] has no colored multi-layer equivalent in vanilla, so keep its exact snow layer state
            return RecolorBucket.FIXED;
        }
        if (name.endsWith("_stairs")) return RecolorBucket.STAIRS;
        if (name.endsWith("_slab")) return RecolorBucket.SLAB;
        if (name.endsWith("_wall")) return RecolorBucket.WALL;
        if (name.endsWith("_fence_gate")) return RecolorBucket.FENCE_GATE;
        if (name.endsWith("_fence")) return RecolorBucket.FENCE;
        if (name.endsWith("_trapdoor")) return RecolorBucket.TRAPDOOR;
        if (name.endsWith("_carpet")) return RecolorBucket.CARPET;
        if (name.endsWith("_pane") || name.endsWith("_bars")) return RecolorBucket.PANE;
        if (name.endsWith("_button")) return RecolorBucket.BUTTON;
        if (name.endsWith("_pressure_plate")) return RecolorBucket.PLATE;
        if (name.endsWith("_leaves")) return RecolorBucket.LEAVES;

        BlockColorRecord rec = VanillaBlockAppearanceResolver.getEmbeddedTable().get(name);
        if (rec != null) {
            if (rec.shapeKind == ShapeKind.TRANSLUCENT_CUBE) {
                return RecolorBucket.TRANSLUCENT_CUBE;
            }
            if (rec.shapeKind == ShapeKind.CUBE) {
                return RecolorBucket.CUBE;
            }
            return RecolorBucket.FIXED;
        }
        if (name.contains("stained_glass") || name.equals("tinted_glass") || name.equals("glass")) {
            return RecolorBucket.TRANSLUCENT_CUBE;
        }
        return RecolorBucket.FIXED;
    }

    /**
     * Returns the visual volume weight (0..1) of a block for Gaussian color convolution,
     * or {@code 0.0} for air, liquids, plants, and invisible blocks so they never taint colors.
     */
    public static double getConvolutionWeight(BlockState state) {
        if (state == null || state.getBlockType() == null) {
            return 0.0;
        }
        String name = VanillaBlockAppearanceResolver.cleanBlockName(state.getBlockType().id());
        if (name.equals("snow")) {
            try {
                Object layersObj = state.getState(PropertyKey.LAYERS);
                int layers = layersObj instanceof Number ? ((Number) layersObj).intValue() : Integer.parseInt(String.valueOf(layersObj));
                return Math.max(0.125, Math.min(1.0, layers / 8.0));
            } catch (Throwable ignored) {
                return 0.25;
            }
        }
        RecolorBucket bucket = classifyBucketByName(name);
        switch (bucket) {
            case CUBE:
            case TRANSLUCENT_CUBE:
            case LEAVES:
                return 1.0;
            case STAIRS:
                return 0.75;
            case SLAB:
                return 0.5;
            case WALL:
            case FENCE:
            case FENCE_GATE:
            case PANE:
                return 0.4;
            case TRAPDOOR:
            case CARPET:
            case PLATE:
            case BUTTON:
                return 0.25;
            case FIXED:
            default:
                return 0.0;
        }
    }

    /**
     * Builds (and caches) all valid color candidates per {@link RecolorBucket} using the 1,200-block
     * embedded sRGB table, excluding un-waxed oxidizable copper, gravity blocks, and tile entities.
     */
    public static Map<RecolorBucket, List<CandidateColorEntry>> getCandidateBuckets() {
        Map<RecolorBucket, List<CandidateColorEntry>> current = cachedBuckets;
        if (current != null) {
            return current;
        }
        synchronized (SubBlockRecolorUtil.class) {
            if (cachedBuckets != null) {
                return cachedBuckets;
            }
            Map<RecolorBucket, List<CandidateColorEntry>> map = new EnumMap<>(RecolorBucket.class);
            for (RecolorBucket b : RecolorBucket.values()) {
                map.put(b, new ArrayList<>());
            }

            Map<String, BlockColorRecord> table = VanillaBlockAppearanceResolver.getEmbeddedTable();

            // 1. Curated solid full-cube builder blocks (same safe non-gravity, non-tile-entity set as BlockColorMapper)
            for (String solidName : CURATED_SOLID_BLOCK_NAMES) {
                BlockColorRecord rec = table.get(solidName);
                if (rec != null) {
                    int[] rgb = averageRecordRgb(rec);
                    map.get(RecolorBucket.CUBE).add(new CandidateColorEntry(solidName, rgb[0], rgb[1], rgb[2], RecolorBucket.CUBE));
                }
            }

            // 2. Stained glass cubes
            for (String glassName : STAINED_GLASS_NAMES) {
                BlockColorRecord rec = table.get(glassName);
                if (rec != null) {
                    int[] rgb = averageRecordRgb(rec);
                    map.get(RecolorBucket.TRANSLUCENT_CUBE).add(
                            new CandidateColorEntry(glassName, rgb[0], rgb[1], rgb[2], RecolorBucket.TRANSLUCENT_CUBE)
                    );
                }
            }

            // 3. Scan all sub-blocks & leaves in the 1,200-block table
            for (BlockColorRecord rec : table.values()) {
                String name = rec.blockName;
                if (isUnwaxedOxidizableCopper(name) || name.equals("petrified_oak_slab")) {
                    continue;
                }
                RecolorBucket bucket = classifyBucketByName(name);
                if (bucket == RecolorBucket.STAIRS
                        || bucket == RecolorBucket.SLAB
                        || bucket == RecolorBucket.WALL
                        || bucket == RecolorBucket.FENCE
                        || bucket == RecolorBucket.FENCE_GATE
                        || bucket == RecolorBucket.TRAPDOOR
                        || bucket == RecolorBucket.CARPET
                        || bucket == RecolorBucket.PANE
                        || bucket == RecolorBucket.BUTTON
                        || bucket == RecolorBucket.PLATE
                        || bucket == RecolorBucket.LEAVES) {
                    int[] rgb = averageRecordRgb(rec);
                    map.get(bucket).add(new CandidateColorEntry(name, rgb[0], rgb[1], rgb[2], bucket));
                }
            }

            Map<RecolorBucket, List<CandidateColorEntry>> unmodifiable = new EnumMap<>(RecolorBucket.class);
            for (Map.Entry<RecolorBucket, List<CandidateColorEntry>> e : map.entrySet()) {
                unmodifiable.put(e.getKey(), Collections.unmodifiableList(e.getValue()));
            }
            cachedBuckets = Collections.unmodifiableMap(unmodifiable);
            return cachedBuckets;
        }
    }

    /**
     * Pure-data lookup of the closest block ID in the same {@link RecolorBucket} (works both on a live server
     * and in headless unit tests).
     */
    public static CandidateColorEntry findBestCandidate(
            RecolorBucket bucket,
            int targetR,
            int targetG,
            int targetB,
            Set<String> optionalLocalPaletteNames,
            boolean checkRuntimeAvailability
    ) {
        if (bucket == null || bucket == RecolorBucket.FIXED) {
            return null;
        }
        List<CandidateColorEntry> pool = getCandidateBuckets().get(bucket);
        if (pool == null || pool.isEmpty()) {
            return null;
        }

        // First try local-palette restricted candidates if at least 2 options exist in this bucket
        if (optionalLocalPaletteNames != null && !optionalLocalPaletteNames.isEmpty()) {
            CandidateColorEntry bestLocal = null;
            double bestLocalDist = Double.MAX_VALUE;
            int localCount = 0;
            for (CandidateColorEntry entry : pool) {
                if (!optionalLocalPaletteNames.contains(entry.blockName)) {
                    continue;
                }
                if (checkRuntimeAvailability && !isBlockAvailableOnServer(entry.blockName)) {
                    continue;
                }
                localCount++;
                double d = BlockColorMapper.colorDistanceSq(targetR, targetG, targetB, entry.r, entry.g, entry.b);
                if (d < bestLocalDist) {
                    bestLocalDist = d;
                    bestLocal = entry;
                }
            }
            if (localCount >= 2 && bestLocal != null) {
                return bestLocal;
            }
        }

        CandidateColorEntry best = null;
        double bestDist = Double.MAX_VALUE;
        for (CandidateColorEntry entry : pool) {
            if (checkRuntimeAvailability && !isBlockAvailableOnServer(entry.blockName)) {
                continue;
            }
            double d = BlockColorMapper.colorDistanceSq(targetR, targetG, targetB, entry.r, entry.g, entry.b);
            if (d < bestDist) {
                bestDist = d;
                best = entry;
            }
        }
        return best;
    }

    /**
     * Expands a set of observed block names in a brush region into all sibling sub-block/full-block names
     * belonging to the same material families so local-palette mode ({@code -p}) works seamlessly across
     * full blocks, stairs, slabs, walls, fences, trapdoors, carpets, buttons, and plates.
     */
    public static Set<String> expandLocalPaletteFamilies(Set<String> observedBlockNames) {
        if (observedBlockNames == null || observedBlockNames.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> expanded = new HashSet<>(observedBlockNames);
        Set<String> stems = new HashSet<>();
        for (String raw : observedBlockNames) {
            String clean = VanillaBlockAppearanceResolver.cleanBlockName(raw);
            expanded.add(clean);
            stems.add(extractMaterialStem(clean));
        }

        for (List<CandidateColorEntry> bucketList : getCandidateBuckets().values()) {
            for (CandidateColorEntry entry : bucketList) {
                if (stems.contains(extractMaterialStem(entry.blockName))) {
                    expanded.add(entry.blockName);
                }
            }
        }

        // Also link registered SubBlockFamily siblings when running on a server
        try {
            for (SubBlockFamily fam : BlockColorMapper.getSubBlockFamilies()) {
                boolean present = false;
                BlockType[] members = {
                        fam.fullBlock, fam.stairs, fam.slab, fam.wall, fam.fence, fam.fenceGate, fam.trapdoor, fam.carpet
                };
                for (BlockType m : members) {
                    if (m != null && expanded.contains(VanillaBlockAppearanceResolver.cleanBlockName(m.id()))) {
                        present = true;
                        break;
                    }
                }
                if (present) {
                    for (BlockType m : members) {
                        if (m != null) {
                            expanded.add(VanillaBlockAppearanceResolver.cleanBlockName(m.id()));
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return expanded;
    }

    /**
     * Recolors {@code origState} to {@code (targetR, targetG, targetB)} while preserving its exact
     * sub-block class and all blockstate properties.
     */
    public static BlockState recolorPreservingSubBlock(
            BlockState origState,
            int targetR,
            int targetG,
            int targetB,
            Set<String> expandedLocalPalette
    ) {
        if (origState == null || origState.getBlockType() == null) {
            return origState;
        }
        BlockType origType = origState.getBlockType();
        String origName = VanillaBlockAppearanceResolver.cleanBlockName(origType.id());

        // Special case: snow[layers=8] acts like a full snow block, whereas snow[layers=1..7] stays untouched
        if (origName.equals("snow")) {
            int layers = readIntProperty(origState, PropertyKey.LAYERS, 1);
            if (layers < 8) {
                return origState;
            }
        }

        RecolorBucket bucket = origName.equals("snow") ? RecolorBucket.CUBE : classifyBucketByName(origName);
        if (bucket == RecolorBucket.FIXED) {
            return origState;
        }

        int[] origRgb = VanillaBlockAppearanceResolver.getAverageRgb(origType);
        if (BlockColorMapper.colorDistanceSq(targetR, targetG, targetB, origRgb[0], origRgb[1], origRgb[2]) < MIN_CHANGE_DIST_SQ) {
            return origState;
        }

        // Handle double slabs: keep type=double on the newly chosen slab so sub-block class is strictly preserved
        CandidateColorEntry best = findBestCandidate(bucket, targetR, targetG, targetB, expandedLocalPalette, true);
        if (best == null || best.blockName.equals(origName)) {
            return origState;
        }

        // Only switch if the candidate is meaningfully closer to targetRGB than the current block already is
        double origDist = BlockColorMapper.colorDistanceSq(targetR, targetG, targetB, origRgb[0], origRgb[1], origRgb[2]);
        double newDist = BlockColorMapper.colorDistanceSq(targetR, targetG, targetB, best.r, best.g, best.b);
        if (newDist + 4.0 >= origDist) {
            return origState;
        }

        BlockType targetType = getServerBlockType(best.blockName);
        if (targetType == null || targetType.getDefaultState() == null) {
            return origState;
        }

        return copyCompatibleStateProperties(origState, targetType, bucket == RecolorBucket.LEAVES);
    }

    /**
     * Copies every shared blockstate property (facing, half, shape, type, north/east/south/west/up,
     * open, in_wall, powered, face, axis, waterlogged) from {@code sourceState} onto {@code targetType}.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static BlockState copyCompatibleStateProperties(
            BlockState sourceState,
            BlockType targetType,
            boolean forcePersistentLeaves
    ) {
        BlockState out = targetType.getDefaultState();
        if (sourceState == null || out == null) {
            return out;
        }
        try {
            Map<Property<?>, Object> rawStates = sourceState.getStates();
            if (rawStates != null) {
                for (Map.Entry<Property<?>, Object> entry : rawStates.entrySet()) {
                    Property prop = entry.getKey();
                    Object val = entry.getValue();
                    if (prop == null || val == null) {
                        continue;
                    }
                    try {
                        Property targetProp = targetType.getProperty(prop.getName());
                        if (targetProp != null) {
                            Object coerced = val;
                            if (!targetProp.getValues().contains(val)) {
                                coerced = targetProp.getValueFor(String.valueOf(val));
                            }
                            if (coerced != null) {
                                out = out.with(targetProp, coerced);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        if (forcePersistentLeaves) {
            out = BlockColorMapper.withPropertySafe(out, PropertyKey.PERSISTENT, true);
        }
        return out;
    }

    private static String extractMaterialStem(String blockName) {
        String s = blockName;
        if (s.startsWith("waxed_")) s = s.substring("waxed_".length());
        if (s.startsWith("stripped_")) s = s.substring("stripped_".length());

        String[] shapeSuffixes = {
                "_stained_glass_pane", "_stained_glass",
                "_fence_gate", "_pressure_plate", "_stairs", "_slab", "_wall",
                "_fence", "_trapdoor", "_carpet", "_button", "_pane"
        };
        for (String suf : shapeSuffixes) {
            if (s.endsWith(suf) && s.length() > suf.length()) {
                s = s.substring(0, s.length() - suf.length());
                break;
            }
        }

        String[] containerSuffixes = {
                "_planks", "_concrete_powder", "_concrete", "_wool", "_terracotta",
                "_wood", "_hyphae", "_block", "_leaves"
        };
        for (String suf : containerSuffixes) {
            if (s.endsWith(suf) && s.length() > suf.length()) {
                s = s.substring(0, s.length() - suf.length());
                break;
            }
        }

        if (s.equals("bricks")) {
            s = "brick";
        } else if (s.endsWith("_bricks") && s.length() > "_bricks".length()) {
            s = s.substring(0, s.length() - 1); // *_bricks -> *_brick
        } else if (s.endsWith("_tiles") && s.length() > "_tiles".length()) {
            s = s.substring(0, s.length() - 1); // *_tiles -> *_tile
        }
        return s;
    }

    private static boolean isUnwaxedOxidizableCopper(String name) {
        if (name.startsWith("waxed_")) {
            return false;
        }
        return name.contains("copper") && !name.equals("raw_copper_block") && !name.equals("copper_ore") && !name.equals("deepslate_copper_ore");
    }

    private static int[] averageRecordRgb(BlockColorRecord rec) {
        int r = (rec.sideRgb[0] * 2 + rec.topRgb[0]) / 3;
        int g = (rec.sideRgb[1] * 2 + rec.topRgb[1]) / 3;
        int b = (rec.sideRgb[2] * 2 + rec.topRgb[2]) / 3;
        return new int[]{r, g, b};
    }

    private static boolean isBlockAvailableOnServer(String cleanName) {
        return getServerBlockType(cleanName) != null;
    }

    private static BlockType getServerBlockType(String cleanName) {
        try {
            return BlockTypes.get("minecraft:" + cleanName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int readIntProperty(BlockState state, PropertyKey key, int fallback) {
        try {
            Object v = state.getState(key);
            if (v instanceof Number n) {
                return n.intValue();
            }
            if (v != null) {
                return Integer.parseInt(v.toString());
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static final String[] STAINED_GLASS_NAMES = {
            "white_stained_glass", "light_gray_stained_glass", "gray_stained_glass", "black_stained_glass",
            "brown_stained_glass", "red_stained_glass", "orange_stained_glass", "yellow_stained_glass",
            "lime_stained_glass", "green_stained_glass", "cyan_stained_glass", "light_blue_stained_glass",
            "blue_stained_glass", "purple_stained_glass", "magenta_stained_glass", "pink_stained_glass",
            "tinted_glass", "glass"
    };

    private static final String[] CURATED_SOLID_BLOCK_NAMES = {
            // Concrete (16)
            "white_concrete", "light_gray_concrete", "gray_concrete", "black_concrete",
            "brown_concrete", "red_concrete", "orange_concrete", "yellow_concrete",
            "lime_concrete", "green_concrete", "cyan_concrete", "light_blue_concrete",
            "blue_concrete", "purple_concrete", "magenta_concrete", "pink_concrete",
            // Wool (16)
            "white_wool", "light_gray_wool", "gray_wool", "black_wool",
            "brown_wool", "red_wool", "orange_wool", "yellow_wool",
            "lime_wool", "green_wool", "cyan_wool", "light_blue_wool",
            "blue_wool", "purple_wool", "magenta_wool", "pink_wool",
            // Terracotta (17)
            "terracotta", "white_terracotta", "light_gray_terracotta", "gray_terracotta",
            "black_terracotta", "brown_terracotta", "red_terracotta", "orange_terracotta",
            "yellow_terracotta", "lime_terracotta", "green_terracotta", "cyan_terracotta",
            "light_blue_terracotta", "blue_terracotta", "purple_terracotta", "magenta_terracotta", "pink_terracotta",
            // Planks & Stripped Woods
            "oak_planks", "spruce_planks", "birch_planks", "jungle_planks", "acacia_planks", "dark_oak_planks",
            "mangrove_planks", "cherry_planks", "bamboo_planks", "crimson_planks", "warped_planks", "pale_oak_planks",
            "stripped_oak_wood", "stripped_spruce_wood", "stripped_birch_wood", "stripped_jungle_wood",
            "stripped_acacia_wood", "stripped_dark_oak_wood", "stripped_mangrove_wood", "stripped_cherry_wood",
            "stripped_pale_oak_wood", "crimson_hyphae", "stripped_crimson_hyphae", "warped_hyphae", "stripped_warped_hyphae",
            // Stones, Bricks, Minerals & Natural Blocks
            "snow_block", "quartz_block", "smooth_quartz", "calcite", "diorite", "polished_diorite", "clay",
            "stone", "smooth_stone", "andesite", "polished_andesite", "cobblestone", "stone_bricks",
            "mossy_cobblestone", "mossy_stone_bricks", "tuff", "polished_tuff", "tuff_bricks",
            "deepslate", "cobbled_deepslate", "polished_deepslate", "deepslate_bricks", "deepslate_tiles",
            "smooth_basalt", "blackstone", "polished_blackstone", "polished_blackstone_bricks", "obsidian", "coal_block",
            "mud", "packed_mud", "mud_bricks", "dripstone_block", "granite", "polished_granite", "bricks", "resin_bricks",
            "dirt", "coarse_dirt", "cut_sandstone", "smooth_sandstone", "cut_red_sandstone", "smooth_red_sandstone",
            "end_stone", "end_stone_bricks", "moss_block", "prismarine", "prismarine_bricks", "dark_prismarine",
            "packed_ice", "blue_ice", "purpur_block", "amethyst_block", "nether_bricks", "red_nether_bricks",
            "nether_wart_block", "warped_wart_block", "brown_mushroom_block", "red_mushroom_block", "netherrack",
            "iron_block", "gold_block", "diamond_block", "emerald_block", "lapis_block", "redstone_block",
            "raw_iron_block", "raw_gold_block", "raw_copper_block",
            "waxed_copper_block", "waxed_cut_copper", "waxed_exposed_copper", "waxed_exposed_cut_copper",
            "waxed_weathered_copper", "waxed_weathered_cut_copper", "waxed_oxidized_copper", "waxed_oxidized_cut_copper",
            "honeycomb_block", "shroomlight", "ochre_froglight", "verdant_froglight", "pearlescent_froglight"
    };
}
