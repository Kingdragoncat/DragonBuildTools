package com.dragonbuildtools.command;

import com.google.common.collect.ImmutableList;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.command.util.CommandPermissionsConditionGenerator;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.util.formatting.text.TextComponent;
import com.sk89q.worldedit.util.formatting.text.TranslatableComponent;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import org.enginehub.piston.Command;
import org.enginehub.piston.CommandManager;
import org.enginehub.piston.CommandParameters;
import org.enginehub.piston.gen.CommandCallListener;
import org.enginehub.piston.gen.CommandRegistration;
import org.enginehub.piston.inject.Key;
import org.enginehub.piston.internal.RegistrationUtil;
import org.enginehub.piston.part.ArgAcceptingCommandFlag;
import org.enginehub.piston.part.CommandArgument;
import org.enginehub.piston.part.CommandParts;
import org.enginehub.piston.part.NoArgCommandFlag;

/**
 * Collision-safe Piston command registration for {@link DbtWorldEditCommands}.
 */
public final class DbtWorldEditCommandsRegistration implements CommandRegistration<DbtWorldEditCommands>, CommandPermissionsConditionGenerator.Registration {

    private static final Key<String> STRING_KEY = Key.of(String.class);
    private static final Key<Integer> INTEGER_KEY = Key.of(Integer.class);
    private static final Key<Double> DOUBLE_KEY = Key.of(Double.class);
    private static final Key<Player> PLAYER_KEY = Key.of(Player.class);
    private static final Key<LocalSession> LOCAL_SESSION_KEY = Key.of(LocalSession.class);
    private static final Key<EditSession> EDIT_SESSION_KEY = Key.of(EditSession.class);

    private static final Class<?>[] PIXEL_ART_PARAM_TYPES = new Class<?>[]{
            Player.class, LocalSession.class, EditSession.class, String.class,
            int.class, int.class, boolean.class, boolean.class, boolean.class, boolean.class, boolean.class
    };

    private static final Class<?>[] MODEL_PARAM_TYPES = new Class<?>[]{
            Player.class, LocalSession.class, EditSession.class, String.class,
            int.class, String.class, double.class, double.class, double.class,
            boolean.class, boolean.class, boolean.class, boolean.class, boolean.class, boolean.class, boolean.class
    };

    private static final Class<?>[] ANIM_MODEL_PARAM_TYPES = new Class<?>[]{
            Player.class, LocalSession.class, String.class,
            int.class, String.class, int.class, double.class, String.class,
            boolean.class, boolean.class, boolean.class, boolean.class
    };

    private static final Class<?>[] VIDEO_PARAM_TYPES = new Class<?>[]{
            Player.class, LocalSession.class,
            String.class, String.class, String.class,
            int.class, int.class, double.class, int.class,
            boolean.class, boolean.class, boolean.class, boolean.class, boolean.class, boolean.class,
            boolean.class, boolean.class, boolean.class
    };

    private static final Class<?>[] ANIM_CONTROL_PARAM_TYPES = new Class<?>[]{
            Player.class, String.class, String.class, double.class, boolean.class
    };

    private static final Class<?>[] HEIGHTMAP_PARAM_TYPES = new Class<?>[]{
            Player.class, LocalSession.class, EditSession.class, String.class,
            int.class, int.class, String.class, String.class,
            boolean.class, boolean.class, boolean.class, boolean.class, boolean.class
    };

    private static final Class<?>[] TEXT3D_PARAM_TYPES = new Class<?>[]{
            Player.class, LocalSession.class, EditSession.class,
            int.class, int.class, String.class, String.class,
            boolean.class, boolean.class, boolean.class, boolean.class
    };

    private static final Class<?>[] EXPORT_OBJ_PARAM_TYPES = new Class<?>[]{
            Player.class, LocalSession.class, EditSession.class,
            String.class, boolean.class, boolean.class
    };

    private static final Class<?>[] NEW_WFC_PARAM_TYPES = new Class<?>[]{
            Player.class, LocalSession.class, EditSession.class,
            int.class, int.class, int.class, double.class, double.class,
            String.class, boolean.class, boolean.class, boolean.class, int.class, boolean.class
    };

    private CommandManager manager;
    private DbtWorldEditCommands container;
    private CommandPermissionsConditionGenerator permissionsConditionGenerator;
    private ImmutableList<CommandCallListener> callListeners = ImmutableList.of();

    private final CommandArgument sourceArg = arg("source", "File, folder, or URL", null, STRING_KEY);
    private final CommandArgument widthArg = arg("width", "Width in blocks", "64", INTEGER_KEY);
    private final CommandArgument heightArg = arg("height", "Height in blocks (0 = auto aspect)", "0", INTEGER_KEY);
    private final CommandArgument size48Arg = arg("size", "Target size in blocks", "48", INTEGER_KEY);
    private final CommandArgument size40Arg = arg("size", "Target size in blocks", "40", INTEGER_KEY);
    private final CommandArgument size80Arg = arg("size", "Target size in blocks", "80", INTEGER_KEY);
    private final CommandArgument maxHeightArg = arg("maxHeight", "Maximum elevation in blocks", "40", INTEGER_KEY);
    private final CommandArgument patternOptArg = arg("block", "Optional block/pattern override", "", STRING_KEY);
    private final CommandArgument rxArg = arg("rx", "X-axis rotation in degrees", "0", DOUBLE_KEY);
    private final CommandArgument ryArg = arg("ry", "Y-axis rotation in degrees", "0", DOUBLE_KEY);
    private final CommandArgument rzArg = arg("rz", "Z-axis rotation in degrees", "0", DOUBLE_KEY);
    private final CommandArgument modeArg = arg("mode", "Motion mode (wingflap, spin, hover, breathe, swing, auto)", "wingflap", STRING_KEY);
    private final CommandArgument frames16Arg = arg("frames", "Number of animation frames", "16", INTEGER_KEY);
    private final CommandArgument maxFrames60Arg = arg("maxFrames", "Max frames to decode", "60", INTEGER_KEY);
    private final CommandArgument fps8Arg = arg("fps", "Playback speed in FPS", "8", DOUBLE_KEY);
    private final CommandArgument fps10Arg = arg("fps", "Playback speed in FPS", "10", DOUBLE_KEY);
    private final CommandArgument colormapArg = arg("colormap", "Optional satellite colormap or 'none'", "none", STRING_KEY);

    // Video subcommand arguments
    private final CommandArgument videoSubActionArg = arg("action", "Subcommand: create, start, pause, stop, restart, seek, fps, loop, move, clone, remove, info, list", "list", STRING_KEY);
    private final CommandArgument videoNameArg = arg("video_name", "Unique video instance name (or 'all')", "", STRING_KEY);
    private final CommandArgument videoFileOrArg = arg("file_or_value", "Video/GIF file/URL (for create), new name (for clone), or frame/fps/loop value", "", STRING_KEY);

    private final CommandArgument animActionArg = arg("action", "Action: list, start, pause, resume, speed, frame, stop", "list", STRING_KEY);
    private final CommandArgument animTargetArg = arg("target", "Animation name, ID, or 'all'", "all", STRING_KEY);
    private final CommandArgument animValueArg = arg("value", "FPS or frame index", "0", DOUBLE_KEY);

    private final CommandArgument textHeightArg = arg("height", "Text height in blocks", null, INTEGER_KEY);
    private final CommandArgument textDepthArg = arg("depth", "Extrusion depth in blocks", null, INTEGER_KEY);
    private final CommandArgument textBlockArg = arg("block", "Block or pattern", null, STRING_KEY);
    private final CommandArgument textStringArg = arg("text", "Text to render (use _ for spaces)", null, STRING_KEY);

    // WFC arguments & flags
    private final CommandArgument wfcGridSizeArg = arg("grid_size", "Size of each grid cell on X and Z", null, INTEGER_KEY);
    private final CommandArgument wfcPieceSizeArg = arg("wfc_size", "Size of each 3D WFC piece", "1", INTEGER_KEY);
    private final CommandArgument wfcVariationsArg = arg("variation_count", "Number of variation cells at start of grid", "2", INTEGER_KEY);
    private final CommandArgument wfcAdherenceArg = arg("adherence", "Adherence factor (default 1.0)", "1", DOUBLE_KEY);
    private final CommandArgument wfcTimeoutArg = arg("timeout", "Max seconds before stopping (default 5.0)", "5", DOUBLE_KEY);

    private final ArgAcceptingCommandFlag wfcNeglectFlag = valueFlag('n', "mask", "structure_void",
            "Mask of blocks in variations not placeable in generation; neighbors act like variation edges (default: structure_void)", STRING_KEY);
    private final NoArgCommandFlag wfcClipboardFlag = flag('c', "Use clipboard as the variations' source");
    private final NoArgCommandFlag wfcBaseLayerFlag = flag('b', "Use existing first layer in selection as base; only overwrite non-base blocks with non-air");
    private final NoArgCommandFlag wfcContinuousFlag = flag('j', "Make the whole selection one continuous WFC evaluation area (variations still use grid)");
    private final ArgAcceptingCommandFlag wfcLazyFlag = valueFlag('l', "int", "0",
            "Lazy neighbor tolerance: allow placing a block even if up to <int> neighbors lack it in their adjacency data", INTEGER_KEY);
    private final NoArgCommandFlag wfcOuterBoundaryFlag = flag('g', "Make every outer wall and the ceiling layer act like the bottom layer");

    private final NoArgCommandFlag flatFlag = flag('f', "Flat floor mode");
    private final NoArgCommandFlag noDitherFlag = flag('d', "Disable color dithering");
    private final NoArgCommandFlag noGlassFlag = flag('g', "Disable stained glass");
    private final NoArgCommandFlag centerFlag = flag('c', "Center at player position");
    private final NoArgCommandFlag solidFlag = flag('s', "Solid interior flood-fill");
    private final NoArgCommandFlag shadeFlag = flag('l', "Force directional shading");
    private final NoArgCommandFlag noSubFlag = flag('b', "Disable sub-blocks (full cubes only)");
    private final NoArgCommandFlag noLoopFlag = flag('n', "Play once without looping");
    private final NoArgCommandFlag curveFlag = flag('u', "Curved 3D cinema screen");
    private final NoArgCommandFlag autoStartFlag = flag('a', "Auto-start playback immediately upon creating or cloning");
    private final NoArgCommandFlag restoreOnStopFlag = flag('r', "Hide video screen and restore original terrain when stopping");
    private final NoArgCommandFlag keepFlag = flag('k', "Keep current frame blocks when removing/stopping");
    private final NoArgCommandFlag waterFlag = flag('w', "Fill basins with water at sea level");
    private final NoArgCommandFlag hollowFlag = flag('h', "Hollow terrain shell");
    private final NoArgCommandFlag bevelFlag = flag('v', "Bevel front and back edges");
    private final NoArgCommandFlag toClipboardFlag = flag('o', "Copy generated build to your //paste clipboard instead of placing");
    private final NoArgCommandFlag copyToModelsFlag = flag('m', "Also copy exported .obj + .mtl into plugins/DragonBuildTools/models/");
    private final CommandArgument exportNameArg = arg("name", "Output .obj filename", "exported_build", STRING_KEY);

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static CommandArgument arg(String name, String desc, String def, Key<?> key) {
        CommandArgument.Builder builder = CommandParts.arg(TranslatableComponent.of(name), TextComponent.of(desc));
        if (def != null) {
            builder.defaultsTo((Iterable) ImmutableList.of(def));
        } else {
            builder.defaultsTo((Iterable) ImmutableList.of());
        }
        return builder.ofTypes((Collection) ImmutableList.of(key)).build();
    }

    private static NoArgCommandFlag flag(char c, String desc) {
        return CommandParts.flag(c, TextComponent.of(desc)).build();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgAcceptingCommandFlag valueFlag(char c, String argName, String def, String desc, Key<?> key) {
        return CommandParts.flag(c, TextComponent.of(desc))
                .withRequiredArg()
                .argNamed(TranslatableComponent.of(argName))
                .defaultsTo((Iterable) ImmutableList.of(def))
                .ofTypes((Collection) ImmutableList.of(key))
                .build();
    }

    private DbtWorldEditCommandsRegistration() {
    }

    public static DbtWorldEditCommandsRegistration builder() {
        return new DbtWorldEditCommandsRegistration();
    }

    @Override
    public DbtWorldEditCommandsRegistration commandManager(CommandManager manager) {
        this.manager = manager;
        return this;
    }

    @Override
    public DbtWorldEditCommandsRegistration containerInstance(DbtWorldEditCommands container) {
        this.container = container;
        return this;
    }

    @Override
    public DbtWorldEditCommandsRegistration commandPermissionsConditionGenerator(CommandPermissionsConditionGenerator generator) {
        this.permissionsConditionGenerator = generator;
        return this;
    }

    @Override
    public DbtWorldEditCommandsRegistration listeners(Collection<CommandCallListener> listeners) {
        this.callListeners = ImmutableList.copyOf(listeners);
        return this;
    }

    @Override
    public void build() {
        registerCollisionSafe("dbtpixelart", ImmutableList.of("pixelart", "/pixelart", "dbtimage", "/dbtimage", "dbtimg", "/dbtimg"),
                "Convert a 2D image or URL into Minecraft pixel art",
                ImmutableList.of(flatFlag, noDitherFlag, noGlassFlag, centerFlag, toClipboardFlag, sourceArg, widthArg, heightArg),
                this::invokePixelArt,
                getMethod("pixelArtCommand", PIXEL_ART_PARAM_TYPES));

        registerCollisionSafe("dbtmodel", ImmutableList.of("model", "/model", "3dmodel", "/3dmodel", "voxel", "/voxel", "/dbtmodel"),
                "Convert a 3D model (.obj, .stl, .ply, .glb, .gltf, .vox, .off) into a 3D Minecraft build with sub-blocks",
                ImmutableList.of(solidFlag, noDitherFlag, noGlassFlag, shadeFlag, noSubFlag, centerFlag, toClipboardFlag,
                        sourceArg, size48Arg, patternOptArg, rxArg, ryArg, rzArg),
                this::invokeModel,
                getMethod("modelCommand", MODEL_PARAM_TYPES));

        registerCollisionSafe("animmodel", ImmutableList.of("/animmodel", "3danim", "/3danim", "animate3d", "/animate3d"),
                "Animate a 3D model in real time using sparse frame-delta block swapping",
                ImmutableList.of(solidFlag, noSubFlag, noLoopFlag, centerFlag,
                        sourceArg, size40Arg, modeArg, frames16Arg, fps8Arg, patternOptArg),
                this::invokeAnimModel,
                getMethod("animModelCommand", ANIM_MODEL_PARAM_TYPES));

        registerCollisionSafe("video", ImmutableList.of("/video", "gif", "/gif", "screen", "/screen", "dbtvideo", "/dbtvideo"),
                "Instantiate and control named video/GIF screens (create, start, pause, stop, restart, seek, fps, loop, move, clone, remove, info, list)",
                ImmutableList.of(flatFlag, curveFlag, noDitherFlag, noGlassFlag, noLoopFlag, centerFlag,
                        autoStartFlag, restoreOnStopFlag, keepFlag,
                        videoSubActionArg, videoNameArg, videoFileOrArg, widthArg, heightArg, fps10Arg, maxFrames60Arg),
                this::invokeVideo,
                getMethod("videoCommand", VIDEO_PARAM_TYPES));

        registerCollisionSafe("anim", ImmutableList.of("/anim", "dbtanim", "/dbtanim"),
                "Control active 3D model & video animations (list, start, pause, resume, speed, frame, stop)",
                ImmutableList.of(keepFlag, animActionArg, animTargetArg, animValueArg),
                this::invokeAnimControl,
                getMethod("animControlCommand", ANIM_CONTROL_PARAM_TYPES));

        registerCollisionSafe("heightmap", ImmutableList.of("/heightmap", "terrain", "/terrain", "dem", "/dem"),
                "Convert a heightmap / DEM image (+ optional colormap) into 3D Minecraft terrain",
                ImmutableList.of(waterFlag, hollowFlag, noSubFlag, centerFlag, toClipboardFlag,
                        sourceArg, size80Arg, maxHeightArg, colormapArg, patternOptArg),
                this::invokeHeightmap,
                getMethod("heightmapCommand", HEIGHTMAP_PARAM_TYPES));

        registerCollisionSafe("text3d", ImmutableList.of("/text3d", "3dtext", "/3dtext"),
                "Render 3D extruded text with sub-block stair/slab glyph smoothing",
                ImmutableList.of(bevelFlag, noSubFlag, centerFlag, toClipboardFlag,
                        textHeightArg, textDepthArg, textBlockArg, textStringArg),
                this::invokeText3D,
                getMethod("text3DCommand", TEXT3D_PARAM_TYPES));

        registerCollisionSafe("exportobj", ImmutableList.of("/exportobj", "schemtoobj", "/schemtoobj", "toobj", "/toobj"),
                "Export your WorldEdit selection (or clipboard with -c) to a Wavefront .obj + .mtl 3D mesh",
                ImmutableList.of(wfcClipboardFlag, copyToModelsFlag, exportNameArg),
                this::invokeExportObj,
                getMethod("exportObjCommand", EXPORT_OBJ_PARAM_TYPES));

        registerCollisionSafe("newwfc", ImmutableList.of("/newwfc", "dbtwfc", "/dbtwfc"),
                "Generates a 3D Wave Function Collapse grid from variations (-n <mask>, -c, -b, -j, -l <int>, -g)",
                ImmutableList.of(wfcNeglectFlag, wfcClipboardFlag, wfcBaseLayerFlag, wfcContinuousFlag, wfcLazyFlag, wfcOuterBoundaryFlag,
                        wfcGridSizeArg, wfcPieceSizeArg, wfcVariationsArg, wfcAdherenceArg, wfcTimeoutArg),
                this::invokeNewWfc,
                getMethod("newWfcCommand", NEW_WFC_PARAM_TYPES));
    }

    private Method getMethod(String name, Class<?>... params) {
        return RegistrationUtil.getCommandMethod(DbtWorldEditCommands.class, name, params);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void registerCollisionSafe(
            String primaryName,
            List<String> aliases,
            String desc,
            List parts,
            org.enginehub.piston.Command.Action action,
            Method method
    ) {
        List<String> allCandidates = new ArrayList<>();
        allCandidates.add(primaryName);
        allCandidates.addAll(aliases);

        String chosenPrimary = null;
        List<String> chosenAliases = new ArrayList<>();
        for (String candidate : allCandidates) {
            if (!manager.containsCommand(candidate)) {
                if (chosenPrimary == null) {
                    chosenPrimary = candidate;
                } else {
                    chosenAliases.add(candidate);
                }
            }
        }
        if (chosenPrimary == null) {
            return;
        }
        final String finalPrimary = chosenPrimary;
        final List<String> finalAliases = ImmutableList.copyOf(chosenAliases);
        this.manager.register(finalPrimary, (Consumer<Command.Builder>) builder -> {
            builder.aliases(finalAliases);
            builder.description(TextComponent.of(desc));
            builder.parts(parts);
            builder.action(action);
            if (permissionsConditionGenerator != null && method != null) {
                builder.condition(permissionsConditionGenerator.generateCondition(method));
            }
        });
    }

    private Player extractPlayer(CommandParameters params) {
        return RegistrationUtil.requireOptional(PLAYER_KEY, "player", params.injectedValue(PLAYER_KEY));
    }

    private LocalSession extractSession(CommandParameters params) {
        return RegistrationUtil.requireOptional(LOCAL_SESSION_KEY, "session", params.injectedValue(LOCAL_SESSION_KEY));
    }

    private EditSession extractEditSession(CommandParameters params) {
        return RegistrationUtil.requireOptional(EDIT_SESSION_KEY, "editSession", params.injectedValue(EDIT_SESSION_KEY));
    }

    private int invokePixelArt(CommandParameters params) throws WorldEditException {
        Method method = getMethod("pixelArtCommand", PIXEL_ART_PARAM_TYPES);
        RegistrationUtil.listenersBeforeCall(this.callListeners, method, params);
        try {
            container.pixelArtCommand(
                    extractPlayer(params),
                    extractSession(params),
                    extractEditSession(params),
                    (String) sourceArg.value(params).asSingle(STRING_KEY),
                    (Integer) widthArg.value(params).asSingle(INTEGER_KEY),
                    (Integer) heightArg.value(params).asSingle(INTEGER_KEY),
                    flatFlag.in(params),
                    noDitherFlag.in(params),
                    noGlassFlag.in(params),
                    centerFlag.in(params),
                    toClipboardFlag.in(params)
            );
            RegistrationUtil.listenersAfterCall(this.callListeners, method, params);
            return 1;
        } catch (Throwable t) {
            RegistrationUtil.listenersAfterThrow(this.callListeners, method, params, t);
            throw t;
        }
    }

    private int invokeModel(CommandParameters params) throws WorldEditException {
        Method method = getMethod("modelCommand", MODEL_PARAM_TYPES);
        RegistrationUtil.listenersBeforeCall(this.callListeners, method, params);
        try {
            container.modelCommand(
                    extractPlayer(params),
                    extractSession(params),
                    extractEditSession(params),
                    (String) sourceArg.value(params).asSingle(STRING_KEY),
                    (Integer) size48Arg.value(params).asSingle(INTEGER_KEY),
                    (String) patternOptArg.value(params).asSingle(STRING_KEY),
                    (Double) rxArg.value(params).asSingle(DOUBLE_KEY),
                    (Double) ryArg.value(params).asSingle(DOUBLE_KEY),
                    (Double) rzArg.value(params).asSingle(DOUBLE_KEY),
                    solidFlag.in(params),
                    noDitherFlag.in(params),
                    noGlassFlag.in(params),
                    shadeFlag.in(params),
                    noSubFlag.in(params),
                    centerFlag.in(params),
                    toClipboardFlag.in(params)
            );
            RegistrationUtil.listenersAfterCall(this.callListeners, method, params);
            return 1;
        } catch (Throwable t) {
            RegistrationUtil.listenersAfterThrow(this.callListeners, method, params, t);
            throw t;
        }
    }

    private int invokeAnimModel(CommandParameters params) throws WorldEditException {
        Method method = getMethod("animModelCommand", ANIM_MODEL_PARAM_TYPES);
        RegistrationUtil.listenersBeforeCall(this.callListeners, method, params);
        try {
            container.animModelCommand(
                    extractPlayer(params),
                    extractSession(params),
                    (String) sourceArg.value(params).asSingle(STRING_KEY),
                    (Integer) size40Arg.value(params).asSingle(INTEGER_KEY),
                    (String) modeArg.value(params).asSingle(STRING_KEY),
                    (Integer) frames16Arg.value(params).asSingle(INTEGER_KEY),
                    (Double) fps8Arg.value(params).asSingle(DOUBLE_KEY),
                    (String) patternOptArg.value(params).asSingle(STRING_KEY),
                    solidFlag.in(params),
                    noSubFlag.in(params),
                    noLoopFlag.in(params),
                    centerFlag.in(params)
            );
            RegistrationUtil.listenersAfterCall(this.callListeners, method, params);
            return 1;
        } catch (Throwable t) {
            RegistrationUtil.listenersAfterThrow(this.callListeners, method, params, t);
            throw t;
        }
    }

    private int invokeVideo(CommandParameters params) throws WorldEditException {
        Method method = getMethod("videoCommand", VIDEO_PARAM_TYPES);
        RegistrationUtil.listenersBeforeCall(this.callListeners, method, params);
        try {
            container.videoCommand(
                    extractPlayer(params),
                    extractSession(params),
                    (String) videoSubActionArg.value(params).asSingle(STRING_KEY),
                    (String) videoNameArg.value(params).asSingle(STRING_KEY),
                    (String) videoFileOrArg.value(params).asSingle(STRING_KEY),
                    (Integer) widthArg.value(params).asSingle(INTEGER_KEY),
                    (Integer) heightArg.value(params).asSingle(INTEGER_KEY),
                    (Double) fps10Arg.value(params).asSingle(DOUBLE_KEY),
                    (Integer) maxFrames60Arg.value(params).asSingle(INTEGER_KEY),
                    flatFlag.in(params),
                    curveFlag.in(params),
                    noDitherFlag.in(params),
                    noGlassFlag.in(params),
                    noLoopFlag.in(params),
                    centerFlag.in(params),
                    autoStartFlag.in(params),
                    restoreOnStopFlag.in(params),
                    keepFlag.in(params)
            );
            RegistrationUtil.listenersAfterCall(this.callListeners, method, params);
            return 1;
        } catch (Throwable t) {
            RegistrationUtil.listenersAfterThrow(this.callListeners, method, params, t);
            throw t;
        }
    }

    private int invokeAnimControl(CommandParameters params) {
        Method method = getMethod("animControlCommand", ANIM_CONTROL_PARAM_TYPES);
        RegistrationUtil.listenersBeforeCall(this.callListeners, method, params);
        try {
            container.animControlCommand(
                    extractPlayer(params),
                    (String) animActionArg.value(params).asSingle(STRING_KEY),
                    (String) animTargetArg.value(params).asSingle(STRING_KEY),
                    (Double) animValueArg.value(params).asSingle(DOUBLE_KEY),
                    keepFlag.in(params)
            );
            RegistrationUtil.listenersAfterCall(this.callListeners, method, params);
            return 1;
        } catch (Throwable t) {
            RegistrationUtil.listenersAfterThrow(this.callListeners, method, params, t);
            throw t;
        }
    }

    private int invokeHeightmap(CommandParameters params) throws WorldEditException {
        Method method = getMethod("heightmapCommand", HEIGHTMAP_PARAM_TYPES);
        RegistrationUtil.listenersBeforeCall(this.callListeners, method, params);
        try {
            container.heightmapCommand(
                    extractPlayer(params),
                    extractSession(params),
                    extractEditSession(params),
                    (String) sourceArg.value(params).asSingle(STRING_KEY),
                    (Integer) size80Arg.value(params).asSingle(INTEGER_KEY),
                    (Integer) maxHeightArg.value(params).asSingle(INTEGER_KEY),
                    (String) colormapArg.value(params).asSingle(STRING_KEY),
                    (String) patternOptArg.value(params).asSingle(STRING_KEY),
                    waterFlag.in(params),
                    hollowFlag.in(params),
                    noSubFlag.in(params),
                    centerFlag.in(params),
                    toClipboardFlag.in(params)
            );
            RegistrationUtil.listenersAfterCall(this.callListeners, method, params);
            return 1;
        } catch (Throwable t) {
            RegistrationUtil.listenersAfterThrow(this.callListeners, method, params, t);
            throw t;
        }
    }

    private int invokeText3D(CommandParameters params) throws WorldEditException {
        Method method = getMethod("text3DCommand", TEXT3D_PARAM_TYPES);
        RegistrationUtil.listenersBeforeCall(this.callListeners, method, params);
        try {
            container.text3DCommand(
                    extractPlayer(params),
                    extractSession(params),
                    extractEditSession(params),
                    (Integer) textHeightArg.value(params).asSingle(INTEGER_KEY),
                    (Integer) textDepthArg.value(params).asSingle(INTEGER_KEY),
                    (String) textBlockArg.value(params).asSingle(STRING_KEY),
                    (String) textStringArg.value(params).asSingle(STRING_KEY),
                    bevelFlag.in(params),
                    noSubFlag.in(params),
                    centerFlag.in(params),
                    toClipboardFlag.in(params)
            );
            RegistrationUtil.listenersAfterCall(this.callListeners, method, params);
            return 1;
        } catch (Throwable t) {
            RegistrationUtil.listenersAfterThrow(this.callListeners, method, params, t);
            throw t;
        }
    }

    private int invokeExportObj(CommandParameters params) {
        Method method = getMethod("exportObjCommand", EXPORT_OBJ_PARAM_TYPES);
        RegistrationUtil.listenersBeforeCall(this.callListeners, method, params);
        try {
            container.exportObjCommand(
                    extractPlayer(params),
                    extractSession(params),
                    extractEditSession(params),
                    (String) exportNameArg.value(params).asSingle(STRING_KEY),
                    wfcClipboardFlag.in(params),
                    copyToModelsFlag.in(params)
            );
            RegistrationUtil.listenersAfterCall(this.callListeners, method, params);
            return 1;
        } catch (Throwable t) {
            RegistrationUtil.listenersAfterThrow(this.callListeners, method, params, t);
            throw t;
        }
    }

    private int invokeNewWfc(CommandParameters params) throws WorldEditException {
        Method method = getMethod("newWfcCommand", NEW_WFC_PARAM_TYPES);
        RegistrationUtil.listenersBeforeCall(this.callListeners, method, params);
        try {
            String neglectMaskStr = params.has(wfcNeglectFlag)
                    ? (String) wfcNeglectFlag.value(params).asSingle(STRING_KEY)
                    : "structure_void";
            int lazyTolerance = params.has(wfcLazyFlag)
                    ? (Integer) wfcLazyFlag.value(params).asSingle(INTEGER_KEY)
                    : 0;
            container.newWfcCommand(
                    extractPlayer(params),
                    extractSession(params),
                    extractEditSession(params),
                    (Integer) wfcGridSizeArg.value(params).asSingle(INTEGER_KEY),
                    (Integer) wfcPieceSizeArg.value(params).asSingle(INTEGER_KEY),
                    (Integer) wfcVariationsArg.value(params).asSingle(INTEGER_KEY),
                    (Double) wfcAdherenceArg.value(params).asSingle(DOUBLE_KEY),
                    (Double) wfcTimeoutArg.value(params).asSingle(DOUBLE_KEY),
                    neglectMaskStr,
                    wfcClipboardFlag.in(params),
                    wfcBaseLayerFlag.in(params),
                    wfcContinuousFlag.in(params),
                    lazyTolerance,
                    wfcOuterBoundaryFlag.in(params)
            );
            RegistrationUtil.listenersAfterCall(this.callListeners, method, params);
            return 1;
        } catch (Throwable t) {
            RegistrationUtil.listenersAfterThrow(this.callListeners, method, params, t);
            throw t;
        }
    }
}
