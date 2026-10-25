package com.dragonbuildtools.animation;

import com.dragonbuildtools.DragonBuildToolsPlugin;
import com.dragonbuildtools.converter.Model3DVoxelizer.VoxelGrid;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import com.sk89q.worldedit.world.block.BlockTypes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

/**
 * Real-time voxel animation and named video playback engine using Pre-Voxelized Frame Caching and Sparse Frame-Delta Swapping.
 * Only blocks that actually change between frame (f - 1) and frame (f) are updated each tick.
 * Supports multiple independent video/animation instances sharing the same source file.
 */
public final class AnimationManager {

    public static final class BlockDelta {
        public final int dx;
        public final int dy;
        public final int dz;
        public final BaseBlock block; // non-null (uses AIR for cleared blocks)

        public BlockDelta(int dx, int dy, int dz, BaseBlock block) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.block = block;
        }
    }

    public static final class ActiveAnimation {
        public final int id;
        public final String name;
        public final String sourceFile;
        public final String type;
        public final World world;
        public volatile BlockVector3 origin;
        public final int maxX;
        public final int maxY;
        public final int maxZ;
        public final List<VoxelGrid> frames;
        public final List<List<BlockDelta>> frameDeltas;
        public final Map<BlockVector3, BaseBlock> originalTerrain = new LinkedHashMap<>();

        public volatile double fps;
        public volatile boolean paused = false;
        public volatile boolean loop = true;
        public volatile boolean hiddenTerrainRestored = false;
        public volatile int currentFrame = -1;
        public double tickAccumulator = 0.0;
        public volatile int lastDeltaCount = 0;

        public ActiveAnimation(
                int id,
                String name,
                String sourceFile,
                String type,
                World world,
                BlockVector3 origin,
                int maxX,
                int maxY,
                int maxZ,
                List<VoxelGrid> frames,
                List<List<BlockDelta>> frameDeltas,
                double fps,
                boolean loop,
                boolean paused
        ) {
            this.id = id;
            this.name = name;
            this.sourceFile = sourceFile != null ? sourceFile : name;
            this.type = type;
            this.world = world;
            this.origin = origin;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
            this.frames = frames;
            this.frameDeltas = frameDeltas;
            this.fps = Math.max(0.5, Math.min(20.0, fps));
            this.loop = loop;
            this.paused = paused;
        }
    }

    private final DragonBuildToolsPlugin plugin;
    private final Map<Integer, ActiveAnimation> animations = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private BukkitTask tickerTask;

    public AnimationManager(DragonBuildToolsPlugin plugin) {
        this.plugin = plugin;
    }

    public void startTicker() {
        if (tickerTask != null) {
            return;
        }
        if (plugin != null && Bukkit.getServer() != null) {
            tickerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 1L, 1L);
        }
    }

    public void shutdown() {
        if (tickerTask != null) {
            tickerTask.cancel();
            tickerTask = null;
        }
        for (Integer id : new ArrayList<>(animations.keySet())) {
            stopAnimation(id, true);
        }
        animations.clear();
    }

    /**
     * Pre-computes sparse frame deltas between consecutive frames in a cyclic animation.
     * Frame 0's delta is relative to the last frame (N - 1) so looping is seamless.
     */
    public static List<List<BlockDelta>> computeFrameDeltas(List<VoxelGrid> frames, int maxX, int maxY, int maxZ) {
        if (frames == null || frames.isEmpty()) {
            return Collections.emptyList();
        }
        BaseBlock airBlock = getAirBlock();

        int n = frames.size();
        List<List<BlockDelta>> allDeltas = new ArrayList<>(n);
        for (int f = 0; f < n; f++) {
            VoxelGrid prev = frames.get((f - 1 + n) % n);
            VoxelGrid curr = frames.get(f);
            List<BlockDelta> delta = new ArrayList<>();

            for (int y = 0; y < maxY; y++) {
                for (int z = 0; z < maxZ; z++) {
                    for (int x = 0; x < maxX; x++) {
                        BaseBlock bPrev = getBlockAt(prev, x, y, z);
                        BaseBlock bCurr = getBlockAt(curr, x, y, z);
                        if (sameBlockState(bPrev, bCurr)) {
                            continue;
                        }
                        if (bCurr != null) {
                            delta.add(new BlockDelta(x, y, z, bCurr));
                        } else if (airBlock != null) {
                            delta.add(new BlockDelta(x, y, z, airBlock));
                        }
                    }
                }
            }
            allDeltas.add(delta);
        }
        return allDeltas;
    }

    private static BaseBlock getAirBlock() {
        try {
            return BlockTypes.AIR != null && BlockTypes.AIR.getDefaultState() != null
                    ? BlockTypes.AIR.getDefaultState().toBaseBlock()
                    : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static BaseBlock getBlockAt(VoxelGrid grid, int x, int y, int z) {
        if (grid == null || x < 0 || x >= grid.sizeX || y < 0 || y >= grid.sizeY || z < 0 || z >= grid.sizeZ) {
            return null;
        }
        return grid.blocks[x][y][z];
    }

    private static boolean sameBlockState(BaseBlock a, BaseBlock b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.toImmutableState().equals(b.toImmutableState());
    }

    /**
     * Registers and starts a new pre-voxelized animation at {@code origin}.
     */
    public ActiveAnimation registerAnimation(
            String name,
            String type,
            World world,
            BlockVector3 origin,
            List<VoxelGrid> frames,
            double fps,
            boolean loop
    ) {
        return registerNamedAnimation(name, name, type, world, origin, frames, fps, loop, false);
    }

    /**
     * Registers a named video or 3D animation instance. Multiple instances with different {@code instanceName}s
     * can share the same {@code sourceFile} and even share {@code frames} while maintaining independent state and locations.
     */
    public ActiveAnimation registerNamedAnimation(
            String instanceName,
            String sourceFile,
            String type,
            World world,
            BlockVector3 origin,
            List<VoxelGrid> frames,
            double fps,
            boolean loop,
            boolean startPaused
    ) {
        int maxX = 0, maxY = 0, maxZ = 0;
        for (VoxelGrid g : frames) {
            maxX = Math.max(maxX, g.sizeX);
            maxY = Math.max(maxY, g.sizeY);
            maxZ = Math.max(maxZ, g.sizeZ);
        }

        List<List<BlockDelta>> deltas = computeFrameDeltas(frames, maxX, maxY, maxZ);
        int id = nextId.getAndIncrement();
        String effectiveName = (instanceName == null || instanceName.trim().isEmpty()) ? ("anim_" + id) : instanceName.trim();
        ActiveAnimation anim = new ActiveAnimation(
                id, effectiveName, sourceFile, type, world, origin,
                maxX, maxY, maxZ, frames, deltas, fps, loop, startPaused
        );

        snapshotTerrain(anim);
        renderFullFrame(anim, 0);
        animations.put(id, anim);
        startTicker();
        return anim;
    }

    public void snapshotTerrain(ActiveAnimation anim) {
        if (anim == null || anim.world == null) {
            return;
        }
        anim.originalTerrain.clear();
        BlockVector3 origin = anim.origin;
        try (EditSession session = WorldEdit.getInstance().newEditSessionBuilder().world(anim.world).fastMode(true).build()) {
            for (int y = 0; y < anim.maxY; y++) {
                for (int z = 0; z < anim.maxZ; z++) {
                    for (int x = 0; x < anim.maxX; x++) {
                        boolean touched = false;
                        for (VoxelGrid g : anim.frames) {
                            if (getBlockAt(g, x, y, z) != null) {
                                touched = true;
                                break;
                            }
                        }
                        if (touched) {
                            BlockVector3 pt = origin.add(x, y, z);
                            anim.originalTerrain.put(pt, session.getFullBlock(pt));
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public void restoreTerrain(ActiveAnimation anim) {
        if (anim == null || anim.world == null || anim.originalTerrain.isEmpty()) {
            return;
        }
        try (EditSession session = WorldEdit.getInstance().newEditSessionBuilder().world(anim.world).fastMode(true).build()) {
            for (Map.Entry<BlockVector3, BaseBlock> entry : anim.originalTerrain.entrySet()) {
                if (entry.getValue() != null) {
                    session.setBlock(entry.getKey(), (BlockStateHolder) entry.getValue());
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public void hideAndRestoreTerrain(ActiveAnimation anim) {
        if (anim == null) {
            return;
        }
        anim.paused = true;
        if (!anim.hiddenTerrainRestored) {
            restoreTerrain(anim);
            anim.hiddenTerrainRestored = true;
            anim.currentFrame = -1;
        }
    }

    public void ensureVisible(ActiveAnimation anim) {
        if (anim == null) {
            return;
        }
        if (anim.hiddenTerrainRestored) {
            snapshotTerrain(anim);
            anim.hiddenTerrainRestored = false;
            anim.currentFrame = -1;
            renderFullFrame(anim, 0);
        }
    }

    public void relocateAnimation(ActiveAnimation anim, BlockVector3 newOrigin) {
        if (anim == null || newOrigin == null) {
            return;
        }
        int frameToRender = Math.max(0, anim.currentFrame);
        if (!anim.hiddenTerrainRestored) {
            restoreTerrain(anim);
        }
        anim.origin = newOrigin;
        snapshotTerrain(anim);
        anim.hiddenTerrainRestored = false;
        anim.currentFrame = -1;
        renderFullFrame(anim, frameToRender);
    }

    private void tickAll() {
        if (animations.isEmpty()) {
            return;
        }
        for (ActiveAnimation anim : animations.values()) {
            if (anim.paused || anim.hiddenTerrainRestored || anim.frames.size() <= 1) {
                continue;
            }
            double ticksPerFrame = 20.0 / Math.max(0.5, anim.fps);
            anim.tickAccumulator += 1.0;
            if (anim.tickAccumulator >= ticksPerFrame) {
                anim.tickAccumulator -= ticksPerFrame;
                if (anim.tickAccumulator > ticksPerFrame * 2) {
                    anim.tickAccumulator = 0.0;
                }
                int nextFrame = anim.currentFrame + 1;
                if (nextFrame >= anim.frames.size()) {
                    if (anim.loop) {
                        nextFrame = 0;
                    } else {
                        anim.paused = true;
                        continue;
                    }
                }
                applyFrameDelta(anim, nextFrame);
            }
        }
    }

    public void applyFrameDelta(ActiveAnimation anim, int targetFrame) {
        if (anim == null || anim.world == null || targetFrame < 0 || targetFrame >= anim.frames.size()) {
            return;
        }
        if (anim.hiddenTerrainRestored) {
            ensureVisible(anim);
        }
        int expectedNext = (anim.currentFrame + 1) % anim.frames.size();
        if (targetFrame != expectedNext || anim.currentFrame < 0) {
            renderFullFrame(anim, targetFrame);
            return;
        }

        List<BlockDelta> deltas = anim.frameDeltas.get(targetFrame);
        if (!deltas.isEmpty()) {
            try (EditSession session = WorldEdit.getInstance().newEditSessionBuilder().world(anim.world).fastMode(true).build()) {
                for (BlockDelta d : deltas) {
                    session.setBlock(anim.origin.add(d.dx, d.dy, d.dz), (BlockStateHolder) d.block);
                }
            } catch (Throwable ignored) {
            }
        }
        anim.currentFrame = targetFrame;
        anim.lastDeltaCount = deltas.size();
    }

    public void renderFullFrame(ActiveAnimation anim, int frameIdx) {
        if (anim == null || anim.world == null || frameIdx < 0 || frameIdx >= anim.frames.size()) {
            return;
        }
        if (anim.hiddenTerrainRestored) {
            snapshotTerrain(anim);
            anim.hiddenTerrainRestored = false;
        }
        BaseBlock airBlock = getAirBlock();
        VoxelGrid target = anim.frames.get(frameIdx);
        VoxelGrid prev = (anim.currentFrame >= 0 && anim.currentFrame < anim.frames.size())
                ? anim.frames.get(anim.currentFrame)
                : null;

        int changed = 0;
        try (EditSession session = WorldEdit.getInstance().newEditSessionBuilder().world(anim.world).fastMode(true).build()) {
            for (int y = 0; y < anim.maxY; y++) {
                for (int z = 0; z < anim.maxZ; z++) {
                    for (int x = 0; x < anim.maxX; x++) {
                        BaseBlock bPrev = getBlockAt(prev, x, y, z);
                        BaseBlock bCurr = getBlockAt(target, x, y, z);
                        if (bCurr != null) {
                            if (!sameBlockState(bPrev, bCurr)) {
                                session.setBlock(anim.origin.add(x, y, z), (BlockStateHolder) bCurr);
                                changed++;
                            }
                        } else if (bPrev != null && airBlock != null) {
                            session.setBlock(anim.origin.add(x, y, z), (BlockStateHolder) airBlock);
                            changed++;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        anim.currentFrame = frameIdx;
        anim.lastDeltaCount = changed;
    }

    public boolean stopAnimation(int id, boolean keepCurrentFrame) {
        ActiveAnimation anim = animations.remove(id);
        if (anim == null) {
            return false;
        }
        anim.paused = true;
        if (!keepCurrentFrame && !anim.hiddenTerrainRestored) {
            restoreTerrain(anim);
        }
        return true;
    }

    public boolean removeAnimationByNameOrId(String key, boolean keepCurrentFrame) {
        ActiveAnimation anim = getAnimationByNameOrId(key);
        if (anim == null) {
            return false;
        }
        return stopAnimation(anim.id, keepCurrentFrame);
    }

    public int stopAllAnimations(boolean keepCurrentFrame) {
        int count = 0;
        for (Integer id : new ArrayList<>(animations.keySet())) {
            if (stopAnimation(id, keepCurrentFrame)) {
                count++;
            }
        }
        return count;
    }

    public ActiveAnimation getAnimation(int id) {
        return animations.get(id);
    }

    public ActiveAnimation getAnimationByName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        String target = name.trim();
        for (ActiveAnimation anim : animations.values()) {
            if (anim.name.equalsIgnoreCase(target)) {
                return anim;
            }
        }
        return null;
    }

    public ActiveAnimation getAnimationByNameOrId(String key) {
        if (key == null || key.trim().isEmpty()) {
            return null;
        }
        String trimmed = key.trim();
        ActiveAnimation byName = getAnimationByName(trimmed);
        if (byName != null) {
            return byName;
        }
        String numPart = trimmed.startsWith("#") ? trimmed.substring(1) : trimmed;
        try {
            int id = Integer.parseInt(numPart);
            return animations.get(id);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public List<ActiveAnimation> resolveAnimations(String target, boolean videosOnly) {
        if (target == null || target.trim().isEmpty() || "all".equalsIgnoreCase(target.trim())) {
            List<ActiveAnimation> list = new ArrayList<>();
            for (ActiveAnimation a : animations.values()) {
                if (!videosOnly || a.type.toLowerCase(Locale.ROOT).contains("video")) {
                    list.add(a);
                }
            }
            return list;
        }
        ActiveAnimation single = getAnimationByNameOrId(target);
        if (single != null) {
            return Collections.singletonList(single);
        }
        return Collections.emptyList();
    }

    public Map<Integer, ActiveAnimation> getActiveAnimations() {
        return Collections.unmodifiableMap(animations);
    }
}
