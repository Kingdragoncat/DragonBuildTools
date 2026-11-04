package com.dragonbuildtools.command;

import com.dragonbuildtools.DragonBuildToolsPlugin;
import com.dragonbuildtools.animation.AnimatedModelEngine;
import com.dragonbuildtools.animation.AnimationManager;
import com.dragonbuildtools.animation.AnimationManager.ActiveAnimation;
import com.dragonbuildtools.animation.VideoAndGifEngine;
import com.dragonbuildtools.converter.HeightmapTerrainGenerator;
import com.dragonbuildtools.converter.ImagePixelArtConverter;
import com.dragonbuildtools.converter.Model3DVoxelizer;
import com.dragonbuildtools.converter.ObjSelectionExporter;
import com.dragonbuildtools.demo.DemoAssetGenerator;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * Handles the {@code /dbt} ({@code /dragonbuildtools}, {@code /dbtools}) command, Studio GUI, demos, and help.
 */
public class DbtBukkitCommandHandler implements CommandExecutor, TabCompleter {

    private static final List<String> TOPICS = Arrays.asList(
            "gui", "demo", "help", "pixelart", "model", "animmodel", "video", "anim", "heightmap", "text3d", "exportobj", "newwfc", "files"
    );
    private static final List<String> DEMO_NAMES = Arrays.asList(
            "dragon", "cinema", "knot", "terrain", "text", "setup"
    );

    private final DragonBuildToolsPlugin plugin;

    public DbtBukkitCommandHandler(DragonBuildToolsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("dragonbuildtools.use")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use Dragon Build Tools.");
            return true;
        }

        if (args.length == 0) {
            if (sender instanceof Player player && plugin.getGuiManager() != null) {
                plugin.getGuiManager().openHome(player);
            } else {
                sendHelpTopic(sender, "overview");
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("help") || args[0].equalsIgnoreCase("?")) {
            String topic = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "overview";
            sendHelpTopic(sender, topic);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "gui":
            case "menu":
            case "studio":
                if (sender instanceof Player player && plugin.getGuiManager() != null) {
                    plugin.getGuiManager().openHome(player);
                } else {
                    sender.sendMessage(ChatColor.RED + "Only in-game players can open the GUI.");
                }
                return true;
            case "demo":
                handleDemoSubcommand(sender, args.length >= 2 ? args[1] : "list");
                return true;
            case "pixelart":
            case "image":
            case "img":
                sendHelpTopic(sender, "pixelart");
                return true;
            case "model":
            case "3dmodel":
            case "voxel":
                sendHelpTopic(sender, "model");
                return true;
            case "animmodel":
            case "3danim":
                sendHelpTopic(sender, "animmodel");
                return true;
            case "video":
            case "gif":
                sendHelpTopic(sender, "video");
                return true;
            case "anim":
                handleBukkitAnimSubcommand(sender, Arrays.copyOfRange(args, 1, args.length));
                return true;
            case "heightmap":
            case "terrain":
            case "map":
                sendHelpTopic(sender, "heightmap");
                return true;
            case "text3d":
            case "3dtext":
                sendHelpTopic(sender, "text3d");
                return true;
            case "files":
            case "list":
                sendFilesSummary(sender);
                return true;
            default:
                sendHelpTopic(sender, sub);
                return true;
        }
    }

    public static void sendHelpTopic(CommandSender sender, String topic) {
        switch (topic.toLowerCase(Locale.ROOT)) {
            case "pixelart":
            case "image":
            case "img": {
                sender.sendMessage(ChatColor.DARK_AQUA + "=== " + ChatColor.GOLD + "DBT: 2D Image to Pixel Art" + ChatColor.DARK_AQUA + " ===");
                sender.sendMessage(ChatColor.AQUA + "//pixelart <file|url> [width] [height] [-f] [-d] [-g] [-c]");
                sender.sendMessage(ChatColor.GRAY + "Aliases: " + ChatColor.WHITE + "//dbtimage, //dbtimg, /pixelart");
                sender.sendMessage(ChatColor.GRAY + "Formats: " + ChatColor.WHITE + "PNG, JPG, GIF, WEBP, SVG/SVGZ, BMP, TGA, ICO, PPM/PGM, TIFF");
                sender.sendMessage(ChatColor.GRAY + "Folder:  " + ChatColor.WHITE + "plugins/DragonBuildTools/images/ (or any http/https URL)");
                sender.sendMessage(ChatColor.YELLOW + "Flags:");
                sender.sendMessage(ChatColor.WHITE + "  -f  " + ChatColor.GRAY + "Build flat on the floor instead of upright wall");
                sender.sendMessage(ChatColor.WHITE + "  -d  " + ChatColor.GRAY + "Disable Floyd-Steinberg dithering");
                sender.sendMessage(ChatColor.WHITE + "  -g  " + ChatColor.GRAY + "Disable stained glass for translucent pixels");
                sender.sendMessage(ChatColor.WHITE + "  -c  " + ChatColor.GRAY + "Place starting at exact player block instead of centered");
                return;
            }
            case "model":
            case "3dmodel":
            case "voxel": {
                sender.sendMessage(ChatColor.DARK_AQUA + "=== " + ChatColor.GOLD + "DBT: Static 3D Model to Build" + ChatColor.DARK_AQUA + " ===");
                sender.sendMessage(ChatColor.AQUA + "//model <file|url> [size=48] [block] [rx=0] [ry=0] [rz=0] [-s] [-d] [-g] [-l] [-b] [-c]");
                sender.sendMessage(ChatColor.GRAY + "Aliases: " + ChatColor.WHITE + "//3dmodel, //voxel, //dbtmodel, /model");
                sender.sendMessage(ChatColor.GRAY + "Formats: " + ChatColor.WHITE + ".obj (+ .mtl & textures), .stl, .ply, .glb, .gltf, .vox, .off");
                sender.sendMessage(ChatColor.GRAY + "Folder:  " + ChatColor.WHITE + "plugins/DragonBuildTools/models/ (or any http/https URL)");
                sender.sendMessage(ChatColor.YELLOW + "Flags:");
                sender.sendMessage(ChatColor.WHITE + "  -s  " + ChatColor.GRAY + "Solid interior fill");
                sender.sendMessage(ChatColor.WHITE + "  -l  " + ChatColor.GRAY + "Directional shading");
                sender.sendMessage(ChatColor.WHITE + "  -b  " + ChatColor.GRAY + "Full cube blocks only (disable stairs/slabs/layers)");
                sender.sendMessage(ChatColor.WHITE + "  -d  " + ChatColor.GRAY + "Disable dithering   " + ChatColor.WHITE + "-g " + ChatColor.GRAY + "Disable glass   " + ChatColor.WHITE + "-c " + ChatColor.GRAY + "Center on player");
                return;
            }
            case "animmodel":
            case "3danim": {
                sender.sendMessage(ChatColor.DARK_AQUA + "=== " + ChatColor.GOLD + "DBT: Animated 3D Models" + ChatColor.DARK_AQUA + " ===");
                sender.sendMessage(ChatColor.AQUA + "//animmodel <file|folder|url> [size=40] [mode] [frames=16] [fps=8] [block] [-s] [-b] [-n] [-c]");
                sender.sendMessage(ChatColor.GRAY + "Aliases: " + ChatColor.WHITE + "//3danim, //animate3d, /animmodel");
                sender.sendMessage(ChatColor.GRAY + "Modes:   " + ChatColor.WHITE + "wingflap (default), spin, hover, breathe, swing, auto");
                sender.sendMessage(ChatColor.GRAY + "Sources: " + ChatColor.WHITE + "Any .obj/.stl/.glb/.ply/.vox model, multi-frame .vox, or frame sequence folder in plugins/DragonBuildTools/animations/");
                sender.sendMessage(ChatColor.YELLOW + "Examples:");
                sender.sendMessage(ChatColor.WHITE + "  //animmodel dragon.obj 80 wingflap 16 8");
                sender.sendMessage(ChatColor.WHITE + "  //animmodel statue.glb 60 spin 24 10");
                sender.sendMessage(ChatColor.WHITE + "  //anim stop all  " + ChatColor.GRAY + "(Stops & restores original terrain)");
                return;
            }
            case "video":
            case "gif": {
                sender.sendMessage(ChatColor.DARK_AQUA + "=== " + ChatColor.GOLD + "DBT: Named Video & Animated GIF Screens" + ChatColor.DARK_AQUA + " ===");
                sender.sendMessage(ChatColor.AQUA + "//video create <video_name> <file_name> [width=64] [height=0] [fps=10] [maxFrames=60] [-f] [-u] [-d] [-g] [-n] [-c] [-a]");
                sender.sendMessage(ChatColor.AQUA + "//video start <video_name|all>        " + ChatColor.GRAY + "- Start or resume playback");
                sender.sendMessage(ChatColor.AQUA + "//video pause <video_name|all>        " + ChatColor.GRAY + "- Pause on current frame");
                sender.sendMessage(ChatColor.AQUA + "//video stop <video_name|all> [-r]    " + ChatColor.GRAY + "- Stop & rewind to frame 1 (-r hides screen & restores terrain)");
                sender.sendMessage(ChatColor.AQUA + "//video restart <video_name|all>      " + ChatColor.GRAY + "- Restart playback from frame 1");
                sender.sendMessage(ChatColor.AQUA + "//video seek <video_name> <frame>     " + ChatColor.GRAY + "- Jump to a specific frame");
                sender.sendMessage(ChatColor.AQUA + "//video fps <video_name|all> <fps>    " + ChatColor.GRAY + "- Adjust playback speed (0.5 - 20 FPS)");
                sender.sendMessage(ChatColor.AQUA + "//video loop <video_name> [on|off]    " + ChatColor.GRAY + "- Toggle or set looping");
                sender.sendMessage(ChatColor.AQUA + "//video move <video_name> [-c]        " + ChatColor.GRAY + "- Move video screen to your current position");
                sender.sendMessage(ChatColor.AQUA + "//video clone <src_name> <new_name>   " + ChatColor.GRAY + "- Duplicate a video instance using the same file");
                sender.sendMessage(ChatColor.AQUA + "//video remove <video_name|all> [-k]  " + ChatColor.GRAY + "- Delete video instance (-k keeps current frame blocks)");
                sender.sendMessage(ChatColor.AQUA + "//video list | //video info <video_name>");
                sender.sendMessage(ChatColor.GRAY + "Note: Multiple independent video names can use the exact same <file_name> simultaneously!");
                return;
            }
            case "newwfc":
            case "wfc": {
                sender.sendMessage(ChatColor.DARK_AQUA + "=== " + ChatColor.GOLD + "DBT: 3D Wave Function Collapse (//newwfc)" + ChatColor.DARK_AQUA + " ===");
                sender.sendMessage(ChatColor.AQUA + "//newwfc <grid_size> [wfc_size=1] [variation_count=2] [adherence=1] [timeout=5] [-n <mask>] [-c] [-b] [-j] [-l <int>] [-g]");
                sender.sendMessage(ChatColor.YELLOW + "Flags:");
                sender.sendMessage(ChatColor.WHITE + "  -n <mask> " + ChatColor.GRAY + "Blocks matching <mask> in variations are not placeable; neighbors act like variation edges (default: structure_void)");
                sender.sendMessage(ChatColor.WHITE + "  -c        " + ChatColor.GRAY + "Use the clipboard as the variations' source");
                sender.sendMessage(ChatColor.WHITE + "  -b        " + ChatColor.GRAY + "Keep existing first layer in selection as base and generate off of those (only overwrite non-base blocks with non-air)");
                sender.sendMessage(ChatColor.WHITE + "  -j        " + ChatColor.GRAY + "Make the whole selection one continuous WFC evaluation area (variations still use grid)");
                sender.sendMessage(ChatColor.WHITE + "  -l <int>  " + ChatColor.GRAY + "Lazy mode: allow placing a block even if up to <int> neighbors lack it in their options");
                sender.sendMessage(ChatColor.WHITE + "  -g        " + ChatColor.GRAY + "Make every outer wall and the ceiling layer act like the bottom layer");
                return;
            }
            case "anim": {
                sender.sendMessage(ChatColor.DARK_AQUA + "=== " + ChatColor.GOLD + "DBT: Live Animation Controller" + ChatColor.DARK_AQUA + " ===");
                sender.sendMessage(ChatColor.AQUA + "//anim list                 " + ChatColor.GRAY + "- List active 3D & video animations");
                sender.sendMessage(ChatColor.AQUA + "//anim pause <id|all>       " + ChatColor.GRAY + "- Pause an animation");
                sender.sendMessage(ChatColor.AQUA + "//anim resume <id|all>      " + ChatColor.GRAY + "- Resume a paused animation");
                sender.sendMessage(ChatColor.AQUA + "//anim speed <id> <fps>     " + ChatColor.GRAY + "- Adjust playback FPS (0.5 - 20)");
                sender.sendMessage(ChatColor.AQUA + "//anim frame <id> <index>   " + ChatColor.GRAY + "- Jump to a specific frame");
                sender.sendMessage(ChatColor.AQUA + "//anim stop <id|all> [-k]   " + ChatColor.GRAY + "- Stop & restore terrain (-k keeps current frame)");
                return;
            }
            case "heightmap":
            case "terrain":
            case "map": {
                sender.sendMessage(ChatColor.DARK_AQUA + "=== " + ChatColor.GOLD + "DBT: Heightmaps & Maps to 3D Terrain" + ChatColor.DARK_AQUA + " ===");
                sender.sendMessage(ChatColor.AQUA + "//heightmap <file|url> [size=80] [maxHeight=40] [colormap|none] [block] [-w] [-h] [-b] [-c]");
                sender.sendMessage(ChatColor.GRAY + "Aliases: " + ChatColor.WHITE + "//terrain, //dem, /heightmap");
                sender.sendMessage(ChatColor.GRAY + "Folder:  " + ChatColor.WHITE + "plugins/DragonBuildTools/heightmaps/");
                sender.sendMessage(ChatColor.GRAY + "Flags:   " + ChatColor.WHITE + "-w " + ChatColor.GRAY + "water basins   " + ChatColor.WHITE + "-h " + ChatColor.GRAY + "hollow shell   " + ChatColor.WHITE + "-b " + ChatColor.GRAY + "full cubes only   " + ChatColor.WHITE + "-c " + ChatColor.GRAY + "center");
                return;
            }
            case "text3d":
            case "3dtext": {
                sender.sendMessage(ChatColor.DARK_AQUA + "=== " + ChatColor.GOLD + "DBT: 3D Extruded Text" + ChatColor.DARK_AQUA + " ===");
                sender.sendMessage(ChatColor.AQUA + "//text3d <height> <depth> <block> <text> [-v] [-b] [-c] [-o]");
                sender.sendMessage(ChatColor.GRAY + "Aliases: " + ChatColor.WHITE + "//3dtext, /text3d");
                sender.sendMessage(ChatColor.GRAY + "Example: " + ChatColor.WHITE + "//text3d 24 5 gold_block DRAGON_BUILD_TOOLS -v");
                return;
            }
            case "exportobj":
            case "schemtoobj":
            case "toobj": {
                sender.sendMessage(ChatColor.DARK_AQUA + "=== " + ChatColor.GOLD + "DBT: Export Selection to .OBJ + .MTL" + ChatColor.DARK_AQUA + " ===");
                sender.sendMessage(ChatColor.AQUA + "//exportobj <name> [-c] [-m]");
                sender.sendMessage(ChatColor.GRAY + "Aliases: " + ChatColor.WHITE + "//schemtoobj, //toobj, /exportobj");
                sender.sendMessage(ChatColor.GRAY + "Output:  " + ChatColor.WHITE + "plugins/DragonBuildTools/exports/<name>.obj (+ .mtl)");
                sender.sendMessage(ChatColor.WHITE + "  -c  " + ChatColor.GRAY + "Export from your //copy clipboard instead of your selection");
                sender.sendMessage(ChatColor.WHITE + "  -m  " + ChatColor.GRAY + "Also copy into models/ so you can re-import or //animmodel it");
                return;
            }
            default: {
                sender.sendMessage(ChatColor.DARK_AQUA + "=== " + ChatColor.GOLD + "Dragon Build Tools (DBT) v2.0.0" + ChatColor.DARK_AQUA + " ===");
                sender.sendMessage(ChatColor.YELLOW + "Commands " + ChatColor.GRAY + "(run " + ChatColor.WHITE + "/dbt" + ChatColor.GRAY + " for GUI or " + ChatColor.WHITE + "/dbt help <topic>" + ChatColor.GRAY + "):");
                sender.sendMessage(ChatColor.AQUA + "  /dbt                              " + ChatColor.GRAY + "- Open interactive Chest Studio GUI");
                sender.sendMessage(ChatColor.AQUA + "  /dbt demo <dragon|cinema|knot...> " + ChatColor.GRAY + "- Spawn built-in starter demos right away");
                sender.sendMessage(ChatColor.AQUA + "  //model <src> [size] [block]      " + ChatColor.GRAY + "- 3D Model to Build (-s, -l, -b, -c, -o)");
                sender.sendMessage(ChatColor.AQUA + "  //animmodel <src> [size] [mode]   " + ChatColor.GRAY + "- Animated 3D Model (wingflap/spin/hover/etc.)");
                sender.sendMessage(ChatColor.AQUA + "  //video create <name> <file>      " + ChatColor.GRAY + "- Named Video / GIF Screen (-f, -u, -a, -r)");
                sender.sendMessage(ChatColor.AQUA + "  //newwfc <grid> [wfc] [vars]      " + ChatColor.GRAY + "- 3D WFC Generator (-n, -c, -b, -j, -l, -g)");
                sender.sendMessage(ChatColor.AQUA + "  //exportobj <name> [-c] [-m]      " + ChatColor.GRAY + "- Export selection/clipboard to .obj + .mtl");
                sender.sendMessage(ChatColor.AQUA + "  //pixelart <src> [w] [h]          " + ChatColor.GRAY + "- 2D Image to Pixel Art (-f, -o)");
                sender.sendMessage(ChatColor.AQUA + "  //heightmap <src> [size] [maxY]   " + ChatColor.GRAY + "- Heightmap / DEM to 3D Terrain (-w, -o)");
                sender.sendMessage(ChatColor.AQUA + "  //text3d <h> <depth> <blk> <text> " + ChatColor.GRAY + "- 3D Extruded Text (-v, -o)");
            }
        }
    }

    private void handleDemoSubcommand(CommandSender sender, String preset) {
        String p = preset == null ? "list" : preset.toLowerCase(Locale.ROOT);
        if ("setup".equals(p)) {
            int created = DemoAssetGenerator.ensureStarterAssets(true);
            sender.sendMessage(ChatColor.GREEN + "[DBT] Generated " + created + " starter demo files in plugins/DragonBuildTools/!");
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.YELLOW + "Run /dbt demo setup from console, or run /dbt demo <dragon|cinema|knot|terrain|text> in-game.");
            return;
        }
        DemoAssetGenerator.ensureStarterAssets(false);
        switch (p) {
            case "dragon":
                player.performCommand("/animmodel " + DemoAssetGenerator.DEMO_DRAGON_MODEL + " 48 wingflap 16 8");
                break;
            case "cinema":
                player.performCommand("/video create demo_" + (System.currentTimeMillis() % 1000) + " "
                        + DemoAssetGenerator.DEMO_SYNTHWAVE_GIF + " 64 36 10 24 -u -a");
                break;
            case "knot":
                player.performCommand("/model " + DemoAssetGenerator.DEMO_KNOT_MODEL + " 42");
                break;
            case "terrain":
                player.performCommand("/heightmap " + DemoAssetGenerator.DEMO_ALPINE_HEIGHTMAP + " 80 36 none -w");
                break;
            case "text":
                player.performCommand("/text3d 14 4 gold_block DRAGON -v");
                break;
            default:
                sender.sendMessage(ChatColor.GOLD + "DBT Built-in Demos: " + ChatColor.AQUA + "/dbt demo <dragon|cinema|knot|terrain|text|setup>");
                break;
        }
    }

    private void handleBukkitAnimSubcommand(CommandSender sender, String[] args) {
        AnimationManager mgr = plugin.getAnimationManager();
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            Map<Integer, ActiveAnimation> map = mgr.getActiveAnimations();
            if (map.isEmpty()) {
                sender.sendMessage(ChatColor.YELLOW + "No active DBT animations running.");
                return;
            }
            sender.sendMessage(ChatColor.GOLD + "Active DBT Animations (" + map.size() + "):");
            for (ActiveAnimation a : map.values()) {
                sender.sendMessage(ChatColor.AQUA + " #" + a.id + " " + ChatColor.WHITE + a.name
                        + ChatColor.GRAY + " [" + a.type + "] Frame " + (a.currentFrame + 1) + "/" + a.frames.size()
                        + " @ " + a.fps + " FPS " + (a.paused ? ChatColor.RED + "(PAUSED)" : ChatColor.GREEN + "(PLAYING)"));
            }
            return;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if ("stop".equals(action) && args.length >= 2) {
            boolean keep = args.length >= 3 && (args[2].equalsIgnoreCase("-k") || args[2].equalsIgnoreCase("-keep"));
            if (args[1].equalsIgnoreCase("all")) {
                int stopped = mgr.stopAllAnimations(keep);
                sender.sendMessage(ChatColor.GREEN + "Stopped " + stopped + " animation(s).");
            } else {
                try {
                    int id = Integer.parseInt(args[1]);
                    if (mgr.stopAnimation(id, keep)) {
                        sender.sendMessage(ChatColor.GREEN + "Stopped animation #" + id + ".");
                    } else {
                        sender.sendMessage(ChatColor.RED + "Animation #" + id + " not found.");
                    }
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Invalid animation ID: " + args[1]);
                }
            }
            return;
        }
        sendHelpTopic(sender, "anim");
    }

    public static void sendFilesSummary(CommandSender sender) {
        sender.sendMessage(ChatColor.DARK_AQUA + "=== " + ChatColor.GOLD + "DragonBuildTools Local Asset Folders" + ChatColor.DARK_AQUA + " ===");
        sendDirFiles(sender, "images", ImagePixelArtConverter.getImagesDirectory());
        sendDirFiles(sender, "models", Model3DVoxelizer.getModelsDirectory());
        sendDirFiles(sender, "animations", AnimatedModelEngine.getAnimationsDirectory());
        sendDirFiles(sender, "videos", VideoAndGifEngine.getVideosDirectory());
        sendDirFiles(sender, "heightmaps", HeightmapTerrainGenerator.getHeightmapsDirectory());
        sendDirFiles(sender, "exports", ObjSelectionExporter.getExportsDirectory());
    }

    private static void sendDirFiles(CommandSender sender, String label, File dir) {
        File[] files = dir.listFiles();
        int count = files != null ? files.length : 0;
        StringBuilder sb = new StringBuilder();
        if (files != null) {
            for (int i = 0; i < Math.min(8, files.length); i++) {
                if (i > 0) sb.append(", ");
                sb.append(files[i].getName());
            }
            if (files.length > 8) {
                sb.append(" ... (+" + (files.length - 8) + " more)");
            }
        }
        sender.sendMessage(ChatColor.AQUA + "  " + label + "/ (" + count + "): "
                + ChatColor.WHITE + (count == 0 ? "(empty)" : sb.toString()));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            for (String t : TOPICS) {
                if (t.startsWith(prefix)) {
                    matches.add(t);
                }
            }
            return matches;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("demo")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            for (String d : DEMO_NAMES) {
                if (d.startsWith(prefix)) {
                    matches.add(d);
                }
            }
            return matches;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("help")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            for (String t : TOPICS) {
                if (t.startsWith(prefix)) {
                    matches.add(t);
                }
            }
            return matches;
        }
        return Collections.emptyList();
    }
}
