package com.dragonbuildtools.converter;

import com.dragonbuildtools.converter.Model3DVoxelizer.VoxelGrid;
import com.dragonbuildtools.util.BlockColorMapper;
import com.dragonbuildtools.util.BlockColorMapper.SubBlockFamily;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.Direction;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypes;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Generates 3D extruded text builds with $2\times2$ supersampled sub-block edge smoothing
 * (stairs & slabs on curved letter bevels), configurable depth, and optional beveling.
 */
public final class Text3DGenerator {

    private Text3DGenerator() {
    }

    public static VoxelGrid generate3DText(
            String text,
            int targetHeight,
            int depth,
            boolean bevel,
            boolean useSubBlocks,
            Pattern overridePattern
    ) {
        if (text == null || text.trim().isEmpty()) {
            return new VoxelGrid(1, 1, 1, new BaseBlock[1][1][1], 0);
        }
        int clampedH = Math.max(6, Math.min(128, targetHeight));
        int clampedDepth = Math.max(1, Math.min(64, depth));

        // Render at 2x resolution so each voxel has a 2x2 sub-pixel quad for sub-block stair/slab bevels
        int superH = clampedH * 2;
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, (int) Math.round(superH * 0.88));

        BufferedImage measureImg = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D mg = measureImg.createGraphics();
        mg.setFont(font);
        FontMetrics fm = mg.getFontMetrics();
        int rawW = Math.max(4, fm.stringWidth(text) + 8);
        int rawH = Math.max(4, fm.getAscent() + fm.getDescent() + 8);
        mg.dispose();

        BufferedImage canvas = new BufferedImage(rawW, rawH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = canvas.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setFont(font);
        g2.setColor(Color.WHITE);
        g2.drawString(text, 4, 4 + fm.getAscent());
        g2.dispose();

        // Crop tight bounding box
        int minX = rawW, minY = rawH, maxX = -1, maxY = -1;
        for (int y = 0; y < rawH; y++) {
            for (int x = 0; x < rawW; x++) {
                if (((canvas.getRGB(x, y) >>> 24) & 0xFF) >= 64) {
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < minX || maxY < minY) {
            return new VoxelGrid(1, 1, 1, new BaseBlock[1][1][1], 0);
        }

        BufferedImage cropped = canvas.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
        BufferedImage superImg = ImagePixelArtConverter.resizeImage(cropped,
                Math.max(2, (int) Math.round((double) cropped.getWidth() * superH / cropped.getHeight())),
                superH);

        int gridX = (superImg.getWidth() + 1) / 2;
        int gridY = (superImg.getHeight() + 1) / 2;
        int gridZ = clampedDepth;

        BlockType baseType = null;
        try {
            baseType = BlockTypes.QUARTZ_BLOCK != null ? BlockTypes.QUARTZ_BLOCK : BlockTypes.WHITE_CONCRETE;
        } catch (Throwable ignored) {
        }
        if (overridePattern != null) {
            try {
                BaseBlock sample = overridePattern.applyBlock(BlockVector3.ZERO);
                if (sample != null) {
                    baseType = sample.getBlockType();
                }
            } catch (Throwable ignored) {
            }
        }

        SubBlockFamily subFam = BlockColorMapper.resolveFamilyForBlock(baseType);
        if (subFam == null) {
            subFam = BlockColorMapper.findNearestStairFamily(235, 235, 235, baseType);
        }

        int[][] mask2x2 = new int[gridX][gridY]; // 4-bit mask: bit0=(0,0) bot-left, bit1=(1,0) bot-right, bit2=(0,1) top-left, bit3=(1,1) top-right
        for (int vy = 0; vy < gridY; vy++) {
            for (int vx = 0; vx < gridX; vx++) {
                int imgYBot = superImg.getHeight() - 1 - (vy * 2);
                int imgYTop = imgYBot - 1;
                int imgXLeft = vx * 2;
                int imgXRight = imgXLeft + 1;

                int m = 0;
                if (isSolidPixel(superImg, imgXLeft, imgYBot)) m |= 1;
                if (isSolidPixel(superImg, imgXRight, imgYBot)) m |= 2;
                if (isSolidPixel(superImg, imgXLeft, imgYTop)) m |= 4;
                if (isSolidPixel(superImg, imgXRight, imgYTop)) m |= 8;
                mask2x2[vx][vy] = m;
            }
        }

        BaseBlock[][][] blocks = new BaseBlock[gridX][gridY][gridZ];
        int nonEmpty = 0;

        for (int vy = 0; vy < gridY; vy++) {
            for (int vx = 0; vx < gridX; vx++) {
                int m = mask2x2[vx][vy];
                if (m == 0) continue;

                boolean hasDown = (vy > 0 && mask2x2[vx][vy - 1] != 0);
                boolean hasUp = (vy + 1 < gridY && mask2x2[vx][vy + 1] != 0);
                boolean hasLeft = (vx > 0 && mask2x2[vx - 1][vy] != 0);
                boolean hasRight = (vx + 1 < gridX && mask2x2[vx + 1][vy] != 0);
                boolean isBoundary = !(hasDown && hasUp && hasLeft && hasRight);

                for (int vz = 0; vz < gridZ; vz++) {
                    if (bevel && gridZ >= 3 && isBoundary && (vz == 0 || vz == gridZ - 1) && Integer.bitCount(m) <= 2) {
                        continue;
                    }

                    BaseBlock placed = null;
                    if (useSubBlocks && m != 0xF && !(hasDown && hasUp) && subFam != null) {
                        placed = selectGlyphSubBlock(m, hasDown, hasUp, hasLeft, hasRight, subFam);
                    }
                    if (placed == null) {
                        if (overridePattern != null) {
                            placed = overridePattern.applyBlock(BlockVector3.at(vx, vy, vz));
                        } else if (baseType != null && baseType.getDefaultState() != null) {
                            placed = baseType.getDefaultState().toBaseBlock();
                        }
                    }
                    if (placed != null) {
                        blocks[vx][vy][vz] = placed;
                        nonEmpty++;
                    }
                }
            }
        }

        return new VoxelGrid(gridX, gridY, gridZ, blocks, nonEmpty);
    }

    private static boolean isSolidPixel(BufferedImage img, int x, int y) {
        if (x < 0 || x >= img.getWidth() || y < 0 || y >= img.getHeight()) {
            return false;
        }
        return ((img.getRGB(x, y) >>> 24) & 0xFF) >= 80;
    }

    private static BaseBlock selectGlyphSubBlock(
            int m,
            boolean hasDown,
            boolean hasUp,
            boolean hasLeft,
            boolean hasRight,
            SubBlockFamily fam
    ) {
        int botBits = m & 0x3;
        int topBits = (m >> 2) & 0x3;

        if (!hasUp && topBits == 0 && botBits != 0 && fam.slab != null) {
            return BlockColorMapper.createSlabBlock(fam.slab, "bottom");
        }
        if (!hasDown && botBits == 0 && topBits != 0 && fam.slab != null) {
            return BlockColorMapper.createSlabBlock(fam.slab, "top");
        }
        if (!hasUp && hasDown && fam.stairs != null) {
            if (hasRight && !hasLeft) {
                return BlockColorMapper.createStairBlock(fam.stairs, Direction.EAST, "bottom", "straight");
            }
            if (hasLeft && !hasRight) {
                return BlockColorMapper.createStairBlock(fam.stairs, Direction.WEST, "bottom", "straight");
            }
        }
        if (!hasDown && hasUp && fam.stairs != null) {
            if (hasRight && !hasLeft) {
                return BlockColorMapper.createStairBlock(fam.stairs, Direction.EAST, "top", "straight");
            }
            if (hasLeft && !hasRight) {
                return BlockColorMapper.createStairBlock(fam.stairs, Direction.WEST, "top", "straight");
            }
        }
        return null;
    }
}
