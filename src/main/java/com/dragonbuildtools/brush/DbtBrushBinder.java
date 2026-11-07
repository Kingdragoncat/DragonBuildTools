package com.dragonbuildtools.brush;

import com.fastasyncworldedit.core.command.tool.brush.BrushSettings;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.command.tool.BrushTool;
import com.sk89q.worldedit.command.tool.InvalidToolBindException;
import com.sk89q.worldedit.command.tool.brush.Brush;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.extension.input.InputParseException;
import com.sk89q.worldedit.extension.input.ParserContext;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.util.formatting.text.TextComponent;
import com.sk89q.worldedit.util.formatting.text.format.TextColor;

/**
 * Binds Dragon Build Tools brushes onto a player's held item using FAWE's native
 * {@link BrushTool} and {@link BrushSettings} so {@code /size}, {@code //mask}, {@code //gmask},
 * scroll-wheel sizing, and {@code //undo}/{@code //redo} all work out of the box.
 */
public final class DbtBrushBinder {

    private DbtBrushBinder() {
    }

    public static boolean equipBrush(
            Player player,
            LocalSession session,
            Brush brush,
            double radius,
            String optionalMaskStr,
            String brushIdLabel,
            String summaryMessage
    ) {
        double clampedRadius = Math.max(1.0, Math.min(40.0, radius));
        Mask parsedMask = null;
        if (optionalMaskStr != null && !optionalMaskStr.trim().isEmpty()) {
            try {
                ParserContext ctx = new ParserContext();
                ctx.setActor(player);
                ctx.setWorld(player.getWorld());
                ctx.setSession(session);
                parsedMask = WorldEdit.getInstance().getMaskFactory().parseFromInput(optionalMaskStr.trim(), ctx);
            } catch (InputParseException e) {
                player.print(TextComponent.of("[DBT] Invalid mask '" + optionalMaskStr + "': " + e.getMessage(), TextColor.RED));
                return false;
            }
        }

        try {
            session.setTool(player, null);
            BrushTool brushTool = session.getBrushTool(player);
            if (brushTool == null) {
                player.print(TextComponent.of("[DBT] Hold a non-air item in your main hand to bind a brush!", TextColor.RED));
                return false;
            }

            BrushSettings settings = new BrushSettings();
            settings.addPermission("dragonbuildtools.use");
            settings.addSetting(BrushSettings.SettingType.BRUSH, brushIdLabel);
            settings.setBrush(brush);
            settings.setSize(clampedRadius);
            if (parsedMask != null) {
                settings.setMask(parsedMask);
            }

            brushTool.setPrimary(settings);
            brushTool.setSecondary(settings);

            player.print(TextComponent.of(summaryMessage, TextColor.LIGHT_PURPLE));
            return true;
        } catch (InvalidToolBindException e) {
            player.print(TextComponent.of(
                    "[DBT] Hold an item (like a brush, feather, or shovel) in your main hand before running this brush command!",
                    TextColor.RED
            ));
            return false;
        } catch (Throwable t) {
            player.print(TextComponent.of("[DBT] Failed to equip brush: " + t.getMessage(), TextColor.RED));
            return false;
        }
    }
}
