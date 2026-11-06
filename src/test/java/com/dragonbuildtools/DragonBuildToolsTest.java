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

    private static void writeZipText(ZipOutputStream zos, String entryName, String text) throws Exception {
        writeZipBytes(zos, entryName, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeZipBytes(ZipOutputStream zos, String entryName, byte[] data) throws Exception {
        zos.putNextEntry(new ZipEntry(entryName));
        zos.write(data);
        zos.closeEntry();
    }
}
