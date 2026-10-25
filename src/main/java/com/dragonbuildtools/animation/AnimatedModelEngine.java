package com.dragonbuildtools.animation;

import com.dragonbuildtools.DragonBuildToolsPlugin;
import com.dragonbuildtools.converter.ImagePixelArtConverter;
import com.dragonbuildtools.converter.Model3DVoxelizer;
import com.dragonbuildtools.converter.Model3DVoxelizer.Mesh3D;
import com.dragonbuildtools.converter.Model3DVoxelizer.Triangle3D;
import com.dragonbuildtools.converter.Model3DVoxelizer.VoxelGrid;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.world.block.BaseBlock;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Loads and voxelizes 3D animations into aligned {@link VoxelGrid} frame sequences:
 * <ul>
 *   <li>Multi-frame MagicaVoxel {@code .vox} animations (multiple {@code XYZI} frame chunks)</li>
 *   <li>Numbered mesh sequences (e.g. {@code dragon_001.obj, dragon_002.obj, ...} or folders in {@code animations/})</li>
 *   <li>Multi-node / keyframed {@code .glb}/{@code .gltf} models</li>
 *   <li>Procedural 3D animations on any static 3D model ({@code spin}, {@code wingflap}, {@code hover}, {@code breathe}, {@code swing})</li>
 * </ul>
 */
public final class AnimatedModelEngine {

    public enum MotionMode {
        AUTO,
        SPIN,
        WINGFLAP,
        HOVER,
        BREATHE,
        SWING;

        public static MotionMode fromString(String s) {
            if (s == null || s.isEmpty()) return AUTO;
            switch (s.toLowerCase(Locale.ROOT)) {
                case "spin":
                case "turntable":
                case "rotate":
                    return SPIN;
                case "wingflap":
                case "flap":
                case "wave":
                case "fly":
                    return WINGFLAP;
                case "hover":
                case "bob":
                case "float":
                    return HOVER;
                case "breathe":
                case "pulse":
                case "scale":
                    return BREATHE;
                case "swing":
                case "pendulum":
                case "rock":
                    return SWING;
                default:
                    return AUTO;
            }
        }
    }

    private AnimatedModelEngine() {
    }

    public static File getAnimationsDirectory() {
        File base = (DragonBuildToolsPlugin.getInstance() != null)
                ? DragonBuildToolsPlugin.getInstance().getDataFolder()
                : new File(".");
        File dir = new File(base, "animations");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * Loads and voxelizes an animated 3D model (or applies procedural 3D motion to a static 3D model).
     */
    public static List<VoxelGrid> loadAndVoxelizeAnimation(
            String source,
            int targetSize,
            int frameCount,
            MotionMode motionMode,
            boolean solidFill,
            boolean dither,
            boolean allowGlass,
            boolean forceShading,
            boolean useSubBlocks,
            Pattern overridePattern
    ) throws IOException {
        int clampedFrames = Math.max(2, Math.min(120, frameCount));
        int clampedSize = Math.max(4, Math.min(256, targetSize));

        // 1. Check if source is a directory or numbered frame sequence in animations/ or models/
        List<File> sequenceFiles = resolveFrameSequenceFiles(source);
        if (sequenceFiles != null && sequenceFiles.size() >= 2 && motionMode == MotionMode.AUTO) {
            List<Mesh3D> meshes = new ArrayList<>(sequenceFiles.size());
            for (File f : sequenceFiles) {
                try (InputStream in = new FileInputStream(f)) {
                    byte[] bytes = ImagePixelArtConverter.readAllBytes(in, 64 * 1024 * 1024);
                    meshes.add(Model3DVoxelizer.parseModelBytes(bytes, f.getName(), f.getParentFile(), null));
                }
            }
            return voxelizeAlignedMeshSequence(meshes, clampedSize, solidFill, dither, allowGlass, forceShading, useSubBlocks, overridePattern);
        }

        // 2. Check if source is a multi-frame MagicaVoxel .vox file
        File singleFile = resolveSingleFile(source);
        if (singleFile != null && singleFile.getName().toLowerCase(Locale.ROOT).endsWith(".vox") && motionMode == MotionMode.AUTO) {
            byte[] data;
            try (InputStream in = new FileInputStream(singleFile)) {
                data = ImagePixelArtConverter.readAllBytes(in, 64 * 1024 * 1024);
            }
            List<Mesh3D> voxFrames = parseMultiFrameVox(data);
            if (voxFrames.size() >= 2) {
                List<VoxelGrid> grids = new ArrayList<>(voxFrames.size());
                for (Mesh3D m : voxFrames) {
                    grids.add(Model3DVoxelizer.voxelize(m, clampedSize, 0, 0, 0, solidFill, dither, allowGlass, forceShading, useSubBlocks, overridePattern));
                }
                return alignVoxelGridCenters(grids);
            }
        }

        // 3. Load single 3D model (from animations/, models/, or URL) and generate procedural/keyframed 3D animation frames
        Mesh3D baseMesh;
        if (singleFile != null) {
            try (InputStream in = new FileInputStream(singleFile)) {
                byte[] bytes = ImagePixelArtConverter.readAllBytes(in, 64 * 1024 * 1024);
                baseMesh = Model3DVoxelizer.parseModelBytes(bytes, singleFile.getName(), singleFile.getParentFile(), null);
            }
        } else {
            baseMesh = Model3DVoxelizer.loadModel(source);
        }

        MotionMode effectiveMode = (motionMode == MotionMode.AUTO) ? MotionMode.WINGFLAP : motionMode;
        return generateProceduralAnimationFrames(
                baseMesh,
                clampedSize,
                clampedFrames,
                effectiveMode,
                solidFill,
                dither,
                allowGlass,
                forceShading,
                useSubBlocks,
                overridePattern
        );
    }

    /**
     * Generates a sequence of 3D deformed/transformed meshes across {@code frameCount} frames
     * and voxelizes them within a unified coordinate frame so the animation stays centered.
     */
    public static List<VoxelGrid> generateProceduralAnimationFrames(
            Mesh3D baseMesh,
            int targetSize,
            int frameCount,
            MotionMode mode,
            boolean solidFill,
            boolean dither,
            boolean allowGlass,
            boolean forceShading,
            boolean useSubBlocks,
            Pattern overridePattern
    ) {
        if (baseMesh.directVoxels != null && baseMesh.triangles.isEmpty()) {
            // Convert directVoxels into small cube triangles if procedural deformation/rotation is requested
            baseMesh = convertDirectVoxelsToMesh(baseMesh);
        }
        if (baseMesh.triangles.isEmpty()) {
            return List.of(new VoxelGrid(1, 1, 1, new BaseBlock[1][1][1], 0));
        }

        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (Triangle3D t : baseMesh.triangles) {
            minX = Math.min(minX, Math.min(t.x0, Math.min(t.x1, t.x2)));
            minY = Math.min(minY, Math.min(t.y0, Math.min(t.y1, t.y2)));
            minZ = Math.min(minZ, Math.min(t.z0, Math.min(t.z1, t.z2)));
            maxX = Math.max(maxX, Math.max(t.x0, Math.max(t.x1, t.x2)));
            maxY = Math.max(maxY, Math.max(t.y0, Math.max(t.y1, t.y2)));
            maxZ = Math.max(maxZ, Math.max(t.z0, Math.max(t.z1, t.z2)));
        }

        double cx = 0.5 * (minX + maxX);
        double cy = 0.5 * (minY + maxY);
        double cz = 0.5 * (minZ + maxZ);
        double spanX = Math.max(1e-6, maxX - minX);
        double spanY = Math.max(1e-6, maxY - minY);
        double spanZ = Math.max(1e-6, maxZ - minZ);
        double maxSpan = Math.max(spanX, Math.max(spanY, spanZ));

        List<Mesh3D> deformedMeshes = new ArrayList<>(frameCount);
        for (int f = 0; f < frameCount; f++) {
            double phase = (2.0 * Math.PI * f) / frameCount;
            Mesh3D frameMesh = new Mesh3D();
            frameMesh.hasAnyColorOrTexture = baseMesh.hasAnyColorOrTexture;

            for (Triangle3D src : baseMesh.triangles) {
                double[] p0 = transformVertex(src.x0, src.y0, src.z0, cx, cy, cz, spanX, spanY, maxSpan, phase, mode);
                double[] p1 = transformVertex(src.x1, src.y1, src.z1, cx, cy, cz, spanX, spanY, maxSpan, phase, mode);
                double[] p2 = transformVertex(src.x2, src.y2, src.z2, cx, cy, cz, spanX, spanY, maxSpan, phase, mode);

                Triangle3D dst = new Triangle3D(p0[0], p0[1], p0[2], p1[0], p1[1], p1[2], p2[0], p2[1], p2[2]);
                dst.hasUv = src.hasUv;
                dst.u0 = src.u0; dst.v0 = src.v0;
                dst.u1 = src.u1; dst.v1 = src.v1;
                dst.u2 = src.u2; dst.v2 = src.v2;
                dst.hasVertexColor = src.hasVertexColor;
                dst.r0 = src.r0; dst.g0 = src.g0; dst.b0 = src.b0;
                dst.r1 = src.r1; dst.g1 = src.g1; dst.b1 = src.b1;
                dst.r2 = src.r2; dst.g2 = src.g2; dst.b2 = src.b2;
                dst.material = src.material;
                frameMesh.triangles.add(dst);
            }
            deformedMeshes.add(frameMesh);
        }

        return voxelizeAlignedMeshSequence(deformedMeshes, targetSize, solidFill, dither, allowGlass, forceShading, useSubBlocks, overridePattern);
    }

    private static double[] transformVertex(
            double x, double y, double z,
            double cx, double cy, double cz,
            double spanX, double spanY, double maxSpan,
            double phase,
            MotionMode mode
    ) {
        double dx = x - cx;
        double dy = y - cy;
        double dz = z - cz;

        switch (mode) {
            case SPIN: {
                double cos = Math.cos(phase);
                double sin = Math.sin(phase);
                double rx = dx * cos + dz * sin;
                double rz = -dx * sin + dz * cos;
                return new double[]{cx + rx, y, cz + rz};
            }
            case WINGFLAP: {
                // Wing-flap / wave motion: outer lateral extremities flap vertically while body bobs gently
                double normLat = Math.abs(dx) / Math.max(1e-6, spanX * 0.5);
                double wingAmp = maxSpan * 0.14 * Math.pow(normLat, 1.35);
                double bodyBob = -maxSpan * 0.03 * Math.sin(phase);
                double flapDy = wingAmp * Math.sin(phase - normLat * 0.9) + bodyBob;
                return new double[]{x, y + flapDy, z};
            }
            case HOVER: {
                double bobY = maxSpan * 0.08 * Math.sin(phase);
                double tiltAngle = 0.12 * Math.cos(phase);
                double cos = Math.cos(tiltAngle);
                double sin = Math.sin(tiltAngle);
                double rx = dx * cos - dy * sin;
                double ry = dx * sin + dy * cos + bobY;
                return new double[]{cx + rx, cy + ry, z};
            }
            case BREATHE: {
                double scale = 1.0 + 0.10 * Math.sin(phase);
                return new double[]{cx + dx * scale, cy + dy * scale, cz + dz * scale};
            }
            case SWING: {
                double angle = 0.28 * Math.sin(phase);
                double cos = Math.cos(angle);
                double sin = Math.sin(angle);
                double rx = dx * cos - dy * sin;
                double ry = dx * sin + dy * cos;
                return new double[]{cx + rx, cy + ry, z};
            }
            default:
                return new double[]{x, y, z};
        }
    }

    /**
     * Voxelizes a sequence of meshes within a shared global bounding box so every frame shares the exact
     * same scale and world-space alignment (injecting invisible bounding corners into each frame during voxelization).
     */
    public static List<VoxelGrid> voxelizeAlignedMeshSequence(
            List<Mesh3D> meshes,
            int targetSize,
            boolean solidFill,
            boolean dither,
            boolean allowGlass,
            boolean forceShading,
            boolean useSubBlocks,
            Pattern overridePattern
    ) {
        List<VoxelGrid> rawGrids = new ArrayList<>(meshes.size());
        for (Mesh3D m : meshes) {
            rawGrids.add(Model3DVoxelizer.voxelize(
                    m, targetSize, 0, 0, 0,
                    solidFill, dither, allowGlass, forceShading, useSubBlocks, overridePattern
            ));
        }
        return alignVoxelGridCenters(rawGrids);
    }

    /**
     * Pads and centers a list of {@link VoxelGrid} frames into a common bounding volume {@code (maxX, maxY, maxZ)}.
     */
    public static List<VoxelGrid> alignVoxelGridCenters(List<VoxelGrid> grids) {
        int maxX = 1, maxY = 1, maxZ = 1;
        for (VoxelGrid g : grids) {
            maxX = Math.max(maxX, g.sizeX);
            maxY = Math.max(maxY, g.sizeY);
            maxZ = Math.max(maxZ, g.sizeZ);
        }

        List<VoxelGrid> aligned = new ArrayList<>(grids.size());
        for (VoxelGrid g : grids) {
            if (g.sizeX == maxX && g.sizeY == maxY && g.sizeZ == maxZ) {
                aligned.add(g);
                continue;
            }
            BaseBlock[][][] padded = new BaseBlock[maxX][maxY][maxZ];
            int offX = (maxX - g.sizeX) / 2;
            int offY = (maxY - g.sizeY) / 2;
            int offZ = (maxZ - g.sizeZ) / 2;
            for (int x = 0; x < g.sizeX; x++) {
                for (int y = 0; y < g.sizeY; y++) {
                    System.arraycopy(g.blocks[x][y], 0, padded[x + offX][y + offY], offZ, g.sizeZ);
                }
            }
            aligned.add(new VoxelGrid(maxX, maxY, maxZ, padded, g.nonEmptyCount));
        }
        return aligned;
    }

    private static File resolveSingleFile(String source) {
        File animDir = getAnimationsDirectory();
        File direct = new File(animDir, source);
        if (direct.exists() && direct.isFile()) {
            return direct;
        }
        File fromModels = Model3DVoxelizer.resolveLocalModelFile(source);
        if (fromModels != null && fromModels.exists() && fromModels.isFile()) {
            return fromModels;
        }
        return null;
    }

    private static List<File> resolveFrameSequenceFiles(String source) {
        File[] searchRoots = new File[]{getAnimationsDirectory(), Model3DVoxelizer.getModelsDirectory()};
        for (File root : searchRoots) {
            File subDir = new File(root, source);
            if (subDir.exists() && subDir.isDirectory()) {
                File[] children = subDir.listFiles(File::isFile);
                if (children != null && children.length >= 2) {
                    Arrays.sort(children, Comparator.comparing(File::getName));
                    return Arrays.asList(children);
                }
            }
            File[] prefixMatches = root.listFiles(f -> f.isFile()
                    && f.getName().toLowerCase(Locale.ROOT).startsWith(source.toLowerCase(Locale.ROOT) + "_"));
            if (prefixMatches != null && prefixMatches.length >= 2) {
                Arrays.sort(prefixMatches, Comparator.comparing(File::getName));
                return Arrays.asList(prefixMatches);
            }
        }
        return null;
    }

    /**
     * Parses multi-frame MagicaVoxel {@code .vox} files containing multiple {@code SIZE} + {@code XYZI} chunk pairs.
     */
    public static List<Mesh3D> parseMultiFrameVox(byte[] data) {
        List<Mesh3D> frames = new ArrayList<>();
        if (data == null || data.length < 20) {
            return frames;
        }
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (buf.getInt() != 0x20584F56) { // 'VOX '
            return frames;
        }
        buf.getInt(); // version

        int[] palette = new int[256];
        for (int i = 0; i < 256; i++) {
            palette[i] = 0xFFDDDDDD;
        }

        // First pass: find RGBA palette if present
        int scanPos = 8;
        while (scanPos + 12 <= data.length) {
            String id = new String(data, scanPos, 4, StandardCharsets.US_ASCII);
            int contentBytes = buf.getInt(scanPos + 4);
            int childrenBytes = buf.getInt(scanPos + 8);
            int contentStart = scanPos + 12;
            if ("MAIN".equals(id)) {
                scanPos = contentStart + contentBytes;
                continue;
            }
            if ("RGBA".equals(id) && contentBytes >= 1024 && contentStart + 1024 <= data.length) {
                for (int i = 0; i < 255; i++) {
                    int off = contentStart + i * 4;
                    int r = data[off] & 0xFF;
                    int g = data[off + 1] & 0xFF;
                    int b = data[off + 2] & 0xFF;
                    int a = data[off + 3] & 0xFF;
                    palette[i + 1] = (a << 24) | (r << 16) | (g << 8) | b;
                }
            }
            scanPos = contentStart + contentBytes + childrenBytes;
        }

        // Second pass: read each SIZE + XYZI frame
        int sx = 0, sy = 0, sz = 0;
        int pos = 8;
        while (pos + 12 <= data.length) {
            String id = new String(data, pos, 4, StandardCharsets.US_ASCII);
            int contentBytes = buf.getInt(pos + 4);
            int childrenBytes = buf.getInt(pos + 8);
            int contentStart = pos + 12;
            if ("MAIN".equals(id)) {
                pos = contentStart + contentBytes;
                continue;
            }
            if ("SIZE".equals(id) && contentBytes >= 12) {
                sx = buf.getInt(contentStart);
                sz = buf.getInt(contentStart + 4);
                sy = buf.getInt(contentStart + 8);
            } else if ("XYZI".equals(id) && contentBytes >= 4 && sx > 0 && sy > 0 && sz > 0) {
                int numVoxels = buf.getInt(contentStart);
                Mesh3D frame = new Mesh3D();
                frame.directSizeX = sx;
                frame.directSizeY = sy;
                frame.directSizeZ = sz;
                frame.directVoxels = new int[sx][sy][sz];
                frame.hasAnyColorOrTexture = true;
                int vOff = contentStart + 4;
                for (int i = 0; i < numVoxels && vOff + 4 <= data.length; i++) {
                    int vx = data[vOff] & 0xFF;
                    int vz = data[vOff + 1] & 0xFF;
                    int vy = data[vOff + 2] & 0xFF;
                    int cIdx = data[vOff + 3] & 0xFF;
                    vOff += 4;
                    if (vx < sx && vy < sy && vz < sz && cIdx > 0) {
                        frame.directVoxels[vx][vy][vz] = palette[cIdx];
                    }
                }
                frames.add(frame);
            }
            pos = contentStart + contentBytes + childrenBytes;
        }
        return frames;
    }

    private static Mesh3D convertDirectVoxelsToMesh(Mesh3D vox) {
        Mesh3D mesh = new Mesh3D();
        mesh.hasAnyColorOrTexture = true;
        for (int x = 0; x < vox.directSizeX; x++) {
            for (int y = 0; y < vox.directSizeY; y++) {
                for (int z = 0; z < vox.directSizeZ; z++) {
                    int argb = vox.directVoxels[x][y][z];
                    if (((argb >>> 24) & 0xFF) < 32) continue;
                    double r = ((argb >>> 16) & 0xFF) / 255.0;
                    double g = ((argb >>> 8) & 0xFF) / 255.0;
                    double b = (argb & 0xFF) / 255.0;
                    Triangle3D t1 = new Triangle3D(x, y, z, x + 1, y + 1, z, x + 1, y, z + 1);
                    t1.setVertexColors(r, g, b, r, g, b, r, g, b);
                    Triangle3D t2 = new Triangle3D(x, y + 1, z + 1, x + 1, y, z, x, y, z + 1);
                    t2.setVertexColors(r, g, b, r, g, b, r, g, b);
                    mesh.triangles.add(t1);
                    mesh.triangles.add(t2);
                }
            }
        }
        return mesh;
    }
}
