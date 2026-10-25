package com.dragonbuildtools.converter;

import com.dragonbuildtools.DragonBuildToolsPlugin;
import com.dragonbuildtools.util.BlockColorMapper;
import com.dragonbuildtools.util.CardinalDirectionUtil;



import com.fastasyncworldedit.core.registry.state.PropertyKey;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.Direction;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import com.sk89q.worldedit.world.block.BlockType;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Converts 3D models (.obj + .mtl + textures, .stl ASCII/binary, .ply ASCII/binary,
 * .gltf/.glb glTF 2.0, .vox MagicaVoxel, .off) into 3D voxelized Minecraft builds
 * with sub-voxel octant analysis for stairs, slabs, walls, fences, fence gates, trapdoors,
 * carpets, and snow layers in the exact blockstates.
 */
public final class Model3DVoxelizer {

    private static final String[] SUPPORTED_EXTENSIONS = {
            ".obj", ".stl", ".ply", ".glb", ".gltf", ".vox", ".off"
    };

    // Octant bit masks: bit = dx | (dy << 1) | (dz << 2)
    private static final int NW_BOT = 0x01; // dx=0, dy=0, dz=0
    private static final int NE_BOT = 0x02; // dx=1, dy=0, dz=0
    private static final int NW_TOP = 0x04; // dx=0, dy=1, dz=0
    private static final int NE_TOP = 0x08; // dx=1, dy=1, dz=0
    private static final int SW_BOT = 0x10; // dx=0, dy=0, dz=1
    private static final int SE_BOT = 0x20; // dx=1, dy=0, dz=1
    private static final int SW_TOP = 0x40; // dx=0, dy=1, dz=1
    private static final int SE_TOP = 0x80; // dx=1, dy=1, dz=1

    private static final int BOT_MASK = NW_BOT | NE_BOT | SW_BOT | SE_BOT; // 0x33
    private static final int TOP_MASK = NW_TOP | NE_TOP | SW_TOP | SE_TOP; // 0xCC
    private static final int NORTH_MASK = NW_BOT | NE_BOT | NW_TOP | NE_TOP; // 0x0F
    private static final int SOUTH_MASK = SW_BOT | SE_BOT | SW_TOP | SE_TOP; // 0xF0
    private static final int WEST_MASK = NW_BOT | NW_TOP | SW_BOT | SW_TOP; // 0x55
    private static final int EAST_MASK = NE_BOT | NE_TOP | SE_BOT | SE_TOP; // 0xAA

    public static final class MaterialData {
        public final String name;
        public double kdR = 0.94;
        public double kdG = 0.94;
        public double kdB = 0.94;
        public double alpha = 1.0;
        public BufferedImage texture;
        public boolean hasExplicitColor = false;

        public MaterialData(String name) {
            this.name = name;
        }
    }

    public static final class Triangle3D {
        public double x0, y0, z0;
        public double x1, y1, z1;
        public double x2, y2, z2;

        public double u0, v0;
        public double u1, v1;
        public double u2, v2;
        public boolean hasUv = false;

        public double r0 = 1.0, g0 = 1.0, b0 = 1.0;
        public double r1 = 1.0, g1 = 1.0, b1 = 1.0;
        public double r2 = 1.0, g2 = 1.0, b2 = 1.0;
        public boolean hasVertexColor = false;

        public MaterialData material;

        public Triangle3D(
                double x0, double y0, double z0,
                double x1, double y1, double z1,
                double x2, double y2, double z2
        ) {
            this.x0 = x0;
            this.y0 = y0;
            this.z0 = z0;
            this.x1 = x1;
            this.y1 = y1;
            this.z1 = z1;
            this.x2 = x2;
            this.y2 = y2;
            this.z2 = z2;
        }

        public void setUv(double u0, double v0, double u1, double v1, double u2, double v2) {
            this.u0 = u0;
            this.v0 = v0;
            this.u1 = u1;
            this.v1 = v1;
            this.u2 = u2;
            this.v2 = v2;
            this.hasUv = true;
        }

        public void setVertexColors(
                double r0, double g0, double b0,
                double r1, double g1, double b1,
                double r2, double g2, double b2
        ) {
            this.r0 = r0;
            this.g0 = g0;
            this.b0 = b0;
            this.r1 = r1;
            this.g1 = g1;
            this.b1 = b1;
            this.r2 = r2;
            this.g2 = g2;
            this.b2 = b2;
            this.hasVertexColor = true;
        }
    }

    public static final class Mesh3D {
        public final List<Triangle3D> triangles = new ArrayList<>();
        public boolean hasAnyColorOrTexture = false;

        public int[][][] directVoxels;
        public int directSizeX;
        public int directSizeY;
        public int directSizeZ;
    }

    public static final class VoxelGrid {
        public final int sizeX;
        public final int sizeY;
        public final int sizeZ;
        public final BaseBlock[][][] blocks;
        public final int nonEmptyCount;

        public VoxelGrid(int sizeX, int sizeY, int sizeZ, BaseBlock[][][] blocks, int nonEmptyCount) {
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.blocks = blocks;
            this.nonEmptyCount = nonEmptyCount;
        }
    }

    private Model3DVoxelizer() {
    }

    public static File getModelsDirectory() {
        File base = (DragonBuildToolsPlugin.getInstance() != null) ? DragonBuildToolsPlugin.getInstance().getDataFolder() : new File(".");
        File dir = new File(base, "models");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static File resolveLocalModelFile(String source) {
        File dir = getModelsDirectory();
        File direct = new File(dir, source);
        if (direct.exists() && direct.isFile()) {
            return direct;
        }
        for (String ext : SUPPORTED_EXTENSIONS) {
            File candidate = new File(dir, source + ext);
            if (candidate.exists() && candidate.isFile()) {
                return candidate;
            }
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().equalsIgnoreCase(source)) {
                    return f;
                }
                String lower = f.getName().toLowerCase(Locale.ROOT);
                for (String ext : SUPPORTED_EXTENSIONS) {
                    if (lower.equals((source + ext).toLowerCase(Locale.ROOT))) {
                        return f;
                    }
                }
            }
        }
        File raw = new File(source);
        if (raw.exists() && raw.isFile()) {
            return raw;
        }
        return null;
    }

    public static Mesh3D loadModel(String source) throws IOException {
        byte[] data;
        String hintName = source.toLowerCase(Locale.ROOT);
        File contextDir = getModelsDirectory();
        String baseUrl = null;

        if (source.startsWith("http://") || source.startsWith("https://")) {
            data = ImagePixelArtConverter.downloadBytes(source, 64 * 1024 * 1024);
            int slash = source.lastIndexOf('/');
            if (slash > 8) {
                baseUrl = source.substring(0, slash + 1);
            }
            int q = hintName.indexOf('?');
            if (q > 0) {
                hintName = hintName.substring(0, q);
            }
        } else {
            File file = resolveLocalModelFile(source);
            if (file == null || !file.exists()) {
                throw new IOException("3D model file not found: " + source + " (Place models in plugins/DragonBuildTools/models/ or use a URL)");
            }
            hintName = file.getName().toLowerCase(Locale.ROOT);
            contextDir = file.getParentFile();
            try (InputStream in = new FileInputStream(file)) {
                data = ImagePixelArtConverter.readAllBytes(in, 64 * 1024 * 1024);
            }
        }
        return parseModelBytes(data, hintName, contextDir, baseUrl);
    }

    public static Mesh3D parseModelBytes(byte[] data, String hintName, File contextDir, String baseUrl) throws IOException {
        if (data == null || data.length < 4) {
            throw new IOException("Empty or invalid 3D model data.");
        }
        String lower = hintName != null ? hintName.toLowerCase(Locale.ROOT) : "";

        if ((data[0] == 'V' && data[1] == 'O' && data[2] == 'X' && data[3] == ' ') || lower.endsWith(".vox")) {
            return parseVox(data);
        }
        if ((data[0] == 'g' && data[1] == 'l' && data[2] == 'T' && data[3] == 'F') || lower.endsWith(".glb")) {
            return parseGlb(data, contextDir, baseUrl);
        }
        if (lower.endsWith(".gltf") || looksLikeJsonGltf(data)) {
            return parseGltfJson(new String(data, StandardCharsets.UTF_8), null, contextDir, baseUrl);
        }
        if ((data[0] == 'p' && data[1] == 'l' && data[2] == 'y') || lower.endsWith(".ply")) {
            return parsePly(data);
        }
        if (lower.endsWith(".off") || (data[0] == 'O' && data[1] == 'F' && data[2] == 'F')
                || (data[0] == 'C' && data[1] == 'O' && data[2] == 'F' && data[3] == 'F')) {
            return parseOff(new String(data, StandardCharsets.UTF_8));
        }
        if (lower.endsWith(".stl") || looksLikeStl(data)) {
            return parseStl(data);
        }
        return parseObj(new String(data, StandardCharsets.UTF_8), contextDir, baseUrl);
    }

    // =========================================================================
    // Voxelization Engine (8-Octant Sub-Voxel + Sub-Block Synthesis)
    // =========================================================================

    public static VoxelGrid voxelize(
            Mesh3D mesh,
            int targetSize,
            double rotXDeg,
            double rotYDeg,
            double rotZDeg,
            boolean solidFill,
            boolean dither,
            boolean allowGlass,
            boolean forceShading,
            Pattern overridePattern
    ) {
        return voxelize(mesh, targetSize, rotXDeg, rotYDeg, rotZDeg, solidFill, dither, allowGlass, forceShading, true, overridePattern);
    }

    public static VoxelGrid voxelize(
            Mesh3D mesh,
            int targetSize,
            double rotXDeg,
            double rotYDeg,
            double rotZDeg,
            boolean solidFill,
            boolean dither,
            boolean allowGlass,
            boolean forceShading,
            boolean useSubBlocks,
            Pattern overridePattern
    ) {
        int maxDim = Math.max(1, Math.min(512, targetSize));

        if (mesh.directVoxels != null && mesh.directSizeX > 0 && mesh.directSizeY > 0 && mesh.directSizeZ > 0) {
            return voxelizeDirectGrid(mesh, maxDim, dither, allowGlass, overridePattern);
        }

        if (mesh.triangles.isEmpty()) {
            return new VoxelGrid(0, 0, 0, new BaseBlock[0][0][0], 0);
        }

        double rx = Math.toRadians(rotXDeg);
        double ry = Math.toRadians(rotYDeg);
        double rz = Math.toRadians(rotZDeg);
        boolean hasRot = (rotXDeg != 0 || rotYDeg != 0 || rotZDeg != 0);

        List<Triangle3D> tris = new ArrayList<>(mesh.triangles.size());
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;

        for (Triangle3D src : mesh.triangles) {
            double[] p0 = {src.x0, src.y0, src.z0};
            double[] p1 = {src.x1, src.y1, src.z1};
            double[] p2 = {src.x2, src.y2, src.z2};
            if (hasRot) {
                rotatePoint(p0, rx, ry, rz);
                rotatePoint(p1, rx, ry, rz);
                rotatePoint(p2, rx, ry, rz);
            }
            Triangle3D t = new Triangle3D(p0[0], p0[1], p0[2], p1[0], p1[1], p1[2], p2[0], p2[1], p2[2]);
            t.hasUv = src.hasUv;
            t.u0 = src.u0; t.v0 = src.v0;
            t.u1 = src.u1; t.v1 = src.v1;
            t.u2 = src.u2; t.v2 = src.v2;
            t.hasVertexColor = src.hasVertexColor;
            t.r0 = src.r0; t.g0 = src.g0; t.b0 = src.b0;
            t.r1 = src.r1; t.g1 = src.g1; t.b1 = src.b1;
            t.r2 = src.r2; t.g2 = src.g2; t.b2 = src.b2;
            t.material = src.material;
            tris.add(t);

            minX = Math.min(minX, Math.min(p0[0], Math.min(p1[0], p2[0])));
            minY = Math.min(minY, Math.min(p0[1], Math.min(p1[1], p2[1])));
            minZ = Math.min(minZ, Math.min(p0[2], Math.min(p1[2], p2[2])));
            maxX = Math.max(maxX, Math.max(p0[0], Math.max(p1[0], p2[0])));
            maxY = Math.max(maxY, Math.max(p0[1], Math.max(p1[1], p2[1])));
            maxZ = Math.max(maxZ, Math.max(p0[2], Math.max(p1[2], p2[2])));
        }

        double spanX = Math.max(1e-6, maxX - minX);
        double spanY = Math.max(1e-6, maxY - minY);
        double spanZ = Math.max(1e-6, maxZ - minZ);
        double maxSpan = Math.max(spanX, Math.max(spanY, spanZ));

        double scale = (maxDim - 1.0) / maxSpan;
        int gridX = Math.max(1, (int) Math.ceil(spanX * scale) + 1);
        int gridY = Math.max(1, (int) Math.ceil(spanY * scale) + 1);
        int gridZ = Math.max(1, (int) Math.ceil(spanZ * scale) + 1);

        float[][][] rSum = new float[gridX][gridY][gridZ];
        float[][][] gSum = new float[gridX][gridY][gridZ];
        float[][][] bSum = new float[gridX][gridY][gridZ];
        float[][][] aSum = new float[gridX][gridY][gridZ];
        int[][][] hitCount = new int[gridX][gridY][gridZ];
        byte[][][] octantMask = new byte[gridX][gridY][gridZ];
        byte[][][] layerMask = new byte[gridX][gridY][gridZ];

        boolean applyShading = forceShading || !mesh.hasAnyColorOrTexture;
        final double lightX = 0.45;
        final double lightY = 0.80;
        final double lightZ = 0.39;

        double[] v0 = new double[3];
        double[] v1 = new double[3];
        double[] v2 = new double[3];
        double[] boxCenter = new double[3];
        double[] octCenter = new double[3];
        double[] bary = new double[3];

        for (Triangle3D t : tris) {
            v0[0] = (t.x0 - minX) * scale;
            v0[1] = (t.y0 - minY) * scale;
            v0[2] = (t.z0 - minZ) * scale;

            v1[0] = (t.x1 - minX) * scale;
            v1[1] = (t.y1 - minY) * scale;
            v1[2] = (t.z1 - minZ) * scale;

            v2[0] = (t.x2 - minX) * scale;
            v2[1] = (t.y2 - minY) * scale;
            v2[2] = (t.z2 - minZ) * scale;

            int minVx = Math.max(0, (int) Math.floor(Math.min(v0[0], Math.min(v1[0], v2[0])) - 0.01));
            int maxVx = Math.min(gridX - 1, (int) Math.floor(Math.max(v0[0], Math.max(v1[0], v2[0])) + 0.01));
            int minVy = Math.max(0, (int) Math.floor(Math.min(v0[1], Math.min(v1[1], v2[1])) - 0.01));
            int maxVy = Math.min(gridY - 1, (int) Math.floor(Math.max(v0[1], Math.max(v1[1], v2[1])) + 0.01));
            int minVz = Math.max(0, (int) Math.floor(Math.min(v0[2], Math.min(v1[2], v2[2])) - 0.01));
            int maxVz = Math.min(gridZ - 1, (int) Math.floor(Math.max(v0[2], Math.max(v1[2], v2[2])) + 0.01));

            double e1x = v1[0] - v0[0], e1y = v1[1] - v0[1], e1z = v1[2] - v0[2];
            double e2x = v2[0] - v0[0], e2y = v2[1] - v0[1], e2z = v2[2] - v0[2];
            double nx = e1y * e2z - e1z * e2y;
            double ny = e1z * e2x - e1x * e2z;
            double nz = e1x * e2y - e1y * e2x;
            double nLen = Math.sqrt(nx * nx + ny * ny + nz * nz);
            double shadeMul = 1.0;
            if (applyShading && nLen > 1e-9) {
                nx /= nLen;
                ny /= nLen;
                nz /= nLen;
                double ndotl = Math.abs(nx * lightX + ny * lightY + nz * lightZ);
                shadeMul = 0.68 + 0.32 * ndotl;
            }

            for (int vx = minVx; vx <= maxVx; vx++) {
                boxCenter[0] = vx + 0.5;
                for (int vy = minVy; vy <= maxVy; vy++) {
                    boxCenter[1] = vy + 0.5;
                    for (int vz = minVz; vz <= maxVz; vz++) {
                        boxCenter[2] = vz + 0.5;
                        if (!triangleIntersectsVoxel(v0, v1, v2, boxCenter, 0.502)) {
                            continue;
                        }
                        computeBarycentric(v0, v1, v2, boxCenter, bary);
                        int argb = sampleTriangleColor(t, bary[0], bary[1], bary[2], shadeMul);
                        int a = (argb >>> 24) & 0xFF;
                        if (a < 32) {
                            continue;
                        }
                        rSum[vx][vy][vz] += (argb >>> 16) & 0xFF;
                        gSum[vx][vy][vz] += (argb >>> 8) & 0xFF;
                        bSum[vx][vy][vz] += argb & 0xFF;
                        aSum[vx][vy][vz] += a;
                        hitCount[vx][vy][vz]++;

                        if (useSubBlocks) {
                            int mask = 0;
                            int lMask = 0;
                            for (int dz = 0; dz < 2; dz++) {
                                octCenter[2] = vz + 0.25 + dz * 0.5;
                                for (int dy = 0; dy < 2; dy++) {
                                    octCenter[1] = vy + 0.25 + dy * 0.5;
                                    for (int dx = 0; dx < 2; dx++) {
                                        octCenter[0] = vx + 0.25 + dx * 0.5;
                                        if (triangleIntersectsVoxel(v0, v1, v2, octCenter, 0.265)) {
                                            mask |= (1 << (dx | (dy << 1) | (dz << 2)));
                                            computeBarycentric(v0, v1, v2, octCenter, bary);
                                            double subPy = bary[0] * v0[1] + bary[1] * v1[1] + bary[2] * v2[1];
                                            int subLy = Math.max(0, Math.min(7, (int) ((subPy - vy) * 8.0)));
                                            lMask |= (1 << subLy);
                                        }
                                    }
                                }
                            }
                            if (mask == 0) {
                                computeBarycentric(v0, v1, v2, boxCenter, bary);
                                double px = bary[0] * v0[0] + bary[1] * v1[0] + bary[2] * v2[0];
                                double py = bary[0] * v0[1] + bary[1] * v1[1] + bary[2] * v2[1];
                                double pz = bary[0] * v0[2] + bary[1] * v1[2] + bary[2] * v2[2];
                                int dx = (px - vx >= 0.5) ? 1 : 0;
                                int dy = (py - vy >= 0.5) ? 1 : 0;
                                int dz = (pz - vz >= 0.5) ? 1 : 0;
                                mask = 1 << (dx | (dy << 1) | (dz << 2));
                                int subLy = Math.max(0, Math.min(7, (int) ((py - vy) * 8.0)));
                                lMask |= (1 << subLy);
                            }
                            octantMask[vx][vy][vz] |= (byte) mask;
                            layerMask[vx][vy][vz] |= (byte) lMask;
                        } else {
                            octantMask[vx][vy][vz] = (byte) 0xFF;
                            layerMask[vx][vy][vz] = (byte) 0xFF;
                        }
                    }
                }
            }
        }

        // Optional Solid Interior Flood-Fill
        if (solidFill) {
            fillInteriorVoxels(gridX, gridY, gridZ, hitCount, rSum, gSum, bSum, aSum, octantMask, layerMask);
        }

        // Propagate neighbor support into octant masks so slopes/roofs/overhangs form continuous stairs & slabs
        if (useSubBlocks) {
            propagateNeighborSupportToOctants(gridX, gridY, gridZ, hitCount, octantMask);
        }

        // Resolve preferred base BlockType if an explicit single-block pattern was given
        BlockType preferredBaseType = null;
        if (overridePattern != null) {
            try {
                BaseBlock sample = overridePattern.applyBlock(BlockVector3.ZERO);
                if (sample != null) {
                    preferredBaseType = sample.getBlockType();
                }
            } catch (Throwable ignored) {
            }
        }

        BaseBlock[][][] blocks = new BaseBlock[gridX][gridY][gridZ];
        int nonEmpty = 0;
        int[] matchedRgb = new int[3];

        for (int y = 0; y < gridY; y++) {
            for (int z = 0; z < gridZ; z++) {
                for (int x = 0; x < gridX; x++) {
                    int count = hitCount[x][y][z];
                    if (count <= 0) {
                        continue;
                    }
                    int r = clampByte(rSum[x][y][z] / count);
                    int g = clampByte(gSum[x][y][z] / count);
                    int b = clampByte(bSum[x][y][z] / count);
                    int a = clampByte(aSum[x][y][z] / count);
                    int argb = (a << 24) | (r << 16) | (g << 8) | b;

                    BaseBlock placedBlock = null;
                    if (useSubBlocks && !(allowGlass && a < 210)) {
                        int oct = octantMask[x][y][z] & 0xFF;
                        int lay = layerMask[x][y][z] & 0xFF;
                        if (oct != 0xFF) {
                            placedBlock = selectSubBlockForOctants(
                                    x, y, z,
                                    gridX, gridY, gridZ,
                                    oct, lay,
                                    r, g, b,
                                    hitCount, octantMask,
                                    preferredBaseType
                            );
                        }
                    }

                    if (placedBlock == null) {
                        if (overridePattern != null) {
                            placedBlock = overridePattern.applyBlock(BlockVector3.at(x, y, z));
                        } else {
                            placedBlock = BlockColorMapper.getNearestBlock(argb, allowGlass, matchedRgb);
                        }
                    } else {
                        matchedRgb[0] = r;
                        matchedRgb[1] = g;
                        matchedRgb[2] = b;
                    }

                    if (placedBlock != null) {
                        blocks[x][y][z] = placedBlock;
                        nonEmpty++;
                        if (dither && overridePattern == null) {
                            float errR = r - matchedRgb[0];
                            float errG = g - matchedRgb[1];
                            float errB = b - matchedRgb[2];
                            diffuse3DError(x + 1, y, z, errR * 0.4375f, errG * 0.4375f, errB * 0.4375f, gridX, gridY, gridZ, hitCount, rSum, gSum, bSum);
                            diffuse3DError(x, y, z + 1, errR * 0.3125f, errG * 0.3125f, errB * 0.3125f, gridX, gridY, gridZ, hitCount, rSum, gSum, bSum);
                            diffuse3DError(x, y + 1, z, errR * 0.25f, errG * 0.25f, errB * 0.25f, gridX, gridY, gridZ, hitCount, rSum, gSum, bSum);
                        }
                    }
                }
            }
        }

        if (useSubBlocks) {
            refineSubBlockConnections(gridX, gridY, gridZ, blocks);
        }

        return new VoxelGrid(gridX, gridY, gridZ, blocks, nonEmpty);
    }

    /**
     * Propagates support continuity into surface voxel octant masks based on neighboring occupied blocks.
     */
    private static void propagateNeighborSupportToOctants(
            int gx, int gy, int gz,
            int[][][] hitCount,
            byte[][][] octantMask
    ) {
        byte[][][] updated = new byte[gx][gy][gz];
        for (int x = 0; x < gx; x++) {
            for (int y = 0; y < gy; y++) {
                for (int z = 0; z < gz; z++) {
                    if (hitCount[x][y][z] <= 0) continue;
                    int oct = octantMask[x][y][z] & 0xFF;
                    boolean hasDown = (y > 0 && hitCount[x][y - 1][z] > 0);
                    boolean hasUp = (y + 1 < gy && hitCount[x][y + 1][z] > 0);
                    boolean hasWest = (x > 0 && hitCount[x - 1][y][z] > 0);
                    boolean hasEast = (x + 1 < gx && hitCount[x + 1][y][z] > 0);
                    boolean hasNorth = (z > 0 && hitCount[x][y][z - 1] > 0);
                    boolean hasSouth = (z + 1 < gz && hitCount[x][y][z + 1] > 0);
                    int horizNeighbors = (hasWest ? 1 : 0) + (hasEast ? 1 : 0) + (hasNorth ? 1 : 0) + (hasSouth ? 1 : 0);

                    // If vertically sandwiched between a block below and above on a wall/surface, make it a full block
                    // (unless it is a thin standalone vertical post/column of <= 3 octants with no horizontal body)
                    if (hasDown && hasUp && (Integer.bitCount(oct) >= 4 || horizNeighbors >= 1)) {
                        updated[x][y][z] = (byte) 0xFF;
                        continue;
                    }

                    // If this block supports a top surface cap directly above it, make it a full block so the cap
                    // (snow layer, slab, or stair) sits flush on a solid block instead of stacking sub-blocks
                    if (hasUp && (y + 2 >= gy || hitCount[x][y + 2][z] == 0)
                            && (Integer.bitCount(oct) >= 3 || horizNeighbors >= 1)) {
                        updated[x][y][z] = (byte) 0xFF;
                        continue;
                    }

                    // Resting on a block below with exposed top: fill bottom octants if top octants are partially filled
                    if (hasDown && !hasUp) {
                        if ((oct & TOP_MASK) != 0) {
                            oct |= BOT_MASK;
                        }
                    } else if (hasUp && !hasDown) {
                        // Hanging from a block above with exposed underside: fill top octants if bottom octants are partially filled
                        if ((oct & BOT_MASK) != 0) {
                            oct |= TOP_MASK;
                        }
                    }

                    // Horizontal slope support: if top octants are partially filled on a slope adjacent to a horizontal neighbor
                    if (!hasUp && (oct & BOT_MASK) == BOT_MASK && (oct & TOP_MASK) != 0) {
                        if (hasNorth && !hasSouth) oct |= (TOP_MASK & NORTH_MASK);
                        if (hasSouth && !hasNorth) oct |= (TOP_MASK & SOUTH_MASK);
                        if (hasWest && !hasEast) oct |= (TOP_MASK & WEST_MASK);
                        if (hasEast && !hasWest) oct |= (TOP_MASK & EAST_MASK);
                    } else if (!hasDown && (oct & TOP_MASK) == TOP_MASK && (oct & BOT_MASK) != 0) {
                        if (hasNorth && !hasSouth) oct |= (BOT_MASK & NORTH_MASK);
                        if (hasSouth && !hasNorth) oct |= (BOT_MASK & SOUTH_MASK);
                        if (hasWest && !hasEast) oct |= (BOT_MASK & WEST_MASK);
                        if (hasEast && !hasWest) oct |= (BOT_MASK & EAST_MASK);
                    }

                    updated[x][y][z] = (byte) oct;
                }
            }
        }
        for (int x = 0; x < gx; x++) {
            for (int y = 0; y < gy; y++) {
                System.arraycopy(updated[x][y], 0, octantMask[x][y], 0, gz);
            }
        }
    }

    private static boolean isSnowCompatibleColor(int r, int g, int b, BlockType preferredBase) {
        if (preferredBase != null) {
            String id = preferredBase.id().toLowerCase(Locale.ROOT);
            return id.contains("snow") || id.contains("white") || id.contains("quartz")
                    || id.contains("calcite") || id.contains("diorite");
        }
        int maxC = Math.max(r, Math.max(g, b));
        int minC = Math.min(r, Math.min(g, b));
        return r >= 178 && g >= 178 && b >= 178 && (maxC - minC) <= 20;
    }

    /**
     * Selects the exact sub-block (stairs, slab, wall, fence, fence gate, trapdoor, carpet, or snow layer)
     * and blockstate matching the 8-octant occupancy mask of voxel (x, y, z).
     */
    private static BaseBlock selectSubBlockForOctants(
            int x, int y, int z,
            int gx, int gy, int gz,
            int oct, int lay,
            int r, int g, int b,
            int[][][] hitCount,
            byte[][][] octantMask,
            BlockType preferredBase
    ) {
        boolean hasDown = (y > 0 && hitCount[x][y - 1][z] > 0);
        boolean hasUp = (y + 1 < gy && hitCount[x][y + 1][z] > 0);
        boolean hasNorth = (z > 0 && hitCount[x][y][z - 1] > 0);
        boolean hasSouth = (z + 1 < gz && hitCount[x][y][z + 1] > 0);
        boolean hasWest = (x > 0 && hitCount[x - 1][y][z] > 0);
        boolean hasEast = (x + 1 < gx && hitCount[x + 1][y][z] > 0);

        int botBits = oct & BOT_MASK;
        int topBits = oct & TOP_MASK;
        int botCount = Integer.bitCount(botBits);
        int topCount = Integer.bitCount(topBits);
        int totalBits = botCount + topCount;

        // 1. Snow Layers (1..7) & Carpets on top of a solid surface
        if (hasDown && !hasUp) {
            int maxLayerIdx = (lay != 0)
                    ? (31 - Integer.numberOfLeadingZeros(lay & 0xFF))
                    : (topCount == 0 ? Math.max(0, botCount - 1) : Math.min(6, 3 + topCount));
            int layers = Math.max(1, Math.min(7, maxLayerIdx + 1));
            if (topCount > 0 && layers < 5) {
                layers = Math.min(7, 4 + topCount);
            } else if (topCount == 0 && layers > 4) {
                layers = 4;
            }

            boolean isSnowWhite = isSnowCompatibleColor(r, g, b, preferredBase);
            boolean hasSingleSlopeBack = ((hasNorth && !hasSouth) || (hasSouth && !hasNorth)
                    || (hasWest && !hasEast) || (hasEast && !hasWest));
            if (isSnowWhite && (topCount == 0 || !hasSingleSlopeBack || layers <= 3 || layers >= 6)) {
                BaseBlock snow = BlockColorMapper.createSnowLayerBlock(layers);
                if (snow != null) {
                    return snow;
                }
            }
            if (topCount == 0 && layers == 1 && botCount <= 2) {
                BlockColorMapper.SubBlockFamily carpetFam = BlockColorMapper.findNearestCarpetFamily(r, g, b, preferredBase);
                if (carpetFam != null && carpetFam.carpet != null
                        && (preferredBase != null || BlockColorMapper.colorDistanceSq(r, g, b, carpetFam.r, carpetFam.g, carpetFam.b) < 2800)) {
                    return carpetFam.carpet.getDefaultState().toBaseBlock();
                }
            }
        }

        // 2. Bottom Slab (only bottom octants occupied, >= 2 octants)
        if (topCount == 0 && botCount >= 2 && !hasUp) {
            BlockColorMapper.SubBlockFamily slabFam = BlockColorMapper.findNearestSlabFamily(r, g, b, preferredBase);
            if (slabFam != null && slabFam.slab != null) {
                return BlockColorMapper.createSlabBlock(slabFam.slab, "bottom");
            }
        }

        // 3. Top Slab (only top octants occupied, >= 2 octants)
        if (botCount == 0 && topCount >= 2 && !hasDown) {
            BlockColorMapper.SubBlockFamily slabFam = BlockColorMapper.findNearestSlabFamily(r, g, b, preferredBase);
            if (slabFam != null && slabFam.slab != null) {
                return BlockColorMapper.createSlabBlock(slabFam.slab, "top");
            }
        }

        // 4. Upright Stairs (half=bottom, bottom half full or supported, 1..3 top octants, open above)
        if (!hasUp && (hasDown || y == 0) && (botBits == BOT_MASK || botCount >= 2) && topCount >= 1 && topCount <= 3) {
            BlockColorMapper.SubBlockFamily stairFam = BlockColorMapper.findNearestStairFamily(r, g, b, preferredBase);
            if (stairFam != null && stairFam.stairs != null) {
                BaseBlock stair = matchBackedStair(stairFam.stairs, "bottom", topBits, oct, hasNorth, hasSouth, hasWest, hasEast);
                if (stair != null) {
                    return stair;
                }
            }
        }

        // 5. Upside-Down Stairs (half=top, top half full or supported, 1..3 bottom octants, open below)
        if (!hasDown && hasUp && (topBits == TOP_MASK || topCount >= 2) && botCount >= 1 && botCount <= 3) {
            BlockColorMapper.SubBlockFamily stairFam = BlockColorMapper.findNearestStairFamily(r, g, b, preferredBase);
            if (stairFam != null && stairFam.stairs != null) {
                BaseBlock stair = matchBackedStair(stairFam.stairs, "top", botBits, oct, hasNorth, hasSouth, hasWest, hasEast);
                if (stair != null) {
                    return stair;
                }
            }
        }

        // 6. Horizontal Beams / Struts -> Fence Gates
        if (!hasUp && !hasDown && totalBits <= 4) {
            if (hasWest && hasEast && !hasNorth && !hasSouth) {
                BlockColorMapper.SubBlockFamily gateFam = BlockColorMapper.findNearestFenceGateFamily(r, g, b, preferredBase);
                if (gateFam != null && gateFam.fenceGate != null) {
                    return BlockColorMapper.createFenceGateBlock(gateFam.fenceGate, Direction.NORTH, false, false);
                }
            }
            if (hasNorth && hasSouth && !hasWest && !hasEast) {
                BlockColorMapper.SubBlockFamily gateFam = BlockColorMapper.findNearestFenceGateFamily(r, g, b, preferredBase);
                if (gateFam != null && gateFam.fenceGate != null) {
                    return BlockColorMapper.createFenceGateBlock(gateFam.fenceGate, Direction.EAST, false, false);
                }
            }
        }

        // 7. Thin Vertical Posts, Columns, & Freestanding Walls (totalBits <= 4)
        if (totalBits >= 1 && totalBits <= 4) {
            boolean fullN = hasNorth && (octantMask[x][y][z - 1] & 0xFF) == 0xFF;
            boolean fullS = hasSouth && (octantMask[x][y][z + 1] & 0xFF) == 0xFF;
            boolean fullW = hasWest && (octantMask[x - 1][y][z] & 0xFF) == 0xFF;
            boolean fullE = hasEast && (octantMask[x + 1][y][z] & 0xFF) == 0xFF;
            boolean attachedToSolidTrunk = fullN || fullS || fullW || fullE;

            if (attachedToSolidTrunk) {
                // Surface bevel on a solid trunk/wall: use a backed stair or slab instead of a sticking-out wall/fence post
                if (!hasUp && hasDown && totalBits >= 2) {
                    BlockColorMapper.SubBlockFamily stairFam = BlockColorMapper.findNearestStairFamily(r, g, b, preferredBase);
                    if (stairFam != null && stairFam.stairs != null) {
                        BaseBlock stair = matchBackedStair(stairFam.stairs, "bottom", topBits, oct, hasNorth, hasSouth, hasWest, hasEast);
                        if (stair != null) {
                            return stair;
                        }
                    }
                    BlockColorMapper.SubBlockFamily slabFam = BlockColorMapper.findNearestSlabFamily(r, g, b, preferredBase);
                    if (slabFam != null && slabFam.slab != null) {
                        return BlockColorMapper.createSlabBlock(slabFam.slab, "bottom");
                    }
                } else if (!hasDown && hasUp && totalBits >= 2) {
                    BlockColorMapper.SubBlockFamily stairFam = BlockColorMapper.findNearestStairFamily(r, g, b, preferredBase);
                    if (stairFam != null && stairFam.stairs != null) {
                        BaseBlock stair = matchBackedStair(stairFam.stairs, "top", botBits, oct, hasNorth, hasSouth, hasWest, hasEast);
                        if (stair != null) {
                            return stair;
                        }
                    }
                    BlockColorMapper.SubBlockFamily slabFam = BlockColorMapper.findNearestSlabFamily(r, g, b, preferredBase);
                    if (slabFam != null && slabFam.slab != null) {
                        return BlockColorMapper.createSlabBlock(slabFam.slab, "top");
                    }
                }
            } else {
                boolean connN = hasNorth || ((oct & NORTH_MASK) != 0 && (oct & SOUTH_MASK) == 0);
                boolean connS = hasSouth || ((oct & SOUTH_MASK) != 0 && (oct & NORTH_MASK) == 0);
                boolean connW = hasWest || ((oct & WEST_MASK) != 0 && (oct & EAST_MASK) == 0);
                boolean connE = hasEast || ((oct & EAST_MASK) != 0 && (oct & WEST_MASK) == 0);

                BlockColorMapper.SubBlockFamily wallFam = BlockColorMapper.findNearestWallFamily(r, g, b, preferredBase);
                BlockColorMapper.SubBlockFamily fenceFam = BlockColorMapper.findNearestFenceFamily(r, g, b, preferredBase);
                double wallDist = (wallFam != null && wallFam.wall != null)
                        ? BlockColorMapper.colorDistanceSq(r, g, b, wallFam.r, wallFam.g, wallFam.b) : Double.MAX_VALUE;
                double fenceDist = (fenceFam != null && fenceFam.fence != null)
                        ? BlockColorMapper.colorDistanceSq(r, g, b, fenceFam.r, fenceFam.g, fenceFam.b) : Double.MAX_VALUE;

                if (fenceFam != null && fenceFam.fence != null && totalBits <= 2 && fenceDist < wallDist * 0.85) {
                    return BlockColorMapper.createFenceBlock(fenceFam.fence, connN, connE, connS, connW);
                }
                if (wallFam != null && wallFam.wall != null) {
                    String nState = connN ? (hasUp ? "tall" : "low") : "none";
                    String eState = connE ? (hasUp ? "tall" : "low") : "none";
                    String sState = connS ? (hasUp ? "tall" : "low") : "none";
                    String wState = connW ? (hasUp ? "tall" : "low") : "none";
                    return BlockColorMapper.createWallBlock(wallFam.wall, nState, eState, sState, wState, true);
                }
                if (fenceFam != null && fenceFam.fence != null) {
                    return BlockColorMapper.createFenceBlock(fenceFam.fence, connN, connE, connS, connW);
                }
            }
        }

        // 8. Partial 5..6 octant slope fallback -> Backed Stair or Slab (never when vertically sandwiched)
        if (totalBits >= 5 && totalBits <= 6 && !(hasDown && hasUp)) {
            String half = null;
            if (!hasUp && (hasDown || y == 0) && botCount >= topCount) {
                half = "bottom";
            } else if (!hasDown && hasUp && topCount >= botCount) {
                half = "top";
            }
            if (half != null) {
                BlockColorMapper.SubBlockFamily stairFam = BlockColorMapper.findNearestStairFamily(r, g, b, preferredBase);
                if (stairFam != null && stairFam.stairs != null) {
                    BaseBlock stair = matchBackedStair(
                            stairFam.stairs,
                            half,
                            "bottom".equals(half) ? topBits : botBits,
                            oct,
                            hasNorth, hasSouth, hasWest, hasEast
                    );
                    if (stair != null) {
                        return stair;
                    }
                }
                BlockColorMapper.SubBlockFamily slabFam = BlockColorMapper.findNearestSlabFamily(r, g, b, preferredBase);
                if (slabFam != null && slabFam.slab != null) {
                    return BlockColorMapper.createSlabBlock(slabFam.slab, half);
                }
            }
        }

        return null;
    }

    /**
     * Selects a stair orientation whose tall back faces a solid horizontal neighbor and whose low step faces open air.
     */
    private static BaseBlock matchBackedStair(
            BlockType stairType,
            String half,
            int stepBits,
            int fullOct,
            boolean hasNorth,
            boolean hasSouth,
            boolean hasWest,
            boolean hasEast
    ) {
        boolean validN = hasNorth && !hasSouth;
        boolean validS = hasSouth && !hasNorth;
        boolean validW = hasWest && !hasEast;
        boolean validE = hasEast && !hasWest;

        int scoreN = validN ? (10 + Integer.bitCount(fullOct & NORTH_MASK)) : -1;
        int scoreS = validS ? (10 + Integer.bitCount(fullOct & SOUTH_MASK)) : -1;
        int scoreW = validW ? (10 + Integer.bitCount(fullOct & WEST_MASK)) : -1;
        int scoreE = validE ? (10 + Integer.bitCount(fullOct & EAST_MASK)) : -1;

        Direction bestDir = null;
        int bestScore = 0;
        if (scoreN > bestScore) { bestScore = scoreN; bestDir = Direction.NORTH; }
        if (scoreS > bestScore) { bestScore = scoreS; bestDir = Direction.SOUTH; }
        if (scoreW > bestScore) { bestScore = scoreW; bestDir = Direction.WEST; }
        if (scoreE > bestScore) { bestScore = scoreE; bestDir = Direction.EAST; }

        if (bestDir != null) {
            return BlockColorMapper.createStairBlock(stairType, bestDir, half, "straight");
        }

        // If no horizontal neighbor backs the stair, only use octant stair matching if opposite octants are empty
        BaseBlock octStair = "bottom".equals(half)
                ? matchUprightStair(stairType, stepBits)
                : matchUpsideDownStair(stairType, stepBits);
        if (octStair != null) {
            try {
                Object dirObj = octStair.toImmutableState().getState(PropertyKey.FACING);
                if (dirObj instanceof Direction dir) {
                    boolean frontBlocked = (dir == Direction.NORTH && hasSouth)
                            || (dir == Direction.SOUTH && hasNorth)
                            || (dir == Direction.WEST && hasEast)
                            || (dir == Direction.EAST && hasWest);
                    boolean backSupported = (dir == Direction.NORTH && hasNorth)
                            || (dir == Direction.SOUTH && hasSouth)
                            || (dir == Direction.WEST && hasWest)
                            || (dir == Direction.EAST && hasEast);
                    if (!frontBlocked && backSupported) {
                        return octStair;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static BaseBlock matchUprightStair(BlockType stairType, int topBits) {
        switch (topBits) {
            // Straight (2 adjacent top octants)
            case (NW_TOP | NE_TOP):
                return BlockColorMapper.createStairBlock(stairType, Direction.NORTH, "bottom", "straight");
            case (SW_TOP | SE_TOP):
                return BlockColorMapper.createStairBlock(stairType, Direction.SOUTH, "bottom", "straight");
            case (NW_TOP | SW_TOP):
                return BlockColorMapper.createStairBlock(stairType, Direction.WEST, "bottom", "straight");
            case (NE_TOP | SE_TOP):
                return BlockColorMapper.createStairBlock(stairType, Direction.EAST, "bottom", "straight");
            // Outer corners (1 top octant)
            case NW_TOP:
                return BlockColorMapper.createStairBlock(stairType, Direction.NORTH, "bottom", "outer_left");
            case NE_TOP:
                return BlockColorMapper.createStairBlock(stairType, Direction.NORTH, "bottom", "outer_right");
            case SW_TOP:
                return BlockColorMapper.createStairBlock(stairType, Direction.SOUTH, "bottom", "outer_right");
            case SE_TOP:
                return BlockColorMapper.createStairBlock(stairType, Direction.SOUTH, "bottom", "outer_left");
            // Inner corners (3 top octants)
            case (NW_TOP | NE_TOP | SW_TOP): // missing SE_TOP
                return BlockColorMapper.createStairBlock(stairType, Direction.NORTH, "bottom", "inner_left");
            case (NW_TOP | NE_TOP | SE_TOP): // missing SW_TOP
                return BlockColorMapper.createStairBlock(stairType, Direction.NORTH, "bottom", "inner_right");
            case (SW_TOP | SE_TOP | NW_TOP): // missing NE_TOP
                return BlockColorMapper.createStairBlock(stairType, Direction.SOUTH, "bottom", "inner_right");
            case (SW_TOP | SE_TOP | NE_TOP): // missing NW_TOP
                return BlockColorMapper.createStairBlock(stairType, Direction.SOUTH, "bottom", "inner_left");
            default:
                return null;
        }
    }

    private static BaseBlock matchUpsideDownStair(BlockType stairType, int botBits) {
        switch (botBits) {
            // Straight (2 adjacent bottom octants)
            case (NW_BOT | NE_BOT):
                return BlockColorMapper.createStairBlock(stairType, Direction.NORTH, "top", "straight");
            case (SW_BOT | SE_BOT):
                return BlockColorMapper.createStairBlock(stairType, Direction.SOUTH, "top", "straight");
            case (NW_BOT | SW_BOT):
                return BlockColorMapper.createStairBlock(stairType, Direction.WEST, "top", "straight");
            case (NE_BOT | SE_BOT):
                return BlockColorMapper.createStairBlock(stairType, Direction.EAST, "top", "straight");
            // Outer corners (1 bottom octant)
            case NW_BOT:
                return BlockColorMapper.createStairBlock(stairType, Direction.NORTH, "top", "outer_left");
            case NE_BOT:
                return BlockColorMapper.createStairBlock(stairType, Direction.NORTH, "top", "outer_right");
            case SW_BOT:
                return BlockColorMapper.createStairBlock(stairType, Direction.SOUTH, "top", "outer_right");
            case SE_BOT:
                return BlockColorMapper.createStairBlock(stairType, Direction.SOUTH, "top", "outer_left");
            // Inner corners (3 bottom octants)
            case (NW_BOT | NE_BOT | SW_BOT):
                return BlockColorMapper.createStairBlock(stairType, Direction.NORTH, "top", "inner_left");
            case (NW_BOT | NE_BOT | SE_BOT):
                return BlockColorMapper.createStairBlock(stairType, Direction.NORTH, "top", "inner_right");
            case (SW_BOT | SE_BOT | NW_BOT):
                return BlockColorMapper.createStairBlock(stairType, Direction.SOUTH, "top", "inner_right");
            case (SW_BOT | SE_BOT | NE_BOT):
                return BlockColorMapper.createStairBlock(stairType, Direction.SOUTH, "top", "inner_left");
            default:
                return null;
        }
    }

    /**
     * Post-processes all placed sub-blocks (walls, fences, fence gates, slabs, snow layers, and stairs) so
     * stairs never stack vertically or face backwards, snow layers always rest on solid full blocks,
     * and directional connection properties (north/east/south/west/up/in_wall) match their 3D neighbors.
     */
    private static void refineSubBlockConnections(int gx, int gy, int gz, BaseBlock[][][] blocks) {
        // Pass 1: Eliminate vertically stacked stairs & ensure solid full-block support under snow/carpets/stairs
        for (int y = 0; y < gy; y++) {
            for (int z = 0; z < gz; z++) {
                for (int x = 0; x < gx; x++) {
                    BaseBlock b = blocks[x][y][z];
                    if (b == null) continue;
                    BlockType bt = b.getBlockType();
                    String id = bt.id().toLowerCase(Locale.ROOT);
                    boolean hasUpBlock = (y + 1 < gy && blocks[x][y + 1][z] != null);
                    boolean hasDownBlock = (y > 0 && blocks[x][y - 1][z] != null);

                    if (id.equals("minecraft:snow") || id.endsWith("_carpet")) {
                        if (hasUpBlock) {
                            BaseBlock full = BlockColorMapper.getFullBlockForSubBlock(bt);
                            if (full != null) {
                                blocks[x][y][z] = full;
                            }
                        } else if (hasDownBlock && isNonFullSubBlock(blocks[x][y - 1][z])) {
                            BaseBlock belowFull = BlockColorMapper.getFullBlockForSubBlock(blocks[x][y - 1][z].getBlockType());
                            if (belowFull != null) {
                                blocks[x][y - 1][z] = belowFull;
                            }
                        }
                    } else if (id.endsWith("_stairs")) {
                        String half = getBlockHalf(b, "bottom");
                        if ("bottom".equals(half)) {
                            if (hasUpBlock) {
                                // Upright stair cannot have any block directly above it (prevents stacked stairs & wall notches)
                                BaseBlock full = BlockColorMapper.getFullBlockForSubBlock(bt);
                                if (full != null) {
                                    blocks[x][y][z] = full;
                                }
                            } else if (hasDownBlock && isNonFullSubBlock(blocks[x][y - 1][z])) {
                                // Upgrade sub-block below an upright stair to a full block so stairs never stack on stairs/walls/slabs
                                BaseBlock belowFull = BlockColorMapper.getFullBlockForSubBlock(blocks[x][y - 1][z].getBlockType());
                                if (belowFull != null) {
                                    blocks[x][y - 1][z] = belowFull;
                                }
                            }
                        } else if ("top".equals(half)) {
                            if (hasDownBlock) {
                                // Upside-down stair cannot have any block directly below it
                                BaseBlock full = BlockColorMapper.getFullBlockForSubBlock(bt);
                                if (full != null) {
                                    blocks[x][y][z] = full;
                                }
                            } else if (hasUpBlock && isNonFullSubBlock(blocks[x][y + 1][z])) {
                                // Upgrade sub-block above an upside-down stair to a full block so stairs never stack
                                BaseBlock aboveFull = BlockColorMapper.getFullBlockForSubBlock(blocks[x][y + 1][z].getBlockType());
                                if (aboveFull != null) {
                                    blocks[x][y + 1][z] = aboveFull;
                                }
                            }
                        }
                    } else if (id.endsWith("_slab")) {
                        String type = getBlockTypeProperty(b, "bottom");
                        if ("bottom".equals(type) && hasUpBlock) {
                            BaseBlock full = BlockColorMapper.getFullBlockForSubBlock(bt);
                            if (full != null) {
                                blocks[x][y][z] = full;
                            }
                        } else if ("top".equals(type) && hasDownBlock) {
                            BaseBlock full = BlockColorMapper.getFullBlockForSubBlock(bt);
                            if (full != null) {
                                blocks[x][y][z] = full;
                            }
                        }
                    }
                }
            }
        }

        // Pass 2: Validate stair horizontal backing & front clearance, and update wall/fence/gate connections
        for (int y = 0; y < gy; y++) {
            for (int z = 0; z < gz; z++) {
                for (int x = 0; x < gx; x++) {
                    BaseBlock b = blocks[x][y][z];
                    if (b == null) continue;
                    BlockType bt = b.getBlockType();
                    String id = bt.id().toLowerCase(Locale.ROOT);

                    if (id.endsWith("_stairs")) {
                        String half = getBlockHalf(b, "bottom");
                        Direction facing = getBlockFacing(b, Direction.NORTH);
                        boolean nSolid = (z > 0 && blocks[x][y][z - 1] != null);
                        boolean sSolid = (z + 1 < gz && blocks[x][y][z + 1] != null);
                        boolean wSolid = (x > 0 && blocks[x - 1][y][z] != null);
                        boolean eSolid = (x + 1 < gx && blocks[x + 1][y][z] != null);

                        boolean backSupported = (facing == Direction.NORTH && nSolid)
                                || (facing == Direction.SOUTH && sSolid)
                                || (facing == Direction.WEST && wSolid)
                                || (facing == Direction.EAST && eSolid);
                        boolean frontClear = (facing == Direction.NORTH && !sSolid)
                                || (facing == Direction.SOUTH && !nSolid)
                                || (facing == Direction.WEST && !eSolid)
                                || (facing == Direction.EAST && !wSolid);

                        if (!backSupported || !frontClear) {
                            Direction validDir = null;
                            if (nSolid && !sSolid) validDir = Direction.NORTH;
                            else if (sSolid && !nSolid) validDir = Direction.SOUTH;
                            else if (wSolid && !eSolid) validDir = Direction.WEST;
                            else if (eSolid && !wSolid) validDir = Direction.EAST;

                            if (validDir != null) {
                                blocks[x][y][z] = BlockColorMapper.createStairBlock(bt, validDir, half, "straight");
                            } else if (!frontClear) {
                                BaseBlock full = BlockColorMapper.getFullBlockForSubBlock(bt);
                                if (full != null) {
                                    blocks[x][y][z] = full;
                                }
                            } else {
                                BaseBlock slab = BlockColorMapper.getSlabBlockForSubBlock(bt, half);
                                if (slab != null) {
                                    blocks[x][y][z] = slab;
                                }
                            }
                        }
                    } else if (id.endsWith("_wall")) {
                        boolean hasUpBlock = (y + 1 < gy && blocks[x][y + 1][z] != null);
                        String wN = getWallSide(x, y, z - 1, gx, gy, gz, blocks, hasUpBlock);
                        String wE = getWallSide(x + 1, y, z, gx, gy, gz, blocks, hasUpBlock);
                        String wS = getWallSide(x, y, z + 1, gx, gy, gz, blocks, hasUpBlock);
                        String wW = getWallSide(x - 1, y, z, gx, gy, gz, blocks, hasUpBlock);
                        int count = (!"none".equals(wN) ? 1 : 0) + (!"none".equals(wE) ? 1 : 0)
                                + (!"none".equals(wS) ? 1 : 0) + (!"none".equals(wW) ? 1 : 0);
                        boolean straightNS = (count == 2 && !"none".equals(wN) && !"none".equals(wS));
                        boolean straightEW = (count == 2 && !"none".equals(wE) && !"none".equals(wW));
                        boolean upPost = !(straightNS || straightEW) || hasUpBlock;
                        blocks[x][y][z] = BlockColorMapper.createWallBlock(bt, wN, wE, wS, wW, upPost);
                    } else if (id.endsWith("_fence")) {
                        boolean cN = connectsFence(x, y, z - 1, gx, gy, gz, blocks);
                        boolean cE = connectsFence(x + 1, y, z, gx, gy, gz, blocks);
                        boolean cS = connectsFence(x, y, z + 1, gx, gy, gz, blocks);
                        boolean cW = connectsFence(x - 1, y, z, gx, gy, gz, blocks);
                        blocks[x][y][z] = BlockColorMapper.createFenceBlock(bt, cN, cE, cS, cW);
                    } else if (id.endsWith("_fence_gate")) {
                        boolean wallN = isWallAt(x, y, z - 1, gx, gy, gz, blocks);
                        boolean wallS = isWallAt(x, y, z + 1, gx, gy, gz, blocks);
                        boolean wallE = isWallAt(x + 1, y, z, gx, gy, gz, blocks);
                        boolean wallW = isWallAt(x - 1, y, z, gx, gy, gz, blocks);
                        boolean inWall = wallN || wallS || wallE || wallW;
                        BlockState st = b.toImmutableState();
                        st = BlockColorMapper.withPropertySafe(st, PropertyKey.IN_WALL, inWall);
                        blocks[x][y][z] = st.toBaseBlock();
                    }
                }
            }
        }
    }

    private static boolean isNonFullSubBlock(BaseBlock block) {
        if (block == null) return false;
        String id = block.getBlockType().id().toLowerCase(Locale.ROOT);
        return id.endsWith("_stairs")
                || id.endsWith("_slab")
                || id.endsWith("_wall")
                || id.endsWith("_fence")
                || id.endsWith("_fence_gate")
                || id.endsWith("_carpet")
                || id.endsWith("_trapdoor")
                || id.equals("minecraft:snow");
    }

    private static String getBlockHalf(BaseBlock block, String defaultVal) {
        try {
            Object val = block.toImmutableState().getState(PropertyKey.HALF);
            if (val != null) {
                return val.toString().toLowerCase(Locale.ROOT);
            }
        } catch (Throwable ignored) {
        }
        return defaultVal;
    }

    private static String getBlockTypeProperty(BaseBlock block, String defaultVal) {
        try {
            Object val = block.toImmutableState().getState(PropertyKey.TYPE);
            if (val != null) {
                return val.toString().toLowerCase(Locale.ROOT);
            }
        } catch (Throwable ignored) {
        }
        return defaultVal;
    }

    private static Direction getBlockFacing(BaseBlock block, Direction defaultDir) {
        try {
            Object val = block.toImmutableState().getState(PropertyKey.FACING);
            if (val instanceof Direction d) {
                return d;
            }
        } catch (Throwable ignored) {
        }
        return defaultDir;
    }

    private static String getWallSide(int nx, int ny, int nz, int gx, int gy, int gz, BaseBlock[][][] blocks, boolean hasUpBlock) {
        if (nx < 0 || nx >= gx || ny < 0 || ny >= gy || nz < 0 || nz >= gz) {
            return "none";
        }
        BaseBlock nb = blocks[nx][ny][nz];
        if (nb == null) {
            return "none";
        }
        String id = nb.getBlockType().id().toLowerCase(Locale.ROOT);
        if (id.endsWith("_carpet") || id.equals("minecraft:snow")) {
            return "none";
        }
        return hasUpBlock ? "tall" : "low";
    }

    private static boolean connectsFence(int nx, int ny, int nz, int gx, int gy, int gz, BaseBlock[][][] blocks) {
        if (nx < 0 || nx >= gx || ny < 0 || ny >= gy || nz < 0 || nz >= gz) {
            return false;
        }
        BaseBlock nb = blocks[nx][ny][nz];
        if (nb == null) {
            return false;
        }
        String id = nb.getBlockType().id().toLowerCase(Locale.ROOT);
        return !id.endsWith("_carpet") && !id.equals("minecraft:snow") && !id.endsWith("_wall");
    }

    private static boolean isWallAt(int nx, int ny, int nz, int gx, int gy, int gz, BaseBlock[][][] blocks) {
        if (nx < 0 || nx >= gx || ny < 0 || ny >= gy || nz < 0 || nz >= gz) {
            return false;
        }
        BaseBlock nb = blocks[nx][ny][nz];
        return nb != null && nb.getBlockType().id().toLowerCase(Locale.ROOT).endsWith("_wall");
    }

    private static void diffuse3DError(
            int x, int y, int z,
            float dR, float dG, float dB,
            int gx, int gy, int gz,
            int[][][] hitCount,
            float[][][] rSum, float[][][] gSum, float[][][] bSum
    ) {
        if (x >= 0 && x < gx && y >= 0 && y < gy && z >= 0 && z < gz) {
            int c = hitCount[x][y][z];
            if (c > 0) {
                rSum[x][y][z] += dR * c;
                gSum[x][y][z] += dG * c;
                bSum[x][y][z] += dB * c;
            }
        }
    }

    private static VoxelGrid voxelizeDirectGrid(
            Mesh3D mesh,
            int targetSize,
            boolean dither,
            boolean allowGlass,
            Pattern overridePattern
    ) {
        int sx = mesh.directSizeX;
        int sy = mesh.directSizeY;
        int sz = mesh.directSizeZ;
        int maxSrc = Math.max(sx, Math.max(sy, sz));
        double scale = (targetSize > 0) ? ((double) targetSize / (double) maxSrc) : 1.0;

        int gx = Math.max(1, (int) Math.round(sx * scale));
        int gy = Math.max(1, (int) Math.round(sy * scale));
        int gz = Math.max(1, (int) Math.round(sz * scale));

        BaseBlock[][][] blocks = new BaseBlock[gx][gy][gz];
        int nonEmpty = 0;
        int[] matchedRgb = new int[3];

        for (int x = 0; x < gx; x++) {
            int srcX = Math.min(sx - 1, (int) (x / scale));
            for (int y = 0; y < gy; y++) {
                int srcY = Math.min(sy - 1, (int) (y / scale));
                for (int z = 0; z < gz; z++) {
                    int srcZ = Math.min(sz - 1, (int) (z / scale));
                    int argb = mesh.directVoxels[srcX][srcY][srcZ];
                    if (((argb >>> 24) & 0xFF) < 32) {
                        continue;
                    }
                    if (overridePattern != null) {
                        blocks[x][y][z] = overridePattern.applyBlock(BlockVector3.at(x, y, z));
                        nonEmpty++;
                    } else {
                        BaseBlock b = BlockColorMapper.getNearestBlock(argb, allowGlass, matchedRgb);
                        if (b != null) {
                            blocks[x][y][z] = b;
                            nonEmpty++;
                        }
                    }
                }
            }
        }
        return new VoxelGrid(gx, gy, gz, blocks, nonEmpty);
    }

    private static void fillInteriorVoxels(
            int gx, int gy, int gz,
            int[][][] hitCount,
            float[][][] rSum, float[][][] gSum, float[][][] bSum, float[][][] aSum,
            byte[][][] octantMask, byte[][][] layerMask
    ) {
        boolean[][][] exterior = new boolean[gx][gy][gz];
        ArrayDeque<int[]> queue = new ArrayDeque<>();

        for (int x = 0; x < gx; x++) {
            for (int y = 0; y < gy; y++) {
                for (int z = 0; z < gz; z++) {
                    boolean onBorder = (x == 0 || x == gx - 1 || y == 0 || y == gy - 1 || z == 0 || z == gz - 1);
                    if (onBorder && hitCount[x][y][z] == 0) {
                        exterior[x][y][z] = true;
                        queue.add(new int[]{x, y, z});
                    }
                }
            }
        }

        final int[] dx = {1, -1, 0, 0, 0, 0};
        final int[] dy = {0, 0, 1, -1, 0, 0};
        final int[] dz = {0, 0, 0, 0, 1, -1};

        while (!queue.isEmpty()) {
            int[] cur = queue.poll();
            int cx = cur[0], cy = cur[1], cz = cur[2];
            for (int d = 0; d < 6; d++) {
                int nx = cx + dx[d];
                int ny = cy + dy[d];
                int nz = cz + dz[d];
                if (nx >= 0 && nx < gx && ny >= 0 && ny < gy && nz >= 0 && nz < gz) {
                    if (!exterior[nx][ny][nz] && hitCount[nx][ny][nz] == 0) {
                        exterior[nx][ny][nz] = true;
                        queue.add(new int[]{nx, ny, nz});
                    }
                }
            }
        }

        for (int x = 0; x < gx; x++) {
            for (int y = 0; y < gy; y++) {
                for (int z = 0; z < gz; z++) {
                    if (!exterior[x][y][z] && hitCount[x][y][z] == 0) {
                        hitCount[x][y][z] = 1;
                        rSum[x][y][z] = 128;
                        gSum[x][y][z] = 128;
                        bSum[x][y][z] = 128;
                        aSum[x][y][z] = 255;
                        octantMask[x][y][z] = (byte) 0xFF;
                        layerMask[x][y][z] = (byte) 0xFF;
                    }
                }
            }
        }
    }

    /**
     * Exact 3D Triangle-Box Overlap Test (Akenine-MÃ¶ller Separating Axis Theorem).
     */
    public static boolean triangleIntersectsVoxel(double[] v0, double[] v1, double[] v2, double[] boxCenter, double halfSize) {
        double ax = v0[0] - boxCenter[0], ay = v0[1] - boxCenter[1], az = v0[2] - boxCenter[2];
        double bx = v1[0] - boxCenter[0], by = v1[1] - boxCenter[1], bz = v1[2] - boxCenter[2];
        double cx = v2[0] - boxCenter[0], cy = v2[1] - boxCenter[1], cz = v2[2] - boxCenter[2];

        if (Math.min(ax, Math.min(bx, cx)) > halfSize || Math.max(ax, Math.max(bx, cx)) < -halfSize) return false;
        if (Math.min(ay, Math.min(by, cy)) > halfSize || Math.max(ay, Math.max(by, cy)) < -halfSize) return false;
        if (Math.min(az, Math.min(bz, cz)) > halfSize || Math.max(az, Math.max(bz, cz)) < -halfSize) return false;

        double e0x = bx - ax, e0y = by - ay, e0z = bz - az;
        double e1x = cx - bx, e1y = cy - by, e1z = cz - bz;
        double e2x = ax - cx, e2y = ay - cy, e2z = az - cz;

        if (!axisTestX(e0z, e0y, Math.abs(e0z), Math.abs(e0y), ay, az, cy, cz, halfSize)) return false;
        if (!axisTestY(e0z, e0x, Math.abs(e0z), Math.abs(e0x), ax, az, cx, cz, halfSize)) return false;
        if (!axisTestZ(e0y, e0x, Math.abs(e0y), Math.abs(e0x), ax, ay, cx, cy, halfSize)) return false;

        if (!axisTestX(e1z, e1y, Math.abs(e1z), Math.abs(e1y), ay, az, by, bz, halfSize)) return false;
        if (!axisTestY(e1z, e1x, Math.abs(e1z), Math.abs(e1x), ax, az, bx, bz, halfSize)) return false;
        if (!axisTestZ(e1y, e1x, Math.abs(e1y), Math.abs(e1x), ax, ay, bx, by, halfSize)) return false;

        if (!axisTestX(e2z, e2y, Math.abs(e2z), Math.abs(e2y), ay, az, by, bz, halfSize)) return false;
        if (!axisTestY(e2z, e2x, Math.abs(e2z), Math.abs(e2x), ax, az, bx, bz, halfSize)) return false;
        if (!axisTestZ(e2y, e2x, Math.abs(e2y), Math.abs(e2x), ax, ay, bx, by, halfSize)) return false;

        double nx = e0y * e1z - e0z * e1y;
        double ny = e0z * e1x - e0x * e1z;
        double nz = e0x * e1y - e0y * e1x;
        double r = halfSize * (Math.abs(nx) + Math.abs(ny) + Math.abs(nz));
        double s = nx * ax + ny * ay + nz * az;
        return Math.abs(s) <= r;
    }

    private static boolean axisTestX(double a, double b, double fa, double fb, double v0y, double v0z, double v2y, double v2z, double hs) {
        double p0 = a * v0y - b * v0z;
        double p2 = a * v2y - b * v2z;
        double min = Math.min(p0, p2);
        double max = Math.max(p0, p2);
        double rad = (fa + fb) * hs;
        return !(min > rad || max < -rad);
    }

    private static boolean axisTestY(double a, double b, double fa, double fb, double v0x, double v0z, double v2x, double v2z, double hs) {
        double p0 = -a * v0x + b * v0z;
        double p2 = -a * v2x + b * v2z;
        double min = Math.min(p0, p2);
        double max = Math.max(p0, p2);
        double rad = (fa + fb) * hs;
        return !(min > rad || max < -rad);
    }

    private static boolean axisTestZ(double a, double b, double fa, double fb, double v0x, double v0y, double v2x, double v2y, double hs) {
        double p0 = a * v0x - b * v0y;
        double p2 = a * v2x - b * v2y;
        double min = Math.min(p0, p2);
        double max = Math.max(p0, p2);
        double rad = (fa + fb) * hs;
        return !(min > rad || max < -rad);
    }

    private static void computeBarycentric(double[] a, double[] b, double[] c, double[] p, double[] outBary) {
        double v0x = b[0] - a[0], v0y = b[1] - a[1], v0z = b[2] - a[2];
        double v1x = c[0] - a[0], v1y = c[1] - a[1], v1z = c[2] - a[2];
        double v2x = p[0] - a[0], v2y = p[1] - a[1], v2z = p[2] - a[2];

        double d00 = v0x * v0x + v0y * v0y + v0z * v0z;
        double d01 = v0x * v1x + v0y * v1y + v0z * v1z;
        double d11 = v1x * v1x + v1y * v1y + v1z * v1z;
        double d20 = v2x * v0x + v2y * v0y + v2z * v0z;
        double d21 = v2x * v1x + v2y * v1y + v2z * v1z;

        double denom = d00 * d11 - d01 * d01;
        if (Math.abs(denom) < 1e-12) {
            outBary[0] = 1.0 / 3.0;
            outBary[1] = 1.0 / 3.0;
            outBary[2] = 1.0 / 3.0;
            return;
        }
        double v = (d11 * d20 - d01 * d21) / denom;
        double w = (d00 * d21 - d01 * d20) / denom;
        v = Math.max(0.0, Math.min(1.0, v));
        w = Math.max(0.0, Math.min(1.0 - v, w));
        double u = 1.0 - v - w;
        outBary[0] = u;
        outBary[1] = v;
        outBary[2] = w;
    }

    private static int sampleTriangleColor(Triangle3D t, double w0, double w1, double w2, double shadeMul) {
        double r = 0.94, g = 0.94, b = 0.94, a = 1.0;
        if (t.material != null) {
            r = t.material.kdR;
            g = t.material.kdG;
            b = t.material.kdB;
            a = t.material.alpha;
        }
        if (t.hasVertexColor) {
            double vr = w0 * t.r0 + w1 * t.r1 + w2 * t.r2;
            double vg = w0 * t.g0 + w1 * t.g1 + w2 * t.g2;
            double vb = w0 * t.b0 + w1 * t.b1 + w2 * t.b2;
            if (t.material != null && t.material.hasExplicitColor) {
                r *= vr;
                g *= vg;
                b *= vb;
            } else {
                r = vr;
                g = vg;
                b = vb;
            }
        }
        if (t.hasUv && t.material != null && t.material.texture != null) {
            BufferedImage tex = t.material.texture;
            double u = w0 * t.u0 + w1 * t.u1 + w2 * t.u2;
            double v = w0 * t.v0 + w1 * t.v1 + w2 * t.v2;
            u = u - Math.floor(u);
            v = 1.0 - (v - Math.floor(v));
            int tx = Math.max(0, Math.min(tex.getWidth() - 1, (int) (u * tex.getWidth())));
            int ty = Math.max(0, Math.min(tex.getHeight() - 1, (int) (v * tex.getHeight())));
            int texArgb = tex.getRGB(tx, ty);
            a *= ((texArgb >>> 24) & 0xFF) / 255.0;
            r = (((texArgb >>> 16) & 0xFF) / 255.0) * (t.material.hasExplicitColor ? t.material.kdR : 1.0);
            g = (((texArgb >>> 8) & 0xFF) / 255.0) * (t.material.hasExplicitColor ? t.material.kdG : 1.0);
            b = ((texArgb & 0xFF) / 255.0) * (t.material.hasExplicitColor ? t.material.kdB : 1.0);
        }

        int ri = clampByte(r * shadeMul * 255.0);
        int gi = clampByte(g * shadeMul * 255.0);
        int bi = clampByte(b * shadeMul * 255.0);
        int ai = clampByte(a * 255.0);
        return (ai << 24) | (ri << 16) | (gi << 8) | bi;
    }

    private static void rotatePoint(double[] p, double rx, double ry, double rz) {
        if (rx != 0) {
            double cos = Math.cos(rx), sin = Math.sin(rx);
            double ny = p[1] * cos - p[2] * sin;
            double nz = p[1] * sin + p[2] * cos;
            p[1] = ny;
            p[2] = nz;
        }
        if (ry != 0) {
            double cos = Math.cos(ry), sin = Math.sin(ry);
            double nx = p[0] * cos + p[2] * sin;
            double nz = -p[0] * sin + p[2] * cos;
            p[0] = nx;
            p[2] = nz;
        }
        if (rz != 0) {
            double cos = Math.cos(rz), sin = Math.sin(rz);
            double nx = p[0] * cos - p[1] * sin;
            double ny = p[0] * sin + p[1] * cos;
            p[0] = nx;
            p[1] = ny;
        }
    }

    public static int placeVoxelGrid(
            EditSession editSession,
            Player player,
            VoxelGrid grid,
            boolean centeredAtPlayer,
            BlockVector3 explicitMinOrigin
    ) throws MaxChangedBlocksException {
        if (grid.sizeX == 0 || grid.sizeY == 0 || grid.sizeZ == 0) {
            return 0;
        }

        BlockVector3 origin;
        if (explicitMinOrigin != null) {
            origin = explicitMinOrigin;
        } else if (centeredAtPlayer) {
            BlockVector3 p = player.getBlockIn().toBlockPoint();
            origin = p.subtract(grid.sizeX / 2, 0, grid.sizeZ / 2);
        } else {
            BlockVector3 p = player.getBlockIn().toBlockPoint();
            CardinalDirectionUtil dir = CardinalDirectionUtil.getCardinalDirection(player);
            if (dir == null) {
                dir = CardinalDirectionUtil.NORTH;
            }
            BlockVector3 fwd = dir.getForward();
            int offsetDist = Math.max(grid.sizeX, grid.sizeZ) / 2 + 4;
            BlockVector3 centerGround = p.add(fwd.getBlockX() * offsetDist, 0, fwd.getBlockZ() * offsetDist);
            origin = centerGround.subtract(grid.sizeX / 2, 0, grid.sizeZ / 2);
        }

        int placed = 0;
        for (int y = 0; y < grid.sizeY; y++) {
            for (int z = 0; z < grid.sizeZ; z++) {
                for (int x = 0; x < grid.sizeX; x++) {
                    BaseBlock block = grid.blocks[x][y][z];
                    if (block == null) {
                        continue;
                    }
                    editSession.setBlock(origin.add(x, y, z), (BlockStateHolder) block);
                    placed++;
                }
            }
        }
        return placed;
    }

    // =========================================================================
    // 1. Wavefront OBJ + MTL Parser
    // =========================================================================

    public static Mesh3D parseObj(String objText, File contextDir, String baseUrl) throws IOException {
        Mesh3D mesh = new Mesh3D();
        List<double[]> positions = new ArrayList<>();
        List<double[]> vertexColors = new ArrayList<>();
        List<double[]> uvs = new ArrayList<>();
        Map<String, MaterialData> materials = new HashMap<>();
        MaterialData currentMaterial = new MaterialData("default");

        try (BufferedReader reader = new BufferedReader(new StringReader(objText))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] tokens = line.split("\\s+");
                String keyword = tokens[0].toLowerCase(Locale.ROOT);
                switch (keyword) {
                    case "mtllib": {
                        if (tokens.length >= 2) {
                            String mtlName = line.substring(line.indexOf(tokens[1])).trim();
                            loadMtlLibrary(mtlName, contextDir, baseUrl, materials, mesh);
                        }
                        break;
                    }
                    case "usemtl": {
                        if (tokens.length >= 2) {
                            String matName = tokens[1];
                            currentMaterial = materials.computeIfAbsent(matName, MaterialData::new);
                        }
                        break;
                    }
                    case "v": {
                        if (tokens.length >= 4) {
                            double x = parseDoubleSafe(tokens[1], 0);
                            double y = parseDoubleSafe(tokens[2], 0);
                            double z = parseDoubleSafe(tokens[3], 0);
                            positions.add(new double[]{x, y, z});
                            if (tokens.length >= 7) {
                                double r = parseDoubleSafe(tokens[4], 1.0);
                                double g = parseDoubleSafe(tokens[5], 1.0);
                                double b = parseDoubleSafe(tokens[6], 1.0);
                                if (r > 1.0 || g > 1.0 || b > 1.0) {
                                    r /= 255.0;
                                    g /= 255.0;
                                    b /= 255.0;
                                }
                                vertexColors.add(new double[]{r, g, b});
                                mesh.hasAnyColorOrTexture = true;
                            } else {
                                vertexColors.add(null);
                            }
                        }
                        break;
                    }
                    case "vt": {
                        if (tokens.length >= 3) {
                            double u = parseDoubleSafe(tokens[1], 0);
                            double v = parseDoubleSafe(tokens[2], 0);
                            uvs.add(new double[]{u, v});
                        }
                        break;
                    }
                    case "f": {
                        if (tokens.length >= 4) {
                            int nVerts = tokens.length - 1;
                            int[] vIdx = new int[nVerts];
                            int[] vtIdx = new int[nVerts];
                            for (int i = 0; i < nVerts; i++) {
                                String[] sub = tokens[i + 1].split("/");
                                vIdx[i] = resolveObjIndex(sub[0], positions.size());
                                vtIdx[i] = (sub.length >= 2 && !sub[1].isEmpty()) ? resolveObjIndex(sub[1], uvs.size()) : -1;
                            }
                            for (int i = 1; i + 1 < nVerts; i++) {
                                int i0 = vIdx[0], i1 = vIdx[i], i2 = vIdx[i + 1];
                                if (i0 < 0 || i0 >= positions.size() || i1 < 0 || i1 >= positions.size() || i2 < 0 || i2 >= positions.size()) {
                                    continue;
                                }
                                double[] p0 = positions.get(i0);
                                double[] p1 = positions.get(i1);
                                double[] p2 = positions.get(i2);
                                Triangle3D tri = new Triangle3D(p0[0], p0[1], p0[2], p1[0], p1[1], p1[2], p2[0], p2[1], p2[2]);
                                tri.material = currentMaterial;

                                double[] c0 = vertexColors.get(i0);
                                double[] c1 = vertexColors.get(i1);
                                double[] c2 = vertexColors.get(i2);
                                if (c0 != null && c1 != null && c2 != null) {
                                    tri.setVertexColors(c0[0], c0[1], c0[2], c1[0], c1[1], c1[2], c2[0], c2[1], c2[2]);
                                }

                                int t0 = vtIdx[0], t1 = vtIdx[i], t2 = vtIdx[i + 1];
                                if (t0 >= 0 && t0 < uvs.size() && t1 >= 0 && t1 < uvs.size() && t2 >= 0 && t2 < uvs.size()) {
                                    double[] uv0 = uvs.get(t0);
                                    double[] uv1 = uvs.get(t1);
                                    double[] uv2 = uvs.get(t2);
                                    tri.setUv(uv0[0], uv0[1], uv1[0], uv1[1], uv2[0], uv2[1]);
                                }
                                mesh.triangles.add(tri);
                            }
                        }
                        break;
                    }
                    default:
                        break;
                }
            }
        }
        return mesh;
    }

    private static int resolveObjIndex(String token, int listSize) {
        try {
            int idx = Integer.parseInt(token.trim());
            return idx < 0 ? (listSize + idx) : (idx - 1);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void loadMtlLibrary(
            String mtlName,
            File contextDir,
            String baseUrl,
            Map<String, MaterialData> materials,
            Mesh3D mesh
    ) {
        try {
            byte[] mtlBytes = null;
            if (baseUrl != null) {
                mtlBytes = ImagePixelArtConverter.downloadBytes(baseUrl + mtlName, 16 * 1024 * 1024);
            } else if (contextDir != null) {
                File mtlFile = new File(contextDir, mtlName);
                if (!mtlFile.exists()) {
                    mtlFile = new File(getModelsDirectory(), mtlName);
                }
                if (mtlFile.exists() && mtlFile.isFile()) {
                    try (InputStream in = new FileInputStream(mtlFile)) {
                        mtlBytes = ImagePixelArtConverter.readAllBytes(in, 16 * 1024 * 1024);
                    }
                }
            }
            if (mtlBytes == null) {
                return;
            }
            MaterialData active = null;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(mtlBytes), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    String[] parts = line.split("\\s+");
                    String kw = parts[0].toLowerCase(Locale.ROOT);
                    if ("newmtl".equals(kw) && parts.length >= 2) {
                        active = materials.computeIfAbsent(parts[1], MaterialData::new);
                    } else if (active != null) {
                        if ("kd".equals(kw) && parts.length >= 4) {
                            active.kdR = parseDoubleSafe(parts[1], 0.85);
                            active.kdG = parseDoubleSafe(parts[2], 0.85);
                            active.kdB = parseDoubleSafe(parts[3], 0.85);
                            active.hasExplicitColor = true;
                            mesh.hasAnyColorOrTexture = true;
                        } else if ("d".equals(kw) && parts.length >= 2) {
                            active.alpha = parseDoubleSafe(parts[1], 1.0);
                        } else if ("tr".equals(kw) && parts.length >= 2) {
                            active.alpha = 1.0 - parseDoubleSafe(parts[1], 0.0);
                        } else if ("map_kd".equals(kw) && parts.length >= 2) {
                            String texPath = parts[parts.length - 1];
                            try {
                                if (baseUrl != null) {
                                    active.texture = ImagePixelArtConverter.loadImage(baseUrl + texPath, 0, 0);
                                } else if (contextDir != null) {
                                    File texFile = new File(contextDir, texPath);
                                    if (!texFile.exists()) {
                                        texFile = ImagePixelArtConverter.resolveLocalImageFile(texPath);
                                    }
                                    if (texFile != null && texFile.exists()) {
                                        active.texture = ImagePixelArtConverter.loadImage(texFile.getAbsolutePath(), 0, 0);
                                    }
                                }
                                if (active.texture != null) {
                                    mesh.hasAnyColorOrTexture = true;
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // =========================================================================
    // 2. STL (ASCII & Binary) Parser
    // =========================================================================

    private static boolean looksLikeStl(byte[] data) {
        if (data.length >= 84) {
            int triCount = ByteBuffer.wrap(data, 80, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (triCount > 0 && triCount < 5_000_000 && 84L + (long) triCount * 50L == data.length) {
                return true;
            }
        }
        String head = new String(data, 0, Math.min(data.length, 256), StandardCharsets.US_ASCII).trim().toLowerCase(Locale.ROOT);
        return head.startsWith("solid") && head.contains("facet");
    }

    public static Mesh3D parseStl(byte[] data) throws IOException {
        if (data.length >= 84) {
            int triCount = ByteBuffer.wrap(data, 80, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (triCount > 0 && triCount < 5_000_000 && 84L + (long) triCount * 50L == data.length) {
                return parseBinaryStl(data, triCount);
            }
        }
        String text = new String(data, StandardCharsets.UTF_8);
        if (text.contains("facet") && text.contains("vertex")) {
            return parseAsciiStl(text);
        }
        if (data.length >= 84) {
            int triCount = Math.max(0, (data.length - 84) / 50);
            return parseBinaryStl(data, triCount);
        }
        throw new IOException("Invalid STL model file.");
    }

    private static Mesh3D parseBinaryStl(byte[] data, int triCount) {
        Mesh3D mesh = new Mesh3D();
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(84);
        for (int i = 0; i < triCount && buf.remaining() >= 50; i++) {
            buf.getFloat();
            buf.getFloat();
            buf.getFloat();
            double x0 = buf.getFloat(), z0 = buf.getFloat(), y0 = buf.getFloat();
            double x1 = buf.getFloat(), z1 = buf.getFloat(), y1 = buf.getFloat();
            double x2 = buf.getFloat(), z2 = buf.getFloat(), y2 = buf.getFloat();
            int attr = buf.getShort() & 0xFFFF;

            Triangle3D tri = new Triangle3D(x0, y0, z0, x1, y1, z1, x2, y2, z2);
            if ((attr & 0x8000) != 0) {
                int r = (attr & 0x1F) * 255 / 31;
                int g = ((attr >>> 5) & 0x1F) * 255 / 31;
                int b = ((attr >>> 10) & 0x1F) * 255 / 31;
                tri.setVertexColors(r / 255.0, g / 255.0, b / 255.0,
                        r / 255.0, g / 255.0, b / 255.0,
                        r / 255.0, g / 255.0, b / 255.0);
                mesh.hasAnyColorOrTexture = true;
            }
            mesh.triangles.add(tri);
        }
        return mesh;
    }

    private static Mesh3D parseAsciiStl(String text) throws IOException {
        Mesh3D mesh = new Mesh3D();
        List<double[]> verts = new ArrayList<>(3);
        try (BufferedReader br = new BufferedReader(new StringReader(text))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.toLowerCase(Locale.ROOT).startsWith("vertex")) {
                    String[] p = line.split("\\s+");
                    if (p.length >= 4) {
                        double x = parseDoubleSafe(p[1], 0);
                        double z = parseDoubleSafe(p[2], 0);
                        double y = parseDoubleSafe(p[3], 0);
                        verts.add(new double[]{x, y, z});
                        if (verts.size() == 3) {
                            double[] v0 = verts.get(0), v1 = verts.get(1), v2 = verts.get(2);
                            mesh.triangles.add(new Triangle3D(v0[0], v0[1], v0[2], v1[0], v1[1], v1[2], v2[0], v2[1], v2[2]));
                            verts.clear();
                        }
                    }
                }
            }
        }
        return mesh;
    }

    // =========================================================================
    // 3. Stanford PLY (ASCII & Binary Little/Big Endian) Parser
    // =========================================================================

    public static Mesh3D parsePly(byte[] data) throws IOException {
        Mesh3D mesh = new Mesh3D();
        int headerEnd = findPlyHeaderEnd(data);
        if (headerEnd < 0) {
            throw new IOException("Invalid PLY file: missing end_header.");
        }
        String header = new String(data, 0, headerEnd, StandardCharsets.US_ASCII);
        String format = "ascii";
        int vertexCount = 0;
        int faceCount = 0;
        List<String> vertPropNames = new ArrayList<>();
        List<String> vertPropTypes = new ArrayList<>();
        String currentElement = "";

        for (String rawLine : header.split("\\r?\\n")) {
            String line = rawLine.trim();
            String[] p = line.split("\\s+");
            if (p.length >= 2 && "format".equalsIgnoreCase(p[0])) {
                format = p[1].toLowerCase(Locale.ROOT);
            } else if (p.length >= 3 && "element".equalsIgnoreCase(p[0])) {
                currentElement = p[1].toLowerCase(Locale.ROOT);
                int count = Integer.parseInt(p[2]);
                if ("vertex".equals(currentElement)) {
                    vertexCount = count;
                } else if ("face".equals(currentElement)) {
                    faceCount = count;
                }
            } else if (p.length >= 3 && "property".equalsIgnoreCase(p[0]) && !"list".equalsIgnoreCase(p[1])) {
                if ("vertex".equals(currentElement)) {
                    vertPropTypes.add(p[1].toLowerCase(Locale.ROOT));
                    vertPropNames.add(p[2].toLowerCase(Locale.ROOT));
                }
            }
        }

        int idxX = vertPropNames.indexOf("x");
        int idxY = vertPropNames.indexOf("y");
        int idxZ = vertPropNames.indexOf("z");
        int idxR = vertPropNames.indexOf("red");
        if (idxR < 0) idxR = vertPropNames.indexOf("r");
        int idxG = vertPropNames.indexOf("green");
        if (idxG < 0) idxG = vertPropNames.indexOf("g");
        int idxB = vertPropNames.indexOf("blue");
        if (idxB < 0) idxB = vertPropNames.indexOf("b");

        boolean hasColor = (idxR >= 0 && idxG >= 0 && idxB >= 0);
        if (hasColor) {
            mesh.hasAnyColorOrTexture = true;
        }

        double[][] verts = new double[vertexCount][3];
        double[][] colors = hasColor ? new double[vertexCount][3] : null;

        if ("ascii".equals(format)) {
            BufferedReader br = new BufferedReader(new InputStreamReader(
                    new ByteArrayInputStream(data, headerEnd, data.length - headerEnd), StandardCharsets.UTF_8));
            for (int i = 0; i < vertexCount; i++) {
                String line = readNonEmptyLine(br);
                if (line == null) break;
                String[] tok = line.split("\\s+");
                verts[i][0] = idxX >= 0 && idxX < tok.length ? parseDoubleSafe(tok[idxX], 0) : 0;
                verts[i][1] = idxY >= 0 && idxY < tok.length ? parseDoubleSafe(tok[idxY], 0) : 0;
                verts[i][2] = idxZ >= 0 && idxZ < tok.length ? parseDoubleSafe(tok[idxZ], 0) : 0;
                if (hasColor && colors != null) {
                    double r = idxR < tok.length ? parseDoubleSafe(tok[idxR], 200) : 200;
                    double g = idxG < tok.length ? parseDoubleSafe(tok[idxG], 200) : 200;
                    double b = idxB < tok.length ? parseDoubleSafe(tok[idxB], 200) : 200;
                    if (r > 1.0 || g > 1.0 || b > 1.0) {
                        r /= 255.0; g /= 255.0; b /= 255.0;
                    }
                    colors[i][0] = r; colors[i][1] = g; colors[i][2] = b;
                }
            }
            for (int f = 0; f < faceCount; f++) {
                String line = readNonEmptyLine(br);
                if (line == null) break;
                String[] tok = line.split("\\s+");
                if (tok.length >= 4) {
                    int n = Integer.parseInt(tok[0]);
                    for (int k = 1; k + 1 < n && k + 2 < tok.length; k++) {
                        int i0 = Integer.parseInt(tok[1]);
                        int i1 = Integer.parseInt(tok[k + 1]);
                        int i2 = Integer.parseInt(tok[k + 2]);
                        addPlyTriangle(mesh, verts, colors, i0, i1, i2);
                    }
                }
            }
        } else {
            ByteOrder order = format.contains("big") ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
            ByteBuffer buf = ByteBuffer.wrap(data, headerEnd, data.length - headerEnd).order(order);
            for (int i = 0; i < vertexCount && buf.hasRemaining(); i++) {
                for (int p = 0; p < vertPropTypes.size(); p++) {
                    double val = readPlyProperty(buf, vertPropTypes.get(p));
                    if (p == idxX) verts[i][0] = val;
                    else if (p == idxY) verts[i][1] = val;
                    else if (p == idxZ) verts[i][2] = val;
                    else if (hasColor && colors != null) {
                        boolean isByte = vertPropTypes.get(p).contains("char") || vertPropTypes.get(p).contains("uint8");
                        double norm = isByte ? (val / 255.0) : val;
                        if (p == idxR) colors[i][0] = norm;
                        else if (p == idxG) colors[i][1] = norm;
                        else if (p == idxB) colors[i][2] = norm;
                    }
                }
            }
            for (int f = 0; f < faceCount && buf.hasRemaining(); f++) {
                int n = buf.get() & 0xFF;
                int[] idx = new int[n];
                for (int k = 0; k < n && buf.remaining() >= 4; k++) {
                    idx[k] = buf.getInt();
                }
                for (int k = 1; k + 1 < n; k++) {
                    addPlyTriangle(mesh, verts, colors, idx[0], idx[k], idx[k + 1]);
                }
            }
        }
        return mesh;
    }

    private static void addPlyTriangle(Mesh3D mesh, double[][] verts, double[][] colors, int i0, int i1, int i2) {
        if (i0 < 0 || i0 >= verts.length || i1 < 0 || i1 >= verts.length || i2 < 0 || i2 >= verts.length) {
            return;
        }
        Triangle3D tri = new Triangle3D(
                verts[i0][0], verts[i0][1], verts[i0][2],
                verts[i1][0], verts[i1][1], verts[i1][2],
                verts[i2][0], verts[i2][1], verts[i2][2]
        );
        if (colors != null) {
            tri.setVertexColors(
                    colors[i0][0], colors[i0][1], colors[i0][2],
                    colors[i1][0], colors[i1][1], colors[i1][2],
                    colors[i2][0], colors[i2][1], colors[i2][2]
            );
        }
        mesh.triangles.add(tri);
    }

    private static double readPlyProperty(ByteBuffer buf, String type) {
        switch (type) {
            case "char":
            case "int8":
                return buf.get();
            case "uchar":
            case "uint8":
                return buf.get() & 0xFF;
            case "short":
            case "int16":
                return buf.getShort();
            case "ushort":
            case "uint16":
                return buf.getShort() & 0xFFFF;
            case "int":
            case "int32":
                return buf.getInt();
            case "uint":
            case "uint32":
                return buf.getInt() & 0xFFFFFFFFL;
            case "double":
            case "float64":
                return buf.getDouble();
            case "float":
            case "float32":
            default:
                return buf.getFloat();
        }
    }

    private static int findPlyHeaderEnd(byte[] data) {
        byte[] needle = "end_header".getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            int pos = i + needle.length;
            if (pos < data.length && data[pos] == '\r') pos++;
            if (pos < data.length && data[pos] == '\n') pos++;
            return pos;
        }
        return -1;
    }

    // =========================================================================
    // 4. glTF 2.0 (.gltf & .glb) Parser
    // =========================================================================

    private static boolean looksLikeJsonGltf(byte[] data) {
        int len = Math.min(data.length, 512);
        String head = new String(data, 0, len, StandardCharsets.UTF_8).trim();
        return head.startsWith("{") && head.contains("\"asset\"") && head.contains("\"meshes\"");
    }

    public static Mesh3D parseGlb(byte[] data, File contextDir, String baseUrl) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int magic = buf.getInt();
        if (magic != 0x46546C67) {
            throw new IOException("Invalid GLB magic header.");
        }
        buf.getInt();
        buf.getInt();

        String jsonStr = null;
        byte[] binChunk = null;
        while (buf.remaining() >= 8) {
            int chunkLen = buf.getInt();
            int chunkType = buf.getInt();
            if (chunkLen < 0 || chunkLen > buf.remaining()) {
                break;
            }
            byte[] chunkBytes = new byte[chunkLen];
            buf.get(chunkBytes);
            if (chunkType == 0x4E4F534A) {
                jsonStr = new String(chunkBytes, StandardCharsets.UTF_8);
            } else if (chunkType == 0x004E4942) {
                binChunk = chunkBytes;
            }
        }
        if (jsonStr == null) {
            throw new IOException("GLB file missing JSON chunk.");
        }
        return parseGltfJson(jsonStr, binChunk, contextDir, baseUrl);
    }

    public static Mesh3D parseGltfJson(String jsonStr, byte[] glbBinChunk, File contextDir, String baseUrl) throws IOException {
        Mesh3D mesh = new Mesh3D();
        JsonObject root = JsonParser.parseString(jsonStr).getAsJsonObject();

        List<byte[]> buffers = new ArrayList<>();
        if (root.has("buffers")) {
            JsonArray bufArr = root.getAsJsonArray("buffers");
            for (int i = 0; i < bufArr.size(); i++) {
                JsonObject bObj = bufArr.get(i).getAsJsonObject();
                if (!bObj.has("uri") && glbBinChunk != null) {
                    buffers.add(glbBinChunk);
                } else if (bObj.has("uri")) {
                    String uri = bObj.get("uri").getAsString();
                    if (uri.startsWith("data:")) {
                        int comma = uri.indexOf(',');
                        buffers.add(Base64.getDecoder().decode(uri.substring(comma + 1).trim()));
                    } else if (baseUrl != null) {
                        buffers.add(ImagePixelArtConverter.downloadBytes(baseUrl + uri, 64 * 1024 * 1024));
                    } else if (contextDir != null) {
                        File f = new File(contextDir, uri);
                        try (InputStream in = new FileInputStream(f)) {
                            buffers.add(ImagePixelArtConverter.readAllBytes(in, 64 * 1024 * 1024));
                        }
                    }
                }
            }
        } else if (glbBinChunk != null) {
            buffers.add(glbBinChunk);
        }

        JsonArray bufferViews = root.has("bufferViews") ? root.getAsJsonArray("bufferViews") : new JsonArray();
        JsonArray accessors = root.has("accessors") ? root.getAsJsonArray("accessors") : new JsonArray();
        JsonArray images = root.has("images") ? root.getAsJsonArray("images") : new JsonArray();
        JsonArray textures = root.has("textures") ? root.getAsJsonArray("textures") : new JsonArray();

        List<MaterialData> materials = new ArrayList<>();
        if (root.has("materials")) {
            JsonArray matArr = root.getAsJsonArray("materials");
            for (int i = 0; i < matArr.size(); i++) {
                JsonObject mObj = matArr.get(i).getAsJsonObject();
                MaterialData mat = new MaterialData("gltf_mat_" + i);
                if (mObj.has("pbrMetallicRoughness")) {
                    JsonObject pbr = mObj.getAsJsonObject("pbrMetallicRoughness");
                    if (pbr.has("baseColorFactor")) {
                        JsonArray f = pbr.getAsJsonArray("baseColorFactor");
                        mat.kdR = f.get(0).getAsDouble();
                        mat.kdG = f.get(1).getAsDouble();
                        mat.kdB = f.get(2).getAsDouble();
                        if (f.size() >= 4) mat.alpha = f.get(3).getAsDouble();
                        mat.hasExplicitColor = true;
                        mesh.hasAnyColorOrTexture = true;
                    }
                    if (pbr.has("baseColorTexture")) {
                        int texIdx = pbr.getAsJsonObject("baseColorTexture").get("index").getAsInt();
                        if (texIdx >= 0 && texIdx < textures.size()) {
                            JsonObject tObj = textures.get(texIdx).getAsJsonObject();
                            if (tObj.has("source")) {
                                int imgIdx = tObj.get("source").getAsInt();
                                mat.texture = loadGltfImage(imgIdx, images, bufferViews, buffers, contextDir, baseUrl);
                                if (mat.texture != null) {
                                    mesh.hasAnyColorOrTexture = true;
                                }
                            }
                        }
                    }
                }
                materials.add(mat);
            }
        }

        if (root.has("meshes")) {
            JsonArray meshesArr = root.getAsJsonArray("meshes");
            for (int m = 0; m < meshesArr.size(); m++) {
                JsonObject meshObj = meshesArr.get(m).getAsJsonObject();
                if (!meshObj.has("primitives")) continue;
                JsonArray prims = meshObj.getAsJsonArray("primitives");
                for (int p = 0; p < prims.size(); p++) {
                    JsonObject prim = prims.get(p).getAsJsonObject();
                    JsonObject attrs = prim.getAsJsonObject("attributes");
                    if (attrs == null || !attrs.has("POSITION")) continue;

                    double[][] pos = readGltfVecAccessor(attrs.get("POSITION").getAsInt(), 3, accessors, bufferViews, buffers);
                    double[][] uv = attrs.has("TEXCOORD_0")
                            ? readGltfVecAccessor(attrs.get("TEXCOORD_0").getAsInt(), 2, accessors, bufferViews, buffers) : null;
                    double[][] col = attrs.has("COLOR_0")
                            ? readGltfVecAccessor(attrs.get("COLOR_0").getAsInt(), 3, accessors, bufferViews, buffers) : null;
                    if (col != null) {
                        mesh.hasAnyColorOrTexture = true;
                    }

                    int[] indices;
                    if (prim.has("indices")) {
                        indices = readGltfScalarAccessor(prim.get("indices").getAsInt(), accessors, bufferViews, buffers);
                    } else {
                        indices = new int[pos.length];
                        for (int k = 0; k < pos.length; k++) indices[k] = k;
                    }

                    MaterialData mat = null;
                    if (prim.has("material")) {
                        int matIdx = prim.get("material").getAsInt();
                        if (matIdx >= 0 && matIdx < materials.size()) {
                            mat = materials.get(matIdx);
                        }
                    }

                    for (int i = 0; i + 2 < indices.length; i += 3) {
                        int i0 = indices[i], i1 = indices[i + 1], i2 = indices[i + 2];
                        if (i0 < 0 || i0 >= pos.length || i1 < 0 || i1 >= pos.length || i2 < 0 || i2 >= pos.length) {
                            continue;
                        }
                        Triangle3D tri = new Triangle3D(
                                pos[i0][0], pos[i0][1], pos[i0][2],
                                pos[i1][0], pos[i1][1], pos[i1][2],
                                pos[i2][0], pos[i2][1], pos[i2][2]
                        );
                        tri.material = mat;
                        if (uv != null && i0 < uv.length && i1 < uv.length && i2 < uv.length) {
                            tri.setUv(uv[i0][0], 1.0 - uv[i0][1], uv[i1][0], 1.0 - uv[i1][1], uv[i2][0], 1.0 - uv[i2][1]);
                        }
                        if (col != null && i0 < col.length && i1 < col.length && i2 < col.length) {
                            tri.setVertexColors(
                                    col[i0][0], col[i0][1], col[i0][2],
                                    col[i1][0], col[i1][1], col[i1][2],
                                    col[i2][0], col[i2][1], col[i2][2]
                            );
                        }
                        mesh.triangles.add(tri);
                    }
                }
            }
        }
        return mesh;
    }

    private static BufferedImage loadGltfImage(
            int imgIdx,
            JsonArray images,
            JsonArray bufferViews,
            List<byte[]> buffers,
            File contextDir,
            String baseUrl
    ) {
        try {
            if (imgIdx < 0 || imgIdx >= images.size()) return null;
            JsonObject imgObj = images.get(imgIdx).getAsJsonObject();
            if (imgObj.has("bufferView")) {
                int bvIdx = imgObj.get("bufferView").getAsInt();
                JsonObject bv = bufferViews.get(bvIdx).getAsJsonObject();
                int bufIdx = bv.get("buffer").getAsInt();
                int byteOffset = bv.has("byteOffset") ? bv.get("byteOffset").getAsInt() : 0;
                int byteLength = bv.get("byteLength").getAsInt();
                byte[] slice = new byte[byteLength];
                System.arraycopy(buffers.get(bufIdx), byteOffset, slice, 0, byteLength);
                return ImagePixelArtConverter.decodeImageBytes(slice, "gltf_tex.png", 0, 0);
            } else if (imgObj.has("uri")) {
                String uri = imgObj.get("uri").getAsString();
                if (uri.startsWith("data:")) {
                    return ImagePixelArtConverter.loadImage(uri, 0, 0);
                } else if (baseUrl != null) {
                    return ImagePixelArtConverter.loadImage(baseUrl + uri, 0, 0);
                } else if (contextDir != null) {
                    return ImagePixelArtConverter.loadImage(new File(contextDir, uri).getAbsolutePath(), 0, 0);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static double[][] readGltfVecAccessor(
            int accIdx,
            int numComponents,
            JsonArray accessors,
            JsonArray bufferViews,
            List<byte[]> buffers
    ) {
        JsonObject acc = accessors.get(accIdx).getAsJsonObject();
        int count = acc.get("count").getAsInt();
        int componentType = acc.get("componentType").getAsInt();
        int bvIdx = acc.get("bufferView").getAsInt();
        int accOffset = acc.has("byteOffset") ? acc.get("byteOffset").getAsInt() : 0;

        JsonObject bv = bufferViews.get(bvIdx).getAsJsonObject();
        int bufIdx = bv.get("buffer").getAsInt();
        int bvOffset = bv.has("byteOffset") ? bv.get("byteOffset").getAsInt() : 0;
        int stride = bv.has("byteStride") ? bv.get("byteStride").getAsInt() : 0;

        String typeStr = acc.has("type") ? acc.get("type").getAsString() : "VEC3";
        int actualComponents = "VEC2".equals(typeStr) ? 2 : ("VEC4".equals(typeStr) ? 4 : 3);
        int compBytes = (componentType == 5120 || componentType == 5121) ? 1
                : ((componentType == 5122 || componentType == 5123) ? 2 : 4);
        if (stride == 0) {
            stride = actualComponents * compBytes;
        }

        byte[] raw = buffers.get(bufIdx);
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        double[][] out = new double[count][numComponents];
        int base = bvOffset + accOffset;

        for (int i = 0; i < count; i++) {
            int pos = base + i * stride;
            for (int c = 0; c < numComponents && c < actualComponents; c++) {
                int cPos = pos + c * compBytes;
                if (componentType == 5126) {
                    out[i][c] = buf.getFloat(cPos);
                } else if (componentType == 5121) {
                    out[i][c] = (raw[cPos] & 0xFF) / 255.0;
                } else if (componentType == 5123) {
                    out[i][c] = (buf.getShort(cPos) & 0xFFFF) / 65535.0;
                }
            }
        }
        return out;
    }

    private static int[] readGltfScalarAccessor(
            int accIdx,
            JsonArray accessors,
            JsonArray bufferViews,
            List<byte[]> buffers
    ) {
        JsonObject acc = accessors.get(accIdx).getAsJsonObject();
        int count = acc.get("count").getAsInt();
        int componentType = acc.get("componentType").getAsInt();
        int bvIdx = acc.get("bufferView").getAsInt();
        int accOffset = acc.has("byteOffset") ? acc.get("byteOffset").getAsInt() : 0;

        JsonObject bv = bufferViews.get(bvIdx).getAsJsonObject();
        int bufIdx = bv.get("buffer").getAsInt();
        int bvOffset = bv.has("byteOffset") ? bv.get("byteOffset").getAsInt() : 0;
        byte[] raw = buffers.get(bufIdx);
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int[] out = new int[count];
        int base = bvOffset + accOffset;

        for (int i = 0; i < count; i++) {
            if (componentType == 5121) {
                out[i] = raw[base + i] & 0xFF;
            } else if (componentType == 5123) {
                out[i] = buf.getShort(base + i * 2) & 0xFFFF;
            } else {
                out[i] = buf.getInt(base + i * 4);
            }
        }
        return out;
    }

    // =========================================================================
    // 5. MagicaVoxel (.vox) Parser
    // =========================================================================

    public static Mesh3D parseVox(byte[] data) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (data.length < 8 || data[0] != 'V' || data[1] != 'O' || data[2] != 'X' || data[3] != ' ') {
            throw new IOException("Invalid MagicaVoxel .vox header.");
        }
        buf.position(8);

        int sizeX = 0, sizeY = 0, sizeZ = 0;
        List<int[]> rawVoxels = new ArrayList<>();
        int[] palette = getDefaultVoxPalette();

        while (buf.remaining() >= 12) {
            byte[] idBytes = new byte[4];
            buf.get(idBytes);
            String chunkId = new String(idBytes, StandardCharsets.US_ASCII);
            int contentBytes = buf.getInt();
            int childrenBytes = buf.getInt();
            int contentStart = buf.position();

            if ("SIZE".equals(chunkId) && contentBytes >= 12) {
                sizeX = buf.getInt();
                sizeZ = buf.getInt();
                sizeY = buf.getInt();
            } else if ("XYZI".equals(chunkId) && contentBytes >= 4) {
                int numVoxels = buf.getInt();
                for (int i = 0; i < numVoxels && buf.remaining() >= 4; i++) {
                    int x = buf.get() & 0xFF;
                    int z = buf.get() & 0xFF;
                    int y = buf.get() & 0xFF;
                    int colorIdx = buf.get() & 0xFF;
                    rawVoxels.add(new int[]{x, y, z, colorIdx});
                }
            } else if ("RGBA".equals(chunkId) && contentBytes >= 1024) {
                palette = new int[256];
                for (int i = 1; i <= 255 && buf.remaining() >= 4; i++) {
                    int r = buf.get() & 0xFF;
                    int g = buf.get() & 0xFF;
                    int b = buf.get() & 0xFF;
                    int a = buf.get() & 0xFF;
                    palette[i] = (a << 24) | (r << 16) | (g << 8) | b;
                }
            }

            int nextPos = contentStart + contentBytes;
            if (!"MAIN".equals(chunkId)) {
                nextPos += childrenBytes;
            }
            if (nextPos > buf.limit() || nextPos < contentStart) {
                break;
            }
            buf.position(nextPos);
        }

        Mesh3D mesh = new Mesh3D();
        mesh.hasAnyColorOrTexture = true;
        if (sizeX > 0 && sizeY > 0 && sizeZ > 0) {
            mesh.directSizeX = sizeX;
            mesh.directSizeY = sizeY;
            mesh.directSizeZ = sizeZ;
            mesh.directVoxels = new int[sizeX][sizeY][sizeZ];
            for (int[] v : rawVoxels) {
                int vx = v[0], vy = v[1], vz = v[2], cIdx = v[3];
                if (vx < sizeX && vy < sizeY && vz < sizeZ) {
                    mesh.directVoxels[vx][vy][vz] = palette[cIdx & 0xFF];
                }
            }
        }
        return mesh;
    }

    private static int[] getDefaultVoxPalette() {
        int[] pal = new int[256];
        int idx = 1;
        for (int r = 0; r < 6; r++) {
            for (int g = 0; g < 6; g++) {
                for (int b = 0; b < 6 && idx < 256; b++) {
                    pal[idx++] = (0xFF << 24) | ((255 - r * 51) << 16) | ((255 - g * 51) << 8) | (255 - b * 51);
                }
            }
        }
        while (idx < 256) {
            int v = (255 - (idx - 216) * 6) & 0xFF;
            pal[idx++] = (0xFF << 24) | (v << 16) | (v << 8) | v;
        }
        return pal;
    }

    // =========================================================================
    // 6. OFF (Object File Format) Parser
    // =========================================================================

    public static Mesh3D parseOff(String text) throws IOException {
        Mesh3D mesh = new Mesh3D();
        try (BufferedReader br = new BufferedReader(new StringReader(text))) {
            String first = readNonEmptyLine(br);
            if (first == null) return mesh;
            String countsLine = first.replaceFirst("(?i)^C?OFF\\s*", "").trim();
            if (countsLine.isEmpty()) {
                countsLine = readNonEmptyLine(br);
            }
            if (countsLine == null) return mesh;
            String[] counts = countsLine.split("\\s+");
            int nVerts = Integer.parseInt(counts[0]);
            int nFaces = Integer.parseInt(counts[1]);

            double[][] verts = new double[nVerts][3];
            double[][] cols = new double[nVerts][3];
            boolean hasVertCols = false;

            for (int i = 0; i < nVerts; i++) {
                String line = readNonEmptyLine(br);
                if (line == null) break;
                String[] p = line.split("\\s+");
                verts[i][0] = parseDoubleSafe(p[0], 0);
                verts[i][1] = parseDoubleSafe(p[1], 0);
                verts[i][2] = parseDoubleSafe(p[2], 0);
                if (p.length >= 6) {
                    double r = parseDoubleSafe(p[3], 1.0);
                    double g = parseDoubleSafe(p[4], 1.0);
                    double b = parseDoubleSafe(p[5], 1.0);
                    if (r > 1.0 || g > 1.0 || b > 1.0) {
                        r /= 255.0; g /= 255.0; b /= 255.0;
                    }
                    cols[i][0] = r; cols[i][1] = g; cols[i][2] = b;
                    hasVertCols = true;
                    mesh.hasAnyColorOrTexture = true;
                }
            }

            for (int f = 0; f < nFaces; f++) {
                String line = readNonEmptyLine(br);
                if (line == null) break;
                String[] p = line.split("\\s+");
                int n = Integer.parseInt(p[0]);
                for (int k = 1; k + 1 < n && k + 2 < p.length; k++) {
                    int i0 = Integer.parseInt(p[1]);
                    int i1 = Integer.parseInt(p[k + 1]);
                    int i2 = Integer.parseInt(p[k + 2]);
                    addPlyTriangle(mesh, verts, hasVertCols ? cols : null, i0, i1, i2);
                }
            }
        }
        return mesh;
    }

    private static String readNonEmptyLine(BufferedReader br) throws IOException {
        String line;
        while ((line = br.readLine()) != null) {
            int hash = line.indexOf('#');
            if (hash >= 0) {
                line = line.substring(0, hash);
            }
            line = line.trim();
            if (!line.isEmpty()) {
                return line;
            }
        }
        return null;
    }

    private static double parseDoubleSafe(String s, double def) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int clampByte(double v) {
        int i = (int) Math.round(v);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }
}

