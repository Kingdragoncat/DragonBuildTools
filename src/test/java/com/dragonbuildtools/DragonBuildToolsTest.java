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
import com.dragonbuildtools.wfc.Wfc3DGridGenerator;
import com.sk89q.worldedit.command.util.CommandPermissionsConditionGenerator;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
}
