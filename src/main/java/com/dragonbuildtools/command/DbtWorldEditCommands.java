package com.dragonbuildtools.command;

import com.dragonbuildtools.DragonBuildToolsPlugin;
import com.dragonbuildtools.animation.AnimatedModelEngine;
import com.dragonbuildtools.animation.AnimatedModelEngine.MotionMode;
import com.dragonbuildtools.animation.AnimationManager;
import com.dragonbuildtools.animation.AnimationManager.ActiveAnimation;
import com.dragonbuildtools.animation.VideoAndGifEngine;
import com.dragonbuildtools.converter.HeightmapTerrainGenerator;
import com.dragonbuildtools.converter.ImagePixelArtConverter;
import com.dragonbuildtools.converter.Model3DVoxelizer;
import com.dragonbuildtools.converter.Model3DVoxelizer.Mesh3D;
import com.dragonbuildtools.converter.Model3DVoxelizer.VoxelGrid;
import com.dragonbuildtools.converter.Text3DGenerator;
import com.dragonbuildtools.util.CardinalDirectionUtil;
import com.dragonbuildtools.wfc.Wfc3DGridGenerator;
import com.fastasyncworldedit.core.configuration.Caption;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.EmptyClipboardException;
import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.command.util.CommandPermissions;
import com.sk89q.worldedit.command.util.Logging;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.extension.input.ParserContext;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.util.formatting.text.TextComponent;
import com.sk89q.worldedit.util.formatting.text.format.TextColor;
import com.sk89q.worldedit.world.block.BaseBlock;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;

/**
 * WorldEdit / FAWE commands for Dragon Build Tools (DBT):
 * {@code //pixelart}, {@code //model}, {@code //animmodel}, {@code //video}, {@code //anim},
 * {@code //heightmap}, {@code //text3d}, and {@code //newwfc}.
 */
public class DbtWorldEditCommands {

    @CommandPermissions({"dragonbuildtools.use"})
    @Logging(Logging.LogMode.PLACEMENT)
    public void pixelArtCommand(
            Player player,
            LocalSession session,
            EditSession editSession,
            String source,
            int width,
            int height,
            boolean flat,
            boolean noDither,
            boolean noGlass,
            boolean noCenter
    ) throws WorldEditException {
        if (source.equalsIgnoreCase("help") || source.equalsIgnoreCase("?")) {
            player.print(TextComponent.of("Usage: //pixelart <file|url> [width=64] [height=0] [-f] [-d] [-g] [-c]", TextColor.AQUA));
            return;
        }
        int clampedW = Math.max(1, Math.min(512, width));
        int clampedH = height <= 0 ? 0 : Math.max(1, Math.min(512, height));
        try {
            BufferedImage raw = ImagePixelArtConverter.loadImage(source);
            BufferedImage scaled = ImagePixelArtConverter.resizeImage(raw, clampedW, clampedH);
            BaseBlock[][] grid = ImagePixelArtConverter.imageToBlocks(scaled, !noDither, !noGlass);
            int placed = ImagePixelArtConverter.placePixelArt(editSession, player, grid, flat, !noCenter);
            player.print(TextComponent.of(
                    "[DBT] Built pixel art (" + scaled.getWidth() + "x" + scaled.getHeight() + ", " + placed + " blocks).",
                    TextColor.LIGHT_PURPLE
            ));
        } catch (IOException e) {
            player.print(TextComponent.of("[DBT] Failed to load image: " + e.getMessage(), TextColor.RED));
        }
    }

    @CommandPermissions({"dragonbuildtools.use"})
    @Logging(Logging.LogMode.PLACEMENT)
    public void modelCommand(
            Player player,
            LocalSession session,
            EditSession editSession,
            String source,
            int size,
            String patternStr,
            double rx,
            double ry,
            double rz,
            boolean solid,
            boolean noDither,
            boolean noGlass,
            boolean forceShade,
            boolean noSubBlocks,
            boolean centerOnPlayer
    ) throws WorldEditException {
        if (source.equalsIgnoreCase("help") || source.equalsIgnoreCase("?")) {
            player.print(TextComponent.of("Usage: //model <file|url> [size=48] [block] [rx] [ry] [rz] [-s] [-d] [-g] [-l] [-b] [-c]", TextColor.AQUA));
            return;
        }
        int clampedSize = Math.max(2, Math.min(350, size));
        Pattern overridePattern = parseOptionalPattern(player, session, patternStr);
        BlockVector3 explicitOrigin = getOptionalSelectionMin(player, session);

        try {
            Mesh3D mesh = Model3DVoxelizer.loadModel(source);
            VoxelGrid grid = Model3DVoxelizer.voxelize(
                    mesh, clampedSize, rx, ry, rz,
                    solid, !noDither, !noGlass, forceShade, !noSubBlocks, overridePattern
            );
            int placed = Model3DVoxelizer.placeVoxelGrid(editSession, player, grid, centerOnPlayer, explicitOrigin);
            player.print(TextComponent.of(
                    "[DBT] Built 3D model (" + grid.sizeX + "x" + grid.sizeY + "x" + grid.sizeZ + ", " + placed + " blocks).",
                    TextColor.LIGHT_PURPLE
            ));
        } catch (IOException e) {
            player.print(TextComponent.of("[DBT] Failed to load 3D model: " + e.getMessage(), TextColor.RED));
        }
    }

    @CommandPermissions({"dragonbuildtools.use"})
    public void animModelCommand(
            Player player,
            LocalSession session,
            String source,
            int size,
            String modeStr,
            int frames,
            double fps,
            String patternStr,
            boolean solid,
            boolean noSubBlocks,
            boolean noLoop,
            boolean centerOnPlayer
    ) throws WorldEditException {
        if (source.equalsIgnoreCase("help") || source.equalsIgnoreCase("?")) {
            player.print(TextComponent.of("Usage: //animmodel <file|folder|url> [size=40] [wingflap|spin|hover|breathe|swing] [frames=16] [fps=8] [block]", TextColor.AQUA));
            return;
        }
        int clampedSize = Math.max(4, Math.min(140, size));
        int clampedFrames = Math.max(2, Math.min(60, frames));
        double clampedFps = Math.max(0.5, Math.min(20.0, fps));
        MotionMode mode = MotionMode.fromString(modeStr);
        Pattern overridePattern = parseOptionalPattern(player, session, patternStr);

        player.print(TextComponent.of(
                "[DBT] Pre-voxelizing " + clampedFrames + " 3D animation frames for '" + source + "' (" + mode + ")...",
                TextColor.YELLOW
        ));

        try {
            List<VoxelGrid> voxelFrames = AnimatedModelEngine.loadAndVoxelizeAnimation(
                    source, clampedSize, clampedFrames, mode,
                    solid, true, true, false, !noSubBlocks, overridePattern
            );
            if (voxelFrames.isEmpty()) {
                player.print(TextComponent.of("[DBT] No voxel frames generated.", TextColor.RED));
                return;
            }
            VoxelGrid first = voxelFrames.get(0);
            BlockVector3 origin = computePlacementOrigin(player, first.sizeX, first.sizeZ, centerOnPlayer);

            DragonBuildToolsPlugin plugin = DragonBuildToolsPlugin.getInstance();
            if (plugin != null && plugin.getAnimationManager() != null) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    ActiveAnimation anim = plugin.getAnimationManager().registerAnimation(
                            source, "3D-" + mode.name(), player.getWorld(), origin, voxelFrames, clampedFps, !noLoop
                    );
                    player.print(TextComponent.of(
                            "[DBT] Started 3D Animation #" + anim.id + " (" + voxelFrames.size() + " frames @ "
                                    + clampedFps + " FPS). Use //anim stop " + anim.id + " to stop & restore terrain.",
                            TextColor.GREEN
                    ));
                });
            }
        } catch (IOException e) {
            player.print(TextComponent.of("[DBT] Failed to animate 3D model: " + e.getMessage(), TextColor.RED));
        }
    }

    /**
     * Instantiates and controls named video screens:
     * <ul>
     *   <li>{@code //video create <video_name> <file_name> [width=64] [height=0] [fps=10] [maxFrames=60] [-f] [-u] [-d] [-g] [-n] [-c] [-a]}</li>
     *   <li>{@code //video start <video_name|all>} (or {@code play}, {@code resume})</li>
     *   <li>{@code //video pause <video_name|all>}</li>
     *   <li>{@code //video stop <video_name|all> [-r]}</li>
     *   <li>{@code //video restart <video_name|all>}</li>
     *   <li>{@code //video seek <video_name> <frame>} (or {@code frame})</li>
     *   <li>{@code //video fps <video_name|all> <fps>} (or {@code speed})</li>
     *   <li>{@code //video loop <video_name|all> [on|off|toggle]}</li>
     *   <li>{@code //video move <video_name> [-c]}</li>
     *   <li>{@code //video clone <source_video_name> <new_video_name> [-c] [-a]}</li>
     *   <li>{@code //video remove <video_name|all> [-k]} (or {@code delete})</li>
     *   <li>{@code //video info <video_name>}</li>
     *   <li>{@code //video list}</li>
     * </ul>
     */
    @CommandPermissions({"dragonbuildtools.use"})
    public void videoCommand(
            Player player,
            LocalSession session,
            String subAction,
            String videoName,
            String fileOrArg,
            int width,
            int height,
            double fps,
            int maxFrames,
            boolean flatFloor,
            boolean curvedScreen,
            boolean noDither,
            boolean noGlass,
            boolean noLoop,
            boolean centerOnPlayer,
            boolean autoStart,
            boolean restoreOnStop,
            boolean keepOnRemove
    ) throws WorldEditException {
        DragonBuildToolsPlugin plugin = DragonBuildToolsPlugin.getInstance();
        if (plugin == null || plugin.getAnimationManager() == null) {
            player.print(TextComponent.of("[DBT] Animation manager is not initialized.", TextColor.RED));
            return;
        }
        AnimationManager mgr = plugin.getAnimationManager();
        String act = (subAction == null || subAction.trim().isEmpty()) ? "list" : subAction.trim().toLowerCase(Locale.ROOT);

        if ("help".equals(act) || "?".equals(act)) {
            sendVideoHelp(player);
            return;
        }

        switch (act) {
            case "create":
            case "new":
            case "add": {
                if (videoName == null || videoName.trim().isEmpty() || fileOrArg == null || fileOrArg.trim().isEmpty()) {
                    player.print(TextComponent.of("Usage: //video create <video_name> <file_name> [width=64] [height=0] [fps=10] [maxFrames=60] [-f] [-u] [-d] [-g] [-n] [-c] [-a]", TextColor.RED));
                    return;
                }
                String vName = videoName.trim();
                String sourceFile = fileOrArg.trim();
                if (mgr.getAnimationByName(vName) != null) {
                    player.print(TextComponent.of("[DBT] A video named '" + vName + "' already exists! Choose another <video_name> or run //video remove " + vName + " first.", TextColor.RED));
                    return;
                }

                int clampedW = Math.max(4, Math.min(160, width));
                int clampedH = height <= 0 ? 0 : Math.max(4, Math.min(160, height));
                double clampedFps = Math.max(0.5, Math.min(20.0, fps));
                int clampedMaxFrames = Math.max(2, Math.min(180, maxFrames));
                CardinalDirectionUtil dir = CardinalDirectionUtil.getCardinalDirection(player);

                try {
                    List<VoxelGrid> frames = VideoAndGifEngine.loadVideoFramesAsVoxels(
                            sourceFile, clampedW, clampedH, clampedMaxFrames,
                            flatFloor, curvedScreen, !noDither, !noGlass, dir
                    );
                    VoxelGrid first = frames.get(0);
                    BlockVector3 origin = computePlacementOrigin(player, first.sizeX, first.sizeZ, centerOnPlayer);
                    String screenType = curvedScreen ? "CurvedVideo" : (flatFloor ? "FloorVideo" : "WallVideo");

                    Bukkit.getScheduler().runTask(plugin, () -> {
                        ActiveAnimation anim = mgr.registerNamedAnimation(
                                vName, sourceFile, screenType,
                                player.getWorld(), origin, frames, clampedFps, !noLoop, !autoStart
                        );
                        player.print(TextComponent.of(
                                "[DBT] Created video '" + anim.name + "' (#" + anim.id + ") from '" + anim.sourceFile + "' ("
                                        + frames.size() + " frames, " + first.sizeX + "x" + first.sizeY + "x" + first.sizeZ
                                        + " @ " + clampedFps + " FPS) " + (autoStart ? "[PLAYING]." : "[READY - Frame 1 placed]."),
                                TextColor.GREEN
                        ));
                        if (!autoStart) {
                            player.print(TextComponent.of(
                                    "[DBT] Run //video start " + anim.name + " to play, //video pause " + anim.name
                                            + ", //video fps " + anim.name + " <fps>, or //video remove " + anim.name + ".",
                                    TextColor.AQUA
                            ));
                        }
                    });
                } catch (IOException e) {
                    player.print(TextComponent.of("[DBT] Failed to load video/GIF '" + sourceFile + "': " + e.getMessage(), TextColor.RED));
                }
                return;
            }

            case "start":
            case "play":
            case "resume": {
                if (videoName == null || videoName.trim().isEmpty()) {
                    player.print(TextComponent.of("Usage: //video start <video_name|all>", TextColor.RED));
                    return;
                }
                List<ActiveAnimation> targets = mgr.resolveAnimations(videoName, true);
                if (targets.isEmpty()) {
                    player.print(TextComponent.of("[DBT] Video '" + videoName + "' not found. Use //video list to view instantiated videos.", TextColor.RED));
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (ActiveAnimation a : targets) {
                        mgr.ensureVisible(a);
                        a.paused = false;
                    }
                    player.print(TextComponent.of("[DBT] Started video playback for: " + videoName, TextColor.GREEN));
                });
                return;
            }

            case "pause": {
                if (videoName == null || videoName.trim().isEmpty()) {
                    player.print(TextComponent.of("Usage: //video pause <video_name|all>", TextColor.RED));
                    return;
                }
                List<ActiveAnimation> targets = mgr.resolveAnimations(videoName, true);
                if (targets.isEmpty()) {
                    player.print(TextComponent.of("[DBT] Video '" + videoName + "' not found.", TextColor.RED));
                    return;
                }
                for (ActiveAnimation a : targets) {
                    a.paused = true;
                }
                player.print(TextComponent.of("[DBT] Paused video: " + videoName, TextColor.YELLOW));
                return;
            }

            case "stop": {
                if (videoName == null || videoName.trim().isEmpty()) {
                    player.print(TextComponent.of("Usage: //video stop <video_name|all> [-r]", TextColor.RED));
                    return;
                }
                List<ActiveAnimation> targets = mgr.resolveAnimations(videoName, true);
                if (targets.isEmpty()) {
                    player.print(TextComponent.of("[DBT] Video '" + videoName + "' not found.", TextColor.RED));
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (ActiveAnimation a : targets) {
                        a.paused = true;
                        a.tickAccumulator = 0.0;
                        if (restoreOnStop) {
                            mgr.hideAndRestoreTerrain(a);
                        } else {
                            mgr.renderFullFrame(a, 0);
                        }
                    }
                    player.print(TextComponent.of(
                            "[DBT] Stopped video '" + videoName + "'"
                                    + (restoreOnStop ? " and restored terrain (use //video start " + videoName + " to play again)." : " and rewound to Frame 1."),
                            TextColor.GREEN
                    ));
                });
                return;
            }

            case "restart":
            case "reset":
            case "rewind": {
                if (videoName == null || videoName.trim().isEmpty()) {
                    player.print(TextComponent.of("Usage: //video restart <video_name|all>", TextColor.RED));
                    return;
                }
                List<ActiveAnimation> targets = mgr.resolveAnimations(videoName, true);
                if (targets.isEmpty()) {
                    player.print(TextComponent.of("[DBT] Video '" + videoName + "' not found.", TextColor.RED));
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (ActiveAnimation a : targets) {
                        mgr.ensureVisible(a);
                        a.tickAccumulator = 0.0;
                        mgr.renderFullFrame(a, 0);
                        a.paused = false;
                    }
                    player.print(TextComponent.of("[DBT] Restarted video from Frame 1: " + videoName, TextColor.GREEN));
                });
                return;
            }

            case "seek":
            case "frame":
            case "step":
            case "goto": {
                if (videoName == null || videoName.trim().isEmpty() || fileOrArg == null || fileOrArg.trim().isEmpty()) {
                    player.print(TextComponent.of("Usage: //video seek <video_name> <frame_number>", TextColor.RED));
                    return;
                }
                List<ActiveAnimation> targets = mgr.resolveAnimations(videoName, true);
                if (targets.isEmpty()) {
                    player.print(TextComponent.of("[DBT] Video '" + videoName + "' not found.", TextColor.RED));
                    return;
                }
                int requestedFrame;
                try {
                    requestedFrame = Integer.parseInt(fileOrArg.trim());
                } catch (NumberFormatException e) {
                    player.print(TextComponent.of("[DBT] Invalid frame number: " + fileOrArg, TextColor.RED));
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (ActiveAnimation a : targets) {
                        int zeroBased = Math.max(0, Math.min(a.frames.size() - 1, requestedFrame > 0 ? requestedFrame - 1 : 0));
                        mgr.renderFullFrame(a, zeroBased);
                        player.print(TextComponent.of("[DBT] Video '" + a.name + "' seeked to frame " + (zeroBased + 1) + "/" + a.frames.size() + ".", TextColor.GREEN));
                    }
                });
                return;
            }

            case "fps":
            case "speed":
            case "rate": {
                if (videoName == null || videoName.trim().isEmpty() || fileOrArg == null || fileOrArg.trim().isEmpty()) {
                    player.print(TextComponent.of("Usage: //video fps <video_name|all> <fps>", TextColor.RED));
                    return;
                }
                List<ActiveAnimation> targets = mgr.resolveAnimations(videoName, true);
                if (targets.isEmpty()) {
                    player.print(TextComponent.of("[DBT] Video '" + videoName + "' not found.", TextColor.RED));
                    return;
                }
                double newFps;
                try {
                    newFps = Double.parseDouble(fileOrArg.trim());
                } catch (NumberFormatException e) {
                    player.print(TextComponent.of("[DBT] Invalid FPS value: " + fileOrArg, TextColor.RED));
                    return;
                }
                double clamped = Math.max(0.5, Math.min(20.0, newFps));
                for (ActiveAnimation a : targets) {
                    a.fps = clamped;
                }
                player.print(TextComponent.of("[DBT] Set playback speed to " + clamped + " FPS for: " + videoName, TextColor.GREEN));
                return;
            }

            case "loop": {
                if (videoName == null || videoName.trim().isEmpty()) {
                    player.print(TextComponent.of("Usage: //video loop <video_name|all> [on|off|toggle]", TextColor.RED));
                    return;
                }
                List<ActiveAnimation> targets = mgr.resolveAnimations(videoName, true);
                if (targets.isEmpty()) {
                    player.print(TextComponent.of("[DBT] Video '" + videoName + "' not found.", TextColor.RED));
                    return;
                }
                String mode = (fileOrArg == null) ? "toggle" : fileOrArg.trim().toLowerCase(Locale.ROOT);
                for (ActiveAnimation a : targets) {
                    if ("on".equals(mode) || "true".equals(mode)) {
                        a.loop = true;
                    } else if ("off".equals(mode) || "false".equals(mode)) {
                        a.loop = false;
                    } else {
                        a.loop = !a.loop;
                    }
                    player.print(TextComponent.of("[DBT] Video '" + a.name + "' loop set to: " + (a.loop ? "ON" : "OFF"), TextColor.GREEN));
                }
                return;
            }

            case "move":
            case "tp":
            case "relocate":
            case "here": {
                if (videoName == null || videoName.trim().isEmpty()) {
                    player.print(TextComponent.of("Usage: //video move <video_name> [-c]", TextColor.RED));
                    return;
                }
                ActiveAnimation anim = mgr.getAnimationByNameOrId(videoName);
                if (anim == null) {
                    player.print(TextComponent.of("[DBT] Video '" + videoName + "' not found.", TextColor.RED));
                    return;
                }
                BlockVector3 newOrigin = computePlacementOrigin(player, anim.maxX, anim.maxZ, centerOnPlayer);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    mgr.relocateAnimation(anim, newOrigin);
                    player.print(TextComponent.of(
                            "[DBT] Moved video '" + anim.name + "' to (" + newOrigin.getBlockX() + ", "
                                    + newOrigin.getBlockY() + ", " + newOrigin.getBlockZ() + ").",
                            TextColor.GREEN
                    ));
                });
                return;
            }

            case "clone":
            case "copy":
            case "duplicate": {
                if (videoName == null || videoName.trim().isEmpty() || fileOrArg == null || fileOrArg.trim().isEmpty()) {
                    player.print(TextComponent.of("Usage: //video clone <source_video_name> <new_video_name> [-c] [-a]", TextColor.RED));
                    return;
                }
                ActiveAnimation src = mgr.getAnimationByNameOrId(videoName);
                if (src == null) {
                    player.print(TextComponent.of("[DBT] Source video '" + videoName + "' not found.", TextColor.RED));
                    return;
                }
                String newName = fileOrArg.trim();
                if (mgr.getAnimationByName(newName) != null) {
                    player.print(TextComponent.of("[DBT] A video named '" + newName + "' already exists!", TextColor.RED));
                    return;
                }
                BlockVector3 newOrigin = computePlacementOrigin(player, src.maxX, src.maxZ, centerOnPlayer);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    ActiveAnimation cloned = mgr.registerNamedAnimation(
                            newName, src.sourceFile, src.type,
                            player.getWorld(), newOrigin, src.frames, src.fps, src.loop, !autoStart
                    );
                    player.print(TextComponent.of(
                            "[DBT] Cloned video '" + src.name + "' -> '" + cloned.name + "' (#" + cloned.id
                                    + ", file: " + cloned.sourceFile + "). Run //video start " + cloned.name + " to play!",
                            TextColor.GREEN
                    ));
                });
                return;
            }

            case "remove":
            case "delete":
            case "destroy":
            case "rm": {
                if (videoName == null || videoName.trim().isEmpty()) {
                    player.print(TextComponent.of("Usage: //video remove <video_name|all> [-k]", TextColor.RED));
                    return;
                }
                List<ActiveAnimation> targets = mgr.resolveAnimations(videoName, true);
                if (targets.isEmpty()) {
                    player.print(TextComponent.of("[DBT] Video '" + videoName + "' not found.", TextColor.RED));
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    int removed = 0;
                    for (ActiveAnimation a : targets) {
                        if (mgr.stopAnimation(a.id, keepOnRemove)) {
                            removed++;
                        }
                    }
                    player.print(TextComponent.of(
                            "[DBT] Removed " + removed + " video instance(s)"
                                    + (keepOnRemove ? " (kept screen blocks)." : " (restored original terrain)."),
                            TextColor.GREEN
                    ));
                });
                return;
            }

            case "info": {
                if (videoName == null || videoName.trim().isEmpty()) {
                    player.print(TextComponent.of("Usage: //video info <video_name>", TextColor.RED));
                    return;
                }
                ActiveAnimation a = mgr.getAnimationByNameOrId(videoName);
                if (a == null) {
                    player.print(TextComponent.of("[DBT] Video '" + videoName + "' not found.", TextColor.RED));
                    return;
                }
                String state = a.hiddenTerrainRestored ? "STOPPED (Hidden)" : (a.paused ? "PAUSED" : "PLAYING");
                player.print(TextComponent.of("=== Video Info: " + a.name + " (#" + a.id + ") ===", TextColor.GOLD));
                player.print(TextComponent.of(" Source File: " + a.sourceFile + " | Mode: " + a.type, TextColor.AQUA));
                player.print(TextComponent.of(" Status: " + state + " | Frame: " + (Math.max(0, a.currentFrame) + 1) + "/" + a.frames.size()
                        + " | Speed: " + a.fps + " FPS | Loop: " + (a.loop ? "ON" : "OFF"), TextColor.GREEN));
                player.print(TextComponent.of(" Size: " + a.maxX + "x" + a.maxY + "x" + a.maxZ
                        + " | Origin: (" + a.origin.getBlockX() + ", " + a.origin.getBlockY() + ", " + a.origin.getBlockZ() + ")", TextColor.GRAY));
                return;
            }

            case "list":
            case "ls":
            case "status": {
                List<ActiveAnimation> all = mgr.resolveAnimations("all", false);
                if (all.isEmpty()) {
                    player.print(TextComponent.of("[DBT] No videos or animations are currently instantiated. Use //video create <name> <file> to create one!", TextColor.YELLOW));
                    return;
                }
                player.print(TextComponent.of("=== Instantiated Videos & Animations (" + all.size() + ") ===", TextColor.GOLD));
                for (ActiveAnimation a : all) {
                    String state = a.hiddenTerrainRestored ? "[STOPPED]" : (a.paused ? "[PAUSED]" : "[PLAYING]");
                    player.print(TextComponent.of(
                            " - " + a.name + " (#" + a.id + ", file: " + a.sourceFile + ") [" + a.type + "] "
                                    + "Frame " + (Math.max(0, a.currentFrame) + 1) + "/" + a.frames.size()
                                    + " @ " + a.fps + " FPS " + state
                                    + " at (" + a.origin.getBlockX() + "," + a.origin.getBlockY() + "," + a.origin.getBlockZ() + ")",
                            TextColor.AQUA
                    ));
                }
                return;
            }

            default:
                sendVideoHelp(player);
        }
    }

    private static void sendVideoHelp(Player player) {
        player.print(TextComponent.of("=== //video Commands (Named Video Instances) ===", TextColor.GOLD));
        player.print(TextComponent.of(" //video create <name> <file|url> [width=64] [height=0] [fps=10] [maxFrames=60] [-f] [-u] [-d] [-g] [-n] [-c] [-a]", TextColor.AQUA));
        player.print(TextComponent.of(" //video start <name|all>        - Start / resume video playback", TextColor.GREEN));
        player.print(TextComponent.of(" //video pause <name|all>        - Pause video on current frame", TextColor.GREEN));
        player.print(TextComponent.of(" //video stop <name|all> [-r]    - Stop & rewind to frame 1 (-r hides screen & restores terrain)", TextColor.GREEN));
        player.print(TextComponent.of(" //video restart <name|all>      - Restart playback from frame 1", TextColor.GREEN));
        player.print(TextComponent.of(" //video seek <name> <frame>     - Jump directly to a frame", TextColor.GREEN));
        player.print(TextComponent.of(" //video fps <name|all> <fps>    - Change playback speed (0.5 - 20 FPS)", TextColor.GREEN));
        player.print(TextComponent.of(" //video loop <name> [on|off]    - Toggle or set looping", TextColor.GREEN));
        player.print(TextComponent.of(" //video move <name> [-c]        - Move video screen to your current position", TextColor.GREEN));
        player.print(TextComponent.of(" //video clone <src> <new> [-c]  - Create another video instance with the same file", TextColor.GREEN));
        player.print(TextComponent.of(" //video remove <name|all> [-k]  - Delete video instance (-k keeps blocks)", TextColor.GREEN));
        player.print(TextComponent.of(" //video list | //video info <name>", TextColor.GREEN));
    }

    @CommandPermissions({"dragonbuildtools.use"})
    public void animControlCommand(
            Player player,
            String action,
            String target,
            double value,
            boolean keepBlocks
    ) {
        DragonBuildToolsPlugin plugin = DragonBuildToolsPlugin.getInstance();
        if (plugin == null || plugin.getAnimationManager() == null) {
            return;
        }
        AnimationManager mgr = plugin.getAnimationManager();
        String act = action.toLowerCase(Locale.ROOT);

        switch (act) {
            case "list": {
                List<ActiveAnimation> active = mgr.resolveAnimations("all", false);
                if (active.isEmpty()) {
                    player.print(TextComponent.of("[DBT] No active animations running.", TextColor.YELLOW));
                    return;
                }
                player.print(TextComponent.of("=== Active DBT Animations (" + active.size() + ") ===", TextColor.GOLD));
                for (ActiveAnimation a : active) {
                    player.print(TextComponent.of(
                            " #" + a.id + " " + a.name + " (file: " + a.sourceFile + ") [" + a.type + "] Frame "
                                    + (Math.max(0, a.currentFrame) + 1) + "/" + a.frames.size()
                                    + " (" + a.lastDeltaCount + " delta blocks/tick) @ " + a.fps + " FPS "
                                    + (a.paused ? "[PAUSED]" : "[PLAYING]"),
                            TextColor.AQUA
                    ));
                }
                return;
            }
            case "start":
            case "play":
            case "resume":
            case "pause": {
                boolean pause = "pause".equals(act);
                List<ActiveAnimation> resolved = mgr.resolveAnimations(target, false);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (ActiveAnimation a : resolved) {
                        if (!pause) {
                            mgr.ensureVisible(a);
                        }
                        a.paused = pause;
                    }
                    player.print(TextComponent.of("[DBT] " + (pause ? "Paused" : "Resumed") + " animation(s): " + target, TextColor.GREEN));
                });
                return;
            }
            case "speed":
            case "fps": {
                double newFps = Math.max(0.5, Math.min(20.0, value));
                for (ActiveAnimation a : mgr.resolveAnimations(target, false)) {
                    a.fps = newFps;
                }
                player.print(TextComponent.of("[DBT] Updated speed to " + newFps + " FPS for: " + target, TextColor.GREEN));
                return;
            }
            case "frame":
            case "seek": {
                int idx = Math.max(0, (int) Math.round(value));
                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (ActiveAnimation a : mgr.resolveAnimations(target, false)) {
                        a.paused = true;
                        int clampedIdx = Math.min(a.frames.size() - 1, idx);
                        mgr.renderFullFrame(a, clampedIdx);
                    }
                    player.print(TextComponent.of("[DBT] Stepped to frame " + idx + " for: " + target, TextColor.GREEN));
                });
                return;
            }
            case "stop":
            case "remove": {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if ("all".equalsIgnoreCase(target)) {
                        int count = mgr.stopAllAnimations(keepBlocks);
                        player.print(TextComponent.of("[DBT] Stopped " + count + " animation(s)"
                                + (keepBlocks ? " (kept current frame)." : " (restored original terrain)."), TextColor.GREEN));
                    } else {
                        if (mgr.removeAnimationByNameOrId(target, keepBlocks)) {
                            player.print(TextComponent.of("[DBT] Stopped animation '" + target + "'.", TextColor.GREEN));
                        } else {
                            player.print(TextComponent.of("[DBT] Animation '" + target + "' not found.", TextColor.RED));
                        }
                    }
                });
                return;
            }
            default:
                player.print(TextComponent.of("Usage: //anim <list|start|pause|resume|speed|frame|stop> [name|id|all] [value] [-k]", TextColor.AQUA));
        }
    }

    @CommandPermissions({"dragonbuildtools.use"})
    @Logging(Logging.LogMode.PLACEMENT)
    public void heightmapCommand(
            Player player,
            LocalSession session,
            EditSession editSession,
            String source,
            int size,
            int maxHeight,
            String colormap,
            String patternStr,
            boolean includeWater,
            boolean hollow,
            boolean noSubBlocks,
            boolean centerOnPlayer
    ) throws WorldEditException {
        if (source.equalsIgnoreCase("help") || source.equalsIgnoreCase("?")) {
            player.print(TextComponent.of("Usage: //heightmap <file|url> [size=80] [maxHeight=40] [colormap|none] [block] [-w] [-h] [-b] [-c]", TextColor.AQUA));
            return;
        }
        String cmap = (colormap == null || colormap.equalsIgnoreCase("none") || colormap.isEmpty()) ? null : colormap;
        Pattern overridePattern = parseOptionalPattern(player, session, patternStr);
        BlockVector3 explicitOrigin = getOptionalSelectionMin(player, session);

        try {
            VoxelGrid terrain = HeightmapTerrainGenerator.generateTerrain(
                    source, cmap, size, maxHeight, !hollow, !noSubBlocks, includeWater, overridePattern
            );
            int placed = Model3DVoxelizer.placeVoxelGrid(editSession, player, terrain, centerOnPlayer, explicitOrigin);
            player.print(TextComponent.of(
                    "[DBT] Generated 3D heightmap terrain (" + terrain.sizeX + "x" + terrain.sizeY + "x" + terrain.sizeZ
                            + ", " + placed + " blocks).",
                    TextColor.LIGHT_PURPLE
            ));
        } catch (IOException e) {
            player.print(TextComponent.of("[DBT] Failed to load heightmap: " + e.getMessage(), TextColor.RED));
        }
    }

    @CommandPermissions({"dragonbuildtools.use"})
    @Logging(Logging.LogMode.PLACEMENT)
    public void text3DCommand(
            Player player,
            LocalSession session,
            EditSession editSession,
            int height,
            int depth,
            String patternStr,
            String text,
            boolean bevel,
            boolean noSubBlocks,
            boolean centerOnPlayer
    ) throws WorldEditException {
        String display = text.replace('_', ' ');
        Pattern overridePattern = parseOptionalPattern(player, session, patternStr);
        VoxelGrid grid = Text3DGenerator.generate3DText(display, height, depth, bevel, !noSubBlocks, overridePattern);
        int placed = Model3DVoxelizer.placeVoxelGrid(editSession, player, grid, centerOnPlayer, null);
        player.print(TextComponent.of(
                "[DBT] Built 3D Text '" + display + "' (" + grid.sizeX + "x" + grid.sizeY + "x" + grid.sizeZ
                        + ", " + placed + " blocks).",
                TextColor.LIGHT_PURPLE
        ));
    }

    @CommandPermissions({"dragonbuildtools.use"})
    @Logging(Logging.LogMode.REGION)
    public void newWfcCommand(
            Player player,
            LocalSession session,
            EditSession editSession,
            int gridSizeXZ,
            int wfcSizeXYZ,
            int variationCount,
            double adherence,
            double timeout,
            String neglectMaskStr,
            boolean useClipboard,
            boolean useExistingBaseLayer,
            boolean continuousSelection,
            int lazyTolerance,
            boolean outerWallsAndCeilingRestricted
    ) throws MaxChangedBlocksException {
        Region region;
        try {
            region = session.getSelection(player.getWorld());
        } catch (IncompleteRegionException e) {
            player.print(TextComponent.of("[DBT] Make a cuboid WorldEdit selection first!", TextColor.RED));
            return;
        }
        if (!(region instanceof CuboidRegion)) {
            player.print(TextComponent.of("[DBT] //newwfc only works with cuboid selections.", TextColor.RED));
            return;
        }
        if (gridSizeXZ < 1 || wfcSizeXYZ < 1 || variationCount < 1) {
            player.print(TextComponent.of("[DBT] Grid size, WFC piece size, and variation count must be at least 1!", TextColor.RED));
            return;
        }
        if (lazyTolerance < 0) {
            player.print(TextComponent.of("[DBT] Lazy tolerance (-l) cannot be negative!", TextColor.RED));
            return;
        }

        CuboidRegion cuboidRegion = (CuboidRegion) region;
        Extent sampleExtent = editSession;
        CuboidRegion sampleRegion = cuboidRegion;

        if (useClipboard) {
            try {
                ClipboardHolder holder = session.getClipboard();
                Clipboard clipboard = holder.getClipboard();
                sampleExtent = clipboard;
                Region clipReg = clipboard.getRegion();
                sampleRegion = new CuboidRegion(clipReg.getMinimumPoint(), clipReg.getMaximumPoint());
            } catch (EmptyClipboardException e) {
                player.print(TextComponent.of("[DBT] Your clipboard is empty! Use //copy on your variations first when using -c.", TextColor.RED));
                return;
            }
        }

        if (sampleRegion.getWidth() < gridSizeXZ || sampleRegion.getLength() < gridSizeXZ) {
            String srcName = useClipboard ? "Clipboard" : "Selection";
            player.print(TextComponent.of("[DBT] " + srcName + " (" + sampleRegion.getWidth() + "x" + sampleRegion.getLength()
                    + ") is smaller than grid cell size (" + gridSizeXZ + "x" + gridSizeXZ + ")!", TextColor.RED));
            return;
        }
        if (!continuousSelection && (cuboidRegion.getWidth() < gridSizeXZ || cuboidRegion.getLength() < gridSizeXZ)) {
            player.print(TextComponent.of("[DBT] Selection (" + cuboidRegion.getWidth() + "x" + cuboidRegion.getLength()
                    + ") is smaller than grid cell size (" + gridSizeXZ + "x" + gridSizeXZ + ")! (Or pass -j for continuous evaluation)", TextColor.RED));
            return;
        }

        String effectiveMaskStr = (neglectMaskStr == null || neglectMaskStr.trim().isEmpty())
                ? "structure_void"
                : neglectMaskStr.trim();
        Mask neglectMask = null;
        if (!"none".equalsIgnoreCase(effectiveMaskStr) && !"off".equalsIgnoreCase(effectiveMaskStr)) {
            try {
                ParserContext parserContext = new ParserContext();
                parserContext.setActor(player);
                parserContext.setWorld(player.getWorld());
                parserContext.setExtent(sampleExtent);
                parserContext.setSession(session);
                neglectMask = WorldEdit.getInstance().getMaskFactory().parseFromInput(effectiveMaskStr, parserContext);
            } catch (Exception e) {
                if (!"structure_void".equalsIgnoreCase(effectiveMaskStr)) {
                    player.print(TextComponent.of("[DBT] Invalid -n mask: " + effectiveMaskStr, TextColor.RED));
                    return;
                }
            }
        }

        Wfc3DGridGenerator generator = new Wfc3DGridGenerator(
                editSession,
                sampleExtent,
                sampleRegion,
                cuboidRegion,
                gridSizeXZ,
                wfcSizeXYZ,
                variationCount,
                (float) adherence,
                timeout,
                useClipboard,
                neglectMask,
                effectiveMaskStr,
                useExistingBaseLayer,
                continuousSelection,
                lazyTolerance,
                outerWallsAndCeilingRestricted
        );
        generator.execute();
        player.printInfo(Caption.of("fawe.worldedit.visitor.visitor.block", new Object[]{ editSession.getBlockChangeCount() }));
    }

    private static BlockVector3 computePlacementOrigin(Player player, int sizeX, int sizeZ, boolean centerOnPlayer) {
        BlockVector3 p = player.getBlockIn().toBlockPoint();
        if (centerOnPlayer) {
            return p.subtract(sizeX / 2, 0, sizeZ / 2);
        }
        CardinalDirectionUtil dir = CardinalDirectionUtil.getCardinalDirection(player);
        BlockVector3 fwd = dir.getForward();
        int offsetDist = Math.max(sizeX, sizeZ) / 2 + 5;
        BlockVector3 centerGround = p.add(fwd.getBlockX() * offsetDist, 0, fwd.getBlockZ() * offsetDist);
        return centerGround.subtract(sizeX / 2, 0, sizeZ / 2);
    }

    private static Pattern parseOptionalPattern(Player player, LocalSession session, String patternStr) {
        if (patternStr == null || patternStr.trim().isEmpty()) {
            return null;
        }
        try {
            ParserContext ctx = new ParserContext();
            ctx.setActor(player);
            ctx.setWorld(player.getWorld());
            ctx.setSession(session);
            return WorldEdit.getInstance().getPatternFactory().parseFromInput(patternStr.trim(), ctx);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static BlockVector3 getOptionalSelectionMin(Player player, LocalSession session) {
        try {
            Region sel = session.getSelection(player.getWorld());
            if (sel != null) {
                return sel.getMinimumPoint();
            }
        } catch (IncompleteRegionException ignored) {
        }
        return null;
    }
}
