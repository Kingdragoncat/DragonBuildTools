package com.dragonbuildtools.converter;

import com.dragonbuildtools.DragonBuildToolsPlugin;
import com.dragonbuildtools.converter.Model3DVoxelizer.VoxelGrid;
import com.dragonbuildtools.util.BlockColorMapper;
import com.dragonbuildtools.util.BlockColorMapper.SubBlockFamily;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.Direction;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypes;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Converts 2D heightmap / DEM images (plus optional satellite/color overlay maps) into 3D Minecraft
 * terrain builds with elevation biomes and sub-block slope smoothing (snow layers 1..7, slabs, and stairs).
 */
public final class HeightmapTerrainGenerator {

    private HeightmapTerrainGenerator() {
    }

    public static File getHeightmapsDirectory() {
        File base = (DragonBuildToolsPlugin.getInstance() != null)
                ? DragonBuildToolsPlugin.getInstance().getDataFolder()
                : new File(".");
        File dir = new File(base, "heightmaps");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static BufferedImage loadHeightmapOrColormap(String source) throws IOException {
        if (source.startsWith("http://") || source.startsWith("https://")) {
            return ImagePixelArtConverter.loadImage(source);
        }
        File dir = getHeightmapsDirectory();
        File direct = new File(dir, source);
        if (direct.exists() && direct.isFile()) {
            try (InputStream in = new FileInputStream(direct)) {
                byte[] bytes = ImagePixelArtConverter.readAllBytes(in, 32 * 1024 * 1024);
                return ImagePixelArtConverter.decodeImageBytes(bytes, direct.getName());
            }
        }
        String[] exts = {".png", ".jpg", ".jpeg", ".tif", ".tiff", ".bmp", ".webp", ".pgm", ".ppm", ".tga"};
        for (String ext : exts) {
            File candidate = new File(dir, source + ext);
            if (candidate.exists() && candidate.isFile()) {
                try (InputStream in = new FileInputStream(candidate)) {
                    byte[] bytes = ImagePixelArtConverter.readAllBytes(in, 32 * 1024 * 1024);
                    return ImagePixelArtConverter.decodeImageBytes(bytes, candidate.getName());
                }
            }
        }
        return ImagePixelArtConverter.loadImage(source);
    }

    /**
     * Generates a 3D {@link VoxelGrid} terrain from a heightmap image, optional color overlay image,
     * target horizontal footprint size, and maximum vertical elevation.
     */
    public static VoxelGrid generateTerrain(
            String heightmapSource,
            String colormapSource,
            int targetSize,
            int maxElevation,
            boolean solidUnderground,
            boolean useSubBlocks,
            boolean includeWater,
            Pattern overridePattern
    ) throws IOException {
        int clampedSize = Math.max(4, Math.min(512, targetSize));
        int clampedMaxY = Math.max(4, Math.min(256, maxElevation));

        BufferedImage rawHeight = loadHeightmapOrColormap(heightmapSource);
        int srcW = rawHeight.getWidth();
        int srcH = rawHeight.getHeight();
        int gridX = (srcW >= srcH) ? clampedSize : Math.max(1, (int) Math.round((double) clampedSize * srcW / srcH));
        int gridZ = (srcH >= srcW) ? clampedSize : Math.max(1, (int) Math.round((double) clampedSize * srcH / srcW));

        BufferedImage heightImg = ImagePixelArtConverter.resizeImage(rawHeight, gridX, gridZ);
        BufferedImage colorImg = null;
        if (colormapSource != null && !colormapSource.isEmpty()) {
            BufferedImage rawColor = loadHeightmapOrColormap(colormapSource);
            colorImg = ImagePixelArtConverter.resizeImage(rawColor, gridX, gridZ);
        }

        double[][] exactHeight = new double[gridX][gridZ];
        int[][] topBlockY = new int[gridX][gridZ];
        int[][] topSubEighths = new int[gridX][gridZ]; // 0..7 eighths inside the cap block

        for (int z = 0; z < gridZ; z++) {
            for (int x = 0; x < gridX; x++) {
                int argb = heightImg.getRGB(x, z);
                int r = (argb >>> 16) & 0xFF;
                int g = (argb >>> 8) & 0xFF;
                int b = argb & 0xFF;
                double lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
                double h = 1.0 + lum * (clampedMaxY - 2.0);
                exactHeight[x][z] = h;
                int fullY = Math.max(0, Math.min(clampedMaxY - 2, (int) Math.floor(h)));
                topBlockY[x][z] = fullY;
                int eighths = (int) Math.round((h - fullY) * 8.0);
                topSubEighths[x][z] = Math.max(0, Math.min(7, eighths));
            }
        }

        int seaLevel = includeWater ? Math.max(2, (int) Math.round(clampedMaxY * 0.18)) : -1;
        BaseBlock[][][] blocks = new BaseBlock[gridX][clampedMaxY][gridZ];
        int nonEmpty = 0;

        BaseBlock waterBlock = getBlockOrFallback("minecraft:water", "minecraft:blue_stained_glass");
        BaseBlock bedrockBase = getBlockOrFallback("minecraft:stone", "minecraft:cobblestone");
        BaseBlock dirtBlock = getBlockOrFallback("minecraft:dirt", "minecraft:coarse_dirt");

        for (int z = 0; z < gridZ; z++) {
            for (int x = 0; x < gridX; x++) {
                int surfY = topBlockY[x][z];
                int minNeighborY = surfY;
                if (x > 0) minNeighborY = Math.min(minNeighborY, topBlockY[x - 1][z]);
                if (x + 1 < gridX) minNeighborY = Math.min(minNeighborY, topBlockY[x + 1][z]);
                if (z > 0) minNeighborY = Math.min(minNeighborY, topBlockY[x][z - 1]);
                if (z + 1 < gridZ) minNeighborY = Math.min(minNeighborY, topBlockY[x][z + 1]);

                int startY = solidUnderground ? 0 : Math.max(0, minNeighborY - 1);
                int surfaceColorArgb = (colorImg != null)
                        ? colorImg.getRGB(x, z)
                        : computeBiomeColorArgb(surfY, clampedMaxY, seaLevel);

                BaseBlock surfaceFullBlock = (overridePattern != null)
                        ? overridePattern.applyBlock(BlockVector3.at(x, surfY, z))
                        : resolveBiomeOrColorBlock(surfaceColorArgb, colorImg != null, surfY, clampedMaxY, seaLevel);

                for (int y = startY; y <= surfY; y++) {
                    BaseBlock placed;
                    if (overridePattern != null) {
                        placed = overridePattern.applyBlock(BlockVector3.at(x, y, z));
                    } else if (y == surfY) {
                        placed = surfaceFullBlock;
                    } else if (y >= surfY - 2 && surfY > seaLevel + 2 && surfY < clampedMaxY * 0.62 && colorImg == null) {
                        placed = dirtBlock;
                    } else if (colorImg != null && y >= surfY - 2) {
                        placed = surfaceFullBlock;
                    } else {
                        placed = bedrockBase;
                    }
                    if (placed != null) {
                        blocks[x][y][z] = placed;
                        nonEmpty++;
                    }
                }

                // Fill water up to seaLevel in basins
                if (includeWater && surfY < seaLevel && waterBlock != null) {
                    for (int y = surfY + 1; y <= seaLevel && y < clampedMaxY; y++) {
                        blocks[x][y][z] = waterBlock;
                        nonEmpty++;
                    }
                }

                // Place sub-block slope cap at (x, surfY + 1, z) when above water
                if (useSubBlocks && surfY >= seaLevel && surfY + 1 < clampedMaxY) {
                    int eighths = topSubEighths[x][z];
                    if (eighths >= 1) {
                        BaseBlock cap = selectTerrainSlopeCap(
                                x, z, surfY, eighths,
                                gridX, gridZ, clampedMaxY,
                                exactHeight, surfaceColorArgb, surfaceFullBlock
                        );
                        if (cap != null) {
                            blocks[x][surfY + 1][z] = cap;
                            nonEmpty++;
                        }
                    }
                }
            }
        }

        return new VoxelGrid(gridX, clampedMaxY, gridZ, blocks, nonEmpty);
    }

    private static BaseBlock selectTerrainSlopeCap(
            int x, int z, int surfY, int eighths,
            int gridX, int gridZ, int maxElevation,
            double[][] exactHeight,
            int surfaceColorArgb,
            BaseBlock surfaceFullBlock
    ) {
        int r = (surfaceColorArgb >>> 16) & 0xFF;
        int g = (surfaceColorArgb >>> 8) & 0xFF;
        int b = surfaceColorArgb & 0xFF;
        BlockType baseType = surfaceFullBlock != null ? surfaceFullBlock.getBlockType() : null;

        boolean isSnowPeak = (surfY >= maxElevation * 0.78)
                || (r >= 210 && g >= 210 && b >= 210 && Math.abs(r - b) <= 18);
        if (isSnowPeak) {
            return BlockColorMapper.createSnowLayerBlock(eighths);
        }

        // Compute terrain gradient (dh/dx, dh/dz) to orient stairs along steeper slopes
        double dhX = 0.0;
        if (x > 0 && x + 1 < gridX) dhX = (exactHeight[x + 1][z] - exactHeight[x - 1][z]) * 0.5;
        double dhZ = 0.0;
        if (z > 0 && z + 1 < gridZ) dhZ = (exactHeight[x][z + 1] - exactHeight[x][z - 1]) * 0.5;
        double slopeMag = Math.hypot(dhX, dhZ);

        if (eighths >= 5 && slopeMag >= 0.45) {
            SubBlockFamily stairFam = BlockColorMapper.findNearestStairFamily(r, g, b, baseType);
            if (stairFam != null && stairFam.stairs != null) {
                Direction uphill;
                if (Math.abs(dhX) >= Math.abs(dhZ)) {
                    uphill = (dhX > 0) ? Direction.EAST : Direction.WEST;
                } else {
                    uphill = (dhZ > 0) ? Direction.SOUTH : Direction.NORTH;
                }
                return BlockColorMapper.createStairBlock(stairFam.stairs, uphill, "bottom", "straight");
            }
        }

        if (eighths >= 3 && eighths <= 6) {
            SubBlockFamily slabFam = BlockColorMapper.findNearestSlabFamily(r, g, b, baseType);
            if (slabFam != null && slabFam.slab != null) {
                return BlockColorMapper.createSlabBlock(slabFam.slab, "bottom");
            }
        }

        return null;
    }

    private static int computeBiomeColorArgb(int y, int maxElevation, int seaLevel) {
        double norm = (double) y / Math.max(1.0, maxElevation - 1.0);
        if (seaLevel > 0 && y <= seaLevel + 1) {
            return (255 << 24) | (219 << 16) | (207 << 8) | 163; // sand
        } else if (norm < 0.48) {
            return (255 << 24) | (89 << 16) | (109 << 8) | 45; // moss/grass
        } else if (norm < 0.76) {
            return (255 << 24) | (128 << 16) | (128 << 8) | 128; // stone/andesite
        } else {
            return (255 << 24) | (245 << 16) | (250 << 8) | 250; // snow
        }
    }

    private static BaseBlock resolveBiomeOrColorBlock(
            int colorArgb,
            boolean hasCustomColormap,
            int y,
            int maxElevation,
            int seaLevel
    ) {
        if (hasCustomColormap) {
            return BlockColorMapper.getNearestBlock(colorArgb, false, null);
        }
        double norm = (double) y / Math.max(1.0, maxElevation - 1.0);
        if (seaLevel > 0 && y <= seaLevel + 1) {
            return getBlockOrFallback("minecraft:sand", "minecraft:sandstone");
        } else if (norm < 0.35) {
            return getBlockOrFallback("minecraft:grass_block", "minecraft:moss_block");
        } else if (norm < 0.50) {
            return getBlockOrFallback("minecraft:moss_block", "minecraft:green_terracotta");
        } else if (norm < 0.66) {
            return getBlockOrFallback("minecraft:stone", "minecraft:andesite");
        } else if (norm < 0.78) {
            return getBlockOrFallback("minecraft:andesite", "minecraft:tuff");
        } else if (norm < 0.85) {
            return getBlockOrFallback("minecraft:calcite", "minecraft:diorite");
        } else {
            return getBlockOrFallback("minecraft:snow_block", "minecraft:white_concrete");
        }
    }

    private static BaseBlock getBlockOrFallback(String primaryId, String fallbackId) {
        try {
            BlockType primary = BlockTypes.get(primaryId);
            if (primary != null && primary.getDefaultState() != null) {
                return primary.getDefaultState().toBaseBlock();
            }
            BlockType fallback = BlockTypes.get(fallbackId);
            if (fallback != null && fallback.getDefaultState() != null) {
                return fallback.getDefaultState().toBaseBlock();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
