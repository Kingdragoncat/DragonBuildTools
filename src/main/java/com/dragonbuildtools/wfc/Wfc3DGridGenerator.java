package com.dragonbuildtools.wfc;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/**
 * Optimized 3D Wave Function Collapse grid generator for:
 * {@code //newwfc <grid size xz> <wfc size xyz> <variation count> <adherence> <timeout> [-n <mask>] [-c] [-b] [-j] [-l <int>] [-g]}.
 */
public class Wfc3DGridGenerator {
    // 6 cardinal 3D directions: 0=-X, 1=+X, 2=-Y, 3=+Y, 4=-Z, 5=+Z
    private static final int[] DX = new int[]{ -1, 1, 0, 0, 0, 0 };
    private static final int[] DY = new int[]{ 0, 0, -1, 1, 0, 0 };
    private static final int[] DZ = new int[]{ 0, 0, 0, 0, -1, 1 };

    // Boundary layer bit flags (0..63)
    private static final int BOUND_BOTTOM  = 1;      // py == 0 (-Y)
    private static final int BOUND_CEILING = 1 << 1; // py == tilesY - 1 (+Y)
    private static final int BOUND_WEST    = 1 << 2; // px == 0 (-X)
    private static final int BOUND_EAST    = 1 << 3; // px == tilesX - 1 (+X)
    private static final int BOUND_NORTH   = 1 << 4; // pz == 0 (-Z)
    private static final int BOUND_SOUTH   = 1 << 5; // pz == tilesZ - 1 (+Z)
    private static final int NUM_BOUNDARY_STATES = 64;

    private final EditSession editSession;
    private final Extent sampleExtent;
    private final CuboidRegion sampleRegion;
    private final CuboidRegion selection;
    private final int gridSizeXZ;
    private final int varCellHeight;
    private final int evalSizeX;
    private final int evalSizeY;
    private final int evalSizeZ;
    private final int wfcSize;
    private final int variationCount;
    private final float adherence;
    private final double timeoutSeconds;
    private final long deadlineNanos;

    // Flag options
    private final boolean useClipboard;
    private final Mask neglectMask;
    private final Set<String> neglectBlockIds;
    private final boolean useExistingBaseLayer;
    private final boolean continuousSelection;
    private final int lazyTolerance;
    private final boolean outerWallsAndCeilingRestricted;

    private final Random random = new Random();

    private final List<BaseBlock> blockPalette = new ArrayList<>();
    private final Map<BlockKey, Integer> blockPaletteMap = new HashMap<>();
    private final List<Piece3D> pieces = new ArrayList<>();
    private final Map<PieceKey, Integer> pieceMap = new LinkedHashMap<>();

    // Piece-grid dimensions inside a variation grid cell
    private final int varTilesX;
    private final int varTilesY;
    private final int varTilesZ;
    private final double varCenterX;
    private final double varCenterY;
    private final double varCenterZ;

    // Piece-grid dimensions inside a target evaluation area (either 1 grid cell or the whole selection with -j)
    private final int evalTilesX;
    private final int evalTilesY;
    private final int evalTilesZ;
    private final int totalTilesPerEvalCell;
    private final double evalCenterX;
    private final double evalCenterY;
    private final double evalCenterZ;

    // Bitmask propagator: supportMasks[dir][pieceId] is a long[wordCount] mask of compatible pieces in direction dir
    private int wordCount;
    private long[][][] supportMasks;
    private long[][] boundaryLayerMasks; // [64][wordCount]
    private boolean timedOut = false;

    public Wfc3DGridGenerator(
            EditSession editSession,
            CuboidRegion selection,
            int gridSizeXZ,
            int wfcSizeXYZ,
            int variationCount,
            float adherence,
            double timeoutSeconds
    ) {
        this(
                editSession,
                editSession,
                selection,
                selection,
                gridSizeXZ,
                wfcSizeXYZ,
                variationCount,
                adherence,
                timeoutSeconds,
                false,
                null,
                "structure_void",
                false,
                false,
                0,
                false
        );
    }

    public Wfc3DGridGenerator(
            EditSession editSession,
            Extent sampleExtent,
            CuboidRegion sampleRegion,
            CuboidRegion selection,
            int gridSizeXZ,
            int wfcSizeXYZ,
            int variationCount,
            float adherence,
            double timeoutSeconds,
            boolean useClipboard,
            Mask neglectMask,
            String neglectMaskSpec,
            boolean useExistingBaseLayer,
            boolean continuousSelection,
            int lazyTolerance,
            boolean outerWallsAndCeilingRestricted
    ) {
        this.editSession = editSession;
        this.sampleExtent = sampleExtent != null ? sampleExtent : editSession;
        this.sampleRegion = sampleRegion != null ? sampleRegion : selection;
        this.selection = selection;
        this.gridSizeXZ = Math.max(1, gridSizeXZ);
        this.varCellHeight = Math.max(1, this.sampleRegion.getHeight());

        this.useClipboard = useClipboard;
        this.neglectMask = neglectMask;
        this.neglectBlockIds = parseNeglectBlockIds(neglectMaskSpec);
        this.useExistingBaseLayer = useExistingBaseLayer;
        this.continuousSelection = continuousSelection;
        this.lazyTolerance = Math.max(0, lazyTolerance);
        this.outerWallsAndCeilingRestricted = outerWallsAndCeilingRestricted;

        this.evalSizeX = continuousSelection ? Math.max(1, selection.getWidth()) : this.gridSizeXZ;
        this.evalSizeY = Math.max(1, selection.getHeight());
        this.evalSizeZ = continuousSelection ? Math.max(1, selection.getLength()) : this.gridSizeXZ;

        int minVarDim = Math.min(this.gridSizeXZ, this.varCellHeight);
        int minEvalDim = Math.min(Math.min(this.evalSizeX, this.evalSizeZ), this.evalSizeY);
        this.wfcSize = Math.max(1, Math.min(wfcSizeXYZ, Math.min(minVarDim, minEvalDim)));
        this.variationCount = Math.max(1, variationCount);
        this.adherence = Math.max(0.0F, adherence);
        this.timeoutSeconds = timeoutSeconds > 0.0 ? timeoutSeconds : 60.0;
        this.deadlineNanos = System.nanoTime() + (long) (this.timeoutSeconds * 1_000_000_000.0);

        this.varTilesX = Math.max(1, this.gridSizeXZ - this.wfcSize + 1);
        this.varTilesY = Math.max(1, this.varCellHeight - this.wfcSize + 1);
        this.varTilesZ = Math.max(1, this.gridSizeXZ - this.wfcSize + 1);
        this.varCenterX = (this.varTilesX - 1) * 0.5;
        this.varCenterY = (this.varTilesY - 1) * 0.5;
        this.varCenterZ = (this.varTilesZ - 1) * 0.5;

        this.evalTilesX = Math.max(1, this.evalSizeX - this.wfcSize + 1);
        this.evalTilesY = Math.max(1, this.evalSizeY - this.wfcSize + 1);
        this.evalTilesZ = Math.max(1, this.evalSizeZ - this.wfcSize + 1);
        this.totalTilesPerEvalCell = this.evalTilesX * this.evalTilesY * this.evalTilesZ;
        this.evalCenterX = (this.evalTilesX - 1) * 0.5;
        this.evalCenterY = (this.evalTilesY - 1) * 0.5;
        this.evalCenterZ = (this.evalTilesZ - 1) * 0.5;
    }

    private static Set<String> parseNeglectBlockIds(String spec) {
        String effective = (spec == null || spec.trim().isEmpty()) ? "structure_void" : spec.trim().toLowerCase(Locale.ROOT);
        if ("none".equals(effective) || "off".equals(effective) || "false".equals(effective)) {
            return Collections.emptySet();
        }
        Set<String> set = new HashSet<>();
        for (String part : effective.split("[,|]")) {
            String token = part.trim();
            if (token.isEmpty() || token.startsWith("#") || token.startsWith("!")) {
                continue;
            }
            int bracket = token.indexOf('[');
            if (bracket > 0) {
                token = token.substring(0, bracket);
            }
            if (token.contains(":")) {
                set.add(token);
                set.add(token.substring(token.indexOf(':') + 1));
            } else {
                set.add(token);
                set.add("minecraft:" + token);
            }
        }
        return set;
    }

    private boolean isBlockNeglected(BlockVector3 pos, BaseBlock block) {
        if (block == null) {
            return false;
        }
        if (this.neglectMask != null) {
            try {
                if (this.neglectMask.test(pos)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        if (!this.neglectBlockIds.isEmpty()) {
            try {
                BlockType bt = block.getBlockType();
                if (bt != null && this.neglectBlockIds.contains(bt.id().toLowerCase(Locale.ROOT))) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static boolean isAirBlock(BaseBlock block) {
        if (block == null) {
            return true;
        }
        try {
            BlockType bt = block.getBlockType();
            if (bt == null) {
                return true;
            }
            String id = bt.id();
            if ("minecraft:air".equals(id) || "minecraft:cave_air".equals(id) || "minecraft:void_air".equals(id)) {
                return true;
            }
            return bt.getMaterial().isAir();
        } catch (Throwable ignored) {
            return false;
        }
    }

    static int sampleDistribution(double[] distribution, double threshold) {
        double sum = 0.0;
        for (double v : distribution) {
            sum += v;
        }
        if (sum <= 0.0) {
            for (int i = 0; i < distribution.length; ++i) {
                if (distribution[i] > 0.0) {
                    return i;
                }
            }
            return 0;
        }
        for (int i = 0; i < distribution.length; ++i) {
            distribution[i] /= sum;
        }
        double cumulative = 0.0;
        for (int i = 0; i < distribution.length; ++i) {
            cumulative += distribution[i];
            if (threshold <= cumulative) {
                return i;
            }
        }
        return 0;
    }

    public boolean isTimedOut() {
        return this.timedOut;
    }

    private boolean checkTimeout() {
        if (this.timedOut) {
            return true;
        }
        if (System.nanoTime() >= this.deadlineNanos) {
            this.timedOut = true;
            return true;
        }
        return false;
    }

    public int execute() throws MaxChangedBlocksException {
        int sampleCols = this.sampleRegion.getWidth() / this.gridSizeXZ;
        int sampleRows = this.sampleRegion.getLength() / this.gridSizeXZ;
        int totalSampleCells = sampleCols * sampleRows;
        if (totalSampleCells <= 0) {
            return 0;
        }

        BlockVector3 sampleMin = this.sampleRegion.getMinimumPoint();
        List<BlockVector3> allSampleCellOrigins = new ArrayList<>(totalSampleCells);
        for (int gz = 0; gz < sampleRows; ++gz) {
            for (int gx = 0; gx < sampleCols; ++gx) {
                allSampleCellOrigins.add(sampleMin.add(gx * this.gridSizeXZ, 0, gz * this.gridSizeXZ));
            }
        }

        int dataCount = Math.min(this.variationCount, totalSampleCells);
        List<BlockVector3> dataCellOrigins = new ArrayList<>(dataCount);
        List<BlockVector3> emptyOrigins = new ArrayList<>();
        for (BlockVector3 origin : allSampleCellOrigins) {
            if (dataCellOrigins.size() >= dataCount) {
                break;
            }
            if (this.isSampleCellNonEmpty(origin)) {
                dataCellOrigins.add(origin);
            } else {
                emptyOrigins.add(origin);
            }
        }
        for (BlockVector3 emptyOrigin : emptyOrigins) {
            if (dataCellOrigins.size() >= dataCount) {
                break;
            }
            dataCellOrigins.add(emptyOrigin);
        }

        for (BlockVector3 dataOrigin : dataCellOrigins) {
            this.collectCellData(dataOrigin);
        }

        if (this.pieces.isEmpty()) {
            return 0;
        }

        this.buildPropagatorAndFrequencies();

        if (!this.useClipboard && !this.useExistingBaseLayer) {
            BaseBlock airBlock = getAirBaseBlock();
            if (airBlock != null) {
                for (BlockVector3 dataOrigin : dataCellOrigins) {
                    this.fillVariationCellWithAir(dataOrigin, airBlock);
                }
            }
        }

        List<BlockVector3> evalOrigins = new ArrayList<>();
        BlockVector3 selMin = this.selection.getMinimumPoint();
        if (this.continuousSelection) {
            evalOrigins.add(selMin);
        } else {
            int evalCols = this.selection.getWidth() / this.gridSizeXZ;
            int evalRows = this.selection.getLength() / this.gridSizeXZ;
            if (evalCols * evalRows <= 0) {
                return 0;
            }
            for (int gz = 0; gz < evalRows; ++gz) {
                for (int gx = 0; gx < evalCols; ++gx) {
                    evalOrigins.add(selMin.add(gx * this.gridSizeXZ, 0, gz * this.gridSizeXZ));
                }
            }
        }

        int numPieces = this.pieces.size();
        double[][] distanceWeights = new double[this.totalTilesPerEvalCell][numPieces];
        for (int pz = 0; pz < this.evalTilesZ; ++pz) {
            for (int py = 0; py < this.evalTilesY; ++py) {
                int mappedVarPy = this.mapEvalPyToVarPy(py);
                for (int px = 0; px < this.evalTilesX; ++px) {
                    int tIdx = this.evalTileIndex(px, py, pz);
                    int bFlags = this.computeBoundaryFlags(px, py, pz, this.evalTilesX, this.evalTilesY, this.evalTilesZ);
                    long[] allowedLayerMask = this.boundaryLayerMasks[bFlags];
                    double evalDist = this.computeEvalDistanceFromCenter(px, py, pz);
                    for (int p = 0; p < numPieces; ++p) {
                        if ((allowedLayerMask[p >>> 6] & (1L << (p & 63))) == 0L) {
                            distanceWeights[tIdx][p] = 0.0;
                        } else {
                            Piece3D piece = this.pieces.get(p);
                            distanceWeights[tIdx][p] = piece.computeDistanceWeight(evalDist, mappedVarPy, this.adherence);
                        }
                    }
                }
            }
        }

        int processedCells = 0;
        for (BlockVector3 cellOrigin : evalOrigins) {
            if (this.checkTimeout()) {
                break;
            }
            int[] evaluatedTiles = this.solveGridCell(cellOrigin, distanceWeights);
            this.placeEvaluatedTiles(cellOrigin, evaluatedTiles);
            ++processedCells;
            if (this.timedOut) {
                break;
            }
        }

        return processedCells;
    }

    private static BaseBlock getAirBaseBlock() {
        try {
            if (BlockTypes.AIR != null && BlockTypes.AIR.getDefaultState() != null) {
                return BlockTypes.AIR.getDefaultState().toBaseBlock();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private boolean isSampleCellNonEmpty(BlockVector3 cellOrigin) {
        for (int y = 0; y < this.varCellHeight; ++y) {
            for (int z = 0; z < this.gridSizeXZ; ++z) {
                for (int x = 0; x < this.gridSizeXZ; ++x) {
                    BlockVector3 pos = cellOrigin.add(x, y, z);
                    BaseBlock b = this.getSampleBlock(pos);
                    if (!this.isBlockNeglected(pos, b) && !isAirBlock(b)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private BaseBlock getSampleBlock(BlockVector3 pos) {
        BaseBlock full = this.sampleExtent.getFullBlock(pos);
        if (full == null) {
            full = this.sampleExtent.getBlock(pos).toBaseBlock();
        }
        return full;
    }

    private BaseBlock getTargetBlock(BlockVector3 pos) {
        BaseBlock full = this.editSession.getFullBlock(pos);
        if (full == null) {
            full = this.editSession.getBlock(pos).toBaseBlock();
        }
        return full;
    }

    private void fillVariationCellWithAir(BlockVector3 cellOrigin, BaseBlock airBlock) throws MaxChangedBlocksException {
        for (int y = 0; y < this.varCellHeight; ++y) {
            for (int z = 0; z < this.gridSizeXZ; ++z) {
                for (int x = 0; x < this.gridSizeXZ; ++x) {
                    this.editSession.setBlock(cellOrigin.add(x, y, z), (BlockStateHolder) airBlock);
                }
            }
        }
    }

    private int computeBoundaryFlags(int px, int py, int pz, int tX, int tY, int tZ) {
        int flags = 0;
        if (py == 0) {
            flags |= BOUND_BOTTOM;
        }
        if (!this.outerWallsAndCeilingRestricted) {
            return flags;
        }
        if (tY > 1 && py == tY - 1) {
            flags |= BOUND_CEILING;
        }
        if (px == 0) {
            flags |= BOUND_WEST;
        }
        if (tX > 1 && px == tX - 1) {
            flags |= BOUND_EAST;
        }
        if (pz == 0) {
            flags |= BOUND_NORTH;
        }
        if (tZ > 1 && pz == tZ - 1) {
            flags |= BOUND_SOUTH;
        }
        return flags;
    }

    private void collectCellData(BlockVector3 cellOrigin) {
        int[][][] cellBlocks = new int[this.gridSizeXZ][this.varCellHeight][this.gridSizeXZ];
        for (int y = 0; y < this.varCellHeight; ++y) {
            for (int z = 0; z < this.gridSizeXZ; ++z) {
                for (int x = 0; x < this.gridSizeXZ; ++x) {
                    BlockVector3 pos = cellOrigin.add(x, y, z);
                    BaseBlock fullBlock = this.getSampleBlock(pos);
                    if (this.isBlockNeglected(pos, fullBlock)) {
                        cellBlocks[x][y][z] = -1;
                    } else {
                        cellBlocks[x][y][z] = this.getOrRegisterBlock(fullBlock);
                    }
                }
            }
        }

        int[][][] pieceGrid = new int[this.varTilesX][this.varTilesY][this.varTilesZ];
        for (int py = 0; py < this.varTilesY; ++py) {
            for (int pz = 0; pz < this.varTilesZ; ++pz) {
                for (int px = 0; px < this.varTilesX; ++px) {
                    int[] voxels = new int[this.wfcSize * this.wfcSize * this.wfcSize];
                    boolean containsNeglected = false;
                    for (int dy = 0; dy < this.wfcSize && !containsNeglected; ++dy) {
                        for (int dz = 0; dz < this.wfcSize && !containsNeglected; ++dz) {
                            for (int dx = 0; dx < this.wfcSize; ++dx) {
                                int blockId = cellBlocks[px + dx][py + dy][pz + dz];
                                if (blockId < 0) {
                                    containsNeglected = true;
                                    break;
                                }
                                int voxelIdx = dx + this.wfcSize * (dy + this.wfcSize * dz);
                                voxels[voxelIdx] = blockId;
                            }
                        }
                    }
                    if (containsNeglected) {
                        pieceGrid[px][py][pz] = -1;
                        continue;
                    }
                    int boundaryFlags = this.computeBoundaryFlags(px, py, pz, this.varTilesX, this.varTilesY, this.varTilesZ);
                    Piece3D piece = this.getOrRegisterPiece(voxels, boundaryFlags);
                    double dist = this.computeVarDistanceFromCenter(px, py, pz);
                    piece.addDistanceOccurrence(dist, py);
                    pieceGrid[px][py][pz] = piece.id;
                }
            }
        }

        for (int py = 0; py < this.varTilesY; ++py) {
            for (int pz = 0; pz < this.varTilesZ; ++pz) {
                for (int px = 0; px < this.varTilesX; ++px) {
                    int pId = pieceGrid[px][py][pz];
                    if (pId < 0) {
                        continue;
                    }
                    Piece3D piece = this.pieces.get(pId);
                    for (int d = 0; d < 6; ++d) {
                        int nx = px + DX[d];
                        int ny = py + DY[d];
                        int nz = pz + DZ[d];
                        if (nx >= 0 && nx < this.varTilesX && ny >= 0 && ny < this.varTilesY && nz >= 0 && nz < this.varTilesZ) {
                            int neighborPieceId = pieceGrid[nx][ny][nz];
                            if (neighborPieceId >= 0) {
                                piece.addNeighbor(d, neighborPieceId);
                            }
                        }
                    }
                }
            }
        }
    }

    private double computeVarDistanceFromCenter(int px, int py, int pz) {
        double dx = px - this.varCenterX;
        double dy = py - this.varCenterY;
        double dz = pz - this.varCenterZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private double computeEvalDistanceFromCenter(int px, int py, int pz) {
        double scaleX = (this.evalTilesX > 1 && this.varTilesX > 1)
                ? (double) (this.varTilesX - 1) / (double) (this.evalTilesX - 1)
                : 1.0;
        double scaleY = (this.evalTilesY > 1 && this.varTilesY > 1)
                ? (double) (this.varTilesY - 1) / (double) (this.evalTilesY - 1)
                : 1.0;
        double scaleZ = (this.evalTilesZ > 1 && this.varTilesZ > 1)
                ? (double) (this.varTilesZ - 1) / (double) (this.evalTilesZ - 1)
                : 1.0;
        double dx = (px - this.evalCenterX) * scaleX;
        double dy = (py - this.evalCenterY) * scaleY;
        double dz = (pz - this.evalCenterZ) * scaleZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private int mapEvalPyToVarPy(int evalPy) {
        if (this.evalTilesY <= 1 || this.varTilesY <= 1) {
            return 0;
        }
        return (int) Math.round((double) evalPy * (double) (this.varTilesY - 1) / (double) (this.evalTilesY - 1));
    }

    private int getOrRegisterBlock(BaseBlock block) {
        BlockKey key = new BlockKey(block);
        Integer existing = this.blockPaletteMap.get(key);
        if (existing != null) {
            return existing;
        }
        int id = this.blockPalette.size();
        this.blockPalette.add(block);
        this.blockPaletteMap.put(key, id);
        return id;
    }

    private Piece3D getOrRegisterPiece(int[] voxels, int boundaryFlags) {
        PieceKey key = new PieceKey(voxels, boundaryFlags);
        Integer existingId = this.pieceMap.get(key);
        if (existingId != null) {
            return this.pieces.get(existingId);
        }
        int newId = this.pieces.size();
        Piece3D piece = new Piece3D(newId, voxels, boundaryFlags);
        this.pieces.add(piece);
        this.pieceMap.put(key, newId);
        return piece;
    }

    private void buildPropagatorAndFrequencies() {
        int numPieces = this.pieces.size();
        this.wordCount = (numPieces + 63) >>> 6;
        this.supportMasks = new long[6][numPieces][this.wordCount];
        this.boundaryLayerMasks = new long[NUM_BOUNDARY_STATES][this.wordCount];

        for (Piece3D piece : this.pieces) {
            piece.compileNeighborFrequencies(numPieces);
            this.boundaryLayerMasks[piece.boundaryFlags][piece.id >>> 6] |= (1L << (piece.id & 63));
        }

        for (int bFlags = 0; bFlags < NUM_BOUNDARY_STATES; ++bFlags) {
            if (this.countBits(this.boundaryLayerMasks[bFlags]) == 0) {
                int vertFlags = bFlags & (BOUND_BOTTOM | BOUND_CEILING);
                int wallFlags = bFlags & ~(BOUND_BOTTOM | BOUND_CEILING);
                for (Piece3D piece : this.pieces) {
                    int pVert = piece.boundaryFlags & (BOUND_BOTTOM | BOUND_CEILING);
                    int pWall = piece.boundaryFlags & ~(BOUND_BOTTOM | BOUND_CEILING);
                    if (pVert == vertFlags && ((pWall & wallFlags) != 0 || wallFlags == 0)) {
                        this.boundaryLayerMasks[bFlags][piece.id >>> 6] |= (1L << (piece.id & 63));
                    }
                }
                if (this.countBits(this.boundaryLayerMasks[bFlags]) == 0) {
                    for (Piece3D piece : this.pieces) {
                        if ((piece.boundaryFlags & BOUND_BOTTOM) == (bFlags & BOUND_BOTTOM)) {
                            this.boundaryLayerMasks[bFlags][piece.id >>> 6] |= (1L << (piece.id & 63));
                        }
                    }
                }
            }
        }

        for (int d = 0; d < 6; ++d) {
            int dx = DX[d];
            int dy = DY[d];
            int dz = DZ[d];
            for (int p1 = 0; p1 < numPieces; ++p1) {
                Piece3D piece1 = this.pieces.get(p1);
                for (int observedNeighbor : piece1.neighborLists[d]) {
                    Piece3D neighborPiece = this.pieces.get(observedNeighbor);
                    if (this.isLayerTransitionAllowed(piece1.boundaryFlags, neighborPiece.boundaryFlags, dx, dy, dz)) {
                        this.supportMasks[d][p1][observedNeighbor >>> 6] |= (1L << (observedNeighbor & 63));
                    }
                }
                if (this.wfcSize >= 2) {
                    for (int p2 = 0; p2 < numPieces; ++p2) {
                        Piece3D piece2 = this.pieces.get(p2);
                        if (!this.isLayerTransitionAllowed(piece1.boundaryFlags, piece2.boundaryFlags, dx, dy, dz)) {
                            continue;
                        }
                        if (this.overlapsAgree(piece1.voxels, piece2.voxels, dx, dy, dz)) {
                            this.supportMasks[d][p1][p2 >>> 6] |= (1L << (p2 & 63));
                        }
                    }
                }
            }
        }
    }

    private boolean isLayerTransitionAllowed(int fromFlags, int toFlags, int dx, int dy, int dz) {
        boolean fromBottom = (fromFlags & BOUND_BOTTOM) != 0;
        boolean toBottom = (toFlags & BOUND_BOTTOM) != 0;
        if (dy == 0 && fromBottom != toBottom) return false;
        if (dy > 0 && toBottom) return false;
        if (dy < 0 && fromBottom) return false;

        if (!this.outerWallsAndCeilingRestricted) {
            return true;
        }

        boolean fromCeiling = (fromFlags & BOUND_CEILING) != 0;
        boolean toCeiling = (toFlags & BOUND_CEILING) != 0;
        if (dy == 0 && fromCeiling != toCeiling) return false;
        if (dy > 0 && fromCeiling) return false;
        if (dy < 0 && toCeiling) return false;

        boolean fromWest = (fromFlags & BOUND_WEST) != 0;
        boolean toWest = (toFlags & BOUND_WEST) != 0;
        boolean fromEast = (fromFlags & BOUND_EAST) != 0;
        boolean toEast = (toFlags & BOUND_EAST) != 0;
        if (dx == 0 && (fromWest != toWest || fromEast != toEast)) return false;
        if (dx > 0 && (toWest || fromEast)) return false;
        if (dx < 0 && (fromWest || toEast)) return false;

        boolean fromNorth = (fromFlags & BOUND_NORTH) != 0;
        boolean toNorth = (toFlags & BOUND_NORTH) != 0;
        boolean fromSouth = (fromFlags & BOUND_SOUTH) != 0;
        boolean toSouth = (toFlags & BOUND_SOUTH) != 0;
        if (dz == 0 && (fromNorth != toNorth || fromSouth != toSouth)) return false;
        if (dz > 0 && (toNorth || fromSouth)) return false;
        if (dz < 0 && (fromNorth || toSouth)) return false;

        return true;
    }

    private boolean overlapsAgree(int[] v1, int[] v2, int dx, int dy, int dz) {
        int xmin = dx < 0 ? 0 : dx;
        int xmax = dx < 0 ? dx + this.wfcSize : this.wfcSize;
        int ymin = dy < 0 ? 0 : dy;
        int ymax = dy < 0 ? dy + this.wfcSize : this.wfcSize;
        int zmin = dz < 0 ? 0 : dz;
        int zmax = dz < 0 ? dz + this.wfcSize : this.wfcSize;

        for (int z = zmin; z < zmax; ++z) {
            for (int y = ymin; y < ymax; ++y) {
                for (int x = xmin; x < xmax; ++x) {
                    int idx1 = x + this.wfcSize * (y + this.wfcSize * z);
                    int idx2 = (x - dx) + this.wfcSize * ((y - dy) + this.wfcSize * (z - dz));
                    if (v1[idx1] != v2[idx2]) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private int evalTileIndex(int px, int py, int pz) {
        return px + this.evalTilesX * (py + this.evalTilesY * pz);
    }

    private int[] solveGridCell(BlockVector3 cellOrigin, double[][] distanceWeights) {
        int numPieces = this.pieces.size();
        long[][] wave = new long[this.totalTilesPerEvalCell][this.wordCount];
        long[][] bannedMask = new long[this.totalTilesPerEvalCell][this.wordCount];
        int[] collapsedPiece = new int[this.totalTilesPerEvalCell];
        Arrays.fill(collapsedPiece, -1);

        for (int pz = 0; pz < this.evalTilesZ; ++pz) {
            for (int py = 0; py < this.evalTilesY; ++py) {
                for (int px = 0; px < this.evalTilesX; ++px) {
                    int t = this.evalTileIndex(px, py, pz);
                    int bFlags = this.computeBoundaryFlags(px, py, pz, this.evalTilesX, this.evalTilesY, this.evalTilesZ);
                    System.arraycopy(this.boundaryLayerMasks[bFlags], 0, wave[t], 0, this.wordCount);
                }
            }
        }

        ArrayDeque<Integer> queue = new ArrayDeque<>(this.totalTilesPerEvalCell);
        boolean[] inQueue = new boolean[this.totalTilesPerEvalCell];

        if (this.useExistingBaseLayer) {
            BaseBlock[][] existingBase = new BaseBlock[this.evalSizeX][this.evalSizeZ];
            for (int z = 0; z < this.evalSizeZ; ++z) {
                for (int x = 0; x < this.evalSizeX; ++x) {
                    existingBase[x][z] = this.getTargetBlock(cellOrigin.add(x, 0, z));
                }
            }

            for (int pz = 0; pz < this.evalTilesZ; ++pz) {
                for (int px = 0; px < this.evalTilesX; ++px) {
                    int t = this.evalTileIndex(px, 0, pz);
                    long[] matchingMask = this.findMatchingBaseLayerPieces(px, pz, existingBase, wave[t]);
                    int matchedCount = this.countBits(matchingMask);
                    if (matchedCount > 0) {
                        System.arraycopy(matchingMask, 0, wave[t], 0, this.wordCount);
                        int chosen = this.firstSetBit(wave[t]);
                        if (matchedCount > 1) {
                            double[] weights = new double[numPieces];
                            for (int p = 0; p < numPieces; ++p) {
                                if ((wave[t][p >>> 6] & (1L << (p & 63))) != 0L) {
                                    weights[p] = Math.max(1.0E-6, distanceWeights[t][p]);
                                }
                            }
                            chosen = sampleDistribution(weights, this.random.nextDouble());
                            if ((wave[t][chosen >>> 6] & (1L << (chosen & 63))) == 0L) {
                                chosen = this.firstSetBit(wave[t]);
                            }
                        }
                        Arrays.fill(wave[t], 0L);
                        wave[t][chosen >>> 6] = (1L << (chosen & 63));
                        collapsedPiece[t] = chosen;
                        queue.add(t);
                        inQueue[t] = true;
                    } else {
                        Arrays.fill(wave[t], 0L);
                        collapsedPiece[t] = -2;
                    }
                }
            }

            if (!queue.isEmpty()) {
                this.propagateBitmask(wave, bannedMask, collapsedPiece, queue, inQueue);
            }
        }

        double[] candidateWeights = new double[numPieces];

        for (int step = 0; step < this.totalTilesPerEvalCell; ++step) {
            if (this.checkTimeout()) {
                return collapsedPiece;
            }

            int bestTile = -1;
            double minEntropy = Double.POSITIVE_INFINITY;

            for (int t = 0; t < this.totalTilesPerEvalCell; ++t) {
                if (collapsedPiece[t] != -1) {
                    continue;
                }
                int count = this.countBits(wave[t]);
                if (count == 0) {
                    int recovered = this.recoverContradictedTile(t, collapsedPiece, distanceWeights[t]);
                    wave[t][recovered >>> 6] |= (1L << (recovered & 63));
                    collapsedPiece[t] = recovered;
                    continue;
                }
                if (count == 1) {
                    int onlyPiece = this.firstSetBit(wave[t]);
                    collapsedPiece[t] = onlyPiece;
                    continue;
                }

                double sumW = 0.0;
                double sumWLogW = 0.0;
                long[] mask = wave[t];
                double[] dWeights = distanceWeights[t];
                for (int w = 0; w < this.wordCount; ++w) {
                    long bits = mask[w];
                    while (bits != 0L) {
                        int bit = Long.numberOfTrailingZeros(bits);
                        int p = (w << 6) + bit;
                        double weight = dWeights[p];
                        if (weight > 0.0) {
                            sumW += weight;
                            sumWLogW += weight * Math.log(weight);
                        }
                        bits &= (bits - 1L);
                    }
                }
                double entropy = (sumW > 0.0) ? (Math.log(sumW) - sumWLogW / sumW) : 0.0;
                double noise = 1.0E-5 * this.random.nextDouble();
                if (entropy + noise < minEntropy) {
                    minEntropy = entropy + noise;
                    bestTile = t;
                }
            }

            if (bestTile == -1) {
                break;
            }

            if (this.checkTimeout()) {
                return collapsedPiece;
            }

            int attempts = 0;
            while (this.countBits(wave[bestTile]) > 0 && attempts < 4 && !this.checkTimeout()) {
                ++attempts;
                this.computeCombinedWeights(bestTile, wave, collapsedPiece, distanceWeights[bestTile], candidateWeights);
                int chosenPiece = sampleDistribution(candidateWeights, this.random.nextDouble());
                if ((wave[bestTile][chosenPiece >>> 6] & (1L << (chosenPiece & 63))) == 0L) {
                    chosenPiece = this.firstSetBit(wave[bestTile]);
                }

                boolean canBacktrack = this.countBits(wave[bestTile]) > 1 && attempts < 4;
                long[][] waveBackup = null;
                int[] collapsedBackup = null;
                if (canBacktrack) {
                    waveBackup = new long[this.totalTilesPerEvalCell][];
                    for (int i = 0; i < this.totalTilesPerEvalCell; ++i) {
                        waveBackup[i] = wave[i].clone();
                    }
                    collapsedBackup = collapsedPiece.clone();
                }

                Arrays.fill(wave[bestTile], 0L);
                wave[bestTile][chosenPiece >>> 6] = (1L << (chosenPiece & 63));
                collapsedPiece[bestTile] = chosenPiece;

                queue.clear();
                Arrays.fill(inQueue, false);
                queue.add(bestTile);
                inQueue[bestTile] = true;

                boolean ok = this.propagateBitmask(wave, bannedMask, collapsedPiece, queue, inQueue);
                if (ok || !canBacktrack || this.checkTimeout()) {
                    break;
                }

                for (int i = 0; i < this.totalTilesPerEvalCell; ++i) {
                    System.arraycopy(waveBackup[i], 0, wave[i], 0, this.wordCount);
                }
                System.arraycopy(collapsedBackup, 0, collapsedPiece, 0, this.totalTilesPerEvalCell);
                wave[bestTile][chosenPiece >>> 6] &= ~(1L << (chosenPiece & 63));
                bannedMask[bestTile][chosenPiece >>> 6] |= (1L << (chosenPiece & 63));
            }

            if (collapsedPiece[bestTile] == -1 && !this.checkTimeout()) {
                int recovered = this.recoverContradictedTile(bestTile, collapsedPiece, distanceWeights[bestTile]);
                wave[bestTile][recovered >>> 6] |= (1L << (recovered & 63));
                collapsedPiece[bestTile] = recovered;
            }
        }

        if (!this.timedOut) {
            for (int t = 0; t < this.totalTilesPerEvalCell; ++t) {
                if (this.checkTimeout()) {
                    break;
                }
                if (collapsedPiece[t] == -1) {
                    if (this.countBits(wave[t]) > 0) {
                        collapsedPiece[t] = this.firstSetBit(wave[t]);
                    } else {
                        collapsedPiece[t] = this.recoverContradictedTile(t, collapsedPiece, distanceWeights[t]);
                    }
                }
            }
        }

        return collapsedPiece;
    }

    private long[] findMatchingBaseLayerPieces(int px, int pz, BaseBlock[][] existingBase, long[] allowedWave) {
        long[] result = new long[this.wordCount];
        int numPieces = this.pieces.size();
        for (int p = 0; p < numPieces; ++p) {
            if ((allowedWave[p >>> 6] & (1L << (p & 63))) == 0L) {
                continue;
            }
            Piece3D piece = this.pieces.get(p);
            boolean matches = true;
            for (int dz = 0; dz < this.wfcSize && matches; ++dz) {
                for (int dx = 0; dx < this.wfcSize; ++dx) {
                    int voxelIdx = dx + this.wfcSize * (0 + this.wfcSize * dz);
                    BaseBlock pBlock = this.blockPalette.get(piece.voxels[voxelIdx]);
                    BaseBlock worldBlock = existingBase[px + dx][pz + dz];
                    if (worldBlock == null || !Objects.equals(pBlock.toBlockState(), worldBlock.toBlockState())) {
                        matches = false;
                        break;
                    }
                }
            }
            if (matches) {
                result[p >>> 6] |= (1L << (p & 63));
            }
        }
        if (this.countBits(result) > 0) {
            return result;
        }
        for (int p = 0; p < numPieces; ++p) {
            if ((allowedWave[p >>> 6] & (1L << (p & 63))) == 0L) {
                continue;
            }
            Piece3D piece = this.pieces.get(p);
            boolean matches = true;
            for (int dz = 0; dz < this.wfcSize && matches; ++dz) {
                for (int dx = 0; dx < this.wfcSize; ++dx) {
                    int voxelIdx = dx + this.wfcSize * (0 + this.wfcSize * dz);
                    BaseBlock pBlock = this.blockPalette.get(piece.voxels[voxelIdx]);
                    BaseBlock worldBlock = existingBase[px + dx][pz + dz];
                    if (worldBlock == null || !Objects.equals(pBlock.getBlockType(), worldBlock.getBlockType())) {
                        matches = false;
                        break;
                    }
                }
            }
            if (matches) {
                result[p >>> 6] |= (1L << (p & 63));
            }
        }
        return result;
    }

    private void placeEvaluatedTiles(BlockVector3 cellOrigin, int[] evaluatedTiles) throws MaxChangedBlocksException {
        BaseBlock[][][] outGrid = new BaseBlock[this.evalSizeX][this.evalSizeY][this.evalSizeZ];
        for (int pz = 0; pz < this.evalTilesZ; ++pz) {
            for (int py = 0; py < this.evalTilesY; ++py) {
                for (int px = 0; px < this.evalTilesX; ++px) {
                    int pieceId = evaluatedTiles[this.evalTileIndex(px, py, pz)];
                    if (pieceId < 0) {
                        continue;
                    }
                    Piece3D piece = this.pieces.get(pieceId);
                    for (int dy = 0; dy < this.wfcSize; ++dy) {
                        for (int dz = 0; dz < this.wfcSize; ++dz) {
                            for (int dx = 0; dx < this.wfcSize; ++dx) {
                                int voxelIdx = dx + this.wfcSize * (dy + this.wfcSize * dz);
                                BaseBlock block = this.blockPalette.get(piece.voxels[voxelIdx]);
                                outGrid[px + dx][py + dy][pz + dz] = block;
                            }
                        }
                    }
                }
            }
        }

        int startY = this.useExistingBaseLayer ? 1 : 0;
        for (int y = startY; y < this.evalSizeY; ++y) {
            for (int z = 0; z < this.evalSizeZ; ++z) {
                for (int x = 0; x < this.evalSizeX; ++x) {
                    BaseBlock block = outGrid[x][y][z];
                    if (block == null) {
                        continue;
                    }
                    if (this.useExistingBaseLayer && isAirBlock(block)) {
                        continue;
                    }
                    this.editSession.setBlock(cellOrigin.add(x, y, z), (BlockStateHolder) block);
                }
            }
        }
    }

    private void computeCombinedWeights(
            int tileIdx,
            long[][] wave,
            int[] collapsedPiece,
            double[] distWeightsForTile,
            double[] outWeights
    ) {
        Arrays.fill(outWeights, 0.0);
        int px = tileIdx % this.evalTilesX;
        int py = (tileIdx / this.evalTilesX) % this.evalTilesY;
        int pz = tileIdx / (this.evalTilesX * this.evalTilesY);

        long[] mask = wave[tileIdx];
        for (int w = 0; w < this.wordCount; ++w) {
            long bits = mask[w];
            while (bits != 0L) {
                int bit = Long.numberOfTrailingZeros(bits);
                int p = (w << 6) + bit;
                Piece3D candidate = this.pieces.get(p);

                double distWeight = distWeightsForTile[p];
                double neighborLikeliness = 1.0;

                for (int d = 0; d < 6; ++d) {
                    int nx = px + DX[d];
                    int ny = py + DY[d];
                    int nz = pz + DZ[d];
                    if (nx < 0 || nx >= this.evalTilesX || ny < 0 || ny >= this.evalTilesY || nz < 0 || nz >= this.evalTilesZ) {
                        continue;
                    }
                    int neighborTile = this.evalTileIndex(nx, ny, nz);
                    int neighborCollapsed = collapsedPiece[neighborTile];
                    if (neighborCollapsed == -2) {
                        continue;
                    }
                    if (neighborCollapsed != -1) {
                        int freq = candidate.neighborFrequencies[d][neighborCollapsed];
                        int totalInDir = Math.max(1, candidate.neighborTotals[d]);
                        neighborLikeliness *= (0.05 + ((double) freq / (double) totalInDir) * 10.0);
                    } else {
                        long[] nMask = wave[neighborTile];
                        int matchedFreq = 0;
                        int[] freqTable = candidate.neighborFrequencies[d];
                        for (int nw = 0; nw < this.wordCount; ++nw) {
                            long nBits = nMask[nw];
                            while (nBits != 0L) {
                                int nBit = Long.numberOfTrailingZeros(nBits);
                                int np = (nw << 6) + nBit;
                                matchedFreq += freqTable[np];
                                nBits &= (nBits - 1L);
                            }
                        }
                        if (matchedFreq > 0 && candidate.neighborTotals[d] > 0) {
                            neighborLikeliness *= (0.2 + ((double) matchedFreq / (double) candidate.neighborTotals[d]) * 2.0);
                        }
                    }
                }

                outWeights[p] = Math.max(1.0E-9, distWeight * neighborLikeliness);
                bits &= (bits - 1L);
            }
        }
    }

    private boolean propagateBitmask(
            long[][] wave,
            long[][] bannedMask,
            int[] collapsedPiece,
            ArrayDeque<Integer> queue,
            boolean[] inQueue
    ) {
        if (this.lazyTolerance <= 0) {
            return this.propagateStrictBitmask(wave, collapsedPiece, queue, inQueue);
        }
        return this.propagateLazyBitmask(wave, bannedMask, collapsedPiece, queue, inQueue);
    }

    private boolean propagateStrictBitmask(
            long[][] wave,
            int[] collapsedPiece,
            ArrayDeque<Integer> queue,
            boolean[] inQueue
    ) {
        long[] allowedUnion = new long[this.wordCount];
        while (!queue.isEmpty()) {
            if (this.checkTimeout()) {
                return true;
            }
            int tileIdx = queue.poll();
            inQueue[tileIdx] = false;

            int px = tileIdx % this.evalTilesX;
            int py = (tileIdx / this.evalTilesX) % this.evalTilesY;
            int pz = tileIdx / (this.evalTilesX * this.evalTilesY);
            long[] currentMask = wave[tileIdx];

            for (int d = 0; d < 6; ++d) {
                int nx = px + DX[d];
                int ny = py + DY[d];
                int nz = pz + DZ[d];
                if (nx < 0 || nx >= this.evalTilesX || ny < 0 || ny >= this.evalTilesY || nz < 0 || nz >= this.evalTilesZ) {
                    continue;
                }
                int neighborTile = this.evalTileIndex(nx, ny, nz);
                if (collapsedPiece[neighborTile] == -2) {
                    continue;
                }
                long[] neighborMask = wave[neighborTile];

                Arrays.fill(allowedUnion, 0L);
                long[][] dirSupport = this.supportMasks[d];
                for (int w = 0; w < this.wordCount; ++w) {
                    long bits = currentMask[w];
                    while (bits != 0L) {
                        int bit = Long.numberOfTrailingZeros(bits);
                        int p = (w << 6) + bit;
                        long[] pSupport = dirSupport[p];
                        for (int sw = 0; sw < this.wordCount; ++sw) {
                            allowedUnion[sw] |= pSupport[sw];
                        }
                        bits &= (bits - 1L);
                    }
                }

                boolean changed = false;
                boolean anyRemaining = false;
                for (int w = 0; w < this.wordCount; ++w) {
                    long oldBits = neighborMask[w];
                    long newBits = oldBits & allowedUnion[w];
                    if (newBits != oldBits) {
                        neighborMask[w] = newBits;
                        changed = true;
                    }
                    if (newBits != 0L) {
                        anyRemaining = true;
                    }
                }

                if (!anyRemaining) {
                    return false;
                }
                if (changed && !inQueue[neighborTile]) {
                    queue.add(neighborTile);
                    inQueue[neighborTile] = true;
                }
            }
        }
        return true;
    }

    private boolean propagateLazyBitmask(
            long[][] wave,
            long[][] bannedMask,
            int[] collapsedPiece,
            ArrayDeque<Integer> queue,
            boolean[] inQueue
    ) {
        long[][] suppByDir = new long[6][this.wordCount];
        boolean[] validDir = new boolean[6];

        while (!queue.isEmpty()) {
            if (this.checkTimeout()) {
                return true;
            }
            int tileIdx = queue.poll();
            inQueue[tileIdx] = false;

            int px = tileIdx % this.evalTilesX;
            int py = (tileIdx / this.evalTilesX) % this.evalTilesY;
            int pz = tileIdx / (this.evalTilesX * this.evalTilesY);

            for (int d = 0; d < 6; ++d) {
                int nx = px + DX[d];
                int ny = py + DY[d];
                int nz = pz + DZ[d];
                if (nx < 0 || nx >= this.evalTilesX || ny < 0 || ny >= this.evalTilesY || nz < 0 || nz >= this.evalTilesZ) {
                    continue;
                }
                int neighborTile = this.evalTileIndex(nx, ny, nz);
                if (collapsedPiece[neighborTile] != -1) {
                    continue;
                }

                int activeNeighbors = 0;
                for (int d2 = 0; d2 < 6; ++d2) {
                    int nnx = nx + DX[d2];
                    int nny = ny + DY[d2];
                    int nnz = nz + DZ[d2];
                    if (nnx < 0 || nnx >= this.evalTilesX || nny < 0 || nny >= this.evalTilesY || nnz < 0 || nnz >= this.evalTilesZ) {
                        validDir[d2] = false;
                        continue;
                    }
                    int adjTile = this.evalTileIndex(nnx, nny, nnz);
                    if (collapsedPiece[adjTile] == -2) {
                        validDir[d2] = false;
                        continue;
                    }
                    validDir[d2] = true;
                    ++activeNeighbors;

                    Arrays.fill(suppByDir[d2], 0L);
                    int oppDir = d2 ^ 1;
                    long[][] oppSupport = this.supportMasks[oppDir];
                    long[] adjWave = wave[adjTile];
                    for (int w = 0; w < this.wordCount; ++w) {
                        long bits = adjWave[w];
                        while (bits != 0L) {
                            int bit = Long.numberOfTrailingZeros(bits);
                            int p = (w << 6) + bit;
                            long[] pSupport = oppSupport[p];
                            for (int sw = 0; sw < this.wordCount; ++sw) {
                                suppByDir[d2][sw] |= pSupport[sw];
                            }
                            bits &= (bits - 1L);
                        }
                    }
                }

                long[] neighborMask = wave[neighborTile];
                long[] banned = bannedMask[neighborTile];
                boolean changed = false;
                boolean anyRemaining = false;

                int requiredSupport = Math.max(0, activeNeighbors - this.lazyTolerance);
                for (int w = 0; w < this.wordCount; ++w) {
                    long oldBits = neighborMask[w];
                    long candidateBits = oldBits & ~banned[w];
                    long newBits = 0L;

                    while (candidateBits != 0L) {
                        int bit = Long.numberOfTrailingZeros(candidateBits);
                        long bitMask = 1L << bit;
                        int supportedBy = 0;
                        for (int d2 = 0; d2 < 6; ++d2) {
                            if (validDir[d2] && (suppByDir[d2][w] & bitMask) != 0L) {
                                ++supportedBy;
                            }
                        }
                        if (supportedBy >= requiredSupport) {
                            newBits |= bitMask;
                        }
                        candidateBits &= (candidateBits - 1L);
                    }

                    if (newBits != oldBits) {
                        neighborMask[w] = newBits;
                        changed = true;
                    }
                    if (newBits != 0L) {
                        anyRemaining = true;
                    }
                }

                if (!anyRemaining) {
                    return false;
                }
                if (changed && !inQueue[neighborTile]) {
                    queue.add(neighborTile);
                    inQueue[neighborTile] = true;
                }
            }
        }
        return true;
    }

    private int recoverContradictedTile(
            int tileIdx,
            int[] collapsedPiece,
            double[] distWeightsForTile
    ) {
        int px = tileIdx % this.evalTilesX;
        int py = (tileIdx / this.evalTilesX) % this.evalTilesY;
        int pz = tileIdx / (this.evalTilesX * this.evalTilesY);
        int bFlags = this.computeBoundaryFlags(px, py, pz, this.evalTilesX, this.evalTilesY, this.evalTilesZ);
        long[] allowedLayerMask = this.boundaryLayerMasks[bFlags];

        int bestPiece = 0;
        double bestScore = -1.0;
        int numPieces = this.pieces.size();

        for (int p = 0; p < numPieces; ++p) {
            if ((allowedLayerMask[p >>> 6] & (1L << (p & 63))) == 0L) {
                continue;
            }
            Piece3D candidate = this.pieces.get(p);
            double score = distWeightsForTile[p];
            for (int d = 0; d < 6; ++d) {
                int nx = px + DX[d];
                int ny = py + DY[d];
                int nz = pz + DZ[d];
                if (nx < 0 || nx >= this.evalTilesX || ny < 0 || ny >= this.evalTilesY || nz < 0 || nz >= this.evalTilesZ) {
                    continue;
                }
                int neighborTile = this.evalTileIndex(nx, ny, nz);
                int neighborCollapsed = collapsedPiece[neighborTile];
                if (neighborCollapsed >= 0) {
                    int freq = candidate.neighborFrequencies[d][neighborCollapsed];
                    if (freq > 0) {
                        score *= (2.0 + freq);
                    } else if ((this.supportMasks[d][p][neighborCollapsed >>> 6] & (1L << (neighborCollapsed & 63))) != 0L) {
                        score *= 1.5;
                    }
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestPiece = p;
            }
        }
        return bestPiece;
    }

    private int countBits(long[] mask) {
        int total = 0;
        for (long word : mask) {
            total += Long.bitCount(word);
        }
        return total;
    }

    private int firstSetBit(long[] mask) {
        for (int w = 0; w < mask.length; ++w) {
            if (mask[w] != 0L) {
                return (w << 6) + Long.numberOfTrailingZeros(mask[w]);
            }
        }
        return 0;
    }

    private static final class Piece3D {
        final int id;
        final int[] voxels;
        final int boundaryFlags;
        final List<Double> distancesFromCenter = new ArrayList<>();
        final List<Integer> verticalPositions = new ArrayList<>();
        @SuppressWarnings("unchecked")
        final List<Integer>[] neighborLists = new List[]{
                new ArrayList<Integer>(),
                new ArrayList<Integer>(),
                new ArrayList<Integer>(),
                new ArrayList<Integer>(),
                new ArrayList<Integer>(),
                new ArrayList<Integer>()
        };
        int[][] neighborFrequencies;
        final int[] neighborTotals = new int[6];

        Piece3D(int id, int[] voxels, int boundaryFlags) {
            this.id = id;
            this.voxels = voxels;
            this.boundaryFlags = boundaryFlags;
        }

        void addDistanceOccurrence(double distFromCenter, int py) {
            this.distancesFromCenter.add(distFromCenter);
            this.verticalPositions.add(py);
        }

        void addNeighbor(int direction, int neighborPieceId) {
            this.neighborLists[direction].add(neighborPieceId);
        }

        void compileNeighborFrequencies(int totalPieces) {
            this.neighborFrequencies = new int[6][totalPieces];
            for (int d = 0; d < 6; ++d) {
                List<Integer> list = this.neighborLists[d];
                this.neighborTotals[d] = list.size();
                for (int neighborId : list) {
                    this.neighborFrequencies[d][neighborId] += 1;
                }
            }
        }

        double computeDistanceWeight(double evalDistFromCenter, int evalPy, float adherence) {
            if (adherence <= 0.0F) {
                return Math.max(1.0, this.distancesFromCenter.size());
            }
            double sum = 0.0;
            for (int i = 0; i < this.distancesFromCenter.size(); ++i) {
                double dataDist = this.distancesFromCenter.get(i);
                int dataPy = this.verticalPositions.get(i);
                double distDiff = Math.abs(evalDistFromCenter - dataDist) + 0.35 * Math.abs(evalPy - dataPy);
                double scaled = adherence * distDiff;
                sum += 1.0 / (1.0 + scaled + scaled * scaled);
            }
            return Math.max(1.0E-6, sum);
        }
    }

    private static final class PieceKey {
        final int[] voxels;
        final int boundaryFlags;
        final int hash;

        PieceKey(int[] voxels, int boundaryFlags) {
            this.voxels = voxels;
            this.boundaryFlags = boundaryFlags;
            this.hash = 31 * Arrays.hashCode(voxels) + boundaryFlags;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PieceKey)) return false;
            PieceKey other = (PieceKey) o;
            return this.boundaryFlags == other.boundaryFlags && Arrays.equals(this.voxels, other.voxels);
        }

        @Override
        public int hashCode() {
            return this.hash;
        }
    }

    private static final class BlockKey {
        final BaseBlock block;
        final int hash;

        BlockKey(BaseBlock block) {
            this.block = block;
            this.hash = Objects.hashCode(block.toBlockState());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof BlockKey)) return false;
            BlockKey other = (BlockKey) o;
            return Objects.equals(this.block.toBlockState(), other.block.toBlockState())
                    && Objects.equals(this.block.getNbtData(), other.block.getNbtData());
        }

        @Override
        public int hashCode() {
            return this.hash;
        }
    }
}
