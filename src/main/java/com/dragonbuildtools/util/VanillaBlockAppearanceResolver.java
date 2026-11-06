package com.dragonbuildtools.util;

import com.dragonbuildtools.DragonBuildToolsPlugin;
import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.core.configuration.Settings;
import com.fastasyncworldedit.core.registry.state.PropertyKey;
import com.fastasyncworldedit.core.util.MainUtil;
import com.fastasyncworldedit.core.util.TextureUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sk89q.worldedit.registry.state.Property;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypes;
import com.sk89q.worldedit.world.registry.BlockMaterial;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

/**
 * Resolves per-face sRGB colors, resource-pack / vanilla block textures, and exact 3D block models
 * for all 1,200+ Minecraft blocks across 1.20.x, 1.21.x, and 26.x.
 *
 * <p>Supports user resource packs placed in {@code plugins/DragonBuildTools/resourcepacks/} (both {@code .zip}/{@code .jar}
 * archives and unzipped pack folders) layered over FAWE's runtime-downloaded Mojang client asset JAR
 * ({@code plugins/FastAsyncWorldEdit/textures/*.jar}), with zero vanilla assets embedded inside the plugin JAR.
 */
public final class VanillaBlockAppearanceResolver {

    public enum ShapeKind {
        CUBE,
        CUTOUT_CUBE,
        TRANSLUCENT_CUBE,
        CROSS,
        STAIRS,
        SLAB,
        SNOW,
        CARPET,
        WALL,
        FENCE,
        FENCE_GATE,
        TRAPDOOR,
        DOOR,
        PANE,
        ROD,
        TORCH,
        LANTERN,
        PLATE,
        BUTTON,
        FLAT_SURFACE,
        SLIM_BOX,
        INVISIBLE
    }

    public enum FaceDir {
        UP(0, 1, 0),
        DOWN(0, -1, 0),
        NORTH(0, 0, -1),
        SOUTH(0, 0, 1),
        EAST(1, 0, 0),
        WEST(-1, 0, 0);

        public final int dx;
        public final int dy;
        public final int dz;

        FaceDir(int dx, int dy, int dz) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
        }

        public static FaceDir fromName(String name) {
            if (name == null) return null;
            switch (name.toLowerCase(Locale.ROOT)) {
                case "up": return UP;
                case "down": return DOWN;
                case "north": return NORTH;
                case "south": return SOUTH;
                case "east": return EAST;
                case "west": return WEST;
                default: return null;
            }
        }

        public static FaceDir fromNormal(int nx, int ny, int nz) {
            if (ny > 0) return UP;
            if (ny < 0) return DOWN;
            if (nz < 0) return NORTH;
            if (nz > 0) return SOUTH;
            if (nx > 0) return EAST;
            return WEST;
        }
    }

    public static final class BlockColorRecord {
        public final String blockName;
        public final int[] topRgb;
        public final int[] bottomRgb;
        public final int[] sideRgb;
        public final int[] frontRgb;
        public final ShapeKind shapeKind;

        public BlockColorRecord(
                String blockName,
                int[] topRgb,
                int[] bottomRgb,
                int[] sideRgb,
                int[] frontRgb,
                ShapeKind shapeKind
        ) {
            this.blockName = blockName;
            this.topRgb = topRgb;
            this.bottomRgb = bottomRgb;
            this.sideRgb = sideRgb;
            this.frontRgb = frontRgb;
            this.shapeKind = shapeKind;
        }
    }

    public static final class FaceMaterialAppearance {
        public final String materialKey;
        public final int r;
        public final int g;
        public final int b;
        public final BufferedImage texture;
        public final boolean cutoutAlpha;
        public final boolean translucent;

        public FaceMaterialAppearance(
                String materialKey,
                int r,
                int g,
                int b,
                BufferedImage texture,
                boolean cutoutAlpha,
                boolean translucent
        ) {
            this.materialKey = materialKey;
            this.r = r;
            this.g = g;
            this.b = b;
            this.texture = texture;
            this.cutoutAlpha = cutoutAlpha;
            this.translucent = translucent;
        }
    }

    /**
     * A resolved 3D quad in block-local [0..1]^3 coordinates with Minecraft [0..16] pixel UVs
     * and optional rotated cullface direction.
     */
    public static final class TexturedModelQuad {
        public final FaceMaterialAppearance material;
        public final double[][] verts; // [4][3] in block-local [0..1]
        public final double[][] mcUvs; // [4][2] in [0..16] top-left PNG space
        public final FaceDir cullFace; // nullable

        public TexturedModelQuad(
                FaceMaterialAppearance material,
                double[][] verts,
                double[][] mcUvs,
                FaceDir cullFace
        ) {
            this.material = material;
            this.verts = verts;
            this.mcUvs = mcUvs;
            this.cullFace = cullFace;
        }
    }

    private interface AssetLayer extends AutoCloseable {
        byte[] readEntry(String relativePath);

        String name();

        @Override
        void close();
    }

    private static final class ZipAssetLayer implements AssetLayer {
        private final String name;
        private final ZipFile zip;
        private final String rootPrefix;

        ZipAssetLayer(File file) throws Exception {
            this.name = file.getName();
            this.zip = new ZipFile(file);
            this.rootPrefix = detectZipRootPrefix(this.zip);
        }

        private static String detectZipRootPrefix(ZipFile zf) {
            if (zf.getEntry("assets/minecraft/") != null || zf.getEntry("pack.mcmeta") != null) {
                return "";
            }
            // Support resource pack ZIPs that wrap everything in a single top-level folder
            var entries = zf.entries();
            int checked = 0;
            while (entries.hasMoreElements() && checked++ < 200) {
                String n = entries.nextElement().getName();
                int idx = n.indexOf("assets/minecraft/");
                if (idx > 0) {
                    return n.substring(0, idx);
                }
            }
            return "";
        }

        @Override
        public byte[] readEntry(String relativePath) {
            try {
                ZipEntry entry = zip.getEntry(rootPrefix + relativePath);
                if (entry == null) {
                    return null;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    return in.readAllBytes();
                }
            } catch (Throwable ignored) {
                return null;
            }
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void close() {
            try {
                zip.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static final class DirectoryAssetLayer implements AssetLayer {
        private final String name;
        private final File baseDir;

        DirectoryAssetLayer(File dir) {
            this.name = dir.getName();
            File sub = new File(dir, "assets/minecraft");
            if (!sub.isDirectory()) {
                File[] children = dir.listFiles(File::isDirectory);
                if (children != null && children.length == 1 && new File(children[0], "assets/minecraft").isDirectory()) {
                    dir = children[0];
                }
            }
            this.baseDir = dir;
        }

        @Override
        public byte[] readEntry(String relativePath) {
            try {
                File f = new File(baseDir, relativePath);
                if (f.isFile()) {
                    return Files.readAllBytes(f.toPath());
                }
            } catch (Throwable ignored) {
            }
            return null;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void close() {
        }
    }

    private static final int[] GRASS_TINT = {0x91, 0xBD, 0x59};
    private static final int[] FOLIAGE_TINT = {0x77, 0xAB, 0x2F};
    private static final int[] SPRUCE_TINT = {0x61, 0x99, 0x61};
    private static final int[] BIRCH_TINT = {0x80, 0xA7, 0x55};
    private static final int[] MANGROVE_TINT = {0x92, 0xC6, 0x48};
    private static final int[] LILYPAD_TINT = {0x20, 0x80, 0x30};
    private static final int[] WATER_TINT = {0x3F, 0x76, 0xE4};
    private static final int[] REDSTONE_TINT = {0xAA, 0x00, 0x00};
    private static final int[] STEM_TINT = {0xA7, 0xB8, 0x36};
    private static final int[] LEAF_LITTER_TINT = {0xA3, 0x75, 0x46};

    private static final Map<String, int[]> TINT_BY_BLOCK = buildTintMap();
    private static volatile Map<String, BlockColorRecord> embeddedTable;
    private static final Map<String, int[]> dynamicFallbackCache = new ConcurrentHashMap<>();

    private VanillaBlockAppearanceResolver() {
    }

    private static Map<String, int[]> buildTintMap() {
        Map<String, int[]> m = new HashMap<>();
        for (String g : new String[]{
                "grass_block", "short_grass", "grass", "tall_grass", "fern", "large_fern",
                "potted_fern", "sugar_cane", "pink_petals", "wildflowers", "bush"
        }) {
            m.put(g, GRASS_TINT);
        }
        for (String f : new String[]{"oak_leaves", "jungle_leaves", "acacia_leaves", "dark_oak_leaves", "vine"}) {
            m.put(f, FOLIAGE_TINT);
        }
        m.put("spruce_leaves", SPRUCE_TINT);
        m.put("birch_leaves", BIRCH_TINT);
        m.put("mangrove_leaves", MANGROVE_TINT);
        m.put("lily_pad", LILYPAD_TINT);
        m.put("leaf_litter", LEAF_LITTER_TINT);
        m.put("water", WATER_TINT);
        m.put("water_cauldron", WATER_TINT);
        m.put("bubble_column", WATER_TINT);
        m.put("redstone_wire", REDSTONE_TINT);
        m.put("melon_stem", STEM_TINT);
        m.put("pumpkin_stem", STEM_TINT);
        m.put("attached_melon_stem", STEM_TINT);
        m.put("attached_pumpkin_stem", STEM_TINT);
        return Collections.unmodifiableMap(m);
    }

    public static File getResourcePacksDirectory() {
        DragonBuildToolsPlugin plugin = DragonBuildToolsPlugin.getInstance();
        File base = plugin != null ? plugin.getDataFolder() : new File(".");
        File dir = new File(base, "resourcepacks");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static Map<String, BlockColorRecord> getEmbeddedTable() {
        Map<String, BlockColorRecord> current = embeddedTable;
        if (current != null) {
            return current;
        }
        synchronized (VanillaBlockAppearanceResolver.class) {
            if (embeddedTable != null) {
                return embeddedTable;
            }
            Map<String, BlockColorRecord> loaded = new HashMap<>(1400);
            try (InputStream in = VanillaBlockAppearanceResolver.class.getResourceAsStream("/vanilla_block_colors.tsv")) {
                if (in != null) {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            line = line.trim();
                            if (line.isEmpty() || line.startsWith("#")) {
                                continue;
                            }
                            String[] parts = line.split("\t");
                            if (parts.length < 6) {
                                continue;
                            }
                            String name = parts[0].trim().toLowerCase(Locale.ROOT);
                            int[] top = parseHexRgb(parts[1]);
                            int[] bot = parseHexRgb(parts[2]);
                            int[] side = parseHexRgb(parts[3]);
                            int[] front = parseHexRgb(parts[4]);
                            ShapeKind kind;
                            try {
                                kind = ShapeKind.valueOf(parts[5].trim());
                            } catch (IllegalArgumentException e) {
                                kind = ShapeKind.CUBE;
                            }
                            loaded.put(name, new BlockColorRecord(name, top, bot, side, front, kind));
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            embeddedTable = Collections.unmodifiableMap(loaded);
            return embeddedTable;
        }
    }

    public static String cleanBlockName(String rawId) {
        if (rawId == null) {
            return "stone";
        }
        String s = rawId.trim().toLowerCase(Locale.ROOT);
        int colon = s.indexOf(':');
        if (colon >= 0) {
            s = s.substring(colon + 1);
        }
        int bracket = s.indexOf('[');
        if (bracket >= 0) {
            s = s.substring(0, bracket);
        }
        return s;
    }

    public static ShapeKind getShapeKind(BlockState st) {
        if (st == null || st.getBlockType() == null) {
            return ShapeKind.INVISIBLE;
        }
        String name = cleanBlockName(st.getBlockType().id());
        BlockColorRecord rec = lookupRecordWithVariantFallback(name);
        if (rec != null) {
            return rec.shapeKind;
        }
        return inferShapeKindByNameAndMaterial(name, st.getBlockType());
    }

    private static ShapeKind inferShapeKindByNameAndMaterial(String name, BlockType bt) {
        if (name.equals("air") || name.equals("cave_air") || name.equals("void_air")
                || name.equals("structure_void") || name.equals("barrier") || name.equals("light")
                || name.equals("moving_piston")) {
            return ShapeKind.INVISIBLE;
        }
        if (name.endsWith("_stairs")) return ShapeKind.STAIRS;
        if (name.endsWith("_slab")) return ShapeKind.SLAB;
        if (name.equals("snow")) return ShapeKind.SNOW;
        if (name.endsWith("_carpet")) return ShapeKind.CARPET;
        if (name.endsWith("_wall")) return ShapeKind.WALL;
        if (name.endsWith("_fence")) return ShapeKind.FENCE;
        if (name.endsWith("_fence_gate")) return ShapeKind.FENCE_GATE;
        if (name.endsWith("_trapdoor")) return ShapeKind.TRAPDOOR;
        if (name.endsWith("_door")) return ShapeKind.DOOR;
        if (name.endsWith("_pane") || name.endsWith("_bars")) return ShapeKind.PANE;
        if (name.endsWith("_pressure_plate")) return ShapeKind.PLATE;
        if (name.endsWith("_button")) return ShapeKind.BUTTON;
        if (name.contains("torch")) return ShapeKind.TORCH;
        if (name.contains("lantern") && !name.equals("sea_lantern") && !name.equals("jack_o_lantern")) return ShapeKind.LANTERN;
        if (name.endsWith("_rod") || name.contains("chain")) return ShapeKind.ROD;
        if (name.contains("leaves")) return ShapeKind.CUTOUT_CUBE;
        if (name.contains("glass") || name.contains("ice") || name.contains("water") || name.contains("slime") || name.contains("honey")) {
            return ShapeKind.TRANSLUCENT_CUBE;
        }
        try {
            BlockMaterial mat = bt != null ? bt.getMaterial() : null;
            if (mat != null) {
                if (mat.isAir()) return ShapeKind.INVISIBLE;
                if (!mat.isFullCube()) return ShapeKind.SLIM_BOX;
                if (mat.isTranslucent() || !mat.isOpaque()) return ShapeKind.CUTOUT_CUBE;
            }
        } catch (Throwable ignored) {
        }
        return ShapeKind.CUBE;
    }

    public static int[] getAverageRgb(BlockType bt) {
        if (bt == null) {
            return new int[]{140, 140, 140};
        }
        String name = cleanBlockName(bt.id());
        BlockColorRecord rec = lookupRecordWithVariantFallback(name);
        if (rec != null && rec.shapeKind != ShapeKind.INVISIBLE) {
            int r = (rec.sideRgb[0] * 2 + rec.topRgb[0]) / 3;
            int g = (rec.sideRgb[1] * 2 + rec.topRgb[1]) / 3;
            int b = (rec.sideRgb[2] * 2 + rec.topRgb[2]) / 3;
            return new int[]{r, g, b};
        }
        return queryRuntimeFallbackRgb(name, bt);
    }

    private static BlockColorRecord lookupRecordWithVariantFallback(String name) {
        Map<String, BlockColorRecord> table = getEmbeddedTable();
        BlockColorRecord direct = table.get(name);
        if (direct != null) {
            return direct;
        }
        String stripped = stripBlockVariantAffixes(name);
        if (!stripped.equals(name)) {
            for (String candidate : new String[]{
                    stripped,
                    stripped + "s",
                    stripped + "_planks",
                    stripped + "_block",
                    stripped + "_bricks"
            }) {
                BlockColorRecord parent = table.get(candidate);
                if (parent != null) {
                    ShapeKind actualKind = inferShapeKindByNameAndMaterial(name, null);
                    return new BlockColorRecord(name, parent.topRgb, parent.bottomRgb, parent.sideRgb, parent.frontRgb, actualKind);
                }
            }
        }
        return null;
    }

    private static String stripBlockVariantAffixes(String name) {
        String s = name;
        if (s.startsWith("waxed_")) {
            s = s.substring("waxed_".length());
        }
        if (s.startsWith("potted_")) {
            s = s.substring("potted_".length());
        }
        String[] suffixes = {
                "_stairs", "_slab", "_wall", "_fence_gate", "_fence",
                "_trapdoor", "_door", "_pressure_plate", "_button",
                "_pane", "_carpet", "_wall_sign", "_hanging_sign", "_wall_hanging_sign", "_sign", "_wall_banner", "_banner"
        };
        for (String suf : suffixes) {
            if (s.endsWith(suf) && s.length() > suf.length()) {
                s = s.substring(0, s.length() - suf.length());
                break;
            }
        }
        return s;
    }

    private static int[] queryRuntimeFallbackRgb(String cleanName, BlockType bt) {
        int[] cached = dynamicFallbackCache.get(cleanName);
        if (cached != null) {
            return new int[]{cached[0], cached[1], cached[2]};
        }

        int[] fromFawe = tryFaweTextureUtilRgb(bt);
        if (fromFawe == null) {
            String parentName = stripBlockVariantAffixes(cleanName);
            if (!parentName.equals(cleanName)) {
                try {
                    BlockType parentBt = BlockTypes.get("minecraft:" + parentName);
                    if (parentBt == null) parentBt = BlockTypes.get("minecraft:" + parentName + "_planks");
                    if (parentBt == null) parentBt = BlockTypes.get("minecraft:" + parentName + "_block");
                    if (parentBt == null) parentBt = BlockTypes.get("minecraft:" + parentName + "s");
                    fromFawe = tryFaweTextureUtilRgb(parentBt);
                } catch (Throwable ignored) {
                }
            }
        }
        if (fromFawe != null) {
            dynamicFallbackCache.put(cleanName, fromFawe);
            return new int[]{fromFawe[0], fromFawe[1], fromFawe[2]};
        }

        try {
            if (Bukkit.getServer() != null) {
                Material mat = Material.matchMaterial("minecraft:" + cleanName);
                if (mat == null) {
                    mat = Material.matchMaterial(cleanName.toUpperCase(Locale.ROOT));
                }
                if (mat != null && mat.isBlock()) {
                    BlockData bd = mat.createBlockData();
                    Color mc = bd.getMapColor();
                    if (mc != null && (mc.getRed() > 0 || mc.getGreen() > 0 || mc.getBlue() > 0)) {
                        int[] res = {mc.getRed(), mc.getGreen(), mc.getBlue()};
                        dynamicFallbackCache.put(cleanName, res);
                        return new int[]{res[0], res[1], res[2]};
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            if (bt != null && bt.getMaterial() != null) {
                int mc = bt.getMaterial().getMapColor();
                if ((mc & 0xFFFFFF) != 0) {
                    int[] res = {(mc >>> 16) & 0xFF, (mc >>> 8) & 0xFF, mc & 0xFF};
                    dynamicFallbackCache.put(cleanName, res);
                    return new int[]{res[0], res[1], res[2]};
                }
            }
        } catch (Throwable ignored) {
        }

        return new int[]{140, 140, 140};
    }

    private static int[] tryFaweTextureUtilRgb(BlockType bt) {
        if (bt == null) {
            return null;
        }
        try {
            Fawe fawe = Fawe.instance();
            if (fawe == null) {
                return null;
            }
            TextureUtil tu = fawe.getTextureUtil();
            if (tu == null) {
                return null;
            }
            int c = tu.getColor(bt);
            if (((c >>> 24) & 0xFF) > 0 && (c & 0xFFFFFF) != 0) {
                return new int[]{(c >>> 16) & 0xFF, (c >>> 8) & 0xFF, c & 0xFF};
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Session-scoped resource-pack + vanilla asset resolver for {@code //exportobj}.
     */
    public static final class ExportTextureSession implements AutoCloseable {
        private final List<AssetLayer> layers = new ArrayList<>();
        private final Map<String, JsonObject> blockstateJsonCache = new HashMap<>();
        private final Map<String, JsonObject> modelJsonCache = new HashMap<>();
        private final Map<String, BufferedImage> rawTextureCache = new HashMap<>();
        private final Map<String, ResolvedModelFaces> resolvedBlockCache = new HashMap<>();
        private final Map<String, FaceMaterialAppearance> materialPool = new LinkedHashMap<>();

        private static final class ResolvedModelFaces {
            final FaceMaterialAppearance top;
            final FaceMaterialAppearance bottom;
            final FaceMaterialAppearance side;
            final FaceMaterialAppearance front;

            ResolvedModelFaces(
                    FaceMaterialAppearance top,
                    FaceMaterialAppearance bottom,
                    FaceMaterialAppearance side,
                    FaceMaterialAppearance front
            ) {
                this.top = top;
                this.bottom = bottom;
                this.side = side;
                this.front = front;
            }
        }

        private static final class AppliedModelSpec {
            final String modelRef;
            final int xRot;
            final int yRot;
            final boolean uvLock;

            AppliedModelSpec(String modelRef, int xRot, int yRot, boolean uvLock) {
                this.modelRef = modelRef;
                this.xRot = ((xRot % 360) + 360) % 360;
                this.yRot = ((yRot % 360) + 360) % 360;
                this.uvLock = uvLock;
            }
        }

        public ExportTextureSession() {
            this((String) null);
        }

        public ExportTextureSession(String preferredPackName) {
            if ("none".equalsIgnoreCase(preferredPackName) || "colors".equalsIgnoreCase(preferredPackName)) {
                return;
            }
            for (File f : discoverAssetSources(preferredPackName)) {
                addSourceFileOrDir(f);
            }
        }

        public ExportTextureSession(List<File> explicitSources) {
            if (explicitSources != null) {
                for (File f : explicitSources) {
                    addSourceFileOrDir(f);
                }
            }
        }

        private void addSourceFileOrDir(File f) {
            if (f == null || !f.exists()) {
                return;
            }
            try {
                if (f.isDirectory()) {
                    layers.add(new DirectoryAssetLayer(f));
                } else if (f.isFile() && f.length() > 0) {
                    layers.add(new ZipAssetLayer(f));
                }
            } catch (Throwable ignored) {
            }
        }

        public boolean hasAssetPacks() {
            return !layers.isEmpty();
        }

        public List<String> getActiveLayerNames() {
            List<String> names = new ArrayList<>(layers.size());
            for (AssetLayer l : layers) {
                names.add(l.name());
            }
            return names;
        }

        public List<FaceMaterialAppearance> getUsedMaterials() {
            return new ArrayList<>(materialPool.values());
        }

        /**
         * Resolves exact 3D quads directly from the resource pack / Mojang blockstate + model JSONs
         * (including custom 3D resource pack models, rotated stairs/logs/pillars, multipart fences/walls/panes,
         * cross plants, torches, lanterns, trapdoors, etc.).
         * Returns {@code null} if no JSON model with elements exists for this block so the caller can use procedural fallback.
         */
        public List<TexturedModelQuad> resolveBlockModelQuads(
                BlockState st,
                boolean snowyAbove,
                Map<String, String> neighborConnOverrides,
                int posHash
        ) {
            if (layers.isEmpty() || st == null || st.getBlockType() == null) {
                return null;
            }
            Map<String, String> stateProps = extractStatePropertiesMap(st);
            if (neighborConnOverrides != null && !neighborConnOverrides.isEmpty()) {
                stateProps.putAll(neighborConnOverrides);
            }
            return resolveBlockModelQuadsByName(
                    st.getBlockType().id(),
                    stateProps,
                    snowyAbove,
                    posHash,
                    st.getBlockType()
            );
        }

        public List<TexturedModelQuad> resolveBlockModelQuadsByName(
                String rawBlockName,
                Map<String, String> inputStateProps,
                boolean snowyAbove,
                int posHash
        ) {
            return resolveBlockModelQuadsByName(rawBlockName, inputStateProps, snowyAbove, posHash, null);
        }

        private List<TexturedModelQuad> resolveBlockModelQuadsByName(
                String rawBlockName,
                Map<String, String> inputStateProps,
                boolean snowyAbove,
                int posHash,
                BlockType optionalBt
        ) {
            if (layers.isEmpty() || rawBlockName == null) {
                return null;
            }
            String blockName = cleanBlockName(rawBlockName);
            JsonObject bsJson = readBlockstateJson(blockName);
            if (bsJson == null) {
                return null;
            }

            Map<String, String> stateProps = inputStateProps != null
                    ? new HashMap<>(inputStateProps)
                    : new HashMap<>();
            if (snowyAbove && (stateProps.containsKey("snowy") || blockName.equals("grass_block")
                    || blockName.equals("podzol") || blockName.equals("mycelium"))) {
                stateProps.put("snowy", "true");
            }

            List<AppliedModelSpec> applied = selectAppliedModels(bsJson, stateProps, posHash);
            if (applied.isEmpty()) {
                return null;
            }

            BlockColorRecord rec = lookupRecordWithVariantFallback(blockName);
            ShapeKind kind = rec != null ? rec.shapeKind : inferShapeKindByNameAndMaterial(blockName, optionalBt);
            boolean blockTranslucent = (kind == ShapeKind.TRANSLUCENT_CUBE);
            int[] fallbackRgb = rec != null ? rec.sideRgb : queryRuntimeFallbackRgb(blockName, optionalBt);

            List<TexturedModelQuad> quads = new ArrayList<>();
            boolean foundAnyElements = false;

            for (AppliedModelSpec spec : applied) {
                Map<String, String> texMap = new HashMap<>();
                JsonArray elements = resolveModelChain(spec.modelRef, texMap);
                if (elements == null || elements.isEmpty()) {
                    continue;
                }
                foundAnyElements = true;
                boolean hasGrassSideOverlay = blockName.equals("grass_block")
                        && !"true".equals(stateProps.get("snowy"))
                        && texMap.containsKey("overlay");

                for (JsonElement elElem : elements) {
                    if (!elElem.isJsonObject()) continue;
                    JsonObject el = elElem.getAsJsonObject();
                    double[] from = readVec3(el, "from", 0, 0, 0);
                    double[] to = readVec3(el, "to", 16, 16, 16);
                    JsonObject rotObj = el.has("rotation") && el.get("rotation").isJsonObject()
                            ? el.getAsJsonObject("rotation") : null;
                    JsonObject facesObj = el.has("faces") && el.get("faces").isJsonObject()
                            ? el.getAsJsonObject("faces") : null;
                    if (facesObj == null) continue;

                    for (Map.Entry<String, JsonElement> fe : facesObj.entrySet()) {
                        FaceDir faceDir = FaceDir.fromName(fe.getKey());
                        if (faceDir == null || !fe.getValue().isJsonObject()) continue;
                        JsonObject fObj = fe.getValue().getAsJsonObject();
                        String rawTex = getJsonString(fObj, "texture");
                        if (rawTex == null || rawTex.isEmpty()) continue;

                        // Skip coplanar grass_block #overlay element because we composite tinted overlay directly onto grass_block_side
                        if (hasGrassSideOverlay && "#overlay".equals(rawTex)) {
                            continue;
                        }

                        String resolvedTex = rawTex.startsWith("#") ? texMap.get(rawTex.substring(1)) : rawTex;
                        if (resolvedTex == null || resolvedTex.isEmpty()) {
                            continue;
                        }

                        boolean hasTint = fObj.has("tintindex");
                        boolean compositeGrass = hasGrassSideOverlay
                                && (faceDir == FaceDir.NORTH || faceDir == FaceDir.SOUTH || faceDir == FaceDir.EAST || faceDir == FaceDir.WEST)
                                && resolvedTex.endsWith("grass_block_side");

                        FaceMaterialAppearance mat = loadFaceFromTexRef(
                                blockName, resolvedTex, hasTint, compositeGrass, blockTranslucent, fallbackRgb, kind
                        );

                        // Build [4][3] vertices in [0..16] CCW outward order (top-left, bottom-left, bottom-right, top-right)
                        double[][] verts16 = buildFaceVertices16(faceDir, from, to);
                        double[] uvRect = readFaceUv(fObj, faceDir, from, to);
                        int faceTexRot = fObj.has("rotation") ? fObj.get("rotation").getAsInt() : 0;
                        double[][] mcUvs = buildFaceCornersUv(uvRect, faceTexRot);

                        // Element local rotation (22.5 / 45 deg, e.g. cross plants, lanterns, lecterns, anvils)
                        if (rotObj != null) {
                            applyElementRotation16(verts16, rotObj);
                        }

                        // Blockstate X/Y rotation around [8, 8, 8]
                        FaceDir rotatedDir = rotateDirection(faceDir, spec.xRot, spec.yRot);
                        if (spec.xRot != 0 || spec.yRot != 0) {
                            for (int i = 0; i < 4; i++) {
                                rotatePointBlockstate16(verts16[i], spec.xRot, spec.yRot);
                            }
                            if (spec.uvLock && rotObj == null) {
                                for (int i = 0; i < 4; i++) {
                                    mcUvs[i] = projectUvLock16(rotatedDir, verts16[i]);
                                }
                            }
                        }

                        FaceDir cullFace = null;
                        String rawCull = getJsonString(fObj, "cullface");
                        if (rawCull != null) {
                            FaceDir baseCull = FaceDir.fromName(rawCull);
                            if (baseCull != null) {
                                cullFace = rotateDirection(baseCull, spec.xRot, spec.yRot);
                            }
                        }

                        // Scale [0..16] -> [0..1]
                        double[][] verts01 = new double[4][3];
                        for (int i = 0; i < 4; i++) {
                            verts01[i][0] = verts16[i][0] / 16.0;
                            verts01[i][1] = verts16[i][1] / 16.0;
                            verts01[i][2] = verts16[i][2] / 16.0;
                        }
                        quads.add(new TexturedModelQuad(mat, verts01, mcUvs, cullFace));
                    }
                }
            }

            return foundAnyElements ? quads : null;
        }

        public FaceMaterialAppearance resolveFace(BlockState st, FaceDir worldDir, boolean snowyAbove) {
            String blockName = cleanBlockName(st.getBlockType().id());
            boolean snowy = snowyAbove;
            if (!snowy && blockName.equals("grass_block")) {
                snowy = "true".equalsIgnoreCase(readProp(st, PropertyKey.SNOWY, "false"));
            }
            final boolean finalSnowy = snowy;
            String axis = readProp(st, PropertyKey.AXIS, "y");
            String facing = readProp(st, PropertyKey.FACING, "north");

            FaceDir canonicalRole = mapAxisAndFacing(worldDir, axis, facing);
            String stateKey = finalSnowy ? (blockName + "[snowy=true]") : blockName;

            ResolvedModelFaces faces = resolvedBlockCache.computeIfAbsent(
                    stateKey,
                    k -> buildBlockFaces(blockName, st.getBlockType(), st, finalSnowy)
            );
            switch (canonicalRole) {
                case UP:
                    return faces.top;
                case DOWN:
                    return faces.bottom;
                case NORTH:
                    return faces.front;
                case SOUTH:
                case EAST:
                case WEST:
                default:
                    return faces.side;
            }
        }

        private static Map<String, String> extractStatePropertiesMap(BlockState st) {
            Map<String, String> out = new HashMap<>();
            try {
                Map<Property<?>, Object> raw = st.getStates();
                if (raw != null) {
                    for (Map.Entry<Property<?>, Object> e : raw.entrySet()) {
                        if (e.getKey() != null && e.getValue() != null) {
                            out.put(
                                    e.getKey().getName().toLowerCase(Locale.ROOT),
                                    e.getValue().toString().toLowerCase(Locale.ROOT)
                            );
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            return out;
        }

        private static List<AppliedModelSpec> selectAppliedModels(
                JsonObject bsJson,
                Map<String, String> stateProps,
                int posHash
        ) {
            List<AppliedModelSpec> result = new ArrayList<>();
            if (bsJson.has("variants") && bsJson.get("variants").isJsonObject()) {
                JsonObject variants = bsJson.getAsJsonObject("variants");
                String bestKey = null;
                int bestMatchedProps = -1;
                for (Map.Entry<String, JsonElement> entry : variants.entrySet()) {
                    String key = entry.getKey().trim();
                    if (key.isEmpty()) {
                        if (bestMatchedProps < 0) {
                            bestMatchedProps = 0;
                            bestKey = key;
                        }
                        continue;
                    }
                    String[] clauses = key.split(",");
                    boolean allMatch = true;
                    for (String clause : clauses) {
                        int eq = clause.indexOf('=');
                        if (eq <= 0) continue;
                        String pName = clause.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                        String expected = clause.substring(eq + 1).trim().toLowerCase(Locale.ROOT);
                        String actual = stateProps.get(pName);
                        if (actual != null && !actual.equals(expected)) {
                            allMatch = false;
                            break;
                        }
                    }
                    if (allMatch && clauses.length > bestMatchedProps) {
                        bestMatchedProps = clauses.length;
                        bestKey = key;
                    }
                }
                if (bestKey == null && !variants.keySet().isEmpty()) {
                    bestKey = variants.keySet().iterator().next();
                }
                if (bestKey != null) {
                    AppliedModelSpec spec = parseApplyElement(variants.get(bestKey), posHash);
                    if (spec != null) {
                        result.add(spec);
                    }
                }
                return result;
            }

            if (bsJson.has("multipart") && bsJson.get("multipart").isJsonArray()) {
                int partIdx = 0;
                for (JsonElement partEl : bsJson.getAsJsonArray("multipart")) {
                    if (!partEl.isJsonObject()) continue;
                    JsonObject part = partEl.getAsJsonObject();
                    boolean matches = true;
                    if (part.has("when") && part.get("when").isJsonObject()) {
                        matches = evaluateMultipartWhen(part.getAsJsonObject("when"), stateProps);
                    }
                    if (matches && part.has("apply")) {
                        AppliedModelSpec spec = parseApplyElement(part.get("apply"), posHash + partIdx * 31);
                        if (spec != null) {
                            result.add(spec);
                        }
                    }
                    partIdx++;
                }
            }
            return result;
        }

        private static boolean evaluateMultipartWhen(JsonObject whenObj, Map<String, String> stateProps) {
            if (whenObj.has("OR") && whenObj.get("OR").isJsonArray()) {
                for (JsonElement sub : whenObj.getAsJsonArray("OR")) {
                    if (sub.isJsonObject() && evaluateMultipartWhen(sub.getAsJsonObject(), stateProps)) {
                        return true;
                    }
                }
                return false;
            }
            if (whenObj.has("AND") && whenObj.get("AND").isJsonArray()) {
                for (JsonElement sub : whenObj.getAsJsonArray("AND")) {
                    if (sub.isJsonObject() && !evaluateMultipartWhen(sub.getAsJsonObject(), stateProps)) {
                        return false;
                    }
                }
                return true;
            }
            for (Map.Entry<String, JsonElement> e : whenObj.entrySet()) {
                String prop = e.getKey().toLowerCase(Locale.ROOT);
                String expectedRaw = e.getValue().isJsonPrimitive() ? e.getValue().getAsString().toLowerCase(Locale.ROOT) : "";
                String actual = stateProps.get(prop);
                if (actual == null) {
                    // Default common connection booleans when WorldEdit state map omits unset props
                    if (expectedRaw.contains("true") && !expectedRaw.contains("false")) {
                        return false;
                    }
                    continue;
                }
                boolean neg = expectedRaw.startsWith("!");
                String cleanExp = neg ? expectedRaw.substring(1) : expectedRaw;
                String[] opts = cleanExp.split("\\|");
                boolean anyOpt = false;
                for (String opt : opts) {
                    if (actual.equals(opt.trim())) {
                        anyOpt = true;
                        break;
                    }
                }
                if (neg ? anyOpt : !anyOpt) {
                    return false;
                }
            }
            return true;
        }

        private static AppliedModelSpec parseApplyElement(JsonElement applyEl, int posHash) {
            if (applyEl == null) return null;
            JsonObject chosen = null;
            if (applyEl.isJsonArray()) {
                JsonArray arr = applyEl.getAsJsonArray();
                if (arr.isEmpty()) return null;
                if (arr.size() == 1) {
                    chosen = arr.get(0).isJsonObject() ? arr.get(0).getAsJsonObject() : null;
                } else {
                    int totalWeight = 0;
                    for (JsonElement item : arr) {
                        if (item.isJsonObject()) {
                            JsonObject o = item.getAsJsonObject();
                            totalWeight += o.has("weight") ? Math.max(1, o.get("weight").getAsInt()) : 1;
                        }
                    }
                    int pick = Math.floorMod(posHash, Math.max(1, totalWeight));
                    int acc = 0;
                    for (JsonElement item : arr) {
                        if (item.isJsonObject()) {
                            JsonObject o = item.getAsJsonObject();
                            acc += o.has("weight") ? Math.max(1, o.get("weight").getAsInt()) : 1;
                            if (pick < acc) {
                                chosen = o;
                                break;
                            }
                        }
                    }
                }
            } else if (applyEl.isJsonObject()) {
                chosen = applyEl.getAsJsonObject();
            }
            if (chosen == null) return null;
            String model = getJsonString(chosen, "model");
            if (model == null || model.isEmpty()) return null;
            int x = chosen.has("x") ? chosen.get("x").getAsInt() : 0;
            int y = chosen.has("y") ? chosen.get("y").getAsInt() : 0;
            boolean uvlock = chosen.has("uvlock") && chosen.get("uvlock").getAsBoolean();
            return new AppliedModelSpec(model, x, y, uvlock);
        }

        private static double[][] buildFaceVertices16(FaceDir dir, double[] from, double[] to) {
            double x1 = from[0], y1 = from[1], z1 = from[2];
            double x2 = to[0], y2 = to[1], z2 = to[2];
            switch (dir) {
                case SOUTH: // +Z
                    return new double[][]{{x1, y2, z2}, {x1, y1, z2}, {x2, y1, z2}, {x2, y2, z2}};
                case NORTH: // -Z
                    return new double[][]{{x2, y2, z1}, {x2, y1, z1}, {x1, y1, z1}, {x1, y2, z1}};
                case EAST: // +X
                    return new double[][]{{x2, y2, z2}, {x2, y1, z2}, {x2, y1, z1}, {x2, y2, z1}};
                case WEST: // -X
                    return new double[][]{{x1, y2, z1}, {x1, y1, z1}, {x1, y1, z2}, {x1, y2, z2}};
                case UP: // +Y
                    return new double[][]{{x1, y2, z1}, {x1, y2, z2}, {x2, y2, z2}, {x2, y2, z1}};
                case DOWN: // -Y
                default:
                    return new double[][]{{x1, y1, z2}, {x1, y1, z1}, {x2, y1, z1}, {x2, y1, z2}};
            }
        }

        private static double[] readFaceUv(JsonObject fObj, FaceDir dir, double[] from, double[] to) {
            if (fObj.has("uv") && fObj.get("uv").isJsonArray()) {
                JsonArray arr = fObj.getAsJsonArray("uv");
                if (arr.size() >= 4) {
                    return new double[]{
                            arr.get(0).getAsDouble(),
                            arr.get(1).getAsDouble(),
                            arr.get(2).getAsDouble(),
                            arr.get(3).getAsDouble()
                    };
                }
            }
            double x1 = from[0], y1 = from[1], z1 = from[2];
            double x2 = to[0], y2 = to[1], z2 = to[2];
            switch (dir) {
                case SOUTH: return new double[]{x1, 16.0 - y2, x2, 16.0 - y1};
                case NORTH: return new double[]{16.0 - x2, 16.0 - y2, 16.0 - x1, 16.0 - y1};
                case EAST:  return new double[]{16.0 - z2, 16.0 - y2, 16.0 - z1, 16.0 - y1};
                case WEST:  return new double[]{z1, 16.0 - y2, z2, 16.0 - y1};
                case UP:    return new double[]{x1, z1, x2, z2};
                case DOWN:
                default:    return new double[]{x1, 16.0 - z2, x2, 16.0 - z1};
            }
        }

        private static double[][] buildFaceCornersUv(double[] rect, int rotDeg) {
            double u1 = rect[0], v1 = rect[1], u2 = rect[2], v2 = rect[3];
            double[][] base = {
                    {u1, v1}, // top-left
                    {u1, v2}, // bottom-left
                    {u2, v2}, // bottom-right
                    {u2, v1}  // top-right
            };
            int steps = Math.floorMod(rotDeg / 90, 4);
            if (steps == 0) {
                return base;
            }
            double[][] rotated = new double[4][2];
            for (int i = 0; i < 4; i++) {
                rotated[i] = base[(i + 4 - steps) & 3];
            }
            return rotated;
        }

        private static void applyElementRotation16(double[][] verts, JsonObject rotObj) {
            double[] origin = readVec3(rotObj, "origin", 8, 8, 8);
            String axis = getJsonString(rotObj, "axis");
            double angleDeg = rotObj.has("angle") ? rotObj.get("angle").getAsDouble() : 0.0;
            boolean rescale = rotObj.has("rescale") && rotObj.get("rescale").getAsBoolean();
            if (axis == null || angleDeg == 0.0) {
                return;
            }
            double rad = Math.toRadians(angleDeg);
            double cos = Math.cos(rad);
            double sin = Math.sin(rad);
            double scale = rescale && Math.abs(cos) > 1e-6 ? (1.0 / Math.abs(cos)) : 1.0;

            for (double[] p : verts) {
                double dx = p[0] - origin[0];
                double dy = p[1] - origin[1];
                double dz = p[2] - origin[2];
                switch (axis.toLowerCase(Locale.ROOT)) {
                    case "x": {
                        double ny = (dy * cos - dz * sin) * scale;
                        double nz = (dy * sin + dz * cos) * scale;
                        p[1] = origin[1] + ny;
                        p[2] = origin[2] + nz;
                        break;
                    }
                    case "y": {
                        double nx = (dx * cos + dz * sin) * scale;
                        double nz = (-dx * sin + dz * cos) * scale;
                        p[0] = origin[0] + nx;
                        p[2] = origin[2] + nz;
                        break;
                    }
                    case "z": {
                        double nx = (dx * cos - dy * sin) * scale;
                        double ny = (dx * sin + dy * cos) * scale;
                        p[0] = origin[0] + nx;
                        p[1] = origin[1] + ny;
                        break;
                    }
                    default:
                        break;
                }
            }
        }

        private static void rotatePointBlockstate16(double[] p, int xDeg, int yDeg) {
            double dx = p[0] - 8.0;
            double dy = p[1] - 8.0;
            double dz = p[2] - 8.0;

            // BlockModelRotation X (clockwise looking from +X = -xDeg right-hand around +X)
            switch (Math.floorMod(xDeg, 360)) {
                case 90: { double t = dy; dy = dz; dz = -t; break; }
                case 180: { dy = -dy; dz = -dz; break; }
                case 270: { double t = dy; dy = -dz; dz = t; break; }
                default: break;
            }
            // BlockModelRotation Y (clockwise looking from +Y = -yDeg right-hand around +Y)
            switch (Math.floorMod(yDeg, 360)) {
                case 90: { double t = dx; dx = -dz; dz = t; break; }
                case 180: { dx = -dx; dz = -dz; break; }
                case 270: { double t = dx; dx = dz; dz = -t; break; }
                default: break;
            }

            p[0] = 8.0 + dx;
            p[1] = 8.0 + dy;
            p[2] = 8.0 + dz;
        }

        private static FaceDir rotateDirection(FaceDir dir, int xDeg, int yDeg) {
            if (xDeg == 0 && yDeg == 0) return dir;
            double[] p = {8.0 + dir.dx, 8.0 + dir.dy, 8.0 + dir.dz};
            rotatePointBlockstate16(p, xDeg, yDeg);
            int nx = (int) Math.round(p[0] - 8.0);
            int ny = (int) Math.round(p[1] - 8.0);
            int nz = (int) Math.round(p[2] - 8.0);
            return FaceDir.fromNormal(nx, ny, nz);
        }

        private static double[] projectUvLock16(FaceDir worldDir, double[] v16) {
            double x = v16[0], y = v16[1], z = v16[2];
            switch (worldDir) {
                case SOUTH: return new double[]{x, 16.0 - y};
                case NORTH: return new double[]{16.0 - x, 16.0 - y};
                case EAST:  return new double[]{16.0 - z, 16.0 - y};
                case WEST:  return new double[]{z, 16.0 - y};
                case UP:    return new double[]{x, z};
                case DOWN:
                default:    return new double[]{x, 16.0 - z};
            }
        }

        private static double[] readVec3(JsonObject obj, String key, double d0, double d1, double d2) {
            if (obj != null && obj.has(key) && obj.get(key).isJsonArray()) {
                JsonArray a = obj.getAsJsonArray(key);
                if (a.size() >= 3) {
                    return new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
                }
            }
            return new double[]{d0, d1, d2};
        }

        private static FaceDir mapAxisAndFacing(FaceDir worldDir, String axis, String facing) {
            if ("x".equals(axis)) {
                if (worldDir == FaceDir.EAST || worldDir == FaceDir.WEST) {
                    return FaceDir.UP;
                }
                return FaceDir.EAST;
            }
            if ("z".equals(axis)) {
                if (worldDir == FaceDir.NORTH || worldDir == FaceDir.SOUTH) {
                    return FaceDir.UP;
                }
                return FaceDir.EAST;
            }
            if (worldDir == FaceDir.UP || worldDir == FaceDir.DOWN) {
                return worldDir;
            }
            if (facing != null && worldDir.name().equalsIgnoreCase(facing)) {
                return FaceDir.NORTH;
            }
            return FaceDir.EAST;
        }

        private ResolvedModelFaces buildBlockFaces(String blockName, BlockType bt, BlockState st, boolean snowy) {
            ShapeKind kind = getShapeKind(st);
            boolean blockTranslucent = (kind == ShapeKind.TRANSLUCENT_CUBE);

            BlockColorRecord rec = lookupRecordWithVariantFallback(blockName);
            int[] fallbackRgb = rec == null ? queryRuntimeFallbackRgb(blockName, bt) : null;
            int[] defTop = snowy && blockName.equals("grass_block")
                    ? new int[]{249, 254, 254}
                    : (rec != null ? rec.topRgb : fallbackRgb);
            int[] defBot = rec != null ? rec.bottomRgb : fallbackRgb;
            int[] defSide = snowy && blockName.equals("grass_block")
                    ? new int[]{180, 172, 156}
                    : (rec != null ? rec.sideRgb : fallbackRgb);
            int[] defFront = snowy && blockName.equals("grass_block")
                    ? defSide
                    : (rec != null ? rec.frontRgb : fallbackRgb);

            if (layers.isEmpty()) {
                return buildFallbackFaces(blockName, snowy, kind, blockTranslucent, defTop, defBot, defSide, defFront);
            }

            if (snowy && blockName.equals("grass_block")) {
                FaceMaterialAppearance topApp = loadFaceFromTexRef("snow", "block/snow", false, false, false, defTop, kind);
                FaceMaterialAppearance botApp = loadFaceFromTexRef("dirt", "block/dirt", false, false, false, defBot, kind);
                FaceMaterialAppearance sideApp = loadFaceFromTexRef("grass_block_snow", "block/grass_block_snow", false, false, false, defSide, kind);
                return new ResolvedModelFaces(topApp, botApp, sideApp, sideApp);
            }

            String lookupBlock = blockName;
            JsonObject bsJson = readBlockstateJson(lookupBlock);
            if (bsJson == null) {
                String stripped = stripBlockVariantAffixes(lookupBlock);
                if (!stripped.equals(lookupBlock)) {
                    for (String cand : new String[]{stripped, stripped + "_planks", stripped + "_block", stripped + "s"}) {
                        bsJson = readBlockstateJson(cand);
                        if (bsJson != null) {
                            lookupBlock = cand;
                            break;
                        }
                    }
                }
            }

            if (bsJson != null) {
                String modelRef = pickPrimaryModelFromBlockstate(bsJson, snowy);
                if (modelRef != null) {
                    Map<String, String> texMap = new HashMap<>();
                    JsonArray elements = resolveModelChain(modelRef, texMap);

                    Map<String, String> faceTex = new HashMap<>();
                    Map<String, Boolean> faceTinted = new HashMap<>();
                    if (elements != null) {
                        for (JsonElement elElem : elements) {
                            if (!elElem.isJsonObject()) continue;
                            JsonObject el = elElem.getAsJsonObject();
                            JsonObject facesObj = el.has("faces") && el.get("faces").isJsonObject()
                                    ? el.getAsJsonObject("faces") : null;
                            if (facesObj == null) continue;
                            for (String dir : new String[]{"up", "down", "north", "south", "east", "west"}) {
                                if (facesObj.has(dir) && !faceTex.containsKey(dir) && facesObj.get(dir).isJsonObject()) {
                                    JsonObject fObj = facesObj.getAsJsonObject(dir);
                                    String rawT = getJsonString(fObj, "texture");
                                    if ("#overlay".equals(rawT)) continue;
                                    if (rawT != null && rawT.startsWith("#")) {
                                        rawT = texMap.get(rawT.substring(1));
                                    }
                                    if (rawT != null && !rawT.isEmpty()) {
                                        faceTex.put(dir, rawT);
                                        faceTinted.put(dir, fObj.has("tintindex"));
                                    }
                                }
                            }
                        }
                    }

                    String[] topPick = pickTex(lookupBlock, faceTex, faceTinted, texMap, "up", "top", "end", "all", "cross", "plant", "texture", "side", "particle");
                    String[] botPick = pickTex(lookupBlock, faceTex, faceTinted, texMap, "down", "bottom", "end", "all", "cross", "plant", "texture", "side", "particle");
                    String[] sidePick = pickTex(lookupBlock, faceTex, faceTinted, texMap, "east", "west", "south", "side", "all", "cross", "plant", "wall", "texture", "top", "particle");
                    String[] frontPick = pickTex(lookupBlock, faceTex, faceTinted, texMap, "north", "front", "side", "all", "cross", "plant", "texture", "top", "particle");

                    if (topPick[0] != null || sidePick[0] != null) {
                        String fbRef = sidePick[0] != null ? sidePick[0] : topPick[0];
                        FaceMaterialAppearance topApp = loadFaceFromTexRef(
                                lookupBlock, topPick[0] != null ? topPick[0] : fbRef,
                                "true".equals(topPick[1]), false, blockTranslucent, defTop, kind
                        );
                        FaceMaterialAppearance botApp = loadFaceFromTexRef(
                                lookupBlock, botPick[0] != null ? botPick[0] : fbRef,
                                "true".equals(botPick[1]), false, blockTranslucent, defBot, kind
                        );
                        FaceMaterialAppearance sideApp = loadFaceFromTexRef(
                                lookupBlock, sidePick[0] != null ? sidePick[0] : fbRef,
                                "true".equals(sidePick[1]), lookupBlock.equals("grass_block"), blockTranslucent, defSide, kind
                        );
                        FaceMaterialAppearance frontApp = loadFaceFromTexRef(
                                lookupBlock, frontPick[0] != null ? frontPick[0] : fbRef,
                                "true".equals(frontPick[1]), lookupBlock.equals("grass_block"), blockTranslucent, defFront, kind
                        );
                        return new ResolvedModelFaces(topApp, botApp, sideApp, frontApp);
                    }
                }
            }

            if (blockName.equals("water") || blockName.equals("bubble_column")) {
                FaceMaterialAppearance w = loadFaceFromTexRef("water", "block/water_still", true, false, true, defTop, kind);
                return new ResolvedModelFaces(w, w, w, w);
            }
            if (blockName.equals("lava")) {
                FaceMaterialAppearance l = loadFaceFromTexRef("lava", "block/lava_still", false, false, false, defTop, kind);
                return new ResolvedModelFaces(l, l, l, l);
            }

            return buildFallbackFaces(blockName, snowy, kind, blockTranslucent, defTop, defBot, defSide, defFront);
        }

        private ResolvedModelFaces buildFallbackFaces(
                String blockName,
                boolean snowy,
                ShapeKind kind,
                boolean translucent,
                int[] topRgb,
                int[] botRgb,
                int[] sideRgb,
                int[] frontRgb
        ) {
            String prefix = snowy ? blockName + "_snowy" : blockName;
            boolean sameAll = sameRgb(topRgb, botRgb) && sameRgb(topRgb, sideRgb) && sameRgb(topRgb, frontRgb);
            if (sameAll) {
                FaceMaterialAppearance single = getOrCreateSolidMaterial(prefix, topRgb, translucent);
                return new ResolvedModelFaces(single, single, single, single);
            }
            FaceMaterialAppearance top = getOrCreateSolidMaterial(prefix + "_top", topRgb, translucent);
            FaceMaterialAppearance bot = sameRgb(botRgb, topRgb) ? top : getOrCreateSolidMaterial(prefix + "_bottom", botRgb, translucent);
            FaceMaterialAppearance side = sameRgb(sideRgb, topRgb) ? top : getOrCreateSolidMaterial(prefix + "_side", sideRgb, translucent);
            FaceMaterialAppearance front = sameRgb(frontRgb, sideRgb) ? side : getOrCreateSolidMaterial(prefix + "_front", frontRgb, translucent);
            return new ResolvedModelFaces(top, bot, side, front);
        }

        private FaceMaterialAppearance getOrCreateSolidMaterial(String rawKey, int[] rgb, boolean translucent) {
            String key = sanitizeMatKey(rawKey);
            return materialPool.computeIfAbsent(key, k -> {
                BufferedImage tile = createSolidTile(rgb[0], rgb[1], rgb[2], translucent ? 170 : 255);
                return new FaceMaterialAppearance(k, rgb[0], rgb[1], rgb[2], tile, false, translucent);
            });
        }

        private FaceMaterialAppearance loadFaceFromTexRef(
                String blockName,
                String texRef,
                boolean modelHasTintIndex,
                boolean compositeGrassSideOverlay,
                boolean blockTranslucent,
                int[] fallbackRgb,
                ShapeKind kind
        ) {
            String rel = stripNamespace(texRef);
            int[] tintRgb = TINT_BY_BLOCK.get(blockName);
            boolean wantsTint = tintRgb != null && (modelHasTintIndex || TINT_BY_BLOCK.containsKey(blockName));

            String matKeyRaw = rel.startsWith("block/") ? rel.substring("block/".length()) : rel;
            if (wantsTint) {
                matKeyRaw = blockName + "_" + matKeyRaw;
            } else if (compositeGrassSideOverlay) {
                matKeyRaw = "grass_block_side_tinted";
            }
            String matKey = sanitizeMatKey(matKeyRaw);

            FaceMaterialAppearance existing = materialPool.get(matKey);
            if (existing != null) {
                return existing;
            }

            BufferedImage baseImg = readTextureImage(rel);
            if (baseImg == null) {
                return getOrCreateSolidMaterial(matKey, fallbackRgb, blockTranslucent);
            }

            BufferedImage working = copyArgb(baseImg);
            if (wantsTint && isGrayscaleImage(working)) {
                applyTintInPlace(working, tintRgb);
            }
            if (compositeGrassSideOverlay) {
                BufferedImage overlay = readTextureImage("block/grass_block_side_overlay");
                if (overlay != null) {
                    BufferedImage tintedOv = copyArgb(overlay);
                    if (isGrayscaleImage(tintedOv)) {
                        applyTintInPlace(tintedOv, GRASS_TINT);
                    }
                    compositeOverInPlace(working, tintedOv);
                }
            }

            long sr = 0, sg = 0, sb = 0, sw = 0;
            boolean hasCutout = false;
            boolean hasSemi = false;
            int w = working.getWidth();
            int h = working.getHeight();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = working.getRGB(x, y);
                    int a = (argb >>> 24) & 0xFF;
                    if (a < 20) {
                        hasCutout = true;
                        continue;
                    }
                    if (a < 245) {
                        hasSemi = true;
                    }
                    int r = (argb >>> 16) & 0xFF;
                    int g = (argb >>> 8) & 0xFF;
                    int b = argb & 0xFF;
                    sr += (long) r * a;
                    sg += (long) g * a;
                    sb += (long) b * a;
                    sw += a;
                }
            }

            int avgR = sw > 0 ? (int) (sr / sw) : fallbackRgb[0];
            int avgG = sw > 0 ? (int) (sg / sw) : fallbackRgb[1];
            int avgB = sw > 0 ? (int) (sb / sw) : fallbackRgb[2];

            if (hasCutout) {
                int zeroAlphaAvgRgb = (avgR << 16) | (avgG << 8) | avgB;
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int argb = working.getRGB(x, y);
                        if (((argb >>> 24) & 0xFF) < 8) {
                            working.setRGB(x, y, zeroAlphaAvgRgb);
                        }
                    }
                }
            }

            boolean cutout = (hasCutout || kind == ShapeKind.CROSS || kind == ShapeKind.CUTOUT_CUBE) && kind != ShapeKind.CUBE;
            boolean trans = blockTranslucent || hasSemi;

            FaceMaterialAppearance created = new FaceMaterialAppearance(matKey, avgR, avgG, avgB, working, cutout, trans);
            materialPool.put(matKey, created);
            return created;
        }

        private String[] pickTex(
                String blockName,
                Map<String, String> faceTex,
                Map<String, Boolean> faceTinted,
                Map<String, String> texMap,
                String... keys
        ) {
            for (String k : keys) {
                String ft = faceTex.get(k);
                if (ft != null && !ft.isEmpty()) {
                    return new String[]{ft, String.valueOf(Boolean.TRUE.equals(faceTinted.get(k)))};
                }
                String mt = texMap.get(k);
                if (mt != null && !mt.isEmpty()) {
                    return new String[]{mt, String.valueOf(TINT_BY_BLOCK.containsKey(blockName))};
                }
            }
            for (Map.Entry<String, String> e : texMap.entrySet()) {
                if (!"overlay".equals(e.getKey()) && e.getValue() != null && !e.getValue().isEmpty()) {
                    return new String[]{e.getValue(), String.valueOf(TINT_BY_BLOCK.containsKey(blockName))};
                }
            }
            return new String[]{null, "false"};
        }

        private JsonObject readBlockstateJson(String blockName) {
            if (blockstateJsonCache.containsKey(blockName)) {
                return blockstateJsonCache.get(blockName);
            }
            byte[] raw = readLayerEntry("assets/minecraft/blockstates/" + blockName + ".json");
            JsonObject parsed = null;
            if (raw != null) {
                try {
                    JsonElement el = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8));
                    if (el.isJsonObject()) {
                        parsed = el.getAsJsonObject();
                    }
                } catch (Throwable ignored) {
                }
            }
            blockstateJsonCache.put(blockName, parsed);
            return parsed;
        }

        private static String pickPrimaryModelFromBlockstate(JsonObject bsJson, boolean preferSnowy) {
            if (bsJson.has("variants") && bsJson.get("variants").isJsonObject()) {
                JsonObject variants = bsJson.getAsJsonObject("variants");
                String bestKey = null;
                int bestScore = Integer.MIN_VALUE;
                for (String k : variants.keySet()) {
                    int score = 0;
                    if (k.isEmpty()) score += 50;
                    if (preferSnowy) {
                        if (k.contains("snowy=true")) score += 40;
                        if (k.contains("snowy=false")) score -= 20;
                    } else {
                        if (k.contains("snowy=false")) score += 20;
                        if (k.contains("snowy=true")) score -= 20;
                    }
                    if (k.contains("axis=y")) score += 10;
                    if (k.contains("half=bottom") || k.contains("half=lower") || k.contains("type=bottom")) score += 5;
                    if (score > bestScore) {
                        bestScore = score;
                        bestKey = k;
                    }
                }
                if (bestKey != null) {
                    JsonElement v = variants.get(bestKey);
                    if (v.isJsonArray() && !v.getAsJsonArray().isEmpty()) {
                        v = v.getAsJsonArray().get(0);
                    }
                    if (v.isJsonObject()) {
                        return getJsonString(v.getAsJsonObject(), "model");
                    }
                }
            }
            if (bsJson.has("multipart") && bsJson.get("multipart").isJsonArray()) {
                for (JsonElement part : bsJson.getAsJsonArray("multipart")) {
                    if (!part.isJsonObject()) continue;
                    JsonElement app = part.getAsJsonObject().get("apply");
                    if (app != null && app.isJsonArray() && !app.getAsJsonArray().isEmpty()) {
                        app = app.getAsJsonArray().get(0);
                    }
                    if (app != null && app.isJsonObject()) {
                        String m = getJsonString(app.getAsJsonObject(), "model");
                        if (m != null) return m;
                    }
                }
            }
            return null;
        }

        private JsonArray resolveModelChain(String modelRef, Map<String, String> outTextures) {
            Map<String, String> rawTex = new HashMap<>();
            JsonArray elements = null;
            String cur = modelRef;
            for (int depth = 0; depth < 10 && cur != null && !cur.isEmpty(); depth++) {
                JsonObject m = readModelJson(cur);
                if (m == null) {
                    break;
                }
                if (m.has("textures") && m.get("textures").isJsonObject()) {
                    JsonObject tObj = m.getAsJsonObject("textures");
                    for (Map.Entry<String, JsonElement> e : tObj.entrySet()) {
                        if (rawTex.containsKey(e.getKey())) continue;
                        String val = extractTextureRefString(e.getValue());
                        if (val != null && !val.isEmpty()) {
                            rawTex.put(e.getKey(), val);
                        }
                    }
                }
                if (elements == null && m.has("elements") && m.get("elements").isJsonArray()) {
                    elements = m.getAsJsonArray("elements");
                }
                cur = getJsonString(m, "parent");
            }
            for (Map.Entry<String, String> e : rawTex.entrySet()) {
                String val = e.getValue();
                for (int hop = 0; hop < 8 && val != null && val.startsWith("#"); hop++) {
                    val = rawTex.get(val.substring(1));
                }
                if (val != null && !val.isEmpty() && !val.startsWith("#")) {
                    outTextures.put(e.getKey(), val);
                }
            }
            return elements;
        }

        private static String extractTextureRefString(JsonElement el) {
            if (el == null || el.isJsonNull()) {
                return null;
            }
            if (el.isJsonPrimitive()) {
                return el.getAsString();
            }
            if (el.isJsonObject()) {
                JsonObject obj = el.getAsJsonObject();
                String sp = getJsonString(obj, "sprite");
                if (sp != null) return sp;
                String tx = getJsonString(obj, "texture");
                if (tx != null) return tx;
                for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                    if (entry.getValue().isJsonPrimitive()) {
                        return entry.getValue().getAsString();
                    }
                }
            }
            return null;
        }

        private JsonObject readModelJson(String modelRef) {
            String rel = stripNamespace(modelRef);
            if (!rel.startsWith("block/") && !rel.startsWith("item/")) {
                rel = "block/" + rel;
            }
            if (modelJsonCache.containsKey(rel)) {
                return modelJsonCache.get(rel);
            }
            byte[] raw = readLayerEntry("assets/minecraft/models/" + rel + ".json");
            JsonObject parsed = null;
            if (raw != null) {
                try {
                    JsonElement el = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8));
                    if (el.isJsonObject()) {
                        parsed = el.getAsJsonObject();
                    }
                } catch (Throwable ignored) {
                }
            }
            modelJsonCache.put(rel, parsed);
            return parsed;
        }

        private BufferedImage readTextureImage(String relPath) {
            String rel = stripNamespace(relPath);
            if (rawTextureCache.containsKey(rel)) {
                return rawTextureCache.get(rel);
            }
            byte[] raw = readLayerEntry("assets/minecraft/textures/" + rel + ".png");
            BufferedImage img = null;
            if (raw != null) {
                try {
                    BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(raw));
                    if (decoded != null && decoded.getWidth() > 0 && decoded.getHeight() > 0) {
                        int w = decoded.getWidth();
                        int h = Math.min(w, decoded.getHeight());
                        img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                        Graphics2D g2 = img.createGraphics();
                        g2.drawImage(decoded, 0, 0, w, h, 0, 0, w, h, null);
                        g2.dispose();
                    }
                } catch (Throwable ignored) {
                }
            }
            rawTextureCache.put(rel, img);
            return img;
        }

        private byte[] readLayerEntry(String entryPath) {
            for (AssetLayer layer : layers) {
                byte[] bytes = layer.readEntry(entryPath);
                if (bytes != null) {
                    return bytes;
                }
            }
            return null;
        }

        @Override
        public void close() {
            for (AssetLayer layer : layers) {
                layer.close();
            }
            layers.clear();
        }
    }

    /**
     * Discovers user resource packs in {@code plugins/DragonBuildTools/resourcepacks/} (and {@code textures/}),
     * layered on top of FAWE's runtime-cached Mojang client asset JAR ({@code plugins/FastAsyncWorldEdit/textures/*.jar}).
     */
    public static List<File> discoverAssetSources(String preferredPackName) {
        List<File> found = new ArrayList<>();
        try {
            DragonBuildToolsPlugin plugin = DragonBuildToolsPlugin.getInstance();
            if (plugin != null) {
                File rpDir = new File(plugin.getDataFolder(), "resourcepacks");
                File texDir = new File(plugin.getDataFolder(), "textures");
                if (preferredPackName != null && !preferredPackName.isEmpty() && !"auto".equalsIgnoreCase(preferredPackName)) {
                    collectMatchingPack(rpDir, preferredPackName, found);
                    collectMatchingPack(texDir, preferredPackName, found);
                }
                collectPacksInDir(rpDir, found);
                collectPacksInDir(texDir, found);

                File pluginsDir = plugin.getDataFolder().getParentFile();
                if (pluginsDir != null) {
                    collectPacksInDir(new File(pluginsDir, "FastAsyncWorldEdit/textures"), found);
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            Fawe fawe = Fawe.instance();
            if (fawe != null) {
                fawe.getTextureUtil();
                File faweTexDir = MainUtil.getFile(fawe.platform().getDirectory(), Settings.settings().PATHS.TEXTURES);
                collectPacksInDir(faweTexDir, found);
            }
        } catch (Throwable ignored) {
        }
        return found;
    }

    private static void collectMatchingPack(File dir, String preferred, List<File> out) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        String prefLow = preferred.toLowerCase(Locale.ROOT).replace(' ', '_');
        for (File f : files) {
            String nLow = f.getName().toLowerCase(Locale.ROOT);
            String nNorm = nLow.replace(' ', '_');
            if (nLow.equals(prefLow) || nNorm.equals(prefLow)
                    || nNorm.equals(prefLow + ".zip") || nNorm.equals(prefLow + ".jar")
                    || nNorm.startsWith(prefLow)) {
                if (isPackSource(f) && !out.contains(f)) {
                    out.add(f);
                }
            }
        }
    }

    private static void collectPacksInDir(File dir, List<File> out) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        java.util.Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File f : files) {
            if (isPackSource(f) && !out.contains(f)) {
                out.add(f);
            }
        }
    }

    private static boolean isPackSource(File f) {
        if (f.isDirectory()) {
            if (new File(f, "assets").isDirectory() || new File(f, "pack.mcmeta").isFile()) {
                return true;
            }
            File[] children = f.listFiles(File::isDirectory);
            return children != null && children.length == 1
                    && (new File(children[0], "assets").isDirectory() || new File(children[0], "pack.mcmeta").isFile());
        }
        String low = f.getName().toLowerCase(Locale.ROOT);
        return f.isFile() && (low.endsWith(".zip") || low.endsWith(".jar"));
    }

    private static BufferedImage createSolidTile(int r, int g, int b, int alpha) {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        int argb = ((alpha & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                img.setRGB(x, y, argb);
            }
        }
        return img;
    }

    private static BufferedImage copyArgb(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    private static boolean isGrayscaleImage(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                if (a >= 24) {
                    int r = (argb >>> 16) & 0xFF;
                    int g = (argb >>> 8) & 0xFF;
                    int b = argb & 0xFF;
                    int max = Math.max(r, Math.max(g, b));
                    int min = Math.min(r, Math.min(g, b));
                    if (max - min > 5) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static void applyTintInPlace(BufferedImage img, int[] tintRgb) {
        int w = img.getWidth();
        int h = img.getHeight();
        int tr = tintRgb[0];
        int tg = tintRgb[1];
        int tb = tintRgb[2];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                int r = (((argb >>> 16) & 0xFF) * tr) / 255;
                int g = (((argb >>> 8) & 0xFF) * tg) / 255;
                int b = ((argb & 0xFF) * tb) / 255;
                img.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
    }

    private static void compositeOverInPlace(BufferedImage base, BufferedImage overlay) {
        Graphics2D g = base.createGraphics();
        g.drawImage(overlay, 0, 0, base.getWidth(), base.getHeight(), null);
        g.dispose();
    }

    private static boolean sameRgb(int[] a, int[] b) {
        return a[0] == b[0] && a[1] == b[1] && a[2] == b[2];
    }

    private static int[] parseHexRgb(String hex) {
        try {
            int val = Integer.parseInt(hex.trim(), 16);
            return new int[]{(val >>> 16) & 0xFF, (val >>> 8) & 0xFF, val & 0xFF};
        } catch (NumberFormatException e) {
            return new int[]{140, 140, 140};
        }
    }

    private static String stripNamespace(String ref) {
        if (ref == null) return "";
        int colon = ref.indexOf(':');
        return colon >= 0 ? ref.substring(colon + 1) : ref;
    }

    private static String getJsonString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) return null;
        JsonElement el = obj.get(key);
        return (el != null && el.isJsonPrimitive()) ? el.getAsString() : null;
    }

    private static String sanitizeMatKey(String raw) {
        return raw.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static String readProp(BlockState st, PropertyKey key, String fallback) {
        if (st == null || key == null) {
            return fallback;
        }
        try {
            Object val = st.getState(key);
            if (val != null) {
                return val.toString().toLowerCase(Locale.ROOT);
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }
}
