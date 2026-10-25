package com.dragonbuildtools.util;

import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.math.BlockVector3;

/**
 * Resolves a WorldEdit player's horizontal cardinal facing direction and unit offset vectors.
 */
public enum CardinalDirectionUtil {
    NORTH(BlockVector3.at(0, 0, -1), BlockVector3.at(1, 0, 0)),
    EAST(BlockVector3.at(1, 0, 0), BlockVector3.at(0, 0, 1)),
    SOUTH(BlockVector3.at(0, 0, 1), BlockVector3.at(-1, 0, 0)),
    WEST(BlockVector3.at(-1, 0, 0), BlockVector3.at(0, 0, -1));

    private final BlockVector3 forward;
    private final BlockVector3 right;

    CardinalDirectionUtil(BlockVector3 forward, BlockVector3 right) {
        this.forward = forward;
        this.right = right;
    }

    public BlockVector3 getForward() {
        return forward;
    }

    public BlockVector3 getRight() {
        return right;
    }

    public static CardinalDirectionUtil getCardinalDirection(Player player) {
        if (player == null || player.getLocation() == null) {
            return NORTH;
        }
        double rot = (player.getLocation().getYaw() - 90.0) % 360.0;
        if (rot < 0) {
            rot += 360.0;
        }
        if (rot >= 45.0 && rot < 135.0) {
            return NORTH;
        } else if (rot >= 135.0 && rot < 225.0) {
            return EAST;
        } else if (rot >= 225.0 && rot < 315.0) {
            return SOUTH;
        } else {
            return WEST;
        }
    }
}
