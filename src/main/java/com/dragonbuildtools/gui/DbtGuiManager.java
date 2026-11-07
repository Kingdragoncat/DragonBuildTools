package com.dragonbuildtools.gui;

import com.dragonbuildtools.DragonBuildToolsPlugin;
import com.dragonbuildtools.animation.AnimatedModelEngine;
import com.dragonbuildtools.animation.AnimationManager;
import com.dragonbuildtools.animation.AnimationManager.ActiveAnimation;
import com.dragonbuildtools.animation.VideoAndGifEngine;
import com.dragonbuildtools.converter.HeightmapTerrainGenerator;
import com.dragonbuildtools.converter.ImagePixelArtConverter;
import com.dragonbuildtools.converter.Model3DVoxelizer;
import com.dragonbuildtools.demo.DemoAssetGenerator;
import com.dragonbuildtools.util.VanillaBlockAppearanceResolver;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Interactive 54-slot Chest Studio GUI for Dragon Build Tools ({@code /dbt} or {@code /dbt gui}).
 */
public final class DbtGuiManager implements Listener {

    public enum MenuView {
        HOME,
        BROWSE_MODELS,
        BROWSE_ANIMATIONS,
        BROWSE_VIDEOS,
        BROWSE_HEIGHTMAPS,
        BROWSE_IMAGES,
        BROWSE_RESOURCEPACKS,
        ACTIVE_ANIMATIONS
    }

    public static final class DbtMenuHolder implements InventoryHolder {
        public final MenuView view;
        public final Map<Integer, String> slotPayloads = new HashMap<>();
        private Inventory inventory;

        public DbtMenuHolder(MenuView view) {
            this.view = view;
        }

        public void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final DragonBuildToolsPlugin plugin;

    public DbtGuiManager(DragonBuildToolsPlugin plugin) {
        this.plugin = plugin;
    }

    public void openHome(Player player) {
        DbtMenuHolder holder = new DbtMenuHolder(MenuView.HOME);
        String ver = plugin != null ? plugin.getDescription().getVersion() : "2.2.0";
        Inventory inv = Bukkit.createInventory(holder, 54, ChatColor.GOLD + "" + ChatColor.BOLD + "Dragon Build Tools v" + ver);
        holder.setInventory(inv);
        fillBorder(inv, Material.BLACK_STAINED_GLASS_PANE);

        // Row 1 header accent
        inv.setItem(4, makeItem(Material.DRAGON_HEAD,
                ChatColor.GOLD + "" + ChatColor.BOLD + "Dragon Build Tools Studio",
                ChatColor.GRAY + "Click a starter demo below to test right away,",
                ChatColor.GRAY + "or browse your local asset folders!"));

        // Row 2: Instant 1-Click Starter Demos
        inv.setItem(10, makeItem(Material.ELYTRA,
                ChatColor.GOLD + "Demo: Wing-Flapping 3D Dragon",
                ChatColor.GRAY + "Spawns a live animated 3D dragon statue",
                ChatColor.GRAY + "using sparse frame-delta block updates.",
                "",
                ChatColor.YELLOW + "Click " + ChatColor.WHITE + "-> //animmodel dragon_statue.obj 48 wingflap"));

        inv.setItem(11, makeItem(Material.PAINTING,
                ChatColor.LIGHT_PURPLE + "Demo: Curved Synthwave Screen",
                ChatColor.GRAY + "Creates & auto-plays an animated curved",
                ChatColor.GRAY + "cinema screen right in front of you.",
                "",
                ChatColor.YELLOW + "Click " + ChatColor.WHITE + "-> //video create demo_cinema ... -u -a"));

        inv.setItem(12, makeItem(Material.AMETHYST_CLUSTER,
                ChatColor.AQUA + "Demo: Sub-Block Crystal Knot",
                ChatColor.GRAY + "Builds a smooth 3D trefoil torus knot",
                ChatColor.GRAY + "contouring curves with stairs & slabs.",
                "",
                ChatColor.YELLOW + "Left-Click " + ChatColor.WHITE + "-> Build in world",
                ChatColor.YELLOW + "Right-Click " + ChatColor.WHITE + "-> Copy to //paste clipboard (-o)"));

        inv.setItem(13, makeItem(Material.GRASS_BLOCK,
                ChatColor.GREEN + "Demo: Alpine Island Terrain",
                ChatColor.GRAY + "Generates a 3D island from a heightmap",
                ChatColor.GRAY + "with water basins, cliffs & snow peaks.",
                "",
                ChatColor.YELLOW + "Left-Click " + ChatColor.WHITE + "-> Build in world",
                ChatColor.YELLOW + "Right-Click " + ChatColor.WHITE + "-> Copy to //paste clipboard (-o)"));

        inv.setItem(14, makeItem(Material.OAK_SIGN,
                ChatColor.GOLD + "Demo: 3D Extruded Beveled Text",
                ChatColor.GRAY + "Extrudes smooth beveled 3D typography",
                ChatColor.GRAY + "with automatic stair & slab curves.",
                "",
                ChatColor.YELLOW + "Left-Click " + ChatColor.WHITE + "-> Build 'DRAGON' in gold",
                ChatColor.YELLOW + "Right-Click " + ChatColor.WHITE + "-> Copy to //paste clipboard (-o)"));

        inv.setItem(16, makeItem(Material.CRAFTING_TABLE,
                ChatColor.AQUA + "Export Selection to .OBJ",
                ChatColor.GRAY + "Exports your WorldEdit selection to a",
                ChatColor.GRAY + "textured .obj + .mtl + atlas 3D mesh!",
                ChatColor.DARK_GRAY + "Supports packs in resourcepacks/",
                "",
                ChatColor.YELLOW + "Click " + ChatColor.WHITE + "-> //exportobj my_build -m"));

        // Row 3: Sculpting & Color Brushes
        inv.setItem(21, makeItem(Material.MAGMA_CREAM,
                ChatColor.LIGHT_PURPLE + "Brush: Gaussian Color Blur",
                ChatColor.GRAY + "3D Gaussian blur across block colors.",
                ChatColor.GRAY + "Sub-blocks blur color while staying",
                ChatColor.GRAY + "the exact stair/slab/wall they were!",
                "",
                ChatColor.YELLOW + "Left-Click " + ChatColor.WHITE + "-> //gblur 5 1.6 1.0",
                ChatColor.YELLOW + "Right-Click " + ChatColor.WHITE + "-> Local palette only (-p)"));

        inv.setItem(23, makeItem(Material.SLIME_BALL,
                ChatColor.GREEN + "Brush: Sub-Block BlendBall",
                ChatColor.GRAY + "2x2x2 sub-voxel BlendBall smoothing!",
                ChatColor.GRAY + "Chamfers steps, corners, bumps & holes",
                ChatColor.GRAY + "into directional stairs & slabs.",
                "",
                ChatColor.YELLOW + "Left-Click " + ChatColor.WHITE + "-> //subblend 5 1",
                ChatColor.YELLOW + "Right-Click " + ChatColor.WHITE + "-> Geometry only (-g)"));

        // Row 4: Local Folder Browsers
        inv.setItem(28, makeItem(Material.ARMOR_STAND,
                ChatColor.AQUA + "Browse 3D Models",
                ChatColor.DARK_GRAY + "plugins/DragonBuildTools/models/",
                ChatColor.GRAY + "Files: " + ChatColor.WHITE + countFiles(Model3DVoxelizer.getModelsDirectory(), ".obj", ".glb", ".gltf", ".stl", ".ply", ".vox", ".off"),
                "",
                ChatColor.YELLOW + "Click to browse & spawn models"));

        inv.setItem(29, makeItem(Material.BLAZE_POWDER,
                ChatColor.GOLD + "Browse 3D Animations",
                ChatColor.DARK_GRAY + "plugins/DragonBuildTools/animations/",
                ChatColor.GRAY + "Models + sequences available",
                "",
                ChatColor.YELLOW + "Click to launch animated 3D models"));

        inv.setItem(30, makeItem(Material.ENDER_EYE,
                ChatColor.LIGHT_PURPLE + "Browse Videos & GIFs",
                ChatColor.DARK_GRAY + "plugins/DragonBuildTools/videos/",
                ChatColor.GRAY + "Files: " + ChatColor.WHITE + countFiles(VideoAndGifEngine.getVideosDirectory(), ".gif", ".mp4", ".webm", ".mov", ".png"),
                "",
                ChatColor.YELLOW + "Click to create named video screens"));

        inv.setItem(31, makeItem(Material.FILLED_MAP,
                ChatColor.GREEN + "Browse Heightmaps",
                ChatColor.DARK_GRAY + "plugins/DragonBuildTools/heightmaps/",
                ChatColor.GRAY + "Files: " + ChatColor.WHITE + countFiles(HeightmapTerrainGenerator.getHeightmapsDirectory(), ".png", ".jpg", ".jpeg", ".bmp", ".tif"),
                "",
                ChatColor.YELLOW + "Click to generate 3D terrain"));

        inv.setItem(32, makeItem(Material.ITEM_FRAME,
                ChatColor.YELLOW + "Browse 2D Pixel Art Images",
                ChatColor.DARK_GRAY + "plugins/DragonBuildTools/images/",
                ChatColor.GRAY + "Files: " + ChatColor.WHITE + countFiles(ImagePixelArtConverter.getImagesDirectory(), ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".bmp"),
                "",
                ChatColor.YELLOW + "Click to build wall or floor art"));

        inv.setItem(33, makeItem(Material.CHEST,
                ChatColor.AQUA + "Resource Packs (.OBJ Export)",
                ChatColor.DARK_GRAY + "plugins/DragonBuildTools/resourcepacks/",
                ChatColor.GRAY + "Packs: " + ChatColor.WHITE + collectResourcePacks().size(),
                ChatColor.GRAY + "Drop .zip or folder packs here for //exportobj!",
                "",
                ChatColor.YELLOW + "Click to export with a specific pack"));

        int activeCount = plugin.getAnimationManager() != null ? plugin.getAnimationManager().getActiveAnimations().size() : 0;
        inv.setItem(34, makeItem(Material.REDSTONE_TORCH,
                ChatColor.RED + "" + ChatColor.BOLD + "Live Animation Controller",
                ChatColor.GRAY + "Active animations: " + ChatColor.GREEN + activeCount,
                ChatColor.GRAY + "Pause, resume, adjust FPS, or restore terrain.",
                "",
                ChatColor.YELLOW + "Click to manage running animations"));

        // Bottom utility bar
        inv.setItem(47, makeItem(Material.BOOK,
                ChatColor.AQUA + "Print Command Reference",
                ChatColor.GRAY + "Sends full command & flag list to chat."));

        inv.setItem(49, makeItem(Material.BARRIER,
                ChatColor.RED + "Close Menu"));

        inv.setItem(51, makeItem(Material.NETHER_STAR,
                ChatColor.GOLD + "Re-Generate Starter Demo Files",
                ChatColor.GRAY + "Restores any deleted starter demo files",
                ChatColor.GRAY + "inside plugins/DragonBuildTools/."));

        player.openInventory(inv);
    }

    public void openBrowser(Player player, MenuView view) {
        File folder;
        String title;
        Material iconMat;
        String[] exts;
        switch (view) {
            case BROWSE_MODELS:
                folder = Model3DVoxelizer.getModelsDirectory();
                title = "DBT: 3D Models";
                iconMat = Material.STONE_BRICK_STAIRS;
                exts = new String[]{".obj", ".glb", ".gltf", ".stl", ".ply", ".vox", ".off"};
                break;
            case BROWSE_ANIMATIONS:
                folder = Model3DVoxelizer.getModelsDirectory();
                title = "DBT: Animate a 3D Model";
                iconMat = Material.ELYTRA;
                exts = new String[]{".obj", ".glb", ".gltf", ".stl", ".ply", ".vox", ".off"};
                break;
            case BROWSE_VIDEOS:
                folder = VideoAndGifEngine.getVideosDirectory();
                title = "DBT: Videos & GIFs";
                iconMat = Material.PAINTING;
                exts = new String[]{".gif", ".mp4", ".webm", ".mov", ".avi"};
                break;
            case BROWSE_HEIGHTMAPS:
                folder = HeightmapTerrainGenerator.getHeightmapsDirectory();
                title = "DBT: Heightmaps";
                iconMat = Material.GRASS_BLOCK;
                exts = new String[]{".png", ".jpg", ".jpeg", ".webp", ".bmp", ".tif", ".tiff"};
                break;
            case BROWSE_RESOURCEPACKS:
                folder = VanillaBlockAppearanceResolver.getResourcePacksDirectory();
                title = "DBT: Resource Packs (.OBJ)";
                iconMat = Material.CHEST;
                exts = new String[]{".zip", ".jar"};
                break;
            case BROWSE_IMAGES:
            default:
                folder = ImagePixelArtConverter.getImagesDirectory();
                title = "DBT: 2D Pixel Art";
                iconMat = Material.GLOW_ITEM_FRAME;
                exts = new String[]{".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".svgz", ".bmp"};
                break;
        }

        DbtMenuHolder holder = new DbtMenuHolder(view);
        Inventory inv = Bukkit.createInventory(holder, 54, ChatColor.DARK_AQUA + "" + ChatColor.BOLD + title);
        holder.setInventory(inv);
        fillBorder(inv, Material.GRAY_STAINED_GLASS_PANE);

        List<File> files = view == MenuView.BROWSE_RESOURCEPACKS
                ? collectResourcePacks()
                : collectMatchingFiles(folder, exts);
        if (view == MenuView.BROWSE_ANIMATIONS) {
            files.addAll(collectMatchingFiles(AnimatedModelEngine.getAnimationsDirectory(), exts));
        }

        int[] contentSlots = getInteriorSlots();
        for (int i = 0; i < Math.min(files.size(), contentSlots.length); i++) {
            File f = files.get(i);
            int slot = contentSlots[i];
            holder.slotPayloads.put(slot, f.getName());

            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.DARK_GRAY + (f.isDirectory() ? "Unzipped Pack Folder" : String.format("%.1f KB", f.length() / 1024.0)));
            lore.add("");
            if (view == MenuView.BROWSE_VIDEOS) {
                lore.add(ChatColor.YELLOW + "Left-Click " + ChatColor.WHITE + "-> Flat Wall Screen (auto-play)");
                lore.add(ChatColor.YELLOW + "Right-Click " + ChatColor.WHITE + "-> Curved Screen (-u -a)");
            } else if (view == MenuView.BROWSE_ANIMATIONS) {
                lore.add(ChatColor.YELLOW + "Left-Click " + ChatColor.WHITE + "-> Animate (wingflap)");
                lore.add(ChatColor.YELLOW + "Right-Click " + ChatColor.WHITE + "-> Animate (spin turntable)");
            } else if (view == MenuView.BROWSE_RESOURCEPACKS) {
                lore.add(ChatColor.YELLOW + "Left-Click " + ChatColor.WHITE + "-> Export Selection with this pack");
                lore.add(ChatColor.YELLOW + "Right-Click " + ChatColor.WHITE + "-> Export Clipboard (-c) with this pack");
            } else {
                lore.add(ChatColor.YELLOW + "Left-Click " + ChatColor.WHITE + "-> Build in front of you");
                lore.add(ChatColor.YELLOW + "Right-Click " + ChatColor.WHITE + "-> Copy to //paste clipboard (-o)");
            }
            inv.setItem(slot, makeItem(iconMat, ChatColor.AQUA + f.getName(), lore.toArray(new String[0])));
        }

        inv.setItem(45, makeItem(Material.ARROW, ChatColor.YELLOW + "<- Back to DBT Studio"));
        inv.setItem(49, makeItem(Material.BARRIER, ChatColor.RED + "Close"));
        player.openInventory(inv);
    }

    public void openActiveAnimations(Player player) {
        DbtMenuHolder holder = new DbtMenuHolder(MenuView.ACTIVE_ANIMATIONS);
        Inventory inv = Bukkit.createInventory(holder, 54, ChatColor.RED + "" + ChatColor.BOLD + "DBT: Active Animations");
        holder.setInventory(inv);
        fillBorder(inv, Material.BLACK_STAINED_GLASS_PANE);

        AnimationManager mgr = plugin.getAnimationManager();
        if (mgr != null) {
            List<ActiveAnimation> list = new ArrayList<>(mgr.getActiveAnimations().values());
            list.sort(Comparator.comparingInt(a -> a.id));
            int[] slots = getInteriorSlots();
            for (int i = 0; i < Math.min(list.size(), slots.length); i++) {
                ActiveAnimation a = list.get(i);
                int slot = slots[i];
                holder.slotPayloads.put(slot, a.name);
                Material mat = a.paused ? Material.ORANGE_CONCRETE : Material.LIME_CONCRETE;
                inv.setItem(slot, makeItem(mat,
                        (a.paused ? ChatColor.YELLOW + "[PAUSED] " : ChatColor.GREEN + "[PLAYING] ") + ChatColor.WHITE + a.name,
                        ChatColor.GRAY + "ID: " + ChatColor.AQUA + "#" + a.id + ChatColor.GRAY + " (" + a.type + ")",
                        ChatColor.GRAY + "Source: " + ChatColor.WHITE + a.sourceFile,
                        ChatColor.GRAY + "Frame: " + ChatColor.WHITE + (Math.max(0, a.currentFrame) + 1) + "/" + a.frames.size()
                                + ChatColor.GRAY + " @ " + ChatColor.GOLD + a.fps + " FPS",
                        "",
                        ChatColor.YELLOW + "Left-Click " + ChatColor.WHITE + "-> " + (a.paused ? "Resume" : "Pause"),
                        ChatColor.YELLOW + "Right-Click " + ChatColor.WHITE + "-> Cycle FPS (4/8/12/16/20)",
                        ChatColor.RED + "Shift+Left " + ChatColor.WHITE + "-> Stop & Restore Terrain",
                        ChatColor.GOLD + "Shift+Right " + ChatColor.WHITE + "-> Stop & Keep Frame Blocks (-k)"
                ));
            }
        }

        inv.setItem(45, makeItem(Material.ARROW, ChatColor.YELLOW + "<- Back to DBT Studio"));
        inv.setItem(49, makeItem(Material.BARRIER, ChatColor.RED + "Close"));
        inv.setItem(53, makeItem(Material.TNT,
                ChatColor.RED + "" + ChatColor.BOLD + "Stop All Animations",
                ChatColor.GRAY + "Stops every active animation and restores",
                ChatColor.GRAY + "all original terrain blocks."));
        player.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof DbtMenuHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() != event.getInventory()) {
            return;
        }
        int slot = event.getSlot();
        ClickType click = event.getClick();

        if (holder.view == MenuView.HOME) {
            handleHomeClick(player, slot, click);
            return;
        }

        if (slot == 45) {
            openHome(player);
            return;
        }
        if (slot == 49) {
            player.closeInventory();
            return;
        }

        if (holder.view == MenuView.ACTIVE_ANIMATIONS) {
            handleActiveAnimClick(player, holder, slot, click);
            return;
        }

        String fileName = holder.slotPayloads.get(slot);
        if (fileName == null) {
            return;
        }
        boolean right = click.isRightClick();
        player.closeInventory();

        switch (holder.view) {
            case BROWSE_MODELS:
                player.performCommand("/model " + fileName + " 48" + (right ? " -o" : ""));
                break;
            case BROWSE_ANIMATIONS:
                player.performCommand("/animmodel " + fileName + " 44 " + (right ? "spin" : "wingflap") + " 16 8");
                break;
            case BROWSE_VIDEOS: {
                String safeId = "screen_" + (System.currentTimeMillis() % 10000);
                player.performCommand("/video create " + safeId + " " + fileName + " 64 0 10 60 -a" + (right ? " -u" : ""));
                break;
            }
            case BROWSE_HEIGHTMAPS:
                player.performCommand("/heightmap " + fileName + " 80 36 none -w" + (right ? " -o" : ""));
                break;
            case BROWSE_IMAGES:
                player.performCommand("/pixelart " + fileName + " 64 0" + (right ? " -o" : ""));
                break;
            case BROWSE_RESOURCEPACKS: {
                String safePack = fileName.replace(' ', '_');
                player.performCommand("/exportobj build_" + (System.currentTimeMillis() % 10000) + " -p " + safePack + " -m" + (right ? " -c" : ""));
                break;
            }
            default:
                break;
        }
    }

    private void handleHomeClick(Player player, int slot, ClickType click) {
        boolean right = click.isRightClick();
        switch (slot) {
            case 10:
                DemoAssetGenerator.ensureStarterAssets(false);
                player.closeInventory();
                player.performCommand("/animmodel " + DemoAssetGenerator.DEMO_DRAGON_MODEL + " 48 wingflap 16 8");
                break;
            case 11:
                DemoAssetGenerator.ensureStarterAssets(false);
                player.closeInventory();
                player.performCommand("/video create demo_" + (System.currentTimeMillis() % 1000) + " "
                        + DemoAssetGenerator.DEMO_SYNTHWAVE_GIF + " 64 36 10 24 -u -a");
                break;
            case 12:
                DemoAssetGenerator.ensureStarterAssets(false);
                player.closeInventory();
                player.performCommand("/model " + DemoAssetGenerator.DEMO_KNOT_MODEL + " 42" + (right ? " -o" : ""));
                break;
            case 13:
                DemoAssetGenerator.ensureStarterAssets(false);
                player.closeInventory();
                player.performCommand("/heightmap " + DemoAssetGenerator.DEMO_ALPINE_HEIGHTMAP + " 80 36 none -w" + (right ? " -o" : ""));
                break;
            case 14:
                player.closeInventory();
                player.performCommand("/text3d 14 4 gold_block DRAGON -v" + (right ? " -o" : ""));
                break;
            case 16:
                player.closeInventory();
                player.performCommand("/exportobj build_" + (System.currentTimeMillis() % 10000) + " -m");
                break;
            case 21:
                player.closeInventory();
                ensureHeldItemForBrush(player, ChatColor.LIGHT_PURPLE + "DBT Gaussian Blur Brush");
                player.performCommand("/gblur 5 1.6 1.0" + (right ? " -p" : ""));
                break;
            case 23:
                player.closeInventory();
                ensureHeldItemForBrush(player, ChatColor.GREEN + "DBT Sub-Block BlendBall");
                player.performCommand("/subblend 5 1" + (right ? " -g" : ""));
                break;
            case 28:
                openBrowser(player, MenuView.BROWSE_MODELS);
                break;
            case 29:
                openBrowser(player, MenuView.BROWSE_ANIMATIONS);
                break;
            case 30:
                openBrowser(player, MenuView.BROWSE_VIDEOS);
                break;
            case 31:
                openBrowser(player, MenuView.BROWSE_HEIGHTMAPS);
                break;
            case 32:
                openBrowser(player, MenuView.BROWSE_IMAGES);
                break;
            case 33:
                openBrowser(player, MenuView.BROWSE_RESOURCEPACKS);
                break;
            case 34:
                openActiveAnimations(player);
                break;
            case 47:
                player.closeInventory();
                player.performCommand("dbt help");
                break;
            case 49:
                player.closeInventory();
                break;
            case 51: {
                int count = DemoAssetGenerator.ensureStarterAssets(true);
                player.sendMessage(ChatColor.GREEN + "[DBT] Generated " + count + " starter demo files in plugins/DragonBuildTools/!");
                openHome(player);
                break;
            }
            default:
                break;
        }
    }

    private void handleActiveAnimClick(Player player, DbtMenuHolder holder, int slot, ClickType click) {
        AnimationManager mgr = plugin.getAnimationManager();
        if (mgr == null) {
            return;
        }
        if (slot == 53) {
            int stopped = mgr.stopAllAnimations(false);
            player.sendMessage(ChatColor.GREEN + "[DBT] Stopped " + stopped + " animation(s) and restored terrain.");
            openActiveAnimations(player);
            return;
        }
        String name = holder.slotPayloads.get(slot);
        if (name == null) {
            return;
        }
        ActiveAnimation anim = mgr.getAnimationByNameOrId(name);
        if (anim == null) {
            openActiveAnimations(player);
            return;
        }
        if (click == ClickType.SHIFT_LEFT) {
            mgr.removeAnimationByNameOrId(anim.name, false);
            player.sendMessage(ChatColor.YELLOW + "[DBT] Stopped '" + anim.name + "' and restored terrain.");
        } else if (click == ClickType.SHIFT_RIGHT) {
            mgr.removeAnimationByNameOrId(anim.name, true);
            player.sendMessage(ChatColor.GOLD + "[DBT] Stopped '" + anim.name + "' and kept current blocks.");
        } else if (click.isRightClick()) {
            double next = anim.fps < 8 ? 8 : (anim.fps < 12 ? 12 : (anim.fps < 16 ? 16 : (anim.fps < 20 ? 20 : 4)));
            anim.fps = next;
        } else {
            if (anim.paused) {
                mgr.ensureVisible(anim);
                anim.paused = false;
            } else {
                anim.paused = true;
            }
        }
        openActiveAnimations(player);
    }

    private static void fillBorder(Inventory inv, Material paneMat) {
        ItemStack pane = makeItem(paneMat, " ");
        int size = inv.getSize();
        for (int i = 0; i < size; i++) {
            int row = i / 9;
            int col = i % 9;
            if (row == 0 || row == (size / 9) - 1 || col == 0 || col == 8) {
                inv.setItem(i, pane);
            }
        }
    }

    private static int[] getInteriorSlots() {
        int[] slots = new int[28];
        int idx = 0;
        for (int r = 1; r <= 4; r++) {
            for (int c = 1; c <= 7; c++) {
                slots[idx++] = r * 9 + c;
            }
        }
        return slots;
    }

    private static ItemStack makeItem(Material mat, String title, String... loreLines) {
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(title);
            if (loreLines != null && loreLines.length > 0) {
                meta.setLore(Arrays.asList(loreLines));
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static void ensureHeldItemForBrush(Player player, String defaultToolTitle) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) {
            player.getInventory().setItemInMainHand(makeItem(Material.FEATHER, defaultToolTitle));
        }
    }

    private static int countFiles(File dir, String... exts) {
        return collectMatchingFiles(dir, exts).size();
    }

    private static List<File> collectMatchingFiles(File dir, String... exts) {
        List<File> out = new ArrayList<>();
        if (dir == null || !dir.exists()) {
            return out;
        }
        File[] list = dir.listFiles();
        if (list == null) {
            return out;
        }
        for (File f : list) {
            if (!f.isFile()) {
                continue;
            }
            String lower = f.getName().toLowerCase();
            for (String ext : exts) {
                if (lower.endsWith(ext)) {
                    out.add(f);
                    break;
                }
            }
        }
        out.sort(Comparator.comparing(File::getName));
        return out;
    }

    private static List<File> collectResourcePacks() {
        List<File> out = new ArrayList<>();
        File dir = VanillaBlockAppearanceResolver.getResourcePacksDirectory();
        if (dir == null || !dir.exists()) {
            return out;
        }
        File[] list = dir.listFiles();
        if (list == null) {
            return out;
        }
        for (File f : list) {
            if (f.isDirectory()) {
                out.add(f);
            } else if (f.isFile()) {
                String lower = f.getName().toLowerCase();
                if (lower.endsWith(".zip") || lower.endsWith(".jar")) {
                    out.add(f);
                }
            }
        }
        out.sort(Comparator.comparing(File::getName));
        return out;
    }
}
