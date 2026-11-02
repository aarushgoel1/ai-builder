package com.example.aiworldedit;

import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;

/**
 * Terrain helpers: real ground height (ignoring trees and plants) and a
 * terrain description in the same LOCAL coordinates the AI plans in.
 */
public final class WorldScanner {
    private WorldScanner() {}

    /** Y of the top ground block at a column, skipping logs, leaves and plants. MIN_VALUE if unloaded. */
    public static int groundY(World world, int x, int z) {
        int top = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (top <= world.getBottomY()) return Integer.MIN_VALUE;
        int y = top - 1;
        for (int i = 0; i < 40 && y > world.getBottomY(); i++, y--) {
            BlockState s = world.getBlockState(new BlockPos(x, y, z));
            if (!(s.isIn(BlockTags.LOGS) || s.isIn(BlockTags.LEAVES) || s.isReplaceable())) return y;
        }
        return top - 1;
    }

    /** Copies the real blocks in a world box into a grid (client thread). */
    public static VoxelGrid toGrid(World world, int[] box) {
        VoxelGrid g = new VoxelGrid(box[0], box[1], box[2], box[3], box[4], box[5]);
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int x = box[0]; x <= box[3]; x++)
            for (int y = box[1]; y <= box[4]; y++)
                for (int z = box[2]; z <= box[5]; z++) {
                    pos.set(x, y, z);
                    g.load(x, y, z, Registries.BLOCK.getId(world.getBlockState(pos).getBlock()).getPath());
                }
        return g;
    }

    /** Block id (without "minecraft:") at a position. */
    public static String blockAt(World world, net.minecraft.util.math.BlockPos pos) {
        return Registries.BLOCK.getId(world.getBlockState(pos).getBlock()).getPath();
    }

    /** "desert" / "snowy" / null from the ground at (and just above) a position. */
    public static String climateAt(World world, net.minecraft.util.math.BlockPos pos) {
        String c = StylePresets.climateOfGround(blockAt(world, pos.up()));
        return c != null ? c : StylePresets.climateOfGround(blockAt(world, pos));
    }

    /**
     * Every block in the game with its state properties and their allowed values, so saved builds from
     * newer (or older) versions can be cleaned up before WorldEdit sees them.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static java.util.Map<String, java.util.Map<String, java.util.Set<String>>> stateTable() {
        java.util.Map<String, java.util.Map<String, java.util.Set<String>>> out = new java.util.HashMap<>();
        for (net.minecraft.block.Block b : Registries.BLOCK) {
            net.minecraft.util.Identifier id = Registries.BLOCK.getId(b);
            if (!"minecraft".equals(id.getNamespace())) continue;
            java.util.Map<String, java.util.Set<String>> props = new java.util.HashMap<>();
            for (net.minecraft.state.property.Property p : b.getStateManager().getProperties()) {
                java.util.Set<String> vals = new java.util.HashSet<>();
                for (Object v : p.getValues()) vals.add(p.name((Comparable) v));
                props.put(p.getName(), vals);
            }
            out.put(id.getPath(), props);
        }
        return out;
    }

    /** Map colour (RGB) of every block, for the study pictures of saved builds. */
    public static java.util.Map<String, Integer> mapColors() {
        java.util.Map<String, Integer> out = new java.util.HashMap<>();
        for (net.minecraft.block.Block b : Registries.BLOCK) {
            net.minecraft.util.Identifier id = Registries.BLOCK.getId(b);
            try {
                out.put(id.getPath(), b.getDefaultMapColor().color);
            } catch (Throwable ignored) {
                // some blocks have no map colour
            }
        }
        return out;
    }

    /** Every block id in the game (e.g. "stone_bricks"), for validating AI-written patterns. */
    public static java.util.Set<String> allBlockIds() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (net.minecraft.block.Block b : Registries.BLOCK) {
            net.minecraft.util.Identifier id = Registries.BLOCK.getId(b);
            // Vanilla ids work without a namespace; modded ones keep theirs.
            ids.add(id.getNamespace().equals("minecraft") ? id.getPath() : id.toString());
        }
        return ids;
    }

    public static BuildPlanner.Ground ground(World world) {
        return (x, z) -> groundY(world, x, z);
    }

    /** Height grid in local coordinates: values are ground height relative to the anchor's ground. */
    public static String describe(World world, BuildPlanner.Frame frame) {
        Config cfg = Config.get();
        int radius = Math.max(4, cfg.scanRadius);
        int step = Math.max(1, cfg.scanStep);
        int groundRef = frame.anchor.getY() - 1; // the block the anchor stands on

        StringBuilder out = new StringBuilder();
        out.append("TERRAIN (local coordinates, sampled every ").append(step).append(" blocks)\n");
        out.append("Each number = ground height relative to the anchor's ground (0 = same level, ")
            .append("+2 = two blocks higher, -3 = three lower). '~' = under water (number is the sea/lake floor).\n");
        out.append("        x:");
        for (int x = -radius; x <= radius; x += step) out.append(String.format("%5d", x));
        out.append('\n');

        Map<String, Integer> materials = new HashMap<>();
        for (int z = -step; z <= radius * 2; z += step) {
            out.append(String.format("  z=%-5d ", z));
            for (int x = -radius; x <= radius; x += step) {
                int wx = frame.wx(x, z), wz = frame.wz(x, z);
                int gy = groundY(world, wx, wz);
                if (gy == Integer.MIN_VALUE) {
                    out.append("    ?");
                    continue;
                }
                BlockState state = world.getBlockState(new BlockPos(wx, gy, wz));
                String id = Registries.BLOCK.getId(state.getBlock()).toString();
                materials.merge(id, 1, Integer::sum);
                String above = Registries.BLOCK.getId(
                    world.getBlockState(new BlockPos(wx, gy + 1, wz)).getBlock()).toString();
                String cell = (gy - groundRef) + (above.contains("water") ? "~" : "");
                out.append(String.format("%5s", cell));
            }
            out.append('\n');
        }
        out.append("Common ground blocks: ");
        materials.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .limit(8)
            .forEach(e -> out.append(e.getKey()).append(" x").append(e.getValue()).append(", "));
        out.append("\nDimension: ").append(world.getRegistryKey().getValue());
        out.append("\nComponents that sit on the ground (building, tower, wall, path, tree, lamp_post) "
            + "adapt to this terrain automatically.");
        return out.toString();
    }
}
