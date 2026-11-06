package com.dragonbuildtools.util;


import com.fastasyncworldedit.core.registry.state.PropertyKey;
import com.fastasyncworldedit.core.util.TextureUtil;
import com.sk89q.worldedit.util.Direction;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Perceptual RGB/ARGB-to-Minecraft-block color and sub-block state mapper.
 * Supports full blocks, stained glass, stairs (all facings, top/bottom halves, and inner/outer corner shapes),
 * slabs (bottom/top/double), walls (directional low/tall/up), fences, fence gates, trapdoors, carpets, and snow layers (1-8).
 */
public final class BlockColorMapper {

    public static final class PaletteEntry {
        public final String id;
        public final BlockType blockType;
        public final BaseBlock baseBlock;
        public final int r;
        public final int g;
        public final int b;
        public final boolean translucent;

        public PaletteEntry(String id, BlockType blockType, int r, int g, int b, boolean translucent) {
            this.id = id;
            this.blockType = blockType;
            this.baseBlock = blockType.getDefaultState().toBaseBlock();
            this.r = r;
            this.g = g;
            this.b = b;
            this.translucent = translucent;
        }
    }

    /**
     * Represents a complete structural material family with its average sRGB color and sub-block variants.
     */
    public static final class SubBlockFamily {
        public final String name;
        public final int r;
        public final int g;
        public final int b;
        public final BlockType fullBlock;
        public final BlockType stairs;
        public final BlockType slab;
        public final BlockType wall;
        public final BlockType fence;
        public final BlockType fenceGate;
        public final BlockType trapdoor;
        public final BlockType carpet;

        public SubBlockFamily(
                String name,
                int r, int g, int b,
                BlockType fullBlock,
                BlockType stairs,
                BlockType slab,
                BlockType wall,
                BlockType fence,
                BlockType fenceGate,
                BlockType trapdoor,
                BlockType carpet
        ) {
            this.name = name;
            this.r = r;
            this.g = g;
            this.b = b;
            this.fullBlock = fullBlock;
            this.stairs = stairs;
            this.slab = slab;
            this.wall = wall;
            this.fence = fence;
            this.fenceGate = fenceGate;
            this.trapdoor = trapdoor;
            this.carpet = carpet;
        }
    }

    private static volatile List<PaletteEntry> solidPalette;
    private static volatile List<PaletteEntry> glassPalette;
    private static volatile List<SubBlockFamily> subBlockFamilies;
    private static volatile TextureUtil cachedTextureUtil;

    private BlockColorMapper() {
    }

    /**
     * Estimates an RGB color [r, g, b] (0..255) for any BlockType for .obj/.mtl exporting.
     */
    public static int[] estimateBlockRgb(BlockType bt) {
        return VanillaBlockAppearanceResolver.getAverageRgb(bt);
    }

    /**
     * Computes perceptual redmean color distance squared between two RGB colors.
     */
    public static double colorDistanceSq(int r1, int g1, int b1, int r2, int g2, int b2) {
        double rMean = (r1 + r2) * 0.5;
        double dr = r1 - r2;
        double dg = g1 - g2;
        double db = b1 - b2;
        double dist = (2.0 + rMean / 256.0) * dr * dr
                + 4.0 * dg * dg
                + (2.0 + (255.0 - rMean) / 256.0) * db * db;
        int c1 = Math.max(r1, Math.max(g1, b1)) - Math.min(r1, Math.min(g1, b1));
        int c2 = Math.max(r2, Math.max(g2, b2)) - Math.min(r2, Math.min(g2, b2));
        if (c1 <= 14 && c2 > 16) {
            double dc = c2 - c1;
            dist += 5.0 * dc * dc;
        }
        return dist;
    }

    /**
     * Safely sets a property on a {@link BlockState} without throwing if a server version uses a different value representation.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static BlockState withPropertySafe(BlockState state, PropertyKey key, Object value) {
        if (state == null || key == null || value == null) {
            return state;
        }
        try {
            if (!state.getBlockType().hasProperty(key)) {
                return state;
            }
            return state.with(key, value);
        } catch (Throwable t1) {
            try {
                if (value instanceof Direction) {
                    return state.with(key, ((Direction) value).name().toLowerCase(Locale.ROOT));
                }
                if (value instanceof String) {
                    String s = (String) value;
                    if ("north".equalsIgnoreCase(s)) return state.with(key, Direction.NORTH);
                    if ("south".equalsIgnoreCase(s)) return state.with(key, Direction.SOUTH);
                    if ("east".equalsIgnoreCase(s)) return state.with(key, Direction.EAST);
                    if ("west".equalsIgnoreCase(s)) return state.with(key, Direction.WEST);
                }
            } catch (Throwable ignored) {
            }
            return state;
        }
    }

    /**
     * Builds a stair {@link BaseBlock} with the given {@code facing}, {@code half} ("bottom" or "top"),
     * and {@code shape} ("straight", "inner_left", "inner_right", "outer_left", "outer_right").
     */
    public static BaseBlock createStairBlock(BlockType stairType, Direction facing, String half, String shape) {
        if (stairType == null || stairType.getDefaultState() == null) {
            return null;
        }
        BlockState state = stairType.getDefaultState();
        state = withPropertySafe(state, PropertyKey.FACING, facing != null ? facing : Direction.NORTH);
        state = withPropertySafe(state, PropertyKey.HALF, half != null ? half : "bottom");
        state = withPropertySafe(state, PropertyKey.SHAPE, shape != null ? shape : "straight");
        state = withPropertySafe(state, PropertyKey.WATERLOGGED, false);
        return state.toBaseBlock();
    }

    /**
     * Builds a slab {@link BaseBlock} with {@code type} ("bottom", "top", or "double").
     */
    public static BaseBlock createSlabBlock(BlockType slabType, String type) {
        if (slabType == null || slabType.getDefaultState() == null) {
            return null;
        }
        BlockState state = slabType.getDefaultState();
        state = withPropertySafe(state, PropertyKey.TYPE, type != null ? type : "bottom");
        state = withPropertySafe(state, PropertyKey.WATERLOGGED, false);
        return state.toBaseBlock();
    }

    /**
     * Builds a wall {@link BaseBlock} with directional heights ("none", "low", "tall") and central post {@code up}.
     */
    public static BaseBlock createWallBlock(
            BlockType wallType,
            String north,
            String east,
            String south,
            String west,
            boolean up
    ) {
        if (wallType == null || wallType.getDefaultState() == null) {
            return null;
        }
        BlockState state = wallType.getDefaultState();
        if (true) {
            state = withPropertySafe(state, PropertyKey.NORTH, north != null ? north : "none");
            state = withPropertySafe(state, PropertyKey.EAST, east != null ? east : "none");
            state = withPropertySafe(state, PropertyKey.SOUTH, south != null ? south : "none");
            state = withPropertySafe(state, PropertyKey.WEST, west != null ? west : "none");
        } else {
            state = withPropertySafe(state, PropertyKey.NORTH, !"none".equals(north));
            state = withPropertySafe(state, PropertyKey.EAST, !"none".equals(east));
            state = withPropertySafe(state, PropertyKey.SOUTH, !"none".equals(south));
            state = withPropertySafe(state, PropertyKey.WEST, !"none".equals(west));
        }
        state = withPropertySafe(state, PropertyKey.UP, up);
        state = withPropertySafe(state, PropertyKey.WATERLOGGED, false);
        return state.toBaseBlock();
    }

    /**
     * Builds a fence {@link BaseBlock} with directional boolean connections.
     */
    public static BaseBlock createFenceBlock(
            BlockType fenceType,
            boolean north,
            boolean east,
            boolean south,
            boolean west
    ) {
        if (fenceType == null || fenceType.getDefaultState() == null) {
            return null;
        }
        BlockState state = fenceType.getDefaultState();
        state = withPropertySafe(state, PropertyKey.NORTH, north);
        state = withPropertySafe(state, PropertyKey.EAST, east);
        state = withPropertySafe(state, PropertyKey.SOUTH, south);
        state = withPropertySafe(state, PropertyKey.WEST, west);
        state = withPropertySafe(state, PropertyKey.WATERLOGGED, false);
        return state.toBaseBlock();
    }

    /**
     * Builds a fence gate {@link BaseBlock} with {@code facing}, {@code open}, and {@code inWall}.
     */
    public static BaseBlock createFenceGateBlock(
            BlockType gateType,
            Direction facing,
            boolean open,
            boolean inWall
    ) {
        if (gateType == null || gateType.getDefaultState() == null) {
            return null;
        }
        BlockState state = gateType.getDefaultState();
        state = withPropertySafe(state, PropertyKey.FACING, facing != null ? facing : Direction.NORTH);
        state = withPropertySafe(state, PropertyKey.OPEN, open);
        state = withPropertySafe(state, PropertyKey.IN_WALL, inWall);
        state = withPropertySafe(state, PropertyKey.POWERED, false);
        return state.toBaseBlock();
    }

    /**
     * Builds a trapdoor {@link BaseBlock} with {@code facing}, {@code half} ("bottom" or "top"), and {@code open}.
     */
    public static BaseBlock createTrapdoorBlock(
            BlockType trapdoorType,
            Direction facing,
            String half,
            boolean open
    ) {
        if (trapdoorType == null || trapdoorType.getDefaultState() == null) {
            return null;
        }
        BlockState state = trapdoorType.getDefaultState();
        state = withPropertySafe(state, PropertyKey.FACING, facing != null ? facing : Direction.NORTH);
        state = withPropertySafe(state, PropertyKey.HALF, half != null ? half : "bottom");
        state = withPropertySafe(state, PropertyKey.OPEN, open);
        state = withPropertySafe(state, PropertyKey.WATERLOGGED, false);
        return state.toBaseBlock();
    }

    /**
     * Builds a snow layer {@link BaseBlock} with {@code layers} in [1..8].
     */
    public static BaseBlock createSnowLayerBlock(int layers) {
        try {
            BlockType snow = BlockTypes.SNOW;
            if (snow == null || snow.getDefaultState() == null) {
                return null;
            }
            int clamped = Math.max(1, Math.min(8, layers));
            BlockState state = withPropertySafe(snow.getDefaultState(), PropertyKey.LAYERS, clamped);
            return state.toBaseBlock();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Finds the closest {@link SubBlockFamily} that has a non-null sub-block for the requested predicate,
     * or resolves the family corresponding to an explicit override {@link BlockType}.
     */
    public static SubBlockFamily findNearestStairFamily(int r, int g, int b, BlockType preferredBase) {
        if (preferredBase != null) {
            SubBlockFamily resolved = resolveFamilyForBlock(preferredBase);
            if (resolved != null && resolved.stairs != null) {
                return resolved;
            }
        }
        SubBlockFamily best = null;
        double bestDist = Double.MAX_VALUE;
        for (SubBlockFamily fam : getSubBlockFamilies()) {
            if (fam.stairs == null) continue;
            double d = colorDistanceSq(r, g, b, fam.r, fam.g, fam.b);
            if (d < bestDist) {
                bestDist = d;
                best = fam;
            }
        }
        return best;
    }

    public static SubBlockFamily findNearestSlabFamily(int r, int g, int b, BlockType preferredBase) {
        if (preferredBase != null) {
            SubBlockFamily resolved = resolveFamilyForBlock(preferredBase);
            if (resolved != null && resolved.slab != null) {
                return resolved;
            }
        }
        SubBlockFamily best = null;
        double bestDist = Double.MAX_VALUE;
        for (SubBlockFamily fam : getSubBlockFamilies()) {
            if (fam.slab == null) continue;
            double d = colorDistanceSq(r, g, b, fam.r, fam.g, fam.b);
            if (d < bestDist) {
                bestDist = d;
                best = fam;
            }
        }
        return best;
    }

    public static SubBlockFamily findNearestWallFamily(int r, int g, int b, BlockType preferredBase) {
        if (preferredBase != null) {
            SubBlockFamily resolved = resolveFamilyForBlock(preferredBase);
            if (resolved != null && resolved.wall != null) {
                return resolved;
            }
        }
        SubBlockFamily best = null;
        double bestDist = Double.MAX_VALUE;
        for (SubBlockFamily fam : getSubBlockFamilies()) {
            if (fam.wall == null) continue;
            double d = colorDistanceSq(r, g, b, fam.r, fam.g, fam.b);
            if (d < bestDist) {
                bestDist = d;
                best = fam;
            }
        }
        return best;
    }

    public static SubBlockFamily findNearestFenceFamily(int r, int g, int b, BlockType preferredBase) {
        if (preferredBase != null) {
            SubBlockFamily resolved = resolveFamilyForBlock(preferredBase);
            if (resolved != null && resolved.fence != null) {
                return resolved;
            }
        }
        SubBlockFamily best = null;
        double bestDist = Double.MAX_VALUE;
        for (SubBlockFamily fam : getSubBlockFamilies()) {
            if (fam.fence == null) continue;
            double d = colorDistanceSq(r, g, b, fam.r, fam.g, fam.b);
            if (d < bestDist) {
                bestDist = d;
                best = fam;
            }
        }
        if (preferredBase == null && bestDist > 2400.0) {
            return null;
        }
        return best;
    }

    public static SubBlockFamily findNearestFenceGateFamily(int r, int g, int b, BlockType preferredBase) {
        if (preferredBase != null) {
            SubBlockFamily resolved = resolveFamilyForBlock(preferredBase);
            if (resolved != null && resolved.fenceGate != null) {
                return resolved;
            }
        }
        SubBlockFamily best = null;
        double bestDist = Double.MAX_VALUE;
        for (SubBlockFamily fam : getSubBlockFamilies()) {
            if (fam.fenceGate == null) continue;
            double d = colorDistanceSq(r, g, b, fam.r, fam.g, fam.b);
            if (d < bestDist) {
                bestDist = d;
                best = fam;
            }
        }
        if (preferredBase == null && bestDist > 2400.0) {
            return null;
        }
        return best;
    }

    public static SubBlockFamily findNearestTrapdoorFamily(int r, int g, int b, BlockType preferredBase) {
        if (preferredBase != null) {
            SubBlockFamily resolved = resolveFamilyForBlock(preferredBase);
            if (resolved != null && resolved.trapdoor != null) {
                return resolved;
            }
        }
        SubBlockFamily best = null;
        double bestDist = Double.MAX_VALUE;
        for (SubBlockFamily fam : getSubBlockFamilies()) {
            if (fam.trapdoor == null) continue;
            double d = colorDistanceSq(r, g, b, fam.r, fam.g, fam.b);
            if (d < bestDist) {
                bestDist = d;
                best = fam;
            }
        }
        return best;
    }

    public static SubBlockFamily findNearestCarpetFamily(int r, int g, int b, BlockType preferredBase) {
        if (preferredBase != null) {
            SubBlockFamily resolved = resolveFamilyForBlock(preferredBase);
            if (resolved != null && resolved.carpet != null) {
                return resolved;
            }
        }
        SubBlockFamily best = null;
        double bestDist = Double.MAX_VALUE;
        for (SubBlockFamily fam : getSubBlockFamilies()) {
            if (fam.carpet == null) continue;
            double d = colorDistanceSq(r, g, b, fam.r, fam.g, fam.b);
            if (d < bestDist) {
                bestDist = d;
                best = fam;
            }
        }
        return best;
    }

    /**
     * Resolves a {@link SubBlockFamily} for an arbitrary base {@link BlockType} (e.g., oak_planks, stone_bricks, cobblestone).
     */
    public static SubBlockFamily resolveFamilyForBlock(BlockType bt) {
        if (bt == null) {
            return null;
        }
        for (SubBlockFamily fam : getSubBlockFamilies()) {
            if (bt.equals(fam.fullBlock) || bt.equals(fam.stairs) || bt.equals(fam.slab)
                    || bt.equals(fam.wall) || bt.equals(fam.fence)) {
                return fam;
            }
        }
        String raw = bt.id().replace("minecraft:", "").toLowerCase(Locale.ROOT);
        String prefix = raw
                .replace("_planks", "")
                .replace("_block", "")
                .replace("_bricks", "_brick")
                .replace("_tiles", "_tile");
        BlockType stairs = firstBlock(raw + "_stairs", prefix + "_stairs");
        BlockType slab = firstBlock(raw + "_slab", prefix + "_slab");
        BlockType wall = firstBlock(raw + "_wall", prefix + "_wall");
        BlockType fence = firstBlock(raw + "_fence", prefix + "_fence");
        BlockType gate = firstBlock(raw + "_fence_gate", prefix + "_fence_gate");
        BlockType trapdoor = firstBlock(raw + "_trapdoor", prefix + "_trapdoor");
        BlockType carpet = firstBlock(raw + "_carpet", prefix + "_carpet");
        if (stairs != null || slab != null || wall != null || fence != null) {
            return new SubBlockFamily(raw, 128, 128, 128, bt, stairs, slab, wall, fence, gate, trapdoor, carpet);
        }
        return null;
    }

    /**
     * Resolves the full-block {@link BaseBlock} corresponding to a stair, slab, wall, or fence {@link BlockType}.
     */
    public static BaseBlock getFullBlockForSubBlock(BlockType subBlockType) {
        if (subBlockType == null) {
            return null;
        }
        String raw = subBlockType.id().replace("minecraft:", "").toLowerCase(Locale.ROOT);
        if (raw.equals("snow")) {
            try {
                if (BlockTypes.SNOW_BLOCK != null && BlockTypes.SNOW_BLOCK.getDefaultState() != null) {
                    return BlockTypes.SNOW_BLOCK.getDefaultState().toBaseBlock();
                }
            } catch (Throwable ignored) {
            }
        }
        SubBlockFamily fam = resolveFamilyForBlock(subBlockType);
        if (fam != null && fam.fullBlock != null && fam.fullBlock.getDefaultState() != null) {
            return fam.fullBlock.getDefaultState().toBaseBlock();
        }
        String baseName = raw
                .replace("_stairs", "")
                .replace("_slab", "")
                .replace("_wall", "")
                .replace("_fence_gate", "")
                .replace("_fence", "")
                .replace("_carpet", "");
        BlockType resolved = firstBlock(
                baseName,
                baseName + "_planks",
                baseName + "_block",
                baseName + "_concrete",
                baseName + "_wool",
                baseName + "s"
        );
        if (resolved != null && resolved.getDefaultState() != null) {
            return resolved.getDefaultState().toBaseBlock();
        }
        return null;
    }

    /**
     * Resolves the slab {@link BaseBlock} ({@code "bottom"} or {@code "top"}) corresponding to a sub-block {@link BlockType}.
     */
    public static BaseBlock getSlabBlockForSubBlock(BlockType subBlockType, String half) {
        if (subBlockType == null) {
            return null;
        }
        SubBlockFamily fam = resolveFamilyForBlock(subBlockType);
        if (fam != null && fam.slab != null) {
            return createSlabBlock(fam.slab, half);
        }
        String raw = subBlockType.id().replace("minecraft:", "").toLowerCase(Locale.ROOT);
        String baseName = raw
                .replace("_stairs", "")
                .replace("_slab", "")
                .replace("_wall", "")
                .replace("_fence_gate", "")
                .replace("_fence", "");
        BlockType slabType = firstBlock(baseName + "_slab");
        if (slabType != null) {
            return createSlabBlock(slabType, half);
        }
        return getFullBlockForSubBlock(subBlockType);
    }

    /**
     * Finds the nearest Minecraft {@link BaseBlock} for an ARGB pixel/voxel color.
     */
    public static BaseBlock getNearestBlock(int argb, boolean allowGlass, int[] outMatchedRgb) {
        int a = (argb >>> 24) & 0xFF;
        if (a < 32) {
            return null;
        }
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;

        if (allowGlass && a < 210) {
            List<PaletteEntry> glass = getGlassPalette();
            if (!glass.isEmpty()) {
                PaletteEntry bestGlass = findClosestEntry(glass, r, g, b);
                if (bestGlass != null) {
                    if (outMatchedRgb != null && outMatchedRgb.length >= 3) {
                        outMatchedRgb[0] = bestGlass.r;
                        outMatchedRgb[1] = bestGlass.g;
                        outMatchedRgb[2] = bestGlass.b;
                    }
                    return bestGlass.baseBlock;
                }
            }
        }

        List<PaletteEntry> palette = getSolidPalette();
        PaletteEntry best = findClosestEntry(palette, r, g, b);
        if (best != null) {
            if (outMatchedRgb != null && outMatchedRgb.length >= 3) {
                outMatchedRgb[0] = best.r;
                outMatchedRgb[1] = best.g;
                outMatchedRgb[2] = best.b;
            }
            return best.baseBlock;
        }

        TextureUtil tu = getFaweTextureUtil();
        if (tu != null) {
            int opaqueArgb = (0xFF << 24) | (r << 16) | (g << 8) | b;
            BlockType bt = tu.getNearestBlock(opaqueArgb);
            if (bt != null && bt.getDefaultState() != null) {
                if (outMatchedRgb != null && outMatchedRgb.length >= 3) {
                    int blockColor = tu.getColor(bt);
                    outMatchedRgb[0] = (blockColor >>> 16) & 0xFF;
                    outMatchedRgb[1] = (blockColor >>> 8) & 0xFF;
                    outMatchedRgb[2] = blockColor & 0xFF;
                }
                return bt.getDefaultState().toBaseBlock();
            }
        }

        if (outMatchedRgb != null && outMatchedRgb.length >= 3) {
            outMatchedRgb[0] = r;
            outMatchedRgb[1] = g;
            outMatchedRgb[2] = b;
        }
        try {
            BlockType fallback = BlockTypes.WHITE_CONCRETE != null ? BlockTypes.WHITE_CONCRETE : BlockTypes.STONE;
            return fallback != null && fallback.getDefaultState() != null ? fallback.getDefaultState().toBaseBlock() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static PaletteEntry findClosestEntry(List<PaletteEntry> entries, int r, int g, int b) {
        PaletteEntry best = null;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0, size = entries.size(); i < size; i++) {
            PaletteEntry entry = entries.get(i);
            double dist = colorDistanceSq(r, g, b, entry.r, entry.g, entry.b);
            if (dist < bestDist) {
                bestDist = dist;
                best = entry;
            }
        }
        return best;
    }

    private static TextureUtil getFaweTextureUtil() {
        if (cachedTextureUtil != null) {
            return cachedTextureUtil;
        }
        try {
            com.fastasyncworldedit.core.Fawe fawe = com.fastasyncworldedit.core.Fawe.instance();
            if (fawe != null) {
                cachedTextureUtil = fawe.getTextureUtil();
            }
        } catch (Throwable ignored) {
        }
        return cachedTextureUtil;
    }

    public static List<SubBlockFamily> getSubBlockFamilies() {
        List<SubBlockFamily> current = subBlockFamilies;
        if (current != null && !current.isEmpty()) {
            return current;
        }
        synchronized (BlockColorMapper.class) {
            if (subBlockFamilies != null && !subBlockFamilies.isEmpty()) {
                return subBlockFamilies;
            }
            List<SubBlockFamily> list = new ArrayList<>();

            // Wood families (planks, stairs, slabs, fences, fence gates, trapdoors)
            addWoodFamily(list, "oak", 162, 130, 78);
            addWoodFamily(list, "spruce", 114, 84, 48);
            addWoodFamily(list, "birch", 192, 175, 121);
            addWoodFamily(list, "jungle", 160, 115, 80);
            addWoodFamily(list, "acacia", 168, 90, 50);
            addWoodFamily(list, "dark_oak", 66, 43, 20);
            addWoodFamily(list, "mangrove", 117, 54, 48);
            addWoodFamily(list, "cherry", 226, 178, 172);
            addWoodFamily(list, "bamboo", 193, 173, 80);
            addWoodFamily(list, "crimson", 101, 48, 70);
            addWoodFamily(list, "warped", 43, 104, 99);
            addWoodFamily(list, "pale_oak", 227, 217, 210);
            addWoodFamily(list, "poplar", 214, 198, 148);

            // Stone, Brick, Mineral, & Copper families (stairs, slabs, walls)
            addStoneFamily(list, "stone", 125, 125, 125, "stone", "stone_stairs", "stone_slab", "cobblestone_wall", null);
            addStoneFamily(list, "smooth_stone", 158, 158, 158, "smooth_stone", "stone_stairs", "smooth_stone_slab", "stone_brick_wall", null);
            addStoneFamily(list, "cobblestone", 127, 127, 127, "cobblestone", "cobblestone_stairs", "cobblestone_slab", "cobblestone_wall", null);
            addStoneFamily(list, "mossy_cobblestone", 110, 118, 94, "mossy_cobblestone", "mossy_cobblestone_stairs", "mossy_cobblestone_slab", "mossy_cobblestone_wall", "moss_carpet");
            addStoneFamily(list, "stone_bricks", 122, 121, 122, "stone_bricks", "stone_brick_stairs", "stone_brick_slab", "stone_brick_wall", null);
            addStoneFamily(list, "mossy_stone_bricks", 115, 121, 105, "mossy_stone_bricks", "mossy_stone_brick_stairs", "mossy_stone_brick_slab", "mossy_stone_brick_wall", "moss_carpet");
            addStoneFamily(list, "granite", 149, 103, 85, "granite", "granite_stairs", "granite_slab", "granite_wall", null);
            addStoneFamily(list, "polished_granite", 154, 106, 89, "polished_granite", "polished_granite_stairs", "polished_granite_slab", "granite_wall", null);
            addStoneFamily(list, "diorite", 188, 188, 189, "diorite", "diorite_stairs", "diorite_slab", "diorite_wall", "pale_moss_carpet");
            addStoneFamily(list, "polished_diorite", 192, 193, 195, "polished_diorite", "polished_diorite_stairs", "polished_diorite_slab", "diorite_wall", "white_carpet");
            addStoneFamily(list, "andesite", 136, 136, 136, "andesite", "andesite_stairs", "andesite_slab", "andesite_wall", "light_gray_carpet");
            addStoneFamily(list, "polished_andesite", 132, 134, 133, "polished_andesite", "polished_andesite_stairs", "polished_andesite_slab", "andesite_wall", null);
            addStoneFamily(list, "cobbled_deepslate", 77, 77, 80, "cobbled_deepslate", "cobbled_deepslate_stairs", "cobbled_deepslate_slab", "cobbled_deepslate_wall", "gray_carpet");
            addStoneFamily(list, "polished_deepslate", 72, 72, 73, "polished_deepslate", "polished_deepslate_stairs", "polished_deepslate_slab", "polished_deepslate_wall", "gray_carpet");
            addStoneFamily(list, "deepslate_bricks", 70, 70, 71, "deepslate_bricks", "deepslate_brick_stairs", "deepslate_brick_slab", "deepslate_brick_wall", "gray_carpet");
            addStoneFamily(list, "deepslate_tiles", 54, 54, 55, "deepslate_tiles", "deepslate_tile_stairs", "deepslate_tile_slab", "deepslate_tile_wall", "gray_carpet");
            addStoneFamily(list, "tuff", 108, 109, 102, "tuff", "tuff_stairs", "tuff_slab", "tuff_wall", null);
            addStoneFamily(list, "polished_tuff", 98, 100, 95, "polished_tuff", "polished_tuff_stairs", "polished_tuff_slab", "polished_tuff_wall", null);
            addStoneFamily(list, "tuff_bricks", 99, 102, 96, "tuff_bricks", "tuff_brick_stairs", "tuff_brick_slab", "tuff_brick_wall", null);
            addStoneFamily(list, "bricks", 150, 97, 83, "bricks", "brick_stairs", "brick_slab", "brick_wall", null);
            addStoneFamily(list, "mud_bricks", 137, 103, 79, "mud_bricks", "mud_brick_stairs", "mud_brick_slab", "mud_brick_wall", "brown_carpet");
            addStoneFamily(list, "resin_bricks", 214, 110, 42, "resin_bricks", "resin_brick_stairs", "resin_brick_slab", "resin_brick_wall", "orange_carpet");
            addStoneFamily(list, "sandstone", 216, 203, 155, "sandstone", "sandstone_stairs", "sandstone_slab", "sandstone_wall", "yellow_carpet");
            addStoneFamily(list, "smooth_sandstone", 224, 214, 170, "smooth_sandstone", "smooth_sandstone_stairs", "smooth_sandstone_slab", "sandstone_wall", null);
            addStoneFamily(list, "red_sandstone", 186, 99, 29, "red_sandstone", "red_sandstone_stairs", "red_sandstone_slab", "red_sandstone_wall", "orange_carpet");
            addStoneFamily(list, "smooth_red_sandstone", 189, 101, 31, "smooth_red_sandstone", "smooth_red_sandstone_stairs", "smooth_red_sandstone_slab", "red_sandstone_wall", "orange_carpet");
            addStoneFamily(list, "prismarine", 99, 156, 151, "prismarine", "prismarine_stairs", "prismarine_slab", "prismarine_wall", "cyan_carpet");
            addStoneFamily(list, "prismarine_bricks", 99, 171, 158, "prismarine_bricks", "prismarine_brick_stairs", "prismarine_brick_slab", "prismarine_wall", "cyan_carpet");
            addStoneFamily(list, "dark_prismarine", 51, 91, 75, "dark_prismarine", "dark_prismarine_stairs", "dark_prismarine_slab", "prismarine_wall", "green_carpet");
            addStoneFamily(list, "nether_bricks", 44, 21, 26, "nether_bricks", "nether_brick_stairs", "nether_brick_slab", "nether_brick_wall", null, "nether_brick_fence");
            addStoneFamily(list, "red_nether_bricks", 69, 7, 9, "red_nether_bricks", "red_nether_brick_stairs", "red_nether_brick_slab", "red_nether_brick_wall", "red_carpet");
            addStoneFamily(list, "blackstone", 42, 35, 40, "blackstone", "blackstone_stairs", "blackstone_slab", "blackstone_wall", "black_carpet");
            addStoneFamily(list, "polished_blackstone", 53, 48, 56, "polished_blackstone", "polished_blackstone_stairs", "polished_blackstone_slab", "polished_blackstone_wall", "black_carpet");
            addStoneFamily(list, "polished_blackstone_bricks", 48, 42, 49, "polished_blackstone_bricks", "polished_blackstone_brick_stairs", "polished_blackstone_brick_slab", "polished_blackstone_brick_wall", "black_carpet");
            addStoneFamily(list, "end_stone_bricks", 218, 224, 162, "end_stone_bricks", "end_stone_brick_stairs", "end_stone_brick_slab", "end_stone_brick_wall", null);
            addStoneFamily(list, "purpur_block", 169, 125, 169, "purpur_block", "purpur_stairs", "purpur_slab", null, "magenta_carpet");
            addStoneFamily(list, "quartz_block", 235, 229, 222, "quartz_block", "quartz_stairs", "quartz_slab", "diorite_wall", "white_carpet");
            addStoneFamily(list, "smooth_quartz", 238, 233, 228, "smooth_quartz", "smooth_quartz_stairs", "smooth_quartz_slab", "diorite_wall", "white_carpet");
            addStoneFamily(list, "waxed_cut_copper", 191, 106, 80, "waxed_cut_copper", "waxed_cut_copper_stairs", "waxed_cut_copper_slab", "brick_wall", null, null, "waxed_copper_trapdoor");
            addStoneFamily(list, "waxed_exposed_cut_copper", 155, 121, 101, "waxed_exposed_cut_copper", "waxed_exposed_cut_copper_stairs", "waxed_exposed_cut_copper_slab", "granite_wall", null, null, "waxed_exposed_copper_trapdoor");
            addStoneFamily(list, "waxed_weathered_cut_copper", 109, 145, 107, "waxed_weathered_cut_copper", "waxed_weathered_cut_copper_stairs", "waxed_weathered_cut_copper_slab", "mossy_stone_brick_wall", null, null, "waxed_weathered_copper_trapdoor");
            addStoneFamily(list, "waxed_oxidized_cut_copper", 79, 153, 126, "waxed_oxidized_cut_copper", "waxed_oxidized_cut_copper_stairs", "waxed_oxidized_cut_copper_slab", "prismarine_wall", null, null, "waxed_oxidized_copper_trapdoor");
            addStoneFamily(list, "sulfur", 218, 198, 62, "sulfur", "sulfur_stairs", "sulfur_slab", "sulfur_wall", "yellow_carpet");
            addStoneFamily(list, "cinnabar", 184, 52, 46, "cinnabar", "cinnabar_stairs", "cinnabar_slab", "cinnabar_wall", "red_carpet");

            // Wool & Concrete families (supports carpets on all versions, plus wool/concrete stairs & slabs on 26.x!)
            addColorFamily(list, "white", 233, 236, 236, "smooth_quartz_stairs", "smooth_quartz_slab", "diorite_wall", "pale_oak_fence", "pale_oak_fence_gate", "iron_trapdoor");
            addColorFamily(list, "light_gray", 142, 142, 134, "andesite_stairs", "andesite_slab", "andesite_wall", null, null, "iron_trapdoor");
            addColorFamily(list, "gray", 62, 68, 71, "deepslate_tile_stairs", "deepslate_tile_slab", "deepslate_tile_wall", null, null, null);
            addColorFamily(list, "black", 20, 21, 25, "blackstone_stairs", "blackstone_slab", "blackstone_wall", null, null, null);
            addColorFamily(list, "brown", 114, 71, 40, "spruce_stairs", "spruce_slab", "mud_brick_wall", "spruce_fence", "spruce_fence_gate", "spruce_trapdoor");
            addColorFamily(list, "red", 160, 39, 34, "mangrove_stairs", "mangrove_slab", "red_nether_brick_wall", "mangrove_fence", "mangrove_fence_gate", "mangrove_trapdoor");
            addColorFamily(list, "orange", 240, 118, 19, "red_sandstone_stairs", "red_sandstone_slab", "red_sandstone_wall", "acacia_fence", "acacia_fence_gate", "acacia_trapdoor");
            addColorFamily(list, "yellow", 248, 197, 39, "sandstone_stairs", "sandstone_slab", "sandstone_wall", "bamboo_fence", "bamboo_fence_gate", "bamboo_trapdoor");
            addColorFamily(list, "lime", 112, 185, 25, "mossy_cobblestone_stairs", "mossy_cobblestone_slab", "mossy_cobblestone_wall", null, null, null);
            addColorFamily(list, "green", 84, 109, 27, "dark_prismarine_stairs", "dark_prismarine_slab", "mossy_stone_brick_wall", null, null, null);
            addColorFamily(list, "cyan", 21, 137, 145, "warped_stairs", "warped_slab", "prismarine_wall", "warped_fence", "warped_fence_gate", "warped_trapdoor");
            addColorFamily(list, "light_blue", 58, 175, 217, "prismarine_brick_stairs", "prismarine_brick_slab", "prismarine_wall", null, null, null);
            addColorFamily(list, "blue", 53, 57, 157, "dark_prismarine_stairs", "dark_prismarine_slab", "prismarine_wall", null, null, null);
            addColorFamily(list, "purple", 121, 42, 172, "crimson_stairs", "crimson_slab", "blackstone_wall", "crimson_fence", "crimson_fence_gate", "crimson_trapdoor");
            addColorFamily(list, "magenta", 189, 68, 179, "purpur_stairs", "purpur_slab", "brick_wall", "crimson_fence", "crimson_fence_gate", "crimson_trapdoor");
            addColorFamily(list, "pink", 237, 141, 172, "cherry_stairs", "cherry_slab", "granite_wall", "cherry_fence", "cherry_fence_gate", "cherry_trapdoor");

            List<SubBlockFamily> built = Collections.unmodifiableList(list);
            if (!built.isEmpty()) {
                subBlockFamilies = built;
            }
            return built;
        }
    }

    private static void addWoodFamily(List<SubBlockFamily> list, String wood, int r, int g, int b) {
        BlockType full = firstBlock(wood + "_planks", wood + "_block");
        if (full == null) return;
        BlockType stairs = firstBlock(wood + "_stairs");
        BlockType slab = firstBlock(wood + "_slab");
        BlockType fence = firstBlock(wood + "_fence");
        BlockType gate = firstBlock(wood + "_fence_gate");
        BlockType trapdoor = firstBlock(wood + "_trapdoor");
        list.add(new SubBlockFamily(wood, r, g, b, full, stairs, slab, null, fence, gate, trapdoor, null));
    }

    private static void addStoneFamily(
            List<SubBlockFamily> list,
            String name, int r, int g, int b,
            String fullId, String stairsId, String slabId, String wallId, String carpetId
    ) {
        addStoneFamily(list, name, r, g, b, fullId, stairsId, slabId, wallId, carpetId, null, null);
    }

    private static void addStoneFamily(
            List<SubBlockFamily> list,
            String name, int r, int g, int b,
            String fullId, String stairsId, String slabId, String wallId, String carpetId, String fenceId
    ) {
        addStoneFamily(list, name, r, g, b, fullId, stairsId, slabId, wallId, carpetId, fenceId, null);
    }

    private static void addStoneFamily(
            List<SubBlockFamily> list,
            String name, int r, int g, int b,
            String fullId, String stairsId, String slabId, String wallId, String carpetId, String fenceId, String trapdoorId
    ) {
        BlockType full = firstBlock(fullId);
        if (full == null) return;
        list.add(new SubBlockFamily(
                name, r, g, b, full,
                firstBlock(stairsId),
                firstBlock(slabId),
                firstBlock(wallId),
                firstBlock(fenceId),
                null,
                firstBlock(trapdoorId),
                firstBlock(carpetId)
        ));
    }

    private static void addColorFamily(
            List<SubBlockFamily> list,
            String color, int r, int g, int b,
            String fallbackStairs, String fallbackSlab, String fallbackWall,
            String fallbackFence, String fallbackGate, String fallbackTrapdoor
    ) {
        BlockType full = firstBlock(color + "_concrete", color + "_wool");
        if (full == null) return;
        BlockType stairs = firstBlock(color + "_concrete_stairs", color + "_wool_stairs", fallbackStairs);
        BlockType slab = firstBlock(color + "_concrete_slab", color + "_wool_slab", fallbackSlab);
        BlockType wall = firstBlock(fallbackWall);
        BlockType fence = firstBlock(fallbackFence != null ? fallbackFence.trim() : null);
        BlockType gate = firstBlock(fallbackGate);
        BlockType trapdoor = firstBlock(fallbackTrapdoor);
        BlockType carpet = firstBlock(color + "_carpet");
        list.add(new SubBlockFamily(color, r, g, b, full, stairs, slab, wall, fence, gate, trapdoor, carpet));
    }

    private static BlockType firstBlock(String... ids) {
        if (ids == null) return null;
        for (String id : ids) {
            if (id == null || id.isEmpty()) continue;
            String full = id.contains(":") ? id.toLowerCase(Locale.ROOT) : "minecraft:" + id.toLowerCase(Locale.ROOT);
            try {
                BlockType bt = BlockTypes.get(full);
                if (bt != null && bt.getDefaultState() != null) {
                    return bt;
                }
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    public static List<PaletteEntry> getSolidPalette() {
        List<PaletteEntry> current = solidPalette;
        if (current != null && !current.isEmpty()) {
            return current;
        }
        synchronized (BlockColorMapper.class) {
            if (solidPalette != null && !solidPalette.isEmpty()) {
                return solidPalette;
            }
            Map<String, PaletteEntry> map = new HashMap<>();

            // 1. Add curated full-cube palette with accurate average sRGB colors
            registerCuratedSolidBlocks(map);

            // 2. Augment with FAWE TextureUtil colors for any additional blocks in java.util.Collections.<BlockType>emptySet()
            TextureUtil tu = getFaweTextureUtil();
            if (tu != null && false) {
                for (BlockType bt : java.util.Collections.<BlockType>emptySet()) {
                    if (bt == null || bt.getDefaultState() == null) {
                        continue;
                    }
                    try {
                        int color = tu.getColor(bt);
                        int alpha = (color >>> 24) & 0xFF;
                        if (alpha == 255) {
                            int r = (color >>> 16) & 0xFF;
                            int g = (color >>> 8) & 0xFF;
                            int b = color & 0xFF;
                            String id = bt.id();
                            map.put(id, new PaletteEntry(id, bt, r, g, b, false));
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }

            List<PaletteEntry> built = Collections.unmodifiableList(new ArrayList<>(map.values()));
            if (!built.isEmpty()) {
                solidPalette = built;
            }
            return built;
        }
    }

    public static List<PaletteEntry> getGlassPalette() {
        List<PaletteEntry> current = glassPalette;
        if (current != null && !current.isEmpty()) {
            return current;
        }
        synchronized (BlockColorMapper.class) {
            if (glassPalette != null && !glassPalette.isEmpty()) {
                return glassPalette;
            }
            List<PaletteEntry> list = new ArrayList<>();
            addEntry(list, "white_stained_glass", 255, 255, 255, true);
            addEntry(list, "light_gray_stained_glass", 153, 153, 153, true);
            addEntry(list, "gray_stained_glass", 76, 76, 76, true);
            addEntry(list, "black_stained_glass", 25, 25, 25, true);
            addEntry(list, "brown_stained_glass", 102, 76, 51, true);
            addEntry(list, "red_stained_glass", 153, 51, 51, true);
            addEntry(list, "orange_stained_glass", 216, 127, 51, true);
            addEntry(list, "yellow_stained_glass", 229, 229, 51, true);
            addEntry(list, "lime_stained_glass", 127, 204, 25, true);
            addEntry(list, "green_stained_glass", 102, 127, 51, true);
            addEntry(list, "cyan_stained_glass", 76, 127, 153, true);
            addEntry(list, "light_blue_stained_glass", 102, 153, 216, true);
            addEntry(list, "blue_stained_glass", 51, 76, 178, true);
            addEntry(list, "purple_stained_glass", 127, 63, 178, true);
            addEntry(list, "magenta_stained_glass", 178, 76, 216, true);
            addEntry(list, "pink_stained_glass", 242, 127, 165, true);
            addEntry(list, "tinted_glass", 44, 38, 46, true);

            List<PaletteEntry> built = Collections.unmodifiableList(list);
            if (!built.isEmpty()) {
                glassPalette = built;
            }
            return built;
        }
    }

    private static void registerCuratedSolidBlocks(Map<String, PaletteEntry> map) {
        // Concrete (16)
        putEntry(map, "white_concrete", 207, 213, 214);
        putEntry(map, "light_gray_concrete", 125, 125, 115);
        putEntry(map, "gray_concrete", 54, 57, 61);
        putEntry(map, "black_concrete", 8, 10, 15);
        putEntry(map, "brown_concrete", 96, 59, 31);
        putEntry(map, "red_concrete", 142, 32, 32);
        putEntry(map, "orange_concrete", 224, 97, 1);
        putEntry(map, "yellow_concrete", 241, 175, 21);
        putEntry(map, "lime_concrete", 94, 169, 24);
        putEntry(map, "green_concrete", 73, 91, 36);
        putEntry(map, "cyan_concrete", 21, 119, 136);
        putEntry(map, "light_blue_concrete", 35, 137, 198);
        putEntry(map, "blue_concrete", 44, 46, 143);
        putEntry(map, "purple_concrete", 100, 31, 156);
        putEntry(map, "magenta_concrete", 169, 48, 159);
        putEntry(map, "pink_concrete", 213, 101, 142);

        // Wool (16)
        putEntry(map, "white_wool", 233, 236, 236);
        putEntry(map, "light_gray_wool", 142, 142, 134);
        putEntry(map, "gray_wool", 62, 68, 71);
        putEntry(map, "black_wool", 20, 21, 25);
        putEntry(map, "brown_wool", 114, 71, 40);
        putEntry(map, "red_wool", 160, 39, 34);
        putEntry(map, "orange_wool", 240, 118, 19);
        putEntry(map, "yellow_wool", 248, 197, 39);
        putEntry(map, "lime_wool", 112, 185, 25);
        putEntry(map, "green_wool", 84, 109, 27);
        putEntry(map, "cyan_wool", 21, 137, 145);
        putEntry(map, "light_blue_wool", 58, 175, 217);
        putEntry(map, "blue_wool", 53, 57, 157);
        putEntry(map, "purple_wool", 121, 42, 172);
        putEntry(map, "magenta_wool", 189, 68, 179);
        putEntry(map, "pink_wool", 237, 141, 172);

        // Terracotta (17)
        putEntry(map, "terracotta", 152, 94, 67);
        putEntry(map, "white_terracotta", 209, 178, 161);
        putEntry(map, "light_gray_terracotta", 135, 107, 98);
        putEntry(map, "gray_terracotta", 57, 42, 35);
        putEntry(map, "black_terracotta", 37, 22, 16);
        putEntry(map, "brown_terracotta", 77, 51, 35);
        putEntry(map, "red_terracotta", 143, 61, 46);
        putEntry(map, "orange_terracotta", 161, 83, 37);
        putEntry(map, "yellow_terracotta", 186, 133, 35);
        putEntry(map, "lime_terracotta", 103, 117, 52);
        putEntry(map, "green_terracotta", 76, 83, 42);
        putEntry(map, "cyan_terracotta", 86, 91, 91);
        putEntry(map, "light_blue_terracotta", 113, 108, 137);
        putEntry(map, "blue_terracotta", 74, 59, 91);
        putEntry(map, "purple_terracotta", 118, 70, 86);
        putEntry(map, "magenta_terracotta", 149, 88, 108);
        putEntry(map, "pink_terracotta", 161, 78, 78);

        // Planks & Woods
        putEntry(map, "oak_planks", 162, 130, 78);
        putEntry(map, "spruce_planks", 114, 84, 48);
        putEntry(map, "birch_planks", 192, 175, 121);
        putEntry(map, "jungle_planks", 160, 115, 80);
        putEntry(map, "acacia_planks", 168, 90, 50);
        putEntry(map, "dark_oak_planks", 66, 43, 20);
        putEntry(map, "mangrove_planks", 117, 54, 48);
        putEntry(map, "cherry_planks", 226, 178, 172);
        putEntry(map, "bamboo_planks", 193, 173, 80);
        putEntry(map, "crimson_planks", 101, 48, 70);
        putEntry(map, "warped_planks", 43, 104, 99);
        putEntry(map, "pale_oak_planks", 227, 217, 210);

        // Stones, Minerals, & Natural Blocks
        putEntry(map, "snow_block", 249, 254, 254);
        putEntry(map, "quartz_block", 235, 229, 222);
        putEntry(map, "smooth_quartz", 235, 229, 222);
        putEntry(map, "calcite", 223, 224, 220);
        putEntry(map, "diorite", 188, 188, 189);
        putEntry(map, "polished_diorite", 192, 193, 195);
        putEntry(map, "clay", 160, 166, 179);
        putEntry(map, "stone", 125, 125, 125);
        putEntry(map, "smooth_stone", 158, 158, 158);
        putEntry(map, "andesite", 136, 136, 136);
        putEntry(map, "cobblestone", 127, 127, 127);
        putEntry(map, "stone_bricks", 122, 121, 122);
        putEntry(map, "tuff", 108, 109, 102);
        putEntry(map, "deepslate", 80, 80, 82);
        putEntry(map, "polished_deepslate", 72, 72, 73);
        putEntry(map, "deepslate_bricks", 70, 70, 71);
        putEntry(map, "deepslate_tiles", 54, 54, 55);
        putEntry(map, "smooth_basalt", 72, 72, 78);
        putEntry(map, "blackstone", 42, 35, 40);
        putEntry(map, "polished_blackstone", 53, 48, 56);
        putEntry(map, "polished_blackstone_bricks", 48, 42, 49);
        putEntry(map, "obsidian", 15, 10, 24);
        putEntry(map, "coal_block", 16, 15, 15);
        putEntry(map, "mud", 60, 57, 60);
        putEntry(map, "packed_mud", 142, 106, 79);
        putEntry(map, "mud_bricks", 137, 103, 79);
        putEntry(map, "dripstone_block", 134, 107, 92);
        putEntry(map, "granite", 149, 103, 85);
        putEntry(map, "polished_granite", 154, 106, 89);
        putEntry(map, "bricks", 150, 97, 83);
        putEntry(map, "resin_bricks", 214, 110, 42);
        putEntry(map, "dirt", 134, 96, 67);
        putEntry(map, "coarse_dirt", 119, 85, 59);
        putEntry(map, "sand", 219, 207, 163);
        putEntry(map, "cut_sandstone", 217, 206, 159);
        putEntry(map, "red_sand", 190, 102, 33);
        putEntry(map, "cut_red_sandstone", 189, 101, 31);
        putEntry(map, "end_stone", 219, 222, 158);
        putEntry(map, "end_stone_bricks", 218, 224, 162);
        putEntry(map, "sponge", 195, 192, 74);
        putEntry(map, "moss_block", 89, 109, 45);
        putEntry(map, "prismarine", 99, 156, 151);
        putEntry(map, "prismarine_bricks", 99, 171, 158);
        putEntry(map, "dark_prismarine", 51, 91, 75);
        putEntry(map, "packed_ice", 141, 180, 250);
        putEntry(map, "blue_ice", 116, 167, 253);
        putEntry(map, "purpur_block", 169, 125, 169);
        putEntry(map, "amethyst_block", 133, 97, 191);
        putEntry(map, "nether_bricks", 44, 21, 26);
        putEntry(map, "red_nether_bricks", 69, 7, 9);
        putEntry(map, "nether_wart_block", 114, 2, 2);
        putEntry(map, "warped_wart_block", 22, 119, 121);
        putEntry(map, "brown_mushroom_block", 149, 111, 81);
        putEntry(map, "red_mushroom_block", 200, 46, 45);
        putEntry(map, "iron_block", 220, 220, 220);
        putEntry(map, "gold_block", 246, 208, 61);
        putEntry(map, "diamond_block", 98, 237, 228);
        putEntry(map, "emerald_block", 42, 203, 87);
        putEntry(map, "lapis_block", 30, 67, 140);
        putEntry(map, "redstone_block", 175, 24, 5);
        putEntry(map, "raw_iron_block", 166, 135, 107);
        putEntry(map, "raw_gold_block", 221, 169, 46);
        putEntry(map, "raw_copper_block", 154, 105, 79);
        putEntry(map, "waxed_copper_block", 192, 107, 79);
        putEntry(map, "waxed_exposed_copper", 161, 125, 103);
        putEntry(map, "waxed_weathered_copper", 108, 153, 110);
        putEntry(map, "waxed_oxidized_copper", 82, 162, 132);
        putEntry(map, "netherrack", 97, 38, 38);
        putEntry(map, "stripped_mangrove_wood", 119, 54, 47);
        putEntry(map, "crimson_hyphae", 93, 26, 30);
        putEntry(map, "stripped_crimson_hyphae", 137, 57, 90);
        putEntry(map, "warped_hyphae", 58, 58, 77);
        putEntry(map, "stripped_warped_hyphae", 57, 150, 147);
        putEntry(map, "stripped_oak_wood", 177, 144, 86);
        putEntry(map, "stripped_spruce_wood", 115, 89, 52);
        putEntry(map, "stripped_birch_wood", 196, 176, 118);
        putEntry(map, "stripped_dark_oak_wood", 72, 56, 36);
        putEntry(map, "stripped_cherry_wood", 215, 145, 148);
        putEntry(map, "stripped_pale_oak_wood", 245, 238, 232);
        putEntry(map, "honeycomb_block", 229, 148, 29);
        putEntry(map, "shroomlight", 240, 146, 70);
        putEntry(map, "ochre_froglight", 250, 245, 206);
        putEntry(map, "verdant_froglight", 229, 244, 228);
        putEntry(map, "pearlescent_froglight", 245, 228, 241);
    }

    private static void putEntry(Map<String, PaletteEntry> map, String shortId, int r, int g, int b) {
        String fullId = shortId.contains(":") ? shortId.toLowerCase(Locale.ROOT) : "minecraft:" + shortId.toLowerCase(Locale.ROOT);
        try {
            BlockType bt = BlockTypes.get(fullId);
            if (bt != null && bt.getDefaultState() != null) {
                map.put(bt.id(), new PaletteEntry(bt.id(), bt, r, g, b, false));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void addEntry(List<PaletteEntry> list, String shortId, int r, int g, int b, boolean translucent) {
        String fullId = shortId.contains(":") ? shortId.toLowerCase(Locale.ROOT) : "minecraft:" + shortId.toLowerCase(Locale.ROOT);
        try {
            BlockType bt = BlockTypes.get(fullId);
            if (bt != null && bt.getDefaultState() != null) {
                list.add(new PaletteEntry(bt.id(), bt, r, g, b, translucent));
            }
        } catch (Throwable ignored) {
        }
    }
}

