package com.dragonbuildtools;

import com.dragonbuildtools.animation.AnimatedModelEngine;
import com.dragonbuildtools.animation.AnimationManager;
import com.dragonbuildtools.animation.VideoAndGifEngine;
import com.dragonbuildtools.command.DbtBukkitCommandHandler;
import com.dragonbuildtools.command.DbtWorldEditCommands;
import com.dragonbuildtools.command.DbtWorldEditCommandsRegistration;
import com.dragonbuildtools.converter.HeightmapTerrainGenerator;
import com.dragonbuildtools.converter.ImagePixelArtConverter;
import com.dragonbuildtools.converter.Model3DVoxelizer;
import com.dragonbuildtools.converter.ObjSelectionExporter;
import com.dragonbuildtools.demo.DemoAssetGenerator;
import com.dragonbuildtools.gui.DbtGuiManager;
import com.dragonbuildtools.util.VanillaBlockAppearanceResolver;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import com.sk89q.worldedit.extension.platform.Capability;
import com.sk89q.worldedit.extension.platform.PlatformCommandManager;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Dragon Build Tools (DBT) standalone Paper/Spigot + FastAsyncWorldEdit plugin.
 * Converts 2D Images, 3D Models, Animated 3D Models, Videos/GIFs, Heightmaps, and 3D Text
 * into Minecraft builds with sub-block accuracy.
 */
public class DragonBuildToolsPlugin extends JavaPlugin {

    private static DragonBuildToolsPlugin instance;
    private AnimationManager animationManager;
    private DbtGuiManager guiManager;

    public static DragonBuildToolsPlugin getInstance() {
        return instance;
    }

    public AnimationManager getAnimationManager() {
        return animationManager;
    }

    public DbtGuiManager getGuiManager() {
        return guiManager;
    }

    @Override
    public void onEnable() {
        instance = this;

        // Initialize asset directories
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        ImagePixelArtConverter.getImagesDirectory();
        Model3DVoxelizer.getModelsDirectory();
        AnimatedModelEngine.getAnimationsDirectory();
        VideoAndGifEngine.getVideosDirectory();
        HeightmapTerrainGenerator.getHeightmapsDirectory();
        ObjSelectionExporter.getExportsDirectory();
        VanillaBlockAppearanceResolver.getResourcePacksDirectory();

        // Seed built-in starter demo assets & warm up block color/texture tables asynchronously
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            DemoAssetGenerator.ensureStarterAssets(false);
            VanillaBlockAppearanceResolver.getEmbeddedTable();
            VanillaBlockAppearanceResolver.discoverAssetSources(null);
        });

        // Initialize live animation manager & interactive Studio GUI
        this.animationManager = new AnimationManager(this);
        this.guiManager = new DbtGuiManager(this);
        Bukkit.getPluginManager().registerEvents(this.guiManager, this);

        // Register /dbt Bukkit command
        PluginCommand dbtCmd = getCommand("dbt");
        if (dbtCmd != null) {
            DbtBukkitCommandHandler handler = new DbtBukkitCommandHandler(this);
            dbtCmd.setExecutor(handler);
            dbtCmd.setTabCompleter(handler);
        }

        // Register WorldEdit / FAWE commands
        registerWorldEditCommands();

        getLogger().info("Dragon Build Tools (DBT) v" + getDescription().getVersion()
                + " enabled! Run /dbt for the interactive GUI or /dbt help.");
    }

    @Override
    public void onDisable() {
        if (animationManager != null) {
            animationManager.shutdown();
        }
        instance = null;
    }

    private void registerWorldEditCommands() {
        try {
            Plugin wePlugin = Bukkit.getPluginManager().getPlugin("WorldEdit");
            if (!(wePlugin instanceof WorldEditPlugin worldEditPlugin)) {
                wePlugin = Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit");
            }
            WorldEdit worldEdit = (wePlugin instanceof WorldEditPlugin wep)
                    ? wep.getWorldEdit()
                    : WorldEdit.getInstance();
            if (worldEdit == null) {
                getLogger().warning("WorldEdit / FastAsyncWorldEdit instance not found during command registration.");
                return;
            }
            PlatformCommandManager pcm = worldEdit.getPlatformManager().getPlatformCommandManager();
            pcm.registration.register(
                    pcm.getCommandManager(),
                    DbtWorldEditCommandsRegistration.builder(),
                    new DbtWorldEditCommands()
            );
            worldEdit.getPlatformManager()
                    .queryCapability(Capability.USER_COMMANDS)
                    .registerCommands(pcm.getCommandManager());
        } catch (Throwable t) {
            getLogger().warning("Failed to register WorldEdit commands for DragonBuildTools: " + t.getMessage());
        }
    }
}
