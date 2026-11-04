package com.dragonbuildtools.converter;

import com.dragonbuildtools.DragonBuildToolsPlugin;
import com.dragonbuildtools.util.BlockColorMapper;
import com.fastasyncworldedit.core.registry.state.PropertyKey;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Exports a WorldEdit Region or Clipboard into a Wavefront {@code .obj} + {@code .mtl} 3D mesh
 * with hidden-face culling and sub-block geometry (stairs, slabs, snow layers, carpets, walls/fences).
 */
public final class ObjSelectionExporter {

    public static final class ExportResult {
        public final File objFile;
        public final File mtlFile;
        public final int exportedBlocks;
        public final int exportedQuads;
        public final int materialCount;

        public ExportResult(File objFile, File mtlFile, int exportedBlocks, int exportedQuads, int materialCount) {
            this.objFile = objFile;
            this.mtlFile = mtlFile;
            this.exportedBlocks = exportedBlocks;
            this.exportedQuads = exportedQuads;
            this.materialCount = materialCount;
        }
    }

    private static final class Quad {
        final double[][] v; // 4 vertices [4][3]

        Quad(double[] v0, double[] v1, double[] v2, double[] v3) {
            this.v = new double[][]{v0, v1, v2, v3};
        }
    }

    private static final class MaterialBucket {
        final String name;
        final int r;
        final int g;
        final int b;
        final boolean translucent;
        final List<Quad> quads = new ArrayList<>();

        MaterialBucket(String name, int r, int g, int b, boolean translucent) {
            this.name = name;
            this.r = r;
            this.g = g;
            this.b = b;
            this.translucent = translucent;
        }
    }

    private ObjSelectionExporter() {
    }

    public static File getExportsDirectory() {
        DragonBuildToolsPlugin plugin = DragonBuildToolsPlugin.getInstance();
        File base = plugin != null ? plugin.getDataFolder() : new File("exports");
        File dir = plugin != null ? new File(base, "exports") : base;
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static ExportResult exportRegionToObj(
            Extent extent,
            Region region,
            String rawName,
            boolean copyToModelsFolder
    ) throws IOException {
        return exportRegionToObj(extent, region, getExportsDirectory(), rawName, copyToModelsFolder);
    }

    public static ExportResult exportRegionToObj(
            Extent extent,
            Region region,
            File outputDir,
            String rawName,
            boolean copyToModelsFolder
    ) throws IOException {
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
        String baseName = sanitizeFileName(rawName);
        if (baseName.toLowerCase(Locale.ROOT).endsWith(".obj")) {
            baseName = baseName.substring(0, baseName.length() - 4);
        }
        if (baseName.isEmpty()) {
            baseName = "export_build";
        }

        File objFile = new File(outputDir, baseName + ".obj");
        File mtlFile = new File(outputDir, baseName + ".mtl");

        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        int sx = max.x() - min.x() + 1;
        int sy = max.y() - min.y() + 1;
        int sz = max.z() - min.z() + 1;

        BlockState[][][] states = new BlockState[sx][sy][sz];
        boolean[][][] fullOpaque = new boolean[sx][sy][sz];

        int nonAirBlocks = 0;
        for (BlockVector3 pt : region) {
            BlockState st = extent.getBlock(pt);
            if (st == null || isAirLike(st.getBlockType())) {
                continue;
            }
            int lx = pt.x() - min.x();
            int ly = pt.y() - min.y();
            int lz = pt.z() - min.z();
            if (lx < 0 || lx >= sx || ly < 0 || ly >= sy || lz < 0 || lz >= sz) {
                continue;
            }
            states[lx][ly][lz] = st;
            fullOpaque[lx][ly][lz] = isFullOpaqueCube(st);
            nonAirBlocks++;
        }

        double centerX = sx * 0.5;
        double centerZ = sz * 0.5;
        Map<String, MaterialBucket> buckets = new LinkedHashMap<>();

        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    BlockState st = states[x][y][z];
                    if (st == null) {
                        continue;
                    }
                    BlockType bt = st.getBlockType();
                    String id = bt.id();
                    String matName = sanitizeMaterialName(id);
                    MaterialBucket bucket = buckets.computeIfAbsent(matName, k -> {
                        int[] rgb = BlockColorMapper.estimateBlockRgb(bt);
                        boolean trans = id.contains("glass") || id.contains("water") || id.contains("ice") || id.contains("slime");
                        return new MaterialBucket(k, rgb[0], rgb[1], rgb[2], trans);
                    });

                    double bx = x - centerX;
                    double by = y;
                    double bz = z - centerZ;

                    if (fullOpaque[x][y][z]) {
                        emitCulledFullCube(bucket, bx, by, bz, x, y, z, sx, sy, sz, fullOpaque);
                    } else {
                        emitSubBlockGeometry(bucket, st, id, bx, by, bz, x, y, z, sx, sy, sz, states, fullOpaque);
                    }
                }
            }
        }

        // Write .mtl
        try (BufferedWriter mw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(mtlFile), StandardCharsets.UTF_8))) {
            mw.write("# Dragon Build Tools OBJ Material Library\n");
            for (MaterialBucket b : buckets.values()) {
                mw.write("newmtl " + b.name + "\n");
                mw.write(String.format(Locale.US, "Kd %.4f %.4f %.4f\n", b.r / 255.0, b.g / 255.0, b.b / 255.0));
                mw.write(String.format(Locale.US, "Ka %.4f %.4f %.4f\n", (b.r / 255.0) * 0.25, (b.g / 255.0) * 0.25, (b.b / 255.0) * 0.25));
                if (b.translucent) {
                    mw.write("d 0.6000\n");
                }
                mw.write("\n");
            }
        }

        // Write .obj
        int totalQuads = 0;
        int vertexIndex = 1;
        try (BufferedWriter ow = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(objFile), StandardCharsets.UTF_8))) {
            ow.write("# Exported by Dragon Build Tools v2.0\n");
            ow.write("mtllib " + mtlFile.getName() + "\n\n");
            for (MaterialBucket b : buckets.values()) {
                if (b.quads.isEmpty()) {
                    continue;
                }
                ow.write("usemtl " + b.name + "\n");
                for (Quad q : b.quads) {
                    for (int i = 0; i < 4; i++) {
                        ow.write(String.format(Locale.US, "v %.4f %.4f %.4f\n", q.v[i][0], q.v[i][1], q.v[i][2]));
                    }
                    ow.write("f " + vertexIndex + " " + (vertexIndex + 1) + " " + (vertexIndex + 2) + " " + (vertexIndex + 3) + "\n");
                    vertexIndex += 4;
                    totalQuads++;
                }
                ow.write("\n");
            }
        }

        if (copyToModelsFolder) {
            File modelsDir = Model3DVoxelizer.getModelsDirectory();
            Files.copy(objFile.toPath(), new File(modelsDir, objFile.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(mtlFile.toPath(), new File(modelsDir, mtlFile.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        return new ExportResult(objFile, mtlFile, nonAirBlocks, totalQuads, buckets.size());
    }

    private static void emitCulledFullCube(
            MaterialBucket bucket,
            double x0, double y0, double z0,
            int gx, int gy, int gz,
            int sx, int sy, int sz,
            boolean[][][] fullOpaque
    ) {
        double x1 = x0 + 1.0;
        double y1 = y0 + 1.0;
        double z1 = z0 + 1.0;

        // -X
        if (gx == 0 || !fullOpaque[gx - 1][gy][gz]) {
            bucket.quads.add(new Quad(pt(x0, y0, z0), pt(x0, y0, z1), pt(x0, y1, z1), pt(x0, y1, z0)));
        }
        // +X
        if (gx == sx - 1 || !fullOpaque[gx + 1][gy][gz]) {
            bucket.quads.add(new Quad(pt(x1, y0, z1), pt(x1, y0, z0), pt(x1, y1, z0), pt(x1, y1, z1)));
        }
        // -Y
        if (gy == 0 || !fullOpaque[gx][gy - 1][gz]) {
            bucket.quads.add(new Quad(pt(x0, y0, z0), pt(x1, y0, z0), pt(x1, y0, z1), pt(x0, y0, z1)));
        }
        // +Y
        if (gy == sy - 1 || !fullOpaque[gx][gy + 1][gz]) {
            bucket.quads.add(new Quad(pt(x0, y1, z1), pt(x1, y1, z1), pt(x1, y1, z0), pt(x0, y1, z0)));
        }
        // -Z
        if (gz == 0 || !fullOpaque[gx][gy][gz - 1]) {
            bucket.quads.add(new Quad(pt(x1, y0, z0), pt(x0, y0, z0), pt(x0, y1, z0), pt(x1, y1, z0)));
        }
        // +Z
        if (gz == sz - 1 || !fullOpaque[gx][gy][gz + 1]) {
            bucket.quads.add(new Quad(pt(x0, y0, z1), pt(x1, y0, z1), pt(x1, y1, z1), pt(x0, y1, z1)));
        }
    }

    private static void emitSubBlockGeometry(
            MaterialBucket bucket,
            BlockState st,
            String id,
            double x0, double y0, double z0,
            int gx, int gy, int gz,
            int sx, int sy, int sz,
            BlockState[][][] states,
            boolean[][][] fullOpaque
    ) {
        if (id.contains("_slab")) {
            String type = readStateProp(st, PropertyKey.TYPE, "bottom");
            if ("double".equalsIgnoreCase(type)) {
                emitBox(bucket, x0, y0, z0, x0 + 1.0, y0 + 1.0, z0 + 1.0);
            } else if ("top".equalsIgnoreCase(type)) {
                emitBox(bucket, x0, y0 + 0.5, z0, x0 + 1.0, y0 + 1.0, z0 + 1.0);
            } else {
                emitBox(bucket, x0, y0, z0, x0 + 1.0, y0 + 0.5, z0 + 1.0);
            }
            return;
        }
        if (id.equals("minecraft:snow")) {
            String layersStr = readStateProp(st, PropertyKey.LAYERS, "1");
            int layers = 1;
            try {
                layers = Math.max(1, Math.min(8, Integer.parseInt(layersStr)));
            } catch (NumberFormatException ignored) {
            }
            emitBox(bucket, x0, y0, z0, x0 + 1.0, y0 + (layers / 8.0), z0 + 1.0);
            return;
        }
        if (id.contains("_carpet")) {
            emitBox(bucket, x0, y0, z0, x0 + 1.0, y0 + 0.0625, z0 + 1.0);
            return;
        }
        if (id.contains("_stairs")) {
            String half = readStateProp(st, PropertyKey.HALF, "bottom");
            String facing = readStateProp(st, PropertyKey.FACING, "north");
            boolean upsideDown = "top".equalsIgnoreCase(half);
            if (upsideDown) {
                emitBox(bucket, x0, y0 + 0.5, z0, x0 + 1.0, y0 + 1.0, z0 + 1.0);
                emitStepHalf(bucket, facing, x0, y0, z0, x0 + 1.0, y0 + 0.5, z0 + 1.0);
            } else {
                emitBox(bucket, x0, y0, z0, x0 + 1.0, y0 + 0.5, z0 + 1.0);
                emitStepHalf(bucket, facing, x0, y0 + 0.5, z0, x0 + 1.0, y0 + 1.0, z0 + 1.0);
            }
            return;
        }
        if (id.contains("_wall") || id.contains("_fence")) {
            emitBox(bucket, x0 + 0.25, y0, z0 + 0.25, x0 + 0.75, y0 + 1.0, z0 + 0.75);
            return;
        }
        // Translucent full cubes (glass/water): cull against identical block state
        emitBoxCulledSameType(bucket, st.getBlockType(), x0, y0, z0, gx, gy, gz, sx, sy, sz, states, fullOpaque);
    }

    private static void emitStepHalf(
            MaterialBucket bucket,
            String facing,
            double x0, double y0, double z0,
            double x1, double y1, double z1
    ) {
        String f = facing == null ? "north" : facing.toLowerCase(Locale.ROOT);
        switch (f) {
            case "east":
                emitBox(bucket, x0 + 0.5, y0, z0, x1, y1, z1);
                break;
            case "west":
                emitBox(bucket, x0, y0, z0, x0 + 0.5, y1, z1);
                break;
            case "south":
                emitBox(bucket, x0, y0, z0 + 0.5, x1, y1, z1);
                break;
            case "north":
            default:
                emitBox(bucket, x0, y0, z0, x1, y1, z0 + 0.5);
                break;
        }
    }

    private static void emitBoxCulledSameType(
            MaterialBucket bucket,
            BlockType selfType,
            double x0, double y0, double z0,
            int gx, int gy, int gz,
            int sx, int sy, int sz,
            BlockState[][][] states,
            boolean[][][] fullOpaque
    ) {
        double x1 = x0 + 1.0;
        double y1 = y0 + 1.0;
        double z1 = z0 + 1.0;
        if (gx == 0 || (!fullOpaque[gx - 1][gy][gz] && !sameType(states[gx - 1][gy][gz], selfType))) {
            bucket.quads.add(new Quad(pt(x0, y0, z0), pt(x0, y0, z1), pt(x0, y1, z1), pt(x0, y1, z0)));
        }
        if (gx == sx - 1 || (!fullOpaque[gx + 1][gy][gz] && !sameType(states[gx + 1][gy][gz], selfType))) {
            bucket.quads.add(new Quad(pt(x1, y0, z1), pt(x1, y0, z0), pt(x1, y1, z0), pt(x1, y1, z1)));
        }
        if (gy == 0 || (!fullOpaque[gx][gy - 1][gz] && !sameType(states[gx][gy - 1][gz], selfType))) {
            bucket.quads.add(new Quad(pt(x0, y0, z0), pt(x1, y0, z0), pt(x1, y0, z1), pt(x0, y0, z1)));
        }
        if (gy == sy - 1 || (!fullOpaque[gx][gy + 1][gz] && !sameType(states[gx][gy + 1][gz], selfType))) {
            bucket.quads.add(new Quad(pt(x0, y1, z1), pt(x1, y1, z1), pt(x1, y1, z0), pt(x0, y1, z0)));
        }
        if (gz == 0 || (!fullOpaque[gx][gy][gz - 1] && !sameType(states[gx][gy][gz - 1], selfType))) {
            bucket.quads.add(new Quad(pt(x1, y0, z0), pt(x0, y0, z0), pt(x0, y1, z0), pt(x1, y1, z0)));
        }
        if (gz == sz - 1 || (!fullOpaque[gx][gy][gz + 1] && !sameType(states[gx][gy][gz + 1], selfType))) {
            bucket.quads.add(new Quad(pt(x0, y0, z1), pt(x1, y0, z1), pt(x1, y1, z1), pt(x0, y1, z1)));
        }
    }

    private static boolean sameType(BlockState st, BlockType type) {
        return st != null && st.getBlockType().equals(type);
    }

    private static void emitBox(MaterialBucket bucket, double x0, double y0, double z0, double x1, double y1, double z1) {
        bucket.quads.add(new Quad(pt(x0, y0, z0), pt(x0, y0, z1), pt(x0, y1, z1), pt(x0, y1, z0)));
        bucket.quads.add(new Quad(pt(x1, y0, z1), pt(x1, y0, z0), pt(x1, y1, z0), pt(x1, y1, z1)));
        bucket.quads.add(new Quad(pt(x0, y0, z0), pt(x1, y0, z0), pt(x1, y0, z1), pt(x0, y0, z1)));
        bucket.quads.add(new Quad(pt(x0, y1, z1), pt(x1, y1, z1), pt(x1, y1, z0), pt(x0, y1, z0)));
        bucket.quads.add(new Quad(pt(x1, y0, z0), pt(x0, y0, z0), pt(x0, y1, z0), pt(x1, y1, z0)));
        bucket.quads.add(new Quad(pt(x0, y0, z1), pt(x1, y0, z1), pt(x1, y1, z1), pt(x0, y1, z1)));
    }

    private static double[] pt(double x, double y, double z) {
        return new double[]{x, y, z};
    }

    private static boolean isAirLike(BlockType bt) {
        if (bt == null) {
            return true;
        }
        String id = bt.id();
        return id.equals("minecraft:air")
                || id.equals("minecraft:cave_air")
                || id.equals("minecraft:void_air")
                || id.equals("minecraft:structure_void")
                || id.equals("minecraft:light");
    }

    private static boolean isFullOpaqueCube(BlockState st) {
        String id = st.getBlockType().id();
        if (id.contains("_stairs")
                || id.contains("_slab")
                || id.contains("_wall")
                || id.contains("_fence")
                || id.contains("_carpet")
                || id.contains("_trapdoor")
                || id.contains("_pane")
                || id.contains("glass")
                || id.contains("water")
                || id.contains("leaves")
                || id.equals("minecraft:snow")) {
            if (id.contains("_slab")) {
                return "double".equalsIgnoreCase(readStateProp(st, PropertyKey.TYPE, "bottom"));
            }
            if (id.equals("minecraft:snow")) {
                return "8".equals(readStateProp(st, PropertyKey.LAYERS, "1"));
            }
            return false;
        }
        return true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String readStateProp(BlockState st, PropertyKey key, String fallback) {
        try {
            Object val = st.getState(key);
            if (val != null) {
                return val.toString().toLowerCase(Locale.ROOT);
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static String sanitizeFileName(String raw) {
        if (raw == null) {
            return "export_build";
        }
        return raw.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String sanitizeMaterialName(String id) {
        String clean = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
        return clean.replaceAll("[^a-zA-Z0-9_]", "_");
    }
}
