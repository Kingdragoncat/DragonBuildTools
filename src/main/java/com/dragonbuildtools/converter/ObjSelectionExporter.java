package com.dragonbuildtools.converter;

import com.dragonbuildtools.DragonBuildToolsPlugin;
import com.dragonbuildtools.util.VanillaBlockAppearanceResolver;
import com.dragonbuildtools.util.VanillaBlockAppearanceResolver.ExportTextureSession;
import com.dragonbuildtools.util.VanillaBlockAppearanceResolver.FaceDir;
import com.dragonbuildtools.util.VanillaBlockAppearanceResolver.FaceMaterialAppearance;
import com.dragonbuildtools.util.VanillaBlockAppearanceResolver.ShapeKind;
import com.dragonbuildtools.util.VanillaBlockAppearanceResolver.TexturedModelQuad;
import com.fastasyncworldedit.core.registry.state.PropertyKey;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Exports a WorldEdit Region or Clipboard into a Blender-ready Wavefront {@code .obj} + {@code .mtl}
 * + UV-mapped {@code _atlas.png} mesh using resource packs in {@code plugins/DragonBuildTools/resourcepacks/},
 * FAWE's cached Mojang client asset JAR, and DBT's 1,200-block color/shape table.
 */
public final class ObjSelectionExporter {

    public static final class ExportResult {
        public final File objFile;
        public final File mtlFile;
        public final File atlasFile;
        public final int exportedBlocks;
        public final int exportedQuads;
        public final int materialCount;
        public final List<String> packLayersUsed;

        public ExportResult(File objFile, File mtlFile, int exportedBlocks, int exportedQuads, int materialCount) {
            this(objFile, mtlFile, null, exportedBlocks, exportedQuads, materialCount, Collections.emptyList());
        }

        public ExportResult(
                File objFile,
                File mtlFile,
                File atlasFile,
                int exportedBlocks,
                int exportedQuads,
                int materialCount,
                List<String> packLayersUsed
        ) {
            this.objFile = objFile;
            this.mtlFile = mtlFile;
            this.atlasFile = atlasFile;
            this.exportedBlocks = exportedBlocks;
            this.exportedQuads = exportedQuads;
            this.materialCount = materialCount;
            this.packLayersUsed = packLayersUsed != null ? packLayersUsed : Collections.emptyList();
        }
    }

    private static final class Quad {
        final double[][] v;     // [4][3] world-centered coordinates
        final double[][] mcUvs; // [4][2] in [0..16] top-left PNG space

        Quad(double[] v0, double[] v1, double[] v2, double[] v3, double[][] mcUvs) {
            this.v = new double[][]{v0, v1, v2, v3};
            this.mcUvs = mcUvs;
        }
    }

    private static final class MaterialBucket {
        final FaceMaterialAppearance appearance;
        final List<Quad> quads = new ArrayList<>();
        int atlasX;
        int atlasY;
        int atlasTileSize;

        MaterialBucket(FaceMaterialAppearance appearance) {
            this.appearance = appearance;
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
        return exportRegionToObj(extent, region, getExportsDirectory(), rawName, null, null, copyToModelsFolder);
    }

    public static ExportResult exportRegionToObj(
            Extent extent,
            Region region,
            String rawName,
            String preferredPackName,
            boolean copyToModelsFolder
    ) throws IOException {
        return exportRegionToObj(extent, region, getExportsDirectory(), rawName, preferredPackName, null, copyToModelsFolder);
    }

    public static ExportResult exportRegionToObj(
            Extent extent,
            Region region,
            File outputDir,
            String rawName,
            boolean copyToModelsFolder
    ) throws IOException {
        return exportRegionToObj(extent, region, outputDir, rawName, null, null, copyToModelsFolder);
    }

    public static ExportResult exportRegionToObj(
            Extent extent,
            Region region,
            File outputDir,
            String rawName,
            String preferredPackName,
            List<File> explicitPackSources,
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
        boolean colorsOnly = "none".equalsIgnoreCase(preferredPackName) || "colors".equalsIgnoreCase(preferredPackName);
        File atlasFile = colorsOnly ? null : new File(outputDir, baseName + "_atlas.png");

        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        int sx = max.x() - min.x() + 1;
        int sy = max.y() - min.y() + 1;
        int sz = max.z() - min.z() + 1;

        BlockState[][][] states = new BlockState[sx][sy][sz];
        ShapeKind[][][] kinds = new ShapeKind[sx][sy][sz];
        boolean[][][] fullOpaque = new boolean[sx][sy][sz];

        int nonAirBlocks = 0;
        for (BlockVector3 pt : region) {
            BlockState st = extent.getBlock(pt);
            if (st == null || st.getBlockType() == null) {
                continue;
            }
            ShapeKind kind = VanillaBlockAppearanceResolver.getShapeKind(st);
            if (kind == ShapeKind.INVISIBLE) {
                continue;
            }
            int lx = pt.x() - min.x();
            int ly = pt.y() - min.y();
            int lz = pt.z() - min.z();
            if (lx < 0 || lx >= sx || ly < 0 || ly >= sy || lz < 0 || lz >= sz) {
                continue;
            }
            states[lx][ly][lz] = st;
            kinds[lx][ly][lz] = kind;
            fullOpaque[lx][ly][lz] = isStateFullOpaque(st, kind);
            nonAirBlocks++;
        }

        double centerX = sx * 0.5;
        double centerZ = sz * 0.5;
        Map<String, MaterialBucket> buckets = new LinkedHashMap<>();
        List<String> usedPackLayers;

        try (ExportTextureSession texSession = explicitPackSources != null
                ? new ExportTextureSession(explicitPackSources)
                : new ExportTextureSession(preferredPackName)) {

            usedPackLayers = texSession.getActiveLayerNames();

            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz; z++) {
                    for (int x = 0; x < sx; x++) {
                        BlockState st = states[x][y][z];
                        if (st == null) {
                            continue;
                        }
                        ShapeKind kind = kinds[x][y][z];
                        BlockType bt = st.getBlockType();

                        boolean snowyAbove = false;
                        if (y + 1 < sy && states[x][y + 1][z] != null) {
                            String aboveName = VanillaBlockAppearanceResolver.cleanBlockName(states[x][y + 1][z].getBlockType().id());
                            snowyAbove = aboveName.equals("snow") || aboveName.equals("snow_block");
                        }

                        double bx = x - centerX;
                        double by = y;
                        double bz = z - centerZ;

                        // Compute neighbor connection overrides for walls/fences/panes if needed
                        Map<String, String> connOverrides = null;
                        if (kind == ShapeKind.WALL || kind == ShapeKind.FENCE || kind == ShapeKind.PANE) {
                            connOverrides = computeConnectionProps(kind, x, y, z, sx, sy, sz, states, kinds, fullOpaque);
                        }

                        int posHash = mixPositionHash(min.x() + x, min.y() + y, min.z() + z);
                        List<TexturedModelQuad> modelQuads = texSession.resolveBlockModelQuads(st, snowyAbove, connOverrides, posHash);

                        if (modelQuads != null && !modelQuads.isEmpty()) {
                            for (TexturedModelQuad mq : modelQuads) {
                                if (mq.cullFace != null && shouldCullNeighborFace(
                                        x + mq.cullFace.dx, y + mq.cullFace.dy, z + mq.cullFace.dz,
                                        sx, sy, sz, bt, kind, states, fullOpaque
                                )) {
                                    continue;
                                }
                                MaterialBucket bucket = buckets.computeIfAbsent(
                                        mq.material.materialKey,
                                        k -> new MaterialBucket(mq.material)
                                );
                                bucket.quads.add(new Quad(
                                        pt(bx + mq.verts[0][0], by + mq.verts[0][1], bz + mq.verts[0][2]),
                                        pt(bx + mq.verts[1][0], by + mq.verts[1][1], bz + mq.verts[1][2]),
                                        pt(bx + mq.verts[2][0], by + mq.verts[2][1], bz + mq.verts[2][2]),
                                        pt(bx + mq.verts[3][0], by + mq.verts[3][1], bz + mq.verts[3][2]),
                                        mq.mcUvs
                                ));
                            }
                        } else {
                            emitProceduralBlock(
                                    buckets, texSession, st, bt, kind, snowyAbove, connOverrides,
                                    bx, by, bz, x, y, z, sx, sy, sz, states, kinds, fullOpaque
                            );
                        }
                    }
                }
            }
        }

        List<MaterialBucket> activeBuckets = new ArrayList<>();
        for (MaterialBucket b : buckets.values()) {
            if (!b.quads.isEmpty()) {
                activeBuckets.add(b);
            }
        }

        int atlasW = 1;
        int atlasH = 1;
        if (atlasFile != null && !activeBuckets.isEmpty()) {
            int[] dims = writeTextureAtlas(atlasFile, activeBuckets);
            atlasW = dims[0];
            atlasH = dims[1];
        } else {
            atlasFile = null;
        }

        // Write .mtl
        try (BufferedWriter mw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(mtlFile), StandardCharsets.UTF_8))) {
            mw.write("# Dragon Build Tools v2 OBJ Material Library\n");
            for (MaterialBucket b : activeBuckets) {
                FaceMaterialAppearance app = b.appearance;
                mw.write("newmtl " + app.materialKey + "\n");
                mw.write(String.format(Locale.US, "Kd %.4f %.4f %.4f\n", app.r / 255.0, app.g / 255.0, app.b / 255.0));
                mw.write(String.format(Locale.US, "Ka %.4f %.4f %.4f\n", (app.r / 255.0) * 0.25, (app.g / 255.0) * 0.25, (app.b / 255.0) * 0.25));
                mw.write("Ks 0.0000 0.0000 0.0000\n");
                if (app.translucent) {
                    mw.write("d 0.6500\n");
                }
                if (atlasFile != null) {
                    mw.write("map_Kd " + atlasFile.getName() + "\n");
                    if (app.cutoutAlpha || app.translucent) {
                        mw.write("map_d " + atlasFile.getName() + "\n");
                    }
                }
                mw.write("\n");
            }
        }

        // Write .obj with deduplicated vt coordinates
        int totalQuads = 0;
        int vertexIndex = 1;
        List<double[]> uniqueUvs = new ArrayList<>();
        Map<Long, Integer> uvIndexMap = new HashMap<>();

        try (BufferedWriter ow = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(objFile), StandardCharsets.UTF_8))) {
            ow.write("# Exported by Dragon Build Tools v2\n");
            ow.write("mtllib " + mtlFile.getName() + "\n\n");

            if (atlasFile != null) {
                // Pre-register all deduplicated vt lines first for clean, fast OBJ parsing
                for (MaterialBucket b : activeBuckets) {
                    for (Quad q : b.quads) {
                        for (int i = 0; i < 4; i++) {
                            double[] atlasUv = mapMcUvToAtlas(b, q.mcUvs[i][0], q.mcUvs[i][1], atlasW, atlasH);
                            getOrRegisterUv(atlasUv[0], atlasUv[1], uniqueUvs, uvIndexMap);
                        }
                    }
                }
                for (double[] uv : uniqueUvs) {
                    ow.write(String.format(Locale.US, "vt %.6f %.6f\n", uv[0], uv[1]));
                }
                ow.write("\n");
            }

            for (MaterialBucket b : activeBuckets) {
                ow.write("usemtl " + b.appearance.materialKey + "\n");
                for (Quad q : b.quads) {
                    for (int i = 0; i < 4; i++) {
                        ow.write(String.format(Locale.US, "v %.4f %.4f %.4f\n", q.v[i][0], q.v[i][1], q.v[i][2]));
                    }
                    if (atlasFile != null) {
                        int[] ti = new int[4];
                        for (int i = 0; i < 4; i++) {
                            double[] atlasUv = mapMcUvToAtlas(b, q.mcUvs[i][0], q.mcUvs[i][1], atlasW, atlasH);
                            ti[i] = getOrRegisterUv(atlasUv[0], atlasUv[1], uniqueUvs, uvIndexMap);
                        }
                        ow.write("f " + vertexIndex + "/" + ti[0]
                                + " " + (vertexIndex + 1) + "/" + ti[1]
                                + " " + (vertexIndex + 2) + "/" + ti[2]
                                + " " + (vertexIndex + 3) + "/" + ti[3] + "\n");
                    } else {
                        ow.write("f " + vertexIndex + " " + (vertexIndex + 1) + " " + (vertexIndex + 2) + " " + (vertexIndex + 3) + "\n");
                    }
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
            if (atlasFile != null && atlasFile.exists()) {
                Files.copy(atlasFile.toPath(), new File(modelsDir, atlasFile.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        return new ExportResult(objFile, mtlFile, atlasFile, nonAirBlocks, totalQuads, activeBuckets.size(), usedPackLayers);
    }

    private static int[] writeTextureAtlas(File atlasFile, List<MaterialBucket> activeBuckets) throws IOException {
        int maxSrcDim = 16;
        for (MaterialBucket b : activeBuckets) {
            BufferedImage img = b.appearance.texture;
            if (img != null) {
                maxSrcDim = Math.max(maxSrcDim, Math.max(img.getWidth(), img.getHeight()));
            }
        }
        // Choose crisp integer nearest-neighbor upscale (>= 128px so Blender default Linear interpolation stays razor-sharp)
        int tileInner = 128;
        if (maxSrcDim > 128) {
            tileInner = Math.min(256, maxSrcDim);
        } else if (128 % maxSrcDim != 0) {
            tileInner = Math.min(256, ((128 + maxSrcDim - 1) / maxSrcDim) * maxSrcDim);
        }
        int gutter = 8;
        int cellSize = tileInner + gutter * 2;

        int count = activeBuckets.size();
        int cols = Math.max(1, (int) Math.ceil(Math.sqrt(count)));
        int rows = Math.max(1, (int) Math.ceil((double) count / cols));
        int atlasW = cols * cellSize;
        int atlasH = rows * cellSize;

        BufferedImage atlas = new BufferedImage(atlasW, atlasH, BufferedImage.TYPE_INT_ARGB);
        for (int idx = 0; idx < count; idx++) {
            MaterialBucket bucket = activeBuckets.get(idx);
            int col = idx % cols;
            int row = idx / cols;
            int innerX = col * cellSize + gutter;
            int innerY = row * cellSize + gutter;
            bucket.atlasX = innerX;
            bucket.atlasY = innerY;
            bucket.atlasTileSize = tileInner;

            BufferedImage src = bucket.appearance.texture;
            int sw = Math.max(1, src.getWidth());
            int sh = Math.max(1, src.getHeight());

            // Blit nearest-neighbor inner tile + 8px edge-clamped gutter around all 4 sides
            for (int dy = -gutter; dy < tileInner + gutter; dy++) {
                int clampedY = Math.max(0, Math.min(tileInner - 1, dy));
                int sy = Math.min(sh - 1, (clampedY * sh) / tileInner);
                for (int dx = -gutter; dx < tileInner + gutter; dx++) {
                    int clampedX = Math.max(0, Math.min(tileInner - 1, dx));
                    int sx = Math.min(sw - 1, (clampedX * sw) / tileInner);
                    atlas.setRGB(innerX + dx, innerY + dy, src.getRGB(sx, sy));
                }
            }
        }

        ImageIO.write(atlas, "png", atlasFile);
        return new int[]{atlasW, atlasH};
    }

    private static double[] mapMcUvToAtlas(MaterialBucket b, double uMc, double vMc, int atlasW, int atlasH) {
        double uc = Math.max(0.0, Math.min(16.0, uMc));
        double vc = Math.max(0.0, Math.min(16.0, vMc));
        double px = b.atlasX + (uc / 16.0) * b.atlasTileSize;
        double py = b.atlasY + (vc / 16.0) * b.atlasTileSize;
        double uObj = px / atlasW;
        double vObj = 1.0 - (py / atlasH);
        return new double[]{uObj, vObj};
    }

    private static int getOrRegisterUv(double u, double v, List<double[]> list, Map<Long, Integer> map) {
        long ku = Math.round(u * 1_000_000.0);
        long kv = Math.round(v * 1_000_000.0);
        long key = (ku << 32) ^ (kv & 0xFFFFFFFFL);
        Integer existing = map.get(key);
        if (existing != null) {
            return existing;
        }
        int nextIdx = list.size() + 1;
        list.add(new double[]{u, v});
        map.put(key, nextIdx);
        return nextIdx;
    }

    // =========================================================================
    // Procedural Fallback Mesher (used when no pack/JAR JSON exists for a block)
    // =========================================================================

    private static void emitProceduralBlock(
            Map<String, MaterialBucket> buckets,
            ExportTextureSession texSession,
            BlockState st,
            BlockType bt,
            ShapeKind kind,
            boolean snowyAbove,
            Map<String, String> connProps,
            double bx, double by, double bz,
            int gx, int gy, int gz,
            int sx, int sy, int sz,
            BlockState[][][] states,
            ShapeKind[][][] kinds,
            boolean[][][] fullOpaque
    ) {
        switch (kind) {
            case CUBE: {
                emitCulledBox01(buckets, texSession, st, bt, kind, snowyAbove, bx, by, bz, 0, 0, 0, 1, 1, 1, gx, gy, gz, sx, sy, sz, states, fullOpaque, true);
                break;
            }
            case CUTOUT_CUBE:
            case TRANSLUCENT_CUBE: {
                emitCulledBox01(buckets, texSession, st, bt, kind, snowyAbove, bx, by, bz, 0, 0, 0, 1, 1, 1, gx, gy, gz, sx, sy, sz, states, fullOpaque, false);
                break;
            }
            case SLAB: {
                String type = VanillaBlockAppearanceResolver.readProp(st, PropertyKey.TYPE, "bottom");
                if ("double".equals(type)) {
                    emitCulledBox01(buckets, texSession, st, bt, ShapeKind.CUBE, snowyAbove, bx, by, bz, 0, 0, 0, 1, 1, 1, gx, gy, gz, sx, sy, sz, states, fullOpaque, true);
                } else if ("top".equals(type)) {
                    emitBox01(buckets, texSession, st, snowyAbove, bx, by, bz, 0, 0.5, 0, 1, 1.0, 1);
                } else {
                    emitBox01(buckets, texSession, st, snowyAbove, bx, by, bz, 0, 0.0, 0, 1, 0.5, 1);
                }
                break;
            }
            case SNOW: {
                String layersStr = VanillaBlockAppearanceResolver.readProp(st, PropertyKey.LAYERS, "1");
                int layers = 1;
                try {
                    layers = Math.max(1, Math.min(8, Integer.parseInt(layersStr)));
                } catch (NumberFormatException ignored) {
                }
                if (layers == 8) {
                    emitCulledBox01(buckets, texSession, st, bt, ShapeKind.CUBE, false, bx, by, bz, 0, 0, 0, 1, 1, 1, gx, gy, gz, sx, sy, sz, states, fullOpaque, true);
                } else {
                    emitBox01(buckets, texSession, st, false, bx, by, bz, 0, 0, 0, 1, layers / 8.0, 1);
                }
                break;
            }
            case CARPET: {
                emitBox01(buckets, texSession, st, false, bx, by, bz, 0, 0, 0, 1, 0.0625, 1);
                break;
            }
            case PLATE: {
                emitBox01(buckets, texSession, st, false, bx, by, bz, 0.0625, 0, 0.0625, 0.9375, 0.0625, 0.9375);
                break;
            }
            case STAIRS: {
                emitProceduralStairs(buckets, texSession, st, bx, by, bz);
                break;
            }
            case WALL: {
                boolean n = connProps != null && !"none".equals(connProps.getOrDefault("north", "none"));
                boolean s = connProps != null && !"none".equals(connProps.getOrDefault("south", "none"));
                boolean e = connProps != null && !"none".equals(connProps.getOrDefault("east", "none"));
                boolean w = connProps != null && !"none".equals(connProps.getOrDefault("west", "none"));
                boolean up = connProps == null || "true".equals(connProps.getOrDefault("up", "true")) || !(n && s && !e && !w || e && w && !n && !s);
                if (up) {
                    emitBox01(buckets, texSession, st, false, bx, by, bz, 0.25, 0, 0.25, 0.75, 1.0, 0.75);
                }
                if (n) emitBox01(buckets, texSession, st, false, bx, by, bz, 0.3125, 0, 0.0, 0.6875, 0.875, 0.5);
                if (s) emitBox01(buckets, texSession, st, false, bx, by, bz, 0.3125, 0, 0.5, 0.6875, 0.875, 1.0);
                if (w) emitBox01(buckets, texSession, st, false, bx, by, bz, 0.0, 0, 0.3125, 0.5, 0.875, 0.6875);
                if (e) emitBox01(buckets, texSession, st, false, bx, by, bz, 0.5, 0, 0.3125, 1.0, 0.875, 0.6875);
                break;
            }
            case FENCE: {
                emitBox01(buckets, texSession, st, false, bx, by, bz, 0.375, 0, 0.375, 0.625, 1.0, 0.625);
                boolean n = connProps != null && "true".equals(connProps.get("north"));
                boolean s = connProps != null && "true".equals(connProps.get("south"));
                boolean e = connProps != null && "true".equals(connProps.get("east"));
                boolean w = connProps != null && "true".equals(connProps.get("west"));
                if (n) {
                    emitBox01(buckets, texSession, st, false, bx, by, bz, 0.4375, 0.75, 0.0, 0.5625, 0.9375, 0.375);
                    emitBox01(buckets, texSession, st, false, bx, by, bz, 0.4375, 0.375, 0.0, 0.5625, 0.5625, 0.375);
                }
                if (s) {
                    emitBox01(buckets, texSession, st, false, bx, by, bz, 0.4375, 0.75, 0.625, 0.5625, 0.9375, 1.0);
                    emitBox01(buckets, texSession, st, false, bx, by, bz, 0.4375, 0.375, 0.625, 0.5625, 0.5625, 1.0);
                }
                if (w) {
                    emitBox01(buckets, texSession, st, false, bx, by, bz, 0.0, 0.75, 0.4375, 0.375, 0.9375, 0.5625);
                    emitBox01(buckets, texSession, st, false, bx, by, bz, 0.0, 0.375, 0.4375, 0.375, 0.5625, 0.5625);
                }
                if (e) {
                    emitBox01(buckets, texSession, st, false, bx, by, bz, 0.625, 0.75, 0.4375, 1.0, 0.9375, 0.5625);
                    emitBox01(buckets, texSession, st, false, bx, by, bz, 0.625, 0.375, 0.4375, 1.0, 0.5625, 0.5625);
                }
                break;
            }
            case FENCE_GATE: {
                String facing = VanillaBlockAppearanceResolver.readProp(st, PropertyKey.FACING, "north");
                if ("east".equals(facing) || "west".equals(facing)) {
                    emitBox01(buckets, texSession, st, false, bx, by, bz, 0.375, 0.3125, 0.0, 0.625, 1.0, 1.0);
                } else {
                    emitBox01(buckets, texSession, st, false, bx, by, bz, 0.0, 0.3125, 0.375, 1.0, 1.0, 0.625);
                }
                break;
            }
            case PANE: {
                boolean n = connProps != null && "true".equals(connProps.get("north"));
                boolean s = connProps != null && "true".equals(connProps.get("south"));
                boolean e = connProps != null && "true".equals(connProps.get("east"));
                boolean w = connProps != null && "true".equals(connProps.get("west"));
                emitBox01(buckets, texSession, st, false, bx, by, bz, 0.4375, 0, 0.4375, 0.5625, 1.0, 0.5625);
                if (n) emitBox01(buckets, texSession, st, false, bx, by, bz, 0.4375, 0, 0.0, 0.5625, 1.0, 0.4375);
                if (s) emitBox01(buckets, texSession, st, false, bx, by, bz, 0.4375, 0, 0.5625, 0.5625, 1.0, 1.0);
                if (w) emitBox01(buckets, texSession, st, false, bx, by, bz, 0.0, 0, 0.4375, 0.4375, 1.0, 0.5625);
                if (e) emitBox01(buckets, texSession, st, false, bx, by, bz, 0.5625, 0, 0.4375, 1.0, 1.0, 0.5625);
                break;
            }
            case TRAPDOOR: {
                boolean open = "true".equals(VanillaBlockAppearanceResolver.readProp(st, PropertyKey.OPEN, "false"));
                String half = VanillaBlockAppearanceResolver.readProp(st, PropertyKey.HALF, "bottom");
                String facing = VanillaBlockAppearanceResolver.readProp(st, PropertyKey.FACING, "north");
                if (open) {
                    switch (facing) {
                        case "south": emitBox01(buckets, texSession, st, false, bx, by, bz, 0, 0, 0.0, 1, 1, 0.1875); break;
                        case "east":  emitBox01(buckets, texSession, st, false, bx, by, bz, 0.0, 0, 0, 0.1875, 1, 1); break;
                        case "west":  emitBox01(buckets, texSession, st, false, bx, by, bz, 0.8125, 0, 0, 1.0, 1, 1); break;
                        case "north":
                        default:      emitBox01(buckets, texSession, st, false, bx, by, bz, 0, 0, 0.8125, 1, 1, 1.0); break;
                    }
                } else if ("top".equals(half)) {
                    emitBox01(buckets, texSession, st, false, bx, by, bz, 0, 0.8125, 0, 1, 1.0, 1);
                } else {
                    emitBox01(buckets, texSession, st, false, bx, by, bz, 0, 0.0, 0, 1, 0.1875, 1);
                }
                break;
            }
            case DOOR: {
                String facing = VanillaBlockAppearanceResolver.readProp(st, PropertyKey.FACING, "north");
                switch (facing) {
                    case "south": emitBox01(buckets, texSession, st, false, bx, by, bz, 0, 0, 0.0, 1, 1, 0.1875); break;
                    case "east":  emitBox01(buckets, texSession, st, false, bx, by, bz, 0.0, 0, 0, 0.1875, 1, 1); break;
                    case "west":  emitBox01(buckets, texSession, st, false, bx, by, bz, 0.8125, 0, 0, 1.0, 1, 1); break;
                    case "north":
                    default:      emitBox01(buckets, texSession, st, false, bx, by, bz, 0, 0, 0.8125, 1, 1, 1.0); break;
                }
                break;
            }
            case CROSS: {
                FaceMaterialAppearance app = texSession.resolveFace(st, FaceDir.NORTH, false);
                MaterialBucket bucket = buckets.computeIfAbsent(app.materialKey, k -> new MaterialBucket(app));
                double[][] uvFull = {{0, 0}, {0, 16}, {16, 16}, {16, 0}};
                // Diagonal 1 (both sides)
                bucket.quads.add(new Quad(pt(bx + 0.08, by + 1, bz + 0.08), pt(bx + 0.08, by, bz + 0.08), pt(bx + 0.92, by, bz + 0.92), pt(bx + 0.92, by + 1, bz + 0.92), uvFull));
                bucket.quads.add(new Quad(pt(bx + 0.92, by + 1, bz + 0.92), pt(bx + 0.92, by, bz + 0.92), pt(bx + 0.08, by, bz + 0.08), pt(bx + 0.08, by + 1, bz + 0.08), uvFull));
                // Diagonal 2 (both sides)
                bucket.quads.add(new Quad(pt(bx + 0.08, by + 1, bz + 0.92), pt(bx + 0.08, by, bz + 0.92), pt(bx + 0.92, by, bz + 0.08), pt(bx + 0.92, by + 1, bz + 0.08), uvFull));
                bucket.quads.add(new Quad(pt(bx + 0.92, by + 1, bz + 0.08), pt(bx + 0.92, by, bz + 0.08), pt(bx + 0.08, by, bz + 0.92), pt(bx + 0.08, by + 1, bz + 0.92), uvFull));
                break;
            }
            case TORCH: {
                emitBox01(buckets, texSession, st, false, bx, by, bz, 0.4375, 0.0, 0.4375, 0.5625, 0.625, 0.5625);
                break;
            }
            case LANTERN: {
                emitBox01(buckets, texSession, st, false, bx, by, bz, 0.3125, 0.0625, 0.3125, 0.6875, 0.5625, 0.6875);
                break;
            }
            case ROD: {
                emitBox01(buckets, texSession, st, false, bx, by, bz, 0.40625, 0.0, 0.40625, 0.59375, 1.0, 0.59375);
                break;
            }
            case BUTTON: {
                emitBox01(buckets, texSession, st, false, bx, by, bz, 0.3125, 0.375, 0.375, 0.6875, 0.625, 0.625);
                break;
            }
            case FLAT_SURFACE: {
                emitBox01(buckets, texSession, st, false, bx, by, bz, 0.0, 0.0, 0.0, 1.0, 0.03125, 1.0);
                break;
            }
            case SLIM_BOX:
            default: {
                emitBox01(buckets, texSession, st, false, bx, by, bz, 0.0625, 0.0, 0.0625, 0.9375, 0.875, 0.9375);
                break;
            }
        }
    }

    private static void emitProceduralStairs(
            Map<String, MaterialBucket> buckets,
            ExportTextureSession texSession,
            BlockState st,
            double bx, double by, double bz
    ) {
        String half = VanillaBlockAppearanceResolver.readProp(st, PropertyKey.HALF, "bottom");
        String facing = VanillaBlockAppearanceResolver.readProp(st, PropertyKey.FACING, "north");
        String shape = VanillaBlockAppearanceResolver.readProp(st, PropertyKey.SHAPE, "straight");
        boolean topHalf = "top".equals(half);

        if (topHalf) {
            emitBox01(buckets, texSession, st, false, bx, by, bz, 0, 0.5, 0, 1, 1.0, 1);
        } else {
            emitBox01(buckets, texSession, st, false, bx, by, bz, 0, 0.0, 0, 1, 0.5, 1);
        }

        double sy0 = topHalf ? 0.0 : 0.5;
        double sy1 = topHalf ? 0.5 : 1.0;

        int fx = 0, fz = -1;
        switch (facing) {
            case "south": fx = 0;  fz = 1;  break;
            case "east":  fx = 1;  fz = 0;  break;
            case "west":  fx = -1; fz = 0;  break;
            case "north":
            default:      fx = 0;  fz = -1; break;
        }
        int lx = fz, lz = -fx;
        int rx = -fz, rz = fx;

        boolean[][] quadOcc = new boolean[2][2];
        for (int qx = 0; qx < 2; qx++) {
            for (int qz = 0; qz < 2; qz++) {
                int cx = qx == 0 ? -1 : 1;
                int cz = qz == 0 ? -1 : 1;
                boolean inFront = (cx * fx + cz * fz) > 0;
                boolean inLeft = (cx * lx + cz * lz) > 0;
                boolean inRight = (cx * rx + cz * rz) > 0;
                switch (shape) {
                    case "outer_left":  quadOcc[qx][qz] = inFront && inLeft; break;
                    case "outer_right": quadOcc[qx][qz] = inFront && inRight; break;
                    case "inner_left":  quadOcc[qx][qz] = inFront || inLeft; break;
                    case "inner_right": quadOcc[qx][qz] = inFront || inRight; break;
                    case "straight":
                    default:            quadOcc[qx][qz] = inFront; break;
                }
            }
        }

        // Merge adjacent occupied step quadrants along X when possible to reduce quad count
        for (int qz = 0; qz < 2; qz++) {
            double z0 = qz * 0.5;
            double z1 = z0 + 0.5;
            if (quadOcc[0][qz] && quadOcc[1][qz]) {
                emitBox01(buckets, texSession, st, false, bx, by, bz, 0.0, sy0, z0, 1.0, sy1, z1);
            } else if (quadOcc[0][qz]) {
                emitBox01(buckets, texSession, st, false, bx, by, bz, 0.0, sy0, z0, 0.5, sy1, z1);
            } else if (quadOcc[1][qz]) {
                emitBox01(buckets, texSession, st, false, bx, by, bz, 0.5, sy0, z0, 1.0, sy1, z1);
            }
        }
    }

    private static void emitCulledBox01(
            Map<String, MaterialBucket> buckets,
            ExportTextureSession texSession,
            BlockState st,
            BlockType selfType,
            ShapeKind selfKind,
            boolean snowyAbove,
            double bx, double by, double bz,
            double lx0, double ly0, double lz0,
            double lx1, double ly1, double lz1,
            int gx, int gy, int gz,
            int sx, int sy, int sz,
            BlockState[][][] states,
            boolean[][][] fullOpaque,
            boolean opaqueSelf
    ) {
        for (FaceDir dir : FaceDir.values()) {
            int nx = gx + dir.dx;
            int ny = gy + dir.dy;
            int nz = gz + dir.dz;
            if (opaqueSelf) {
                if (nx >= 0 && nx < sx && ny >= 0 && ny < sy && nz >= 0 && nz < sz && fullOpaque[nx][ny][nz]) {
                    continue;
                }
            } else {
                if (shouldCullNeighborFace(nx, ny, nz, sx, sy, sz, selfType, selfKind, states, fullOpaque)) {
                    continue;
                }
            }
            emitSingleBoxFace01(buckets, texSession, st, snowyAbove, dir, bx, by, bz, lx0, ly0, lz0, lx1, ly1, lz1);
        }
    }

    private static void emitBox01(
            Map<String, MaterialBucket> buckets,
            ExportTextureSession texSession,
            BlockState st,
            boolean snowyAbove,
            double bx, double by, double bz,
            double lx0, double ly0, double lz0,
            double lx1, double ly1, double lz1
    ) {
        for (FaceDir dir : FaceDir.values()) {
            emitSingleBoxFace01(buckets, texSession, st, snowyAbove, dir, bx, by, bz, lx0, ly0, lz0, lx1, ly1, lz1);
        }
    }

    private static void emitSingleBoxFace01(
            Map<String, MaterialBucket> buckets,
            ExportTextureSession texSession,
            BlockState st,
            boolean snowyAbove,
            FaceDir dir,
            double bx, double by, double bz,
            double lx0, double ly0, double lz0,
            double lx1, double ly1, double lz1
    ) {
        FaceMaterialAppearance app = texSession.resolveFace(st, dir, snowyAbove);
        MaterialBucket bucket = buckets.computeIfAbsent(app.materialKey, k -> new MaterialBucket(app));

        double x0 = bx + lx0, y0 = by + ly0, z0 = bz + lz0;
        double x1 = bx + lx1, y1 = by + ly1, z1 = bz + lz1;
        double u0, v0, u1, v1;

        switch (dir) {
            case SOUTH: // +Z
                u0 = lx0 * 16.0; v0 = (1.0 - ly1) * 16.0; u1 = lx1 * 16.0; v1 = (1.0 - ly0) * 16.0;
                bucket.quads.add(new Quad(pt(x0, y1, z1), pt(x0, y0, z1), pt(x1, y0, z1), pt(x1, y1, z1), rectUvs(u0, v0, u1, v1)));
                break;
            case NORTH: // -Z
                u0 = (1.0 - lx1) * 16.0; v0 = (1.0 - ly1) * 16.0; u1 = (1.0 - lx0) * 16.0; v1 = (1.0 - ly0) * 16.0;
                bucket.quads.add(new Quad(pt(x1, y1, z0), pt(x1, y0, z0), pt(x0, y0, z0), pt(x0, y1, z0), rectUvs(u0, v0, u1, v1)));
                break;
            case EAST: // +X
                u0 = (1.0 - lz1) * 16.0; v0 = (1.0 - ly1) * 16.0; u1 = (1.0 - lz0) * 16.0; v1 = (1.0 - ly0) * 16.0;
                bucket.quads.add(new Quad(pt(x1, y1, z1), pt(x1, y0, z1), pt(x1, y0, z0), pt(x1, y1, z0), rectUvs(u0, v0, u1, v1)));
                break;
            case WEST: // -X
                u0 = lz0 * 16.0; v0 = (1.0 - ly1) * 16.0; u1 = lz1 * 16.0; v1 = (1.0 - ly0) * 16.0;
                bucket.quads.add(new Quad(pt(x0, y1, z0), pt(x0, y0, z0), pt(x0, y0, z1), pt(x0, y1, z1), rectUvs(u0, v0, u1, v1)));
                break;
            case UP: // +Y
                u0 = lx0 * 16.0; v0 = lz0 * 16.0; u1 = lx1 * 16.0; v1 = lz1 * 16.0;
                bucket.quads.add(new Quad(pt(x0, y1, z0), pt(x0, y1, z1), pt(x1, y1, z1), pt(x1, y1, z0), rectUvs(u0, v0, u1, v1)));
                break;
            case DOWN: // -Y
            default:
                u0 = lx0 * 16.0; v0 = (1.0 - lz1) * 16.0; u1 = lx1 * 16.0; v1 = (1.0 - lz0) * 16.0;
                bucket.quads.add(new Quad(pt(x0, y0, z1), pt(x0, y0, z0), pt(x1, y0, z0), pt(x1, y0, z1), rectUvs(u0, v0, u1, v1)));
                break;
        }
    }

    private static double[][] rectUvs(double u0, double v0, double u1, double v1) {
        return new double[][]{{u0, v0}, {u0, v1}, {u1, v1}, {u1, v0}};
    }

    private static boolean shouldCullNeighborFace(
            int nx, int ny, int nz,
            int sx, int sy, int sz,
            BlockType selfType,
            ShapeKind selfKind,
            BlockState[][][] states,
            boolean[][][] fullOpaque
    ) {
        if (nx < 0 || nx >= sx || ny < 0 || ny >= sy || nz < 0 || nz >= sz) {
            return false;
        }
        if (fullOpaque[nx][ny][nz]) {
            return true;
        }
        if (selfKind == ShapeKind.TRANSLUCENT_CUBE || selfKind == ShapeKind.CUTOUT_CUBE) {
            BlockState nst = states[nx][ny][nz];
            return nst != null && selfType.equals(nst.getBlockType());
        }
        return false;
    }

    private static Map<String, String> computeConnectionProps(
            ShapeKind kind,
            int x, int y, int z,
            int sx, int sy, int sz,
            BlockState[][][] states,
            ShapeKind[][][] kinds,
            boolean[][][] fullOpaque
    ) {
        Map<String, String> m = new HashMap<>();
        boolean cn = connectsHoriz(kind, x, y, z - 1, sx, sy, sz, states, kinds, fullOpaque);
        boolean cs = connectsHoriz(kind, x, y, z + 1, sx, sy, sz, states, kinds, fullOpaque);
        boolean cw = connectsHoriz(kind, x - 1, y, z, sx, sy, sz, states, kinds, fullOpaque);
        boolean ce = connectsHoriz(kind, x + 1, y, z, sx, sy, sz, states, kinds, fullOpaque);

        if (kind == ShapeKind.WALL) {
            m.put("north", cn ? "low" : "none");
            m.put("south", cs ? "low" : "none");
            m.put("west", cw ? "low" : "none");
            m.put("east", ce ? "low" : "none");
            boolean straightLine = (cn && cs && !cw && !ce) || (cw && ce && !cn && !cs);
            boolean abovePresent = (y + 1 < sy && states[x][y + 1][z] != null);
            m.put("up", (!straightLine || abovePresent) ? "true" : "false");
        } else {
            m.put("north", String.valueOf(cn));
            m.put("south", String.valueOf(cs));
            m.put("west", String.valueOf(cw));
            m.put("east", String.valueOf(ce));
        }
        return m;
    }

    private static boolean connectsHoriz(
            ShapeKind selfKind,
            int nx, int ny, int nz,
            int sx, int sy, int sz,
            BlockState[][][] states,
            ShapeKind[][][] kinds,
            boolean[][][] fullOpaque
    ) {
        if (nx < 0 || nx >= sx || ny < 0 || ny >= sy || nz < 0 || nz >= sz) {
            return false;
        }
        if (states[nx][ny][nz] == null) {
            return false;
        }
        if (fullOpaque[nx][ny][nz]) {
            return true;
        }
        ShapeKind nk = kinds[nx][ny][nz];
        if (selfKind == ShapeKind.WALL) {
            return nk == ShapeKind.WALL || nk == ShapeKind.FENCE_GATE || nk == ShapeKind.PANE;
        }
        if (selfKind == ShapeKind.FENCE) {
            return nk == ShapeKind.FENCE || nk == ShapeKind.FENCE_GATE;
        }
        if (selfKind == ShapeKind.PANE) {
            return nk == ShapeKind.PANE || nk == ShapeKind.TRANSLUCENT_CUBE || nk == ShapeKind.WALL;
        }
        return false;
    }

    private static boolean isStateFullOpaque(BlockState st, ShapeKind kind) {
        if (kind == ShapeKind.CUBE) {
            return true;
        }
        if (kind == ShapeKind.SLAB) {
            return "double".equalsIgnoreCase(VanillaBlockAppearanceResolver.readProp(st, PropertyKey.TYPE, "bottom"));
        }
        if (kind == ShapeKind.SNOW) {
            return "8".equals(VanillaBlockAppearanceResolver.readProp(st, PropertyKey.LAYERS, "1"));
        }
        return false;
    }

    private static int mixPositionHash(int x, int y, int z) {
        long h = (long) x * 3129871L ^ (long) z * 116129781L ^ (long) y;
        h = h * h * 42317861L + h * 11L;
        return (int) (h >> 16);
    }

    private static double[] pt(double x, double y, double z) {
        return new double[]{x, y, z};
    }

    private static String sanitizeFileName(String raw) {
        if (raw == null) {
            return "export_build";
        }
        return raw.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
