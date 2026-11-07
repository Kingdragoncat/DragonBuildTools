package com.dragonbuildtools.brush;

import com.dragonbuildtools.util.BlockColorMapper;
import com.dragonbuildtools.util.BlockColorMapper.SubBlockFamily;
import com.dragonbuildtools.util.VanillaBlockAppearanceResolver;
import com.dragonbuildtools.util.VanillaBlockAppearanceResolver.ShapeKind;
import com.fastasyncworldedit.core.registry.state.PropertyKey;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.command.tool.brush.Brush;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.Direction;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Sub-Block BlendBall Brush ({@code //subblend} / {@code /dbr subblend}).
 *
 * <p>Operates like FAWE's {@code /br blendball}, upgraded to 2x2x2 sub-voxel octant resolution:
 * <ul>
 *   <li>Decodes existing full blocks, directional/corner stairs, top/bottom slabs, walls, fences,
 *       trapdoors, and snow layers into an exact 8-octant (0.5-block) occupancy grid.</li>
 *   <li>Runs 26-neighbor 3D BlendBall smoothing on the sub-voxel grid so jagged 1x1x1 steps,
 *       ledges, overhangs, bumps, and cavities naturally chamfer/fill into backed straight stairs,
 *       inner/outer corner stairs, and top/bottom slabs.</li>
 *   <li>Simultaneously performs 3x3x3 BlendBall material majority voting while unifying full blocks
 *       and sub-blocks belonging to the same {@link SubBlockFamily}.</li>
 * </ul>
 */
public final class SubBlockBlendBallBrush implements Brush {

    // Octant bit layout: bit = dx | (dy << 1) | (dz << 2)
    public static final int NW_BOT = 0x01; // dx=0, dy=0, dz=0
    public static final int NE_BOT = 0x02; // dx=1, dy=0, dz=0
    public static final int NW_TOP = 0x04; // dx=0, dy=1, dz=0
    public static final int NE_TOP = 0x08; // dx=1, dy=1, dz=0
    public static final int SW_BOT = 0x10; // dx=0, dy=0, dz=1
    public static final int SE_BOT = 0x20; // dx=1, dy=0, dz=1
    public static final int SW_TOP = 0x40; // dx=0, dy=1, dz=1
    public static final int SE_TOP = 0x80; // dx=1, dy=1, dz=1

    public static final int BOT_MASK = NW_BOT | NE_BOT | SW_BOT | SE_BOT;     // 0x33
    public static final int TOP_MASK = NW_TOP | NE_TOP | SW_TOP | SE_TOP;     // 0xCC
    public static final int NORTH_MASK = NW_BOT | NE_BOT | NW_TOP | NE_TOP;   // 0x0F
    public static final int SOUTH_MASK = SW_BOT | SE_BOT | SW_TOP | SE_TOP;   // 0xF0
    public static final int WEST_MASK = NW_BOT | NW_TOP | SW_BOT | SW_TOP;    // 0x55
    public static final int EAST_MASK = NE_BOT | NE_TOP | SE_BOT | SE_TOP;    // 0xAA
    public static final int FULL_MASK = 0xFF;

    public enum SynthesizedKind {
        AIR,
        FULL_BLOCK,
        SLAB,
        STAIRS,
        WALL,
        SNOW
    }

    public static final class SynthesizedShape {
        public final SynthesizedKind kind;
        public final Direction facing; // for STAIRS
        public final String half;      // "bottom" or "top" for STAIRS / SLAB
        public final String stairShape;// "straight", "inner_left", "inner_right", "outer_left", "outer_right"
        public final int snowLayers;   // 1..7 for SNOW
        public final boolean connN, connE, connS, connW, hasUp; // for WALL

        private SynthesizedShape(
                SynthesizedKind kind,
                Direction facing,
                String half,
                String stairShape,
                int snowLayers,
                boolean connN, boolean connE, boolean connS, boolean connW, boolean hasUp
        ) {
            this.kind = kind;
            this.facing = facing;
            this.half = half;
            this.stairShape = stairShape;
            this.snowLayers = snowLayers;
            this.connN = connN;
            this.connE = connE;
            this.connS = connS;
            this.connW = connW;
            this.hasUp = hasUp;
        }

        public static SynthesizedShape air() {
            return new SynthesizedShape(SynthesizedKind.AIR, null, null, null, 0, false, false, false, false, false);
        }

        public static SynthesizedShape fullBlock() {
            return new SynthesizedShape(SynthesizedKind.FULL_BLOCK, null, null, null, 0, false, false, false, false, false);
        }

        public static SynthesizedShape slab(String half) {
            return new SynthesizedShape(SynthesizedKind.SLAB, null, half, null, 0, false, false, false, false, false);
        }

        public static SynthesizedShape stairs(Direction facing, String half, String stairShape) {
            return new SynthesizedShape(SynthesizedKind.STAIRS, facing, half, stairShape, 0, false, false, false, false, false);
        }

        public static SynthesizedShape wall(boolean connN, boolean connE, boolean connS, boolean connW, boolean hasUp) {
            return new SynthesizedShape(SynthesizedKind.WALL, null, null, null, 0, connN, connE, connS, connW, hasUp);
        }

        public static SynthesizedShape snow(int layers) {
            return new SynthesizedShape(SynthesizedKind.SNOW, null, null, null, layers, false, false, false, false, false);
        }
    }

    private static final double MAX_BRUSH_RADIUS = 35.0;

    private final int minFreqDiff;
    private final boolean airOnly;
    private final boolean geometryOnly;
    private final boolean allowWalls;
    private final boolean allowSnowLayers;

    public SubBlockBlendBallBrush(
            int minFreqDiff,
            boolean airOnly,
            boolean geometryOnly,
            boolean allowWalls,
            boolean allowSnowLayers
    ) {
        this.minFreqDiff = Math.max(1, Math.min(10, minFreqDiff));
        this.airOnly = airOnly;
        this.geometryOnly = geometryOnly;
        this.allowWalls = allowWalls;
        this.allowSnowLayers = allowSnowLayers;
    }

    @Override
    public void build(EditSession editSession, BlockVector3 position, Pattern pattern, double size)
            throws MaxChangedBlocksException {
        double radius = Math.max(1.0, Math.min(MAX_BRUSH_RADIUS, size));
        int rCeil = (int) Math.ceil(radius);
        int pad = rCeil + 2;

        int cx = position.x();
        int cy = position.y();
        int cz = position.z();

        int worldMinY = editSession.getMinY();
        int worldMaxY = editSession.getMaxY();

        int minX = cx - pad;
        int maxX = cx + pad;
        int minY = Math.max(worldMinY, cy - pad);
        int maxY = Math.min(worldMaxY, cy + pad);
        int minZ = cz - pad;
        int maxZ = cz + pad;

        if (minY > maxY) {
            return;
        }

        int bxLen = maxX - minX + 1;
        int byLen = maxY - minY + 1;
        int bzLen = maxZ - minZ + 1;
        int bTotal = bxLen * byLen * bzLen;

        BlockState[] origStates = new BlockState[bTotal];
        BlockType[] canonicalTypes = new BlockType[bTotal];
        byte[] origOctants = new byte[bTotal];
        boolean[] ignoredPlantOrFluid = new boolean[bTotal];

        Map<BlockType, BlockType> canonicalCache = new HashMap<>();
        Set<SubBlockFamily> localFamiliesSet = new HashSet<>();

        // 1. Snapshot block grid & decode every block into its exact 8-bit 2x2x2 sub-voxel octant mask
        for (int y = 0; y < byLen; y++) {
            int wy = minY + y;
            for (int z = 0; z < bzLen; z++) {
                int wz = minZ + z;
                int row = (y * bzLen + z) * bxLen;
                for (int x = 0; x < bxLen; x++) {
                    int wx = minX + x;
                    int idx = row + x;

                    BlockState st = editSession.getBlock(wx, wy, wz);
                    origStates[idx] = st;
                    if (st == null || st.isAir() || st.getBlockType() == null) {
                        origOctants[idx] = 0;
                        continue;
                    }

                    BlockType bt = st.getBlockType();
                    String cleanName = VanillaBlockAppearanceResolver.cleanBlockName(bt.id());
                    if (isFluidOrUntouchable(cleanName)) {
                        ignoredPlantOrFluid[idx] = true;
                        origOctants[idx] = 0;
                        continue;
                    }

                    int oct = decodeBlockStateOctants(st, cleanName);
                    origOctants[idx] = (byte) oct;
                    if (oct != 0) {
                        BlockType canon = canonicalCache.computeIfAbsent(bt, SubBlockBlendBallBrush::resolveCanonicalBlockType);
                        canonicalTypes[idx] = canon;
                        SubBlockFamily fam = BlockColorMapper.resolveFamilyForBlock(canon);
                        if (fam != null) {
                            localFamiliesSet.add(fam);
                        }
                    }
                }
            }
        }

        List<SubBlockFamily> localFamilies = new ArrayList<>(localFamiliesSet);

        // 2. Smooth the 2x2x2 sub-voxel octant grid using 26-neighbor BlendBall voting
        // Note: sub-voxel margin 2 * minFreqDiff makes flat floors/walls (17 vs 9) invariant while
        // chamfering 45-deg stairs (15 vs 11), bumps (16/10 & 7/19), and potholes (19/7 & 14/12).
        int subMinDiff = Math.max(2, minFreqDiff * 2);
        byte[] smoothedOctants = smoothOctantGrid(
                origOctants, bxLen, byLen, bzLen,
                cx - minX, cy - minY, cz - minZ,
                radius, subMinDiff,
                minY == worldMinY
        );

        // 3. Reconstruct smoothed blocks & sub-blocks inside the brush sphere
        double rSq = radius * radius;
        BlockState airState = getAirState();
        Map<BlockType, Integer> neighborFreq = new HashMap<>(16);

        int xStart = Math.max(1, (cx - rCeil) - minX);
        int xEnd = Math.min(bxLen - 2, (cx + rCeil) - minX);
        int yStart = Math.max(0, (cy - rCeil) - minY);
        int yEnd = Math.min(byLen - 1, (cy + rCeil) - minY);
        int zStart = Math.max(1, (cz - rCeil) - minZ);
        int zEnd = Math.min(bzLen - 2, (cz + rCeil) - minZ);

        for (int y = yStart; y <= yEnd; y++) {
            int wy = minY + y;
            int dy = wy - cy;
            for (int z = zStart; z <= zEnd; z++) {
                int wz = minZ + z;
                int dz = wz - cz;
                int dyzSq = dy * dy + dz * dz;
                if (dyzSq > rSq) {
                    continue;
                }
                int row = (y * bzLen + z) * bxLen;
                for (int x = xStart; x <= xEnd; x++) {
                    int wx = minX + x;
                    int dx = wx - cx;
                    if (dyzSq + dx * dx > rSq) {
                        continue;
                    }

                    int idx = row + x;
                    if (ignoredPlantOrFluid[idx]) {
                        continue;
                    }

                    BlockState origState = origStates[idx];
                    int oldOct = origOctants[idx] & 0xFF;
                    int newOct = smoothedOctants[idx] & 0xFF;

                    int downOct = y > 0 ? (smoothedOctants[((y - 1) * bzLen + z) * bxLen + x] & 0xFF)
                            : (minY == worldMinY ? FULL_MASK : 0);
                    int upOct = y + 1 < byLen ? (smoothedOctants[((y + 1) * bzLen + z) * bxLen + x] & 0xFF) : 0;
                    int northOct = smoothedOctants[(y * bzLen + (z - 1)) * bxLen + x] & 0xFF;
                    int southOct = smoothedOctants[(y * bzLen + (z + 1)) * bxLen + x] & 0xFF;
                    int westOct = smoothedOctants[row + (x - 1)] & 0xFF;
                    int eastOct = smoothedOctants[row + (x + 1)] & 0xFF;

                    // Pick target canonical material via 3x3x3 BlendBall neighbor voting
                    BlockType selfCanon = canonicalTypes[idx];
                    BlockType targetCanon = selectTargetMaterial(
                            x, y, z, bxLen, byLen, bzLen,
                            canonicalTypes, selfCanon,
                            minFreqDiff, airOnly || geometryOnly, neighborFreq
                    );

                    boolean snowTheme = allowSnowLayers || isSnowMaterial(targetCanon);
                    SynthesizedShape shape = classifyOctantShape(
                            newOct, downOct, upOct, northOct, southOct, westOct, eastOct,
                            allowWalls, snowTheme
                    );

                    if (shape.kind == SynthesizedKind.AIR) {
                        if (origState != null && !origState.isAir() && airState != null) {
                            editSession.setBlock(wx, wy, wz, airState);
                        }
                        continue;
                    }

                    if (targetCanon == null) {
                        continue;
                    }

                    // If neither the octants nor the material changed, keep the exact original blockstate untouched
                    if (newOct == oldOct && targetCanon.equals(selfCanon) && origState != null && !origState.isAir()) {
                        if (shape.kind == SynthesizedKind.FULL_BLOCK && oldOct == FULL_MASK) {
                            continue;
                        }
                    }

                    BlockState builtState = buildBlockStateForShape(shape, targetCanon, origState, selfCanon, localFamilies);
                    if (builtState != null && !builtState.equals(origState)) {
                        editSession.setBlock(wx, wy, wz, builtState);
                    }
                }
            }
        }
    }

    /**
     * Decodes a Minecraft {@link BlockState} into its 8-bit 2x2x2 sub-voxel octant mask.
     */
    public static int decodeBlockStateOctants(BlockState state, String cleanName) {
        if (state == null || state.isAir()) {
            return 0;
        }
        ShapeKind kind = VanillaBlockAppearanceResolver.getShapeKind(state);
        switch (kind) {
            case INVISIBLE:
            case CROSS:
            case TORCH:
            case BUTTON:
            case PLATE:
            case FLAT_SURFACE:
                return 0;
            case CARPET:
                return 0;
            case SNOW: {
                int layers = readIntProp(state, PropertyKey.LAYERS, 1);
                if (layers >= 7) return FULL_MASK;
                if (layers >= 3) return BOT_MASK;
                return 0;
            }
            case SLAB: {
                String type = readStringProp(state, PropertyKey.TYPE, "bottom");
                if ("double".equals(type)) return FULL_MASK;
                if ("top".equals(type)) return TOP_MASK;
                return BOT_MASK;
            }
            case STAIRS: {
                String half = readStringProp(state, PropertyKey.HALF, "bottom");
                String facing = readStringProp(state, PropertyKey.FACING, "north");
                String shape = readStringProp(state, PropertyKey.SHAPE, "straight");
                return encodeStairOctants(facing, half, shape);
            }
            case TRAPDOOR: {
                boolean open = "true".equals(readStringProp(state, PropertyKey.OPEN, "false"));
                if (open) {
                    String facing = readStringProp(state, PropertyKey.FACING, "north");
                    switch (facing) {
                        case "north": return SOUTH_MASK;
                        case "south": return NORTH_MASK;
                        case "west": return EAST_MASK;
                        case "east": return WEST_MASK;
                        default: return 0;
                    }
                }
                String half = readStringProp(state, PropertyKey.HALF, "bottom");
                return "top".equals(half) ? TOP_MASK : BOT_MASK;
            }
            case WALL:
            case FENCE:
            case FENCE_GATE:
            case PANE:
            case ROD:
            case LANTERN:
                return NW_BOT | NE_BOT | NW_TOP | SE_BOT; // 4 octants partial occupancy
            case CUBE:
            case CUTOUT_CUBE:
            case TRANSLUCENT_CUBE:
            default:
                return FULL_MASK;
        }
    }

    /**
     * Encodes exact 8-bit sub-voxel octants for all 40 Minecraft stair states.
     */
    public static int encodeStairOctants(String facing, String half, String shape) {
        boolean topHalf = "top".equalsIgnoreCase(half);
        int baseMask = topHalf ? TOP_MASK : BOT_MASK;

        int fx = 0, fz = -1;
        if ("south".equalsIgnoreCase(facing)) { fx = 0; fz = 1; }
        else if ("east".equalsIgnoreCase(facing)) { fx = 1; fz = 0; }
        else if ("west".equalsIgnoreCase(facing)) { fx = -1; fz = 0; }

        // Left & right vectors when looking along facing
        int lx = fz, lz = -fx;
        int rx = -fz, rz = fx;

        int stepMask = 0;
        int stepY = topHalf ? 0 : 1;
        String sh = shape != null ? shape.toLowerCase(Locale.ROOT) : "straight";

        for (int dz = 0; dz < 2; dz++) {
            for (int dx = 0; dx < 2; dx++) {
                int cx = dx == 0 ? -1 : 1;
                int cz = dz == 0 ? -1 : 1;
                boolean inFront = (cx * fx + cz * fz) > 0;
                boolean inLeft = (cx * lx + cz * lz) > 0;
                boolean inRight = (cx * rx + cz * rz) > 0;
                boolean occ;
                switch (sh) {
                    case "outer_left":  occ = inFront && inLeft; break;
                    case "outer_right": occ = inFront && inRight; break;
                    case "inner_left":  occ = inFront || inLeft; break;
                    case "inner_right": occ = inFront || inRight; break;
                    case "straight":
                    default:            occ = inFront; break;
                }
                if (occ) {
                    int bit = 1 << (dx | (stepY << 1) | (dz << 2));
                    stepMask |= bit;
                }
            }
        }
        return baseMask | stepMask;
    }

    /**
     * Runs 26-neighbor BlendBall smoothing on the (2*bxLen) x (2*byLen) x (2*bzLen) sub-voxel octant grid
     * for all blocks inside the brush sphere of {@code radius} centered at {@code (cbx, cby, cbz)}.
     */
    public static byte[] smoothOctantGrid(
            byte[] origOctants,
            int bxLen,
            int byLen,
            int bzLen,
            int cbx,
            int cby,
            int cbz,
            double radius,
            int subMinDiff,
            boolean solidBelowBottom
    ) {
        int sxLen = bxLen * 2;
        int syLen = byLen * 2;
        int szLen = bzLen * 2;
        int sTotal = sxLen * syLen * szLen;

        // Unpack block octants into flat boolean/byte sub-voxel array for fast 26-neighbor access
        byte[] sub = new byte[sTotal];
        for (int by = 0; by < byLen; by++) {
            for (int bz = 0; bz < bzLen; bz++) {
                int bRow = (by * bzLen + bz) * bxLen;
                for (int bx = 0; bx < bxLen; bx++) {
                    int oct = origOctants[bRow + bx] & 0xFF;
                    if (oct == 0) continue;
                    for (int dy = 0; dy < 2; dy++) {
                        int sy = by * 2 + dy;
                        for (int dz = 0; dz < 2; dz++) {
                            int sz = bz * 2 + dz;
                            int sRow = (sy * szLen + sz) * sxLen + bx * 2;
                            for (int dx = 0; dx < 2; dx++) {
                                int bit = 1 << (dx | (dy << 1) | (dz << 2));
                                if ((oct & bit) != 0) {
                                    sub[sRow + dx] = 1;
                                }
                            }
                        }
                    }
                }
            }
        }

        byte[] result = origOctants.clone();
        int rCeil = (int) Math.ceil(radius);
        double rSq = radius * radius;

        int xStart = Math.max(1, cbx - rCeil);
        int xEnd = Math.min(bxLen - 2, cbx + rCeil);
        int yStart = Math.max(0, cby - rCeil);
        int yEnd = Math.min(byLen - 1, cby + rCeil);
        int zStart = Math.max(1, cbz - rCeil);
        int zEnd = Math.min(bzLen - 2, cbz + rCeil);

        int syStride = szLen * sxLen;

        for (int by = yStart; by <= yEnd; by++) {
            int dby = by - cby;
            for (int bz = zStart; bz <= zEnd; bz++) {
                int dbz = bz - cbz;
                int dyzSq = dby * dby + dbz * dbz;
                if (dyzSq > rSq) continue;
                int bRow = (by * bzLen + bz) * bxLen;

                for (int bx = xStart; bx <= xEnd; bx++) {
                    int dbx = bx - cbx;
                    if (dyzSq + dbx * dbx > rSq) continue;

                    int oldOct = origOctants[bRow + bx] & 0xFF;
                    int newOct = 0;

                    for (int dy = 0; dy < 2; dy++) {
                        int sy = by * 2 + dy;
                        for (int dz = 0; dz < 2; dz++) {
                            int sz = bz * 2 + dz;
                            for (int dx = 0; dx < 2; dx++) {
                                int sx = bx * 2 + dx;
                                int bit = 1 << (dx | (dy << 1) | (dz << 2));
                                boolean curSolid = (oldOct & bit) != 0;

                                int solidCount = 0;
                                int airCount = 0;

                                for (int oy = -1; oy <= 1; oy++) {
                                    int nsy = sy + oy;
                                    if (nsy < 0) {
                                        if (solidBelowBottom) {
                                            solidCount += 9;
                                        } else {
                                            airCount += 9;
                                        }
                                        continue;
                                    }
                                    if (nsy >= syLen) {
                                        airCount += 9;
                                        continue;
                                    }
                                    int yOff = nsy * syStride;
                                    for (int oz = -1; oz <= 1; oz++) {
                                        int nsz = sz + oz;
                                        int zOff = yOff + nsz * sxLen;
                                        for (int ox = -1; ox <= 1; ox++) {
                                            if (ox == 0 && oy == 0 && oz == 0) continue;
                                            if (sub[zOff + sx + ox] != 0) {
                                                solidCount++;
                                            } else {
                                                airCount++;
                                            }
                                        }
                                    }
                                }

                                boolean nextSolid = curSolid;
                                if (!curSolid && (solidCount - airCount) >= subMinDiff) {
                                    nextSolid = true;
                                } else if (curSolid && (airCount - solidCount) >= subMinDiff) {
                                    nextSolid = false;
                                }

                                if (nextSolid) {
                                    newOct |= bit;
                                }
                            }
                        }
                    }

                    result[bRow + bx] = (byte) newOct;
                }
            }
        }

        return result;
    }

    /**
     * Classifies an 8-bit smoothed octant mask and its 6 neighbor octant masks into an architecturally
     * valid Minecraft block or sub-block (preventing floating or vertically sandwiched stairs/slabs).
     */
    public static SynthesizedShape classifyOctantShape(
            int oct,
            int downOct,
            int upOct,
            int northOct,
            int southOct,
            int westOct,
            int eastOct,
            boolean allowWalls,
            boolean allowSnowLayers
    ) {
        int botBits = oct & BOT_MASK;
        int topBits = oct & TOP_MASK;
        int botCount = Integer.bitCount(botBits);
        int topCount = Integer.bitCount(topBits);
        int total = botCount + topCount;

        if (total == 0) {
            return SynthesizedShape.air();
        }
        if (total == 8) {
            return SynthesizedShape.fullBlock();
        }

        // Neighbors with <= 2 octants classify as AIR (tiny fringe), so require >= 3 total octants
        boolean hasDown = Integer.bitCount(downOct & 0xFF) >= 3
                && (Integer.bitCount(downOct & TOP_MASK) >= 2 || Integer.bitCount(downOct & 0xFF) >= 4);
        boolean hasUp = Integer.bitCount(upOct & 0xFF) >= 3
                && (Integer.bitCount(upOct & BOT_MASK) >= 2 || Integer.bitCount(upOct & 0xFF) >= 5);
        boolean hasNorth = Integer.bitCount(northOct & 0xFF) >= 3
                && (Integer.bitCount(northOct & SOUTH_MASK) >= 2 || Integer.bitCount(northOct & 0xFF) >= 4);
        boolean hasSouth = Integer.bitCount(southOct & 0xFF) >= 3
                && (Integer.bitCount(southOct & NORTH_MASK) >= 2 || Integer.bitCount(southOct & 0xFF) >= 4);
        boolean hasWest = Integer.bitCount(westOct & 0xFF) >= 3
                && (Integer.bitCount(westOct & EAST_MASK) >= 2 || Integer.bitCount(westOct & 0xFF) >= 4);
        boolean hasEast = Integer.bitCount(eastOct & 0xFF) >= 3
                && (Integer.bitCount(eastOct & WEST_MASK) >= 2 || Integer.bitCount(eastOct & 0xFF) >= 4);

        boolean topNorth = Integer.bitCount(northOct & 0xFF) >= 3 && Integer.bitCount(northOct & TOP_MASK) >= 2;
        boolean topSouth = Integer.bitCount(southOct & 0xFF) >= 3 && Integer.bitCount(southOct & TOP_MASK) >= 2;
        boolean topWest = Integer.bitCount(westOct & 0xFF) >= 3 && Integer.bitCount(westOct & TOP_MASK) >= 2;
        boolean topEast = Integer.bitCount(eastOct & 0xFF) >= 3 && Integer.bitCount(eastOct & TOP_MASK) >= 2;

        boolean botNorth = Integer.bitCount(northOct & 0xFF) >= 3 && Integer.bitCount(northOct & BOT_MASK) >= 2;
        boolean botSouth = Integer.bitCount(southOct & 0xFF) >= 3 && Integer.bitCount(southOct & BOT_MASK) >= 2;
        boolean botWest = Integer.bitCount(westOct & 0xFF) >= 3 && Integer.bitCount(westOct & BOT_MASK) >= 2;
        boolean botEast = Integer.bitCount(eastOct & 0xFF) >= 3 && Integer.bitCount(eastOct & BOT_MASK) >= 2;

        // Vertically sandwiched blocks inside walls/pillars stay full blocks (or walls if thin & allowWalls=true)
        if (hasDown && hasUp) {
            if (allowWalls && total <= 4 && !(hasNorth && hasSouth && hasWest && hasEast)) {
                return SynthesizedShape.wall(hasNorth, hasEast, hasSouth, hasWest, true);
            }
            return total >= 4 ? SynthesizedShape.fullBlock() : SynthesizedShape.air();
        }

        // Tiny 1..2 octant fringes in front of newly chamfered stairs should stay AIR (or snow layers if enabled)
        if (total <= 2) {
            if (allowSnowLayers && hasDown && !hasUp && topCount == 0 && (downOct & 0xFF) == FULL_MASK) {
                return SynthesizedShape.snow(total == 1 ? 2 : 3);
            }
            return SynthesizedShape.air();
        }

        // Pure Bottom Slab (only bottom octants occupied, 3..4 octants, open above)
        if (topCount == 0 && botCount >= 3 && !hasUp) {
            return SynthesizedShape.slab("bottom");
        }

        // Pure Top Slab (only top octants occupied, 3..4 octants, open below)
        if (botCount == 0 && topCount >= 3 && !hasDown) {
            return SynthesizedShape.slab("top");
        }

        // Upright Stairs (half=bottom, open above, bottom supported)
        if (!hasUp && botCount >= 3 && topCount >= 1 && topCount <= 3) {
            SynthesizedShape upright = matchUprightStairShape(topBits, oct, topNorth, topSouth, topWest, topEast);
            if (upright != null) {
                return upright;
            }
            return total >= 6 ? SynthesizedShape.fullBlock() : SynthesizedShape.slab("bottom");
        }

        // Upside-Down Stairs (half=top, open below, top supported)
        if (!hasDown && hasUp && topCount >= 3 && botCount >= 1 && botCount <= 3) {
            SynthesizedShape inverted = matchUpsideDownStairShape(botBits, oct, botNorth, botSouth, botWest, botEast);
            if (inverted != null) {
                return inverted;
            }
            return total >= 6 ? SynthesizedShape.fullBlock() : SynthesizedShape.slab("top");
        }

        // Thin vertical column / wall post when allowWalls is enabled
        if (allowWalls && total >= 3 && total <= 4 && (hasDown || hasUp)) {
            return SynthesizedShape.wall(hasNorth, hasEast, hasSouth, hasWest, hasUp);
        }

        if (total >= 6) {
            return SynthesizedShape.fullBlock();
        }
        if (!hasUp && botCount >= topCount) {
            return SynthesizedShape.slab("bottom");
        }
        if (!hasDown && hasUp) {
            return SynthesizedShape.slab("top");
        }
        return SynthesizedShape.air();
    }

    private static SynthesizedShape matchUprightStairShape(
            int topBits,
            int fullOct,
            boolean hasNorth,
            boolean hasSouth,
            boolean hasWest,
            boolean hasEast
    ) {
        int topCount = Integer.bitCount(topBits);

        // 1. Inner corner stairs (3 top octants = 7 total octants)
        if (topCount == 3) {
            if (topBits == (NW_TOP | NE_TOP | SW_TOP) && (hasNorth || hasWest) && !(hasSouth && hasEast)) {
                return SynthesizedShape.stairs(Direction.NORTH, "bottom", "inner_left");
            }
            if (topBits == (NW_TOP | NE_TOP | SE_TOP) && (hasNorth || hasEast) && !(hasSouth && hasWest)) {
                return SynthesizedShape.stairs(Direction.NORTH, "bottom", "inner_right");
            }
            if (topBits == (SW_TOP | SE_TOP | NW_TOP) && (hasSouth || hasWest) && !(hasNorth && hasEast)) {
                return SynthesizedShape.stairs(Direction.SOUTH, "bottom", "inner_right");
            }
            if (topBits == (SW_TOP | SE_TOP | NE_TOP) && (hasSouth || hasEast) && !(hasNorth && hasWest)) {
                return SynthesizedShape.stairs(Direction.SOUTH, "bottom", "inner_left");
            }
        }

        // 2. Straight stairs (2 adjacent top octants)
        if (topBits == (NW_TOP | NE_TOP) && !hasSouth) {
            return SynthesizedShape.stairs(Direction.NORTH, "bottom", "straight");
        }
        if (topBits == (SW_TOP | SE_TOP) && !hasNorth) {
            return SynthesizedShape.stairs(Direction.SOUTH, "bottom", "straight");
        }
        if (topBits == (NW_TOP | SW_TOP) && !hasEast) {
            return SynthesizedShape.stairs(Direction.WEST, "bottom", "straight");
        }
        if (topBits == (NE_TOP | SE_TOP) && !hasWest) {
            return SynthesizedShape.stairs(Direction.EAST, "bottom", "straight");
        }

        // 3. Outer corner stairs (1 top octant = 5 total octants)
        if (topCount == 1) {
            if (topBits == NW_TOP && !hasSouth && !hasEast) {
                return SynthesizedShape.stairs(Direction.NORTH, "bottom", "outer_left");
            }
            if (topBits == NE_TOP && !hasSouth && !hasWest) {
                return SynthesizedShape.stairs(Direction.NORTH, "bottom", "outer_right");
            }
            if (topBits == SW_TOP && !hasNorth && !hasEast) {
                return SynthesizedShape.stairs(Direction.SOUTH, "bottom", "outer_right");
            }
            if (topBits == SE_TOP && !hasNorth && !hasWest) {
                return SynthesizedShape.stairs(Direction.SOUTH, "bottom", "outer_left");
            }
        }

        // 4. Neighbor-backed fallback
        if (hasNorth && !hasSouth) return SynthesizedShape.stairs(Direction.NORTH, "bottom", "straight");
        if (hasSouth && !hasNorth) return SynthesizedShape.stairs(Direction.SOUTH, "bottom", "straight");
        if (hasWest && !hasEast) return SynthesizedShape.stairs(Direction.WEST, "bottom", "straight");
        if (hasEast && !hasWest) return SynthesizedShape.stairs(Direction.EAST, "bottom", "straight");
        return null;
    }

    private static SynthesizedShape matchUpsideDownStairShape(
            int botBits,
            int fullOct,
            boolean hasNorth,
            boolean hasSouth,
            boolean hasWest,
            boolean hasEast
    ) {
        int botCount = Integer.bitCount(botBits);

        // 1. Inner corner upside-down stairs (3 bottom octants + 4 top octants = 7 octants)
        if (botCount == 3) {
            if (botBits == (NW_BOT | NE_BOT | SW_BOT) && (hasNorth || hasWest) && !(hasSouth && hasEast)) {
                return SynthesizedShape.stairs(Direction.NORTH, "top", "inner_left");
            }
            if (botBits == (NW_BOT | NE_BOT | SE_BOT) && (hasNorth || hasEast) && !(hasSouth && hasWest)) {
                return SynthesizedShape.stairs(Direction.NORTH, "top", "inner_right");
            }
            if (botBits == (SW_BOT | SE_BOT | NW_BOT) && (hasSouth || hasWest) && !(hasNorth && hasEast)) {
                return SynthesizedShape.stairs(Direction.SOUTH, "top", "inner_right");
            }
            if (botBits == (SW_BOT | SE_BOT | NE_BOT) && (hasSouth || hasEast) && !(hasNorth && hasWest)) {
                return SynthesizedShape.stairs(Direction.SOUTH, "top", "inner_left");
            }
        }

        // 2. Straight upside-down stairs
        if (botBits == (NW_BOT | NE_BOT) && !hasSouth) {
            return SynthesizedShape.stairs(Direction.NORTH, "top", "straight");
        }
        if (botBits == (SW_BOT | SE_BOT) && !hasNorth) {
            return SynthesizedShape.stairs(Direction.SOUTH, "top", "straight");
        }
        if (botBits == (NW_BOT | SW_BOT) && !hasEast) {
            return SynthesizedShape.stairs(Direction.WEST, "top", "straight");
        }
        if (botBits == (NE_BOT | SE_BOT) && !hasWest) {
            return SynthesizedShape.stairs(Direction.EAST, "top", "straight");
        }

        // 3. Outer corner upside-down stairs (1 bottom octant)
        if (botCount == 1) {
            if (botBits == NW_BOT && !hasSouth && !hasEast) {
                return SynthesizedShape.stairs(Direction.NORTH, "top", "outer_left");
            }
            if (botBits == NE_BOT && !hasSouth && !hasWest) {
                return SynthesizedShape.stairs(Direction.NORTH, "top", "outer_right");
            }
            if (botBits == SW_BOT && !hasNorth && !hasEast) {
                return SynthesizedShape.stairs(Direction.SOUTH, "top", "outer_right");
            }
            if (botBits == SE_BOT && !hasNorth && !hasWest) {
                return SynthesizedShape.stairs(Direction.SOUTH, "top", "outer_left");
            }
        }

        if (hasNorth && !hasSouth) return SynthesizedShape.stairs(Direction.NORTH, "top", "straight");
        if (hasSouth && !hasNorth) return SynthesizedShape.stairs(Direction.SOUTH, "top", "straight");
        if (hasWest && !hasEast) return SynthesizedShape.stairs(Direction.WEST, "top", "straight");
        if (hasEast && !hasWest) return SynthesizedShape.stairs(Direction.EAST, "top", "straight");
        return null;
    }

    private static BlockType selectTargetMaterial(
            int x, int y, int z,
            int bxLen, int byLen, int bzLen,
            BlockType[] canonicalTypes,
            BlockType selfCanon,
            int minFreqDiff,
            boolean preserveExistingMaterial,
            Map<BlockType, Integer> scratchFreq
    ) {
        if (preserveExistingMaterial && selfCanon != null) {
            return selfCanon;
        }

        scratchFreq.clear();
        int selfCount = selfCanon != null ? 1 : 0;
        int maxCount = selfCount;
        BlockType bestType = selfCanon;
        boolean tie = false;

        int yMin = Math.max(0, y - 1);
        int yMax = Math.min(byLen - 1, y + 1);
        int zMin = Math.max(0, z - 1);
        int zMax = Math.min(bzLen - 1, z + 1);
        int xMin = Math.max(0, x - 1);
        int xMax = Math.min(bxLen - 1, x + 1);

        for (int ny = yMin; ny <= yMax; ny++) {
            for (int nz = zMin; nz <= zMax; nz++) {
                int row = (ny * bzLen + nz) * bxLen;
                for (int nx = xMin; nx <= xMax; nx++) {
                    if (nx == x && ny == y && nz == z) continue;
                    BlockType nt = canonicalTypes[row + nx];
                    if (nt == null) continue;
                    int next = scratchFreq.getOrDefault(nt, 0) + 1;
                    scratchFreq.put(nt, next);
                    if (nt.equals(selfCanon)) {
                        selfCount++;
                    }
                    if (next > maxCount) {
                        maxCount = next;
                        bestType = nt;
                        tie = false;
                    } else if (next == maxCount && !nt.equals(bestType)) {
                        tie = true;
                    }
                }
            }
        }

        if (selfCanon == null) {
            return bestType;
        }
        if (!tie && bestType != null && !bestType.equals(selfCanon) && (maxCount - selfCount) >= minFreqDiff) {
            return bestType;
        }
        return selfCanon;
    }

    private static BlockState buildBlockStateForShape(
            SynthesizedShape shape,
            BlockType targetCanon,
            BlockState origState,
            BlockType selfCanon,
            List<SubBlockFamily> localFamilies
    ) {
        switch (shape.kind) {
            case FULL_BLOCK: {
                if (origState != null && targetCanon.equals(selfCanon)
                        && VanillaBlockAppearanceResolver.getShapeKind(origState) == ShapeKind.CUBE) {
                    return origState;
                }
                return targetCanon.getDefaultState();
            }
            case SNOW: {
                BaseBlock snow = BlockColorMapper.createSnowLayerBlock(shape.snowLayers);
                return snow != null ? snow.toImmutableState() : targetCanon.getDefaultState();
            }
            case SLAB: {
                SubBlockFamily fam = resolveBestFamilyForKind(targetCanon, localFamilies, SynthesizedKind.SLAB);
                if (fam != null && fam.slab != null) {
                    BaseBlock slab = BlockColorMapper.createSlabBlock(fam.slab, shape.half);
                    if (slab != null) {
                        return slab.toImmutableState();
                    }
                }
                return targetCanon.getDefaultState();
            }
            case STAIRS: {
                SubBlockFamily fam = resolveBestFamilyForKind(targetCanon, localFamilies, SynthesizedKind.STAIRS);
                if (fam != null && fam.stairs != null) {
                    BaseBlock stair = BlockColorMapper.createStairBlock(fam.stairs, shape.facing, shape.half, shape.stairShape);
                    if (stair != null) {
                        return stair.toImmutableState();
                    }
                }
                return targetCanon.getDefaultState();
            }
            case WALL: {
                SubBlockFamily fam = resolveBestFamilyForKind(targetCanon, localFamilies, SynthesizedKind.WALL);
                if (fam != null && fam.wall != null) {
                    String n = shape.connN ? (shape.hasUp ? "tall" : "low") : "none";
                    String e = shape.connE ? (shape.hasUp ? "tall" : "low") : "none";
                    String s = shape.connS ? (shape.hasUp ? "tall" : "low") : "none";
                    String w = shape.connW ? (shape.hasUp ? "tall" : "low") : "none";
                    BaseBlock wall = BlockColorMapper.createWallBlock(fam.wall, n, e, s, w, true);
                    if (wall != null) {
                        return wall.toImmutableState();
                    }
                }
                return targetCanon.getDefaultState();
            }
            case AIR:
            default:
                return getAirState();
        }
    }

    private static SubBlockFamily resolveBestFamilyForKind(
            BlockType targetCanon,
            List<SubBlockFamily> localFamilies,
            SynthesizedKind needed
    ) {
        SubBlockFamily direct = BlockColorMapper.resolveFamilyForBlock(targetCanon);
        if (familySupports(direct, needed)) {
            return direct;
        }

        int[] rgb = VanillaBlockAppearanceResolver.getAverageRgb(targetCanon);

        // Prefer a close-in-color sub-block family already used inside this build sphere
        SubBlockFamily bestLocal = null;
        double bestLocalDist = 2200.0;
        if (localFamilies != null) {
            for (SubBlockFamily fam : localFamilies) {
                if (!familySupports(fam, needed)) continue;
                double d = BlockColorMapper.colorDistanceSq(rgb[0], rgb[1], rgb[2], fam.r, fam.g, fam.b);
                if (d < bestLocalDist) {
                    bestLocalDist = d;
                    bestLocal = fam;
                }
            }
        }
        if (bestLocal != null) {
            return bestLocal;
        }

        switch (needed) {
            case STAIRS:
                return BlockColorMapper.findNearestStairFamily(rgb[0], rgb[1], rgb[2], targetCanon);
            case SLAB:
                return BlockColorMapper.findNearestSlabFamily(rgb[0], rgb[1], rgb[2], targetCanon);
            case WALL:
                return BlockColorMapper.findNearestWallFamily(rgb[0], rgb[1], rgb[2], targetCanon);
            default:
                return direct;
        }
    }

    private static boolean familySupports(SubBlockFamily fam, SynthesizedKind needed) {
        if (fam == null) return false;
        switch (needed) {
            case STAIRS: return fam.stairs != null;
            case SLAB: return fam.slab != null;
            case WALL: return fam.wall != null;
            default: return true;
        }
    }

    private static BlockType resolveCanonicalBlockType(BlockType bt) {
        if (bt == null) return null;
        BaseBlock full = BlockColorMapper.getFullBlockForSubBlock(bt);
        if (full != null && full.getBlockType() != null) {
            return full.getBlockType();
        }
        return bt;
    }

    private static boolean isFluidOrUntouchable(String cleanName) {
        return cleanName.equals("water") || cleanName.equals("lava") || cleanName.equals("bubble_column")
                || cleanName.equals("bedrock") || cleanName.equals("barrier")
                || cleanName.equals("command_block") || cleanName.equals("chain_command_block")
                || cleanName.equals("repeating_command_block") || cleanName.equals("spawner")
                || cleanName.equals("end_portal") || cleanName.equals("end_portal_frame")
                || cleanName.equals("nether_portal");
    }

    private static boolean isSnowMaterial(BlockType bt) {
        if (bt == null) return false;
        String n = VanillaBlockAppearanceResolver.cleanBlockName(bt.id());
        return n.equals("snow_block") || n.equals("snow") || n.equals("powder_snow");
    }

    private static BlockState getAirState() {
        try {
            return BlockTypes.AIR != null ? BlockTypes.AIR.getDefaultState() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String readStringProp(BlockState state, PropertyKey key, String fallback) {
        try {
            Object val = state.getState(key);
            if (val != null) {
                return val.toString().toLowerCase(Locale.ROOT);
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static int readIntProp(BlockState state, PropertyKey key, int fallback) {
        try {
            Object val = state.getState(key);
            if (val instanceof Number n) {
                return n.intValue();
            }
            if (val != null) {
                return Integer.parseInt(val.toString());
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }
}
