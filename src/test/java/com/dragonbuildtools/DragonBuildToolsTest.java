package com.dragonbuildtools;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dragonbuildtools.animation.AnimatedModelEngine;
import com.dragonbuildtools.animation.AnimatedModelEngine.MotionMode;
import com.dragonbuildtools.animation.AnimationManager;
import com.dragonbuildtools.animation.AnimationManager.ActiveAnimation;
import com.dragonbuildtools.animation.AnimationManager.BlockDelta;
import com.dragonbuildtools.animation.VideoAndGifEngine;
import com.dragonbuildtools.brush.GaussianColorBlurBrush;
import com.dragonbuildtools.brush.SubBlockBlendBallBrush;
import com.dragonbuildtools.brush.SubBlockBlendBallBrush.SynthesizedKind;
import com.dragonbuildtools.brush.SubBlockBlendBallBrush.SynthesizedShape;
import com.dragonbuildtools.brush.SubBlockRecolorUtil;
import com.dragonbuildtools.brush.SubBlockRecolorUtil.CandidateColorEntry;
import com.dragonbuildtools.brush.SubBlockRecolorUtil.RecolorBucket;
import com.dragonbuildtools.command.DbtWorldEditCommandsRegistration;
import com.dragonbuildtools.converter.HeightmapTerrainGenerator;
import com.dragonbuildtools.converter.ImagePixelArtConverter;
import com.dragonbuildtools.converter.Model3DVoxelizer;
import com.dragonbuildtools.converter.Model3DVoxelizer.Mesh3D;
import com.dragonbuildtools.converter.Model3DVoxelizer.VoxelGrid;
import com.dragonbuildtools.converter.Text3DGenerator;
import com.dragonbuildtools.util.BlockColorMapper;
import com.dragonbuildtools.util.VanillaBlockAppearanceResolver;
import com.dragonbuildtools.util.VanillaBlockAppearanceResolver.BlockColorRecord;
import com.dragonbuildtools.util.VanillaBlockAppearanceResolver.ExportTextureSession;
import com.dragonbuildtools.util.VanillaBlockAppearanceResolver.ShapeKind;
import com.dragonbuildtools.util.VanillaBlockAppearanceResolver.TexturedModelQuad;
import com.dragonbuildtools.wfc.Wfc3DGridGenerator;
import com.sk89q.worldedit.command.util.CommandPermissionsConditionGenerator;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.enginehub.piston.CommandManager;
import org.enginehub.piston.impl.CommandManagerServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DragonBuildToolsTest {

    @Test
    void testChromaPenaltyForNeutralColors() {
        double neutralToWhite = BlockColorMapper.colorDistanceSq(200, 200, 200, 192, 193, 195);
        double neutralToPinkCherry = BlockColorMapper.colorDistanceSq(200, 200, 200, 226, 178, 172);
        double neutralToYellowBirch = BlockColorMapper.colorDistanceSq(142, 142, 142, 192, 175, 121);
        assertTrue(neutralToWhite < neutralToPinkCherry * 0.1,
                "Neutral grey/white must strongly prefer neutral stone/quartz over pink cherry");
        assertTrue(neutralToWhite < neutralToYellowBirch * 0.1,
                "Neutral grey must strongly prefer neutral stone over yellow birch");
    }

    @Test
    void test3DVoxelizerAndProceduralAnimation() throws Exception {
        String obj = String.join("\n",
                "v -2 0 -1",
                "v  2 0 -1",
                "v  0 3  0",
                "v  0 0  2",
                "f 1 2 3",
                "f 1 3 4",
                "f 2 4 3",
                "f 1 4 2"
        );
        Mesh3D mesh = Model3DVoxelizer.parseModelBytes(obj.getBytes(StandardCharsets.UTF_8), "pyramid.obj", null, null);
        assertEquals(4, mesh.triangles.size());

        List<VoxelGrid> frames = AnimatedModelEngine.generateProceduralAnimationFrames(
                mesh, 16, 6, MotionMode.WINGFLAP, false, true, false, true, true, null
        );
        assertEquals(6, frames.size());
        VoxelGrid f0 = frames.get(0);
        for (VoxelGrid f : frames) {
            assertEquals(f0.sizeX, f.sizeX, "All animation frames must share aligned X dimension");
            assertEquals(f0.sizeY, f.sizeY, "All animation frames must share aligned Y dimension");
            assertEquals(f0.sizeZ, f.sizeZ, "All animation frames must share aligned Z dimension");
        }

        List<List<BlockDelta>> deltas = AnimationManager.computeFrameDeltas(frames, f0.sizeX, f0.sizeY, f0.sizeZ);
        assertEquals(6, deltas.size());
    }

    @Test
    void testMultipleNamedVideosWithSameFile() {
        AnimationManager mgr = new AnimationManager(null);
        VoxelGrid frame1 = Text3DGenerator.generate3DText("A", 8, 1, false, false, null);
        VoxelGrid frame2 = Text3DGenerator.generate3DText("B", 8, 1, false, false, null);
        List<VoxelGrid> sharedFrames = AnimatedModelEngine.alignVoxelGridCenters(List.of(frame1, frame2));

        // Instantiate two independent named videos ("lobby_left" and "lobby_right") using the exact same file ("intro.gif")
        ActiveAnimation v1 = mgr.registerNamedAnimation(
                "lobby_left", "intro.gif", "WallVideo",
                null, BlockVector3.at(10, 64, 10), sharedFrames, 10.0, true, true
        );
        ActiveAnimation v2 = mgr.registerNamedAnimation(
                "lobby_right", "intro.gif", "WallVideo",
                null, BlockVector3.at(50, 64, 10), sharedFrames, 15.0, false, false
        );

        assertNotEquals(v1.id, v2.id);
        assertEquals("lobby_left", v1.name);
        assertEquals("lobby_right", v2.name);
        assertEquals("intro.gif", v1.sourceFile);
        assertEquals("intro.gif", v2.sourceFile);
        assertTrue(v1.paused, "v1 created without -a should start paused on frame 1");
        assertFalse(v2.paused, "v2 created with -a should start playing");

        assertEquals(v1, mgr.getAnimationByNameOrId("lobby_left"));
        assertEquals(v1, mgr.getAnimationByNameOrId("LOBBY_LEFT"));
        assertEquals(v2, mgr.getAnimationByNameOrId("#" + v2.id));
        assertEquals(2, mgr.resolveAnimations("all", true).size());

        // Test stop with terrain restore (-r) and start again
        mgr.hideAndRestoreTerrain(v2);
        assertTrue(v2.paused);
        assertTrue(v2.hiddenTerrainRestored);
        mgr.ensureVisible(v2);
        assertFalse(v2.hiddenTerrainRestored);

        // Test relocate (move)
        mgr.relocateAnimation(v1, BlockVector3.at(100, 70, 100));
        assertEquals(BlockVector3.at(100, 70, 100), v1.origin);

        // Removing v1 must leave v2 intact
        assertTrue(mgr.removeAnimationByNameOrId("lobby_left", false));
        assertNull(mgr.getAnimationByNameOrId("lobby_left"));
        assertNotNull(mgr.getAnimationByNameOrId("lobby_right"));
    }

    @Test
    void testCommandRegistrationAndWfcFlags() throws Exception {
        CommandManager cm = new CommandManagerServiceImpl().newCommandManager();
        CommandPermissionsConditionGenerator permGen = new CommandPermissionsConditionGenerator();
        java.util.concurrent.atomic.AtomicReference<java.lang.reflect.Method> capturedMethod =
                new java.util.concurrent.atomic.AtomicReference<>();
        org.enginehub.piston.gen.CommandCallListener listener = new org.enginehub.piston.gen.CommandCallListener() {
            @Override
            public void beforeCall(java.lang.reflect.Method commandMethod, org.enginehub.piston.CommandParameters parameters) {
                capturedMethod.set(commandMethod);
            }

            @Override
            public void afterCall(java.lang.reflect.Method commandMethod, org.enginehub.piston.CommandParameters parameters) {
            }

            @Override
            public void afterThrow(java.lang.reflect.Method commandMethod, org.enginehub.piston.CommandParameters parameters, Throwable throwable) {
            }
        };
        assertDoesNotThrow(() -> DbtWorldEditCommandsRegistration.builder()
                .commandManager(cm)
                .commandPermissionsConditionGenerator(permGen)
                .listeners(java.util.List.of(listener))
                .build());
        assertTrue(cm.containsCommand("video"));
        assertTrue(cm.containsCommand("newwfc"));
        assertTrue(cm.containsCommand("exportobj"));
        assertTrue(cm.containsCommand("schemtoobj"));
        assertTrue(cm.containsCommand("gblur"));
        assertTrue(cm.containsCommand("/gblur"));
        assertTrue(cm.containsCommand("subblend"));
        assertTrue(cm.containsCommand("/subblend"));
        assertTrue(cm.containsCommand("dbr"));
        assertTrue(cm.containsCommand("/dbr"));

        // Verify that invoking a command action calls listenersBeforeCall (which FAWE's ProvideBindings.editSession requires)
        try {
            cm.getCommand("newwfc").get().getAction().run(org.enginehub.piston.NoInputCommandParameters.builder().build());
        } catch (Throwable ignored) {
            // Expected to fail after beforeCall when extracting Player from empty parameters
        }
        assertNotNull(capturedMethod.get(), "CommandCallListener.beforeCall must be invoked before parameter extraction");
        assertEquals("newWfcCommand", capturedMethod.get().getName());

        CuboidRegion sampleReg = new CuboidRegion(BlockVector3.at(0, 0, 0), BlockVector3.at(9, 4, 9));
        CuboidRegion targetReg = new CuboidRegion(BlockVector3.at(20, 0, 20), BlockVector3.at(39, 4, 39));
        Wfc3DGridGenerator gen = new Wfc3DGridGenerator(
                null, null, sampleReg, targetReg,
                5, 1, 2, 1.0f, 5.0,
                true, null, "structure_void",
                true, true, 2, true
        );
        assertNotNull(gen);
    }

    @Test
    void testAnimatedGifDecoding(@TempDir File tempDir) throws Exception {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 8, 16);
        g.setColor(Color.BLUE);
        g.fillRect(8, 0, 8, 16);
        g.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "gif", baos);
        List<BufferedImage> decoded = VideoAndGifEngine.decodeAnimatedGifBytes(baos.toByteArray());
        assertFalse(decoded.isEmpty());
        assertEquals(16, decoded.get(0).getWidth());
        assertEquals(16, decoded.get(0).getHeight());
    }

    @Test
    void testHeightmapAndText3DGeneration(@TempDir File tempDir) throws Exception {
        BufferedImage hmap = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int v = Math.min(255, (x + z) * 8);
                int rgb = (255 << 24) | (v << 16) | (v << 8) | v;
                hmap.setRGB(x, z, rgb);
            }
        }
        File hmapFile = new File(tempDir, "test_terrain.png");
        ImageIO.write(hmap, "png", hmapFile);

        BufferedImage loaded = ImagePixelArtConverter.loadImage(hmapFile.getAbsolutePath());
        assertNotNull(loaded);

        VoxelGrid terrain = HeightmapTerrainGenerator.generateTerrain(
                hmapFile.getAbsolutePath(), null, 16, 20, true, true, true, null
        );
        assertEquals(16, terrain.sizeX);
        assertEquals(20, terrain.sizeY);
        assertEquals(16, terrain.sizeZ);

        VoxelGrid textGrid = Text3DGenerator.generate3DText("DBT", 12, 3, true, true, null);
        assertNotNull(textGrid);
        assertTrue(textGrid.sizeX > 0 && textGrid.sizeY > 0 && textGrid.sizeZ == 3);
    }

    @Test
    void testStarterDemoAssetsGeneration(@TempDir File tempDir) throws Exception {
        com.dragonbuildtools.demo.DemoAssetGenerator.writeDragonModel(tempDir);
        com.dragonbuildtools.demo.DemoAssetGenerator.writeCrystalKnotModel(tempDir);
        File gifFile = new File(tempDir, "synthwave_sunset.gif");
        com.dragonbuildtools.demo.DemoAssetGenerator.writeSynthwaveGif(gifFile);
        File hmapFile = new File(tempDir, "alpine_island.png");
        com.dragonbuildtools.demo.DemoAssetGenerator.writeAlpineHeightmap(hmapFile);
        File crestFile = new File(tempDir, "dragon_crest.png");
        com.dragonbuildtools.demo.DemoAssetGenerator.writeDragonCrestImage(crestFile);

        Mesh3D dragonMesh = Model3DVoxelizer.loadModel(new File(tempDir, "dragon_statue.obj").getAbsolutePath());
        assertTrue(dragonMesh.triangles.size() > 50, "Dragon starter mesh must have triangles");

        Mesh3D knotMesh = Model3DVoxelizer.loadModel(new File(tempDir, "crystal_knot.obj").getAbsolutePath());
        assertTrue(knotMesh.triangles.size() > 100, "Crystal knot starter mesh must have triangles");

        List<BufferedImage> gifFrames = VideoAndGifEngine.decodeAnimatedGifBytes(
                java.nio.file.Files.readAllBytes(gifFile.toPath())
        );
        assertEquals(20, gifFrames.size(), "Synthwave starter GIF must contain 20 animated frames");
        assertTrue(hmapFile.length() > 100);
        assertTrue(crestFile.length() > 100);
    }

    @Test
    void testVanillaBlockColorTableAndDustbeamRegression() {
        Map<String, BlockColorRecord> table = VanillaBlockAppearanceResolver.getEmbeddedTable();
        assertTrue(table.size() >= 1190, "Embedded vanilla color table must cover all 1,190+ vanilla blocks");

        // Regression check for DustBeam's candy-cane red blocks that previously fell back to (150, 150, 150) gray
        String[] redBlocks = {
                "netherrack", "stripped_mangrove_wood", "mangrove_planks", "red_concrete_powder",
                "red_mushroom_block", "crimson_hyphae", "red_glazed_terracotta", "red_nether_bricks",
                "red_wool", "red_concrete", "red_terracotta"
        };
        for (String name : redBlocks) {
            BlockColorRecord rec = table.get(name);
            assertNotNull(rec, "Missing block in color table: " + name);
            assertTrue(rec.sideRgb[0] > rec.sideRgb[1] + 20 && rec.sideRgb[0] > rec.sideRgb[2] + 15,
                    name + " must be distinctly red, got RGB=" + java.util.Arrays.toString(rec.sideRgb));
        }

        // Regression check for spruce_leaves (must be dark evergreen CUTOUT_CUBE, not bright lime green CUBE)
        BlockColorRecord spruce = table.get("spruce_leaves");
        assertNotNull(spruce);
        assertEquals(ShapeKind.CUTOUT_CUBE, spruce.shapeKind);
        assertTrue(spruce.sideRgb[1] > spruce.sideRgb[0] && spruce.sideRgb[1] < 110,
                "spruce_leaves must be dark evergreen, got " + java.util.Arrays.toString(spruce.sideRgb));

        // Cherry leaves must be pink, not tinted green
        BlockColorRecord cherry = table.get("cherry_leaves");
        assertNotNull(cherry);
        assertEquals(ShapeKind.CUTOUT_CUBE, cherry.shapeKind);
        assertTrue(cherry.sideRgb[0] > cherry.sideRgb[1] + 20, "cherry_leaves must be pink");

        // Grass block top is green, bottom is brown dirt
        BlockColorRecord grass = table.get("grass_block");
        assertNotNull(grass);
        assertEquals(ShapeKind.CUBE, grass.shapeKind);
        assertTrue(grass.topRgb[1] > grass.topRgb[0], "grass_block top must be green");
        assertTrue(grass.bottomRgb[0] > grass.bottomRgb[1], "grass_block bottom must be dirt brown");

        // Non-cube shapes & invisible blocks
        assertEquals(ShapeKind.INVISIBLE, table.get("barrier").shapeKind);
        assertEquals(ShapeKind.INVISIBLE, table.get("air").shapeKind);
        assertEquals(ShapeKind.INVISIBLE, table.get("structure_void").shapeKind);
        assertEquals(ShapeKind.CROSS, table.get("short_grass").shapeKind);
        assertEquals(ShapeKind.STAIRS, table.get("oak_stairs").shapeKind);
        assertEquals(ShapeKind.SLAB, table.get("stone_brick_slab").shapeKind);
        assertEquals(ShapeKind.SNOW, table.get("snow").shapeKind);
    }

    @Test
    void testCustomResourcePackZipAnd3DBlockModelResolution(@TempDir File tempDir) throws Exception {
        // Build a synthetic custom resource pack ZIP wrapped inside a top-level folder (common CurseForge/Modrinth layout)
        File packZip = new File(tempDir, "TestRenderPack.zip");
        BufferedImage rubyPng = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = rubyPng.createGraphics();
        g.setColor(new Color(220, 25, 45, 255));
        g.fillRect(0, 0, 32, 32);
        g.dispose();
        ByteArrayOutputStream pngBytes = new ByteArrayOutputStream();
        ImageIO.write(rubyPng, "png", pngBytes);

        // Also test animated vertical strip (16x48 -> cropped to top 16x16 frame)
        BufferedImage animPng = new BufferedImage(16, 48, BufferedImage.TYPE_INT_ARGB);
        Graphics2D ga = animPng.createGraphics();
        ga.setColor(new Color(30, 180, 240, 255)); // top frame cyan
        ga.fillRect(0, 0, 16, 16);
        ga.setColor(new Color(0, 0, 0, 255)); // lower frames black
        ga.fillRect(0, 16, 16, 32);
        ga.dispose();
        ByteArrayOutputStream animBytes = new ByteArrayOutputStream();
        ImageIO.write(animPng, "png", animBytes);

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(packZip))) {
            writeZipText(zos, "MyCustomPack/pack.mcmeta", "{\"pack\":{\"pack_format\":34,\"description\":\"Test\"}}");
            writeZipText(zos, "MyCustomPack/assets/minecraft/blockstates/redstone_block.json",
                    "{\"variants\":{\"\":{\"model\":\"minecraft:block/custom_pedestal\"}}}");
            // Test 3D multi-element model + MC 26.2 {"sprite": "..."} texture object syntax
            writeZipText(zos, "MyCustomPack/assets/minecraft/models/block/custom_pedestal.json",
                    "{"
                            + "\"textures\":{\"ruby\":{\"sprite\":\"minecraft:block/ruby_pedestal\"},\"cap\":\"minecraft:block/cyan_anim\"},"
                            + "\"elements\":["
                            + "  {\"from\":[2,0,2],\"to\":[14,12,14],\"faces\":{"
                            + "    \"north\":{\"texture\":\"#ruby\"},\"south\":{\"texture\":\"#ruby\"},"
                            + "    \"east\":{\"texture\":\"#ruby\"},\"west\":{\"texture\":\"#ruby\"},"
                            + "    \"down\":{\"texture\":\"#ruby\",\"cullface\":\"down\"}"
                            + "  }},"
                            + "  {\"from\":[0,12,0],\"to\":[16,16,16],\"faces\":{"
                            + "    \"up\":{\"texture\":\"#cap\",\"cullface\":\"up\"},"
                            + "    \"north\":{\"texture\":\"#cap\"},\"south\":{\"texture\":\"#cap\"},"
                            + "    \"east\":{\"texture\":\"#cap\"},\"west\":{\"texture\":\"#cap\"}"
                            + "  }}"
                            + "]}");
            writeZipBytes(zos, "MyCustomPack/assets/minecraft/textures/block/ruby_pedestal.png", pngBytes.toByteArray());
            writeZipBytes(zos, "MyCustomPack/assets/minecraft/textures/block/cyan_anim.png", animBytes.toByteArray());
        }

        try (ExportTextureSession session = new ExportTextureSession(List.of(packZip))) {
            assertTrue(session.hasAssetPacks());
            List<TexturedModelQuad> quads = session.resolveBlockModelQuadsByName("minecraft:redstone_block", Map.of(), false, 0);
            assertNotNull(quads, "Custom resource pack 3D block model must resolve quads");
            assertEquals(10, quads.size(), "Pedestal model has 5 base faces + 5 cap faces = 10 quads");

            TexturedModelQuad rubyQuad = quads.get(0);
            assertNotNull(rubyQuad.material.texture);
            assertEquals(32, rubyQuad.material.texture.getWidth());
            assertEquals(220, rubyQuad.material.r, 2);
            assertEquals(25, rubyQuad.material.g, 2);

            // Animated 16x48 strip must be cropped to top 16x16 cyan frame (not averaged with black lower frames)
            TexturedModelQuad capUpQuad = quads.stream()
                    .filter(q -> q.cullFace == VanillaBlockAppearanceResolver.FaceDir.UP)
                    .findFirst().orElseThrow();
            assertEquals(16, capUpQuad.material.texture.getWidth());
            assertEquals(16, capUpQuad.material.texture.getHeight());
            assertEquals(180, capUpQuad.material.g, 3);
        }

        // Verify Wavefront .obj + .mtl with both Kd and map_Kd does not double-darken texture on //model re-import
        File texFile = new File(tempDir, "atlas.png");
        Files.write(texFile.toPath(), pngBytes.toByteArray());
        File mtlFile = new File(tempDir, "roundtrip.mtl");
        Files.writeString(mtlFile.toPath(),
                "newmtl ruby\nKd 0.8627 0.0980 0.1765\nmap_Kd atlas.png\n", StandardCharsets.UTF_8);
        File objFile = new File(tempDir, "roundtrip.obj");
        Files.writeString(objFile.toPath(),
                "mtllib roundtrip.mtl\nusemtl ruby\n"
                        + "v 0 0 0\nv 1 0 0\nv 1 1 0\n"
                        + "vt 0.1 0.1\nvt 0.9 0.1\nvt 0.9 0.9\n"
                        + "f 1/1 2/2 3/3\n", StandardCharsets.UTF_8);

        Mesh3D reimported = Model3DVoxelizer.loadModel(objFile.getAbsolutePath());
        assertEquals(1, reimported.triangles.size());
        Model3DVoxelizer.Triangle3D tri = reimported.triangles.get(0);
        assertNotNull(tri.material);
        assertNotNull(tri.material.texture);
        assertFalse(tri.material.multiplyTextureByKd,
                "Wavefront .mtl Kd + map_Kd must not double-multiply Kd onto texture brightness");
        assertEquals(220, (tri.material.texture.getRGB(0, 0) >> 16) & 0xFF, 2);
    }

    @Test
    void testGaussianColorBlurConvolutionAndSubBlockPreservation() {
        // 1. Verify normalized 3D Gaussian convolution blurs red + white walls into intermediate pink/coral
        //    while completely ignoring zero-weight air voxels (never darkening toward black)
        int nx = 8, ny = 5, nz = 5;
        int total = nx * ny * nz;
        float[] wr = new float[total];
        float[] wg = new float[total];
        float[] wb = new float[total];
        float[] ww = new float[total];

        // Place a 1-thick vertical wall at z=2: left half (x=0..3) red (200, 30, 30), right half (x=4..7) white (240, 240, 240)
        // Everything at z != 2 is zero-weight AIR
        for (int y = 0; y < ny; y++) {
            int row = (y * nz + 2) * nx;
            for (int x = 0; x < nx; x++) {
                int idx = row + x;
                ww[idx] = 1.0f;
                if (x < 4) {
                    wr[idx] = 200f;
                    wg[idx] = 30f;
                    wb[idx] = 30f;
                } else {
                    wr[idx] = 240f;
                    wg[idx] = 240f;
                    wb[idx] = 240f;
                }
            }
        }

        GaussianColorBlurBrush.convolve3DNormalizedGaussian(wr, wg, wb, ww, nx, ny, nz, 1.6);

        int seamLeft = (2 * nz + 2) * nx + 3;
        int seamRight = (2 * nz + 2) * nx + 4;
        assertTrue(ww[seamLeft] > 0.1f && ww[seamRight] > 0.1f);
        float blurredLeftR = wr[seamLeft] / ww[seamLeft];
        float blurredLeftG = wg[seamLeft] / ww[seamLeft];
        float blurredRightG = wg[seamRight] / ww[seamRight];

        assertTrue(blurredLeftR > 205f && blurredLeftR < 235f,
                "Normalized Gaussian must not darken toward air black; got R=" + blurredLeftR);
        assertTrue(blurredLeftG > 70f && blurredLeftG < 150f,
                "Red side of seam must blur smoothly toward white; got G=" + blurredLeftG);
        assertTrue(blurredRightG > 120f && blurredRightG < 200f,
                "White side of seam must blur smoothly toward red; got G=" + blurredRightG);

        // 2. Verify every sub-block class strictly recolors ONLY to another block of the exact same sub-block class
        assertEquals(RecolorBucket.STAIRS, SubBlockRecolorUtil.classifyBucketByName("minecraft:oak_stairs"));
        assertEquals(RecolorBucket.SLAB, SubBlockRecolorUtil.classifyBucketByName("stone_brick_slab"));
        assertEquals(RecolorBucket.WALL, SubBlockRecolorUtil.classifyBucketByName("cobblestone_wall"));
        assertEquals(RecolorBucket.FENCE, SubBlockRecolorUtil.classifyBucketByName("spruce_fence"));
        assertEquals(RecolorBucket.FENCE_GATE, SubBlockRecolorUtil.classifyBucketByName("birch_fence_gate"));
        assertEquals(RecolorBucket.TRAPDOOR, SubBlockRecolorUtil.classifyBucketByName("dark_oak_trapdoor"));
        assertEquals(RecolorBucket.CARPET, SubBlockRecolorUtil.classifyBucketByName("red_carpet"));
        assertEquals(RecolorBucket.PANE, SubBlockRecolorUtil.classifyBucketByName("white_stained_glass_pane"));
        assertEquals(RecolorBucket.BUTTON, SubBlockRecolorUtil.classifyBucketByName("cherry_button"));
        assertEquals(RecolorBucket.PLATE, SubBlockRecolorUtil.classifyBucketByName("mangrove_pressure_plate"));
        assertEquals(RecolorBucket.LEAVES, SubBlockRecolorUtil.classifyBucketByName("oak_leaves"));
        assertEquals(RecolorBucket.TRANSLUCENT_CUBE, SubBlockRecolorUtil.classifyBucketByName("red_stained_glass"));
        assertEquals(RecolorBucket.FIXED, SubBlockRecolorUtil.classifyBucketByName("air"));
        assertEquals(RecolorBucket.FIXED, SubBlockRecolorUtil.classifyBucketByName("snow"));

        int[][] testTargets = {{220, 175, 175}, {40, 40, 45}, {190, 60, 50}, {225, 225, 225}};
        for (int[] rgb : testTargets) {
            CandidateColorEntry stairCand = SubBlockRecolorUtil.findBestCandidate(
                    RecolorBucket.STAIRS, rgb[0], rgb[1], rgb[2], null, false);
            assertNotNull(stairCand);
            assertTrue(stairCand.blockName.endsWith("_stairs"), "STAIRS bucket must only return *_stairs, got " + stairCand.blockName);
            assertFalse(stairCand.blockName.contains("copper") && !stairCand.blockName.startsWith("waxed_"),
                    "Must never pick unwaxed oxidizable copper: " + stairCand.blockName);

            CandidateColorEntry slabCand = SubBlockRecolorUtil.findBestCandidate(
                    RecolorBucket.SLAB, rgb[0], rgb[1], rgb[2], null, false);
            assertNotNull(slabCand);
            assertTrue(slabCand.blockName.endsWith("_slab"), "SLAB bucket must only return *_slab, got " + slabCand.blockName);

            CandidateColorEntry wallCand = SubBlockRecolorUtil.findBestCandidate(
                    RecolorBucket.WALL, rgb[0], rgb[1], rgb[2], null, false);
            assertNotNull(wallCand);
            assertTrue(wallCand.blockName.endsWith("_wall"), "WALL bucket must only return *_wall, got " + wallCand.blockName);

            CandidateColorEntry fenceCand = SubBlockRecolorUtil.findBestCandidate(
                    RecolorBucket.FENCE, rgb[0], rgb[1], rgb[2], null, false);
            assertNotNull(fenceCand);
            assertTrue(fenceCand.blockName.endsWith("_fence"), "FENCE bucket must only return *_fence, got " + fenceCand.blockName);

            CandidateColorEntry trapCand = SubBlockRecolorUtil.findBestCandidate(
                    RecolorBucket.TRAPDOOR, rgb[0], rgb[1], rgb[2], null, false);
            assertNotNull(trapCand);
            assertTrue(trapCand.blockName.endsWith("_trapdoor"), "TRAPDOOR bucket must only return *_trapdoor, got " + trapCand.blockName);

            CandidateColorEntry carpetCand = SubBlockRecolorUtil.findBestCandidate(
                    RecolorBucket.CARPET, rgb[0], rgb[1], rgb[2], null, false);
            assertNotNull(carpetCand);
            assertTrue(carpetCand.blockName.endsWith("_carpet"), "CARPET bucket must only return *_carpet, got " + carpetCand.blockName);

            CandidateColorEntry leavesCand = SubBlockRecolorUtil.findBestCandidate(
                    RecolorBucket.LEAVES, rgb[0], rgb[1], rgb[2], null, false);
            assertNotNull(leavesCand);
            assertTrue(leavesCand.blockName.endsWith("_leaves"), "LEAVES bucket must only return *_leaves, got " + leavesCand.blockName);
        }

        // 3. Verify local-palette (-p) expansion links full blocks and sibling sub-blocks
        java.util.Set<String> expanded = SubBlockRecolorUtil.expandLocalPaletteFamilies(
                java.util.Set.of("cherry_planks", "deepslate_bricks")
        );
        assertTrue(expanded.contains("cherry_stairs") && expanded.contains("cherry_slab"));
        assertTrue(expanded.contains("deepslate_brick_stairs") && expanded.contains("deepslate_brick_slab"));
    }

    @Test
    void testSubBlockBlendBallOctantSmoothingAndSynthesis() {
        int n = 9;
        int total = n * n * n;

        // A. Flat floor invariance: y <= 2 solid, y > 2 air -> must stay 100% unchanged
        byte[] flatGrid = new byte[total];
        for (int y = 0; y <= 2; y++) {
            for (int z = 0; z < n; z++) {
                for (int x = 0; x < n; x++) {
                    flatGrid[(y * n + z) * n + x] = (byte) SubBlockBlendBallBrush.FULL_MASK;
                }
            }
        }
        byte[] flatSmoothed = SubBlockBlendBallBrush.smoothOctantGrid(flatGrid, n, n, n, 4, 2, 4, 3.5, 2, true);
        assertEquals(SubBlockBlendBallBrush.FULL_MASK, flatSmoothed[(2 * n + 4) * n + 4] & 0xFF,
                "Flat floor surface block must remain a full block");
        assertEquals(0, flatSmoothed[(3 * n + 4) * n + 4] & 0xFF,
                "Air above a flat floor must remain empty");

        // B. 45-degree raised step (y=3 solid for z >= 4, air for z < 4) -> chamfers step edge into bottom straight SOUTH stairs
        byte[] stepGrid = flatGrid.clone();
        for (int z = 4; z < n; z++) {
            for (int x = 0; x < n; x++) {
                stepGrid[(3 * n + z) * n + x] = (byte) SubBlockBlendBallBrush.FULL_MASK;
            }
        }
        byte[] stepSmoothed = SubBlockBlendBallBrush.smoothOctantGrid(stepGrid, n, n, n, 4, 3, 4, 3.5, 2, true);
        int stepEdgeOct = stepSmoothed[(3 * n + 4) * n + 4] & 0xFF;
        assertEquals(SubBlockBlendBallBrush.encodeStairOctants("south", "bottom", "straight"), stepEdgeOct);

        SynthesizedShape stepShape = sampleClassifiedShape(stepSmoothed, n, 4, 3, 4);
        assertEquals(SynthesizedKind.STAIRS, stepShape.kind);
        assertEquals("bottom", stepShape.half);
        assertEquals("straight", stepShape.stairShape);
        assertEquals(com.sk89q.worldedit.util.Direction.SOUTH, stepShape.facing);

        // Fringe block right in front of the chamfered step (x=4, y=3, z=3) must stay clean AIR (no floating slab clutter)
        SynthesizedShape fringeShape = sampleClassifiedShape(stepSmoothed, n, 4, 3, 3);
        assertEquals(SynthesizedKind.AIR, fringeShape.kind);

        // C. 3D Outer Corner (y=3 solid for x >= 4 && z >= 4) -> chamfers corner (4,3,4) into an outer corner stair
        byte[] outerGrid = flatGrid.clone();
        for (int z = 4; z < n; z++) {
            for (int x = 4; x < n; x++) {
                outerGrid[(3 * n + z) * n + x] = (byte) SubBlockBlendBallBrush.FULL_MASK;
            }
        }
        byte[] outerSmoothed = SubBlockBlendBallBrush.smoothOctantGrid(outerGrid, n, n, n, 4, 3, 4, 3.5, 2, true);
        SynthesizedShape outerShape = sampleClassifiedShape(outerSmoothed, n, 4, 3, 4);
        assertEquals(SynthesizedKind.STAIRS, outerShape.kind);
        assertEquals("bottom", outerShape.half);
        assertTrue(outerShape.stairShape.startsWith("outer_"),
                "3D outer corner must synthesize an outer_* corner stair, got " + outerShape.stairShape);

        // D. 1-block isolated bump on flat floor -> Pass 1 becomes bottom SLAB, Pass 2 smooths away to AIR
        byte[] bumpGrid = flatGrid.clone();
        bumpGrid[(3 * n + 4) * n + 4] = (byte) SubBlockBlendBallBrush.FULL_MASK;
        byte[] bumpPass1 = SubBlockBlendBallBrush.smoothOctantGrid(bumpGrid, n, n, n, 4, 3, 4, 3.5, 2, true);
        SynthesizedShape bumpShape1 = sampleClassifiedShape(bumpPass1, n, 4, 3, 4);
        assertEquals(SynthesizedKind.SLAB, bumpShape1.kind, "1-block bump must soften into a bottom slab on pass 1");
        assertEquals("bottom", bumpShape1.half);

        byte[] bumpPass2 = SubBlockBlendBallBrush.smoothOctantGrid(bumpPass1, n, n, n, 4, 3, 4, 3.5, 2, true);
        SynthesizedShape bumpShape2 = sampleClassifiedShape(bumpPass2, n, 4, 3, 4);
        assertEquals(SynthesizedKind.AIR, bumpShape2.kind, "Bottom slab bump must smooth completely to air on pass 2");

        // E. 1-block isolated pothole in flat floor -> Pass 1 fills to bottom SLAB, Pass 2 fills to FULL_BLOCK
        byte[] holeGrid = flatGrid.clone();
        holeGrid[(2 * n + 4) * n + 4] = 0;
        byte[] holePass1 = SubBlockBlendBallBrush.smoothOctantGrid(holeGrid, n, n, n, 4, 2, 4, 3.5, 2, true);
        SynthesizedShape holeShape1 = sampleClassifiedShape(holePass1, n, 4, 2, 4);
        assertEquals(SynthesizedKind.SLAB, holeShape1.kind, "1-block pothole must partially fill with a bottom slab on pass 1");
        assertEquals("bottom", holeShape1.half);

        byte[] holePass2 = SubBlockBlendBallBrush.smoothOctantGrid(holePass1, n, n, n, 4, 2, 4, 3.5, 2, true);
        SynthesizedShape holeShape2 = sampleClassifiedShape(holePass2, n, 4, 2, 4);
        assertEquals(SynthesizedKind.FULL_BLOCK, holeShape2.kind, "Bottom slab pothole must fill to a full block on pass 2");
    }

    private static SynthesizedShape sampleClassifiedShape(byte[] grid, int n, int x, int y, int z) {
        int oct = grid[(y * n + z) * n + x] & 0xFF;
        int down = grid[((y - 1) * n + z) * n + x] & 0xFF;
        int up = grid[((y + 1) * n + z) * n + x] & 0xFF;
        int north = grid[(y * n + (z - 1)) * n + x] & 0xFF;
        int south = grid[(y * n + (z + 1)) * n + x] & 0xFF;
        int west = grid[(y * n + z) * n + (x - 1)] & 0xFF;
        int east = grid[(y * n + z) * n + (x + 1)] & 0xFF;
        return SubBlockBlendBallBrush.classifyOctantShape(oct, down, up, north, south, west, east, false, false);
    }

    private static void writeZipText(ZipOutputStream zos, String entryName, String text) throws Exception {
        writeZipBytes(zos, entryName, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeZipBytes(ZipOutputStream zos, String entryName, byte[] data) throws Exception {
        zos.putNextEntry(new ZipEntry(entryName));
        zos.write(data);
        zos.closeEntry();
    }
}
