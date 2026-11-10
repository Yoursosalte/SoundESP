package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.BlockListSetting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Set;

/** Highlights selected block types in already received chunks. Never loads or discovers unseen chunks. */
public class BlockESP extends Module {
    private final SettingGroup sgSelection = settings.getDefaultGroup();
    private final SettingGroup sgScanning = settings.createGroup("Scanning");
    private final SettingGroup sgSeed = settings.createGroup("Seed Predictions");
    private final SettingGroup sgRender = settings.createGroup("Highlight Style");

    private final Setting<java.util.List<Block>> blockIds = sgSelection.add(new BlockListSetting.Builder()
        .name("blocks")
        .description("Select the exact block types to highlight.")
        .defaultValue(Blocks.CHEST, Blocks.BARREL, Blocks.SPAWNER)
        .build());

    private final Setting<Integer> scanRadius = sgScanning.add(new IntSetting.Builder()
        .name("scan-radius").description("Search radius in loaded chunks, measured in blocks.")
        .defaultValue(64).range(8, 192).sliderRange(16, 128).build());

    private final Setting<Integer> verticalRadius = sgScanning.add(new IntSetting.Builder()
        .name("vertical-radius").description("Vertical scan distance from your player.")
        .defaultValue(64).range(8, 192).sliderRange(8, 128).build());

    private final Setting<Boolean> fullHeight = sgScanning.add(new BoolSetting.Builder()
        .name("full-world-height")
        .description("Scan from the bottom to the top of loaded chunks, including completely hidden blocks.")
        .defaultValue(true).build());

    private final Setting<Integer> scanBudget = sgScanning.add(new IntSetting.Builder()
        .name("blocks-per-tick").description("How many block positions to examine per game tick.")
        .defaultValue(16384).range(512, 32768).sliderRange(1024, 32768).build());

    private final Setting<Integer> maxMarkers = sgScanning.add(new IntSetting.Builder()
        .name("max-markers").description("Maximum remembered block positions.")
        .defaultValue(2048).range(128, 8192).sliderRange(128, 4096).build());

    private final Setting<Boolean> remember = sgScanning.add(new BoolSetting.Builder()
        .name("remember-unloaded").description("Keep already observed block markers after their chunks unload.")
        .defaultValue(true).build());

    private final Setting<String> worldSeed = sgSeed.add(new StringSetting.Builder()
        .name("world-seed")
        .description("Reference seed only. The addon does not calculate exact ores from the seed.")
        .defaultValue("6608149111735331168").build());

    private final Setting<Boolean> showCandidates = sgSeed.add(new BoolSetting.Builder()
        .name("show-imported-candidates")
        .description("Show manually imported candidate ore positions when their chunks are loaded.")
        .defaultValue(false).build());

    private final Setting<String> candidateCoords = sgSeed.add(new StringSetting.Builder()
        .name("candidate-coordinates")
        .description("Paste predictions from a seed finder: x,y,z;x,y,z. These are NOT confirmed blocks.")
        .defaultValue("").build());

    private final Setting<SettingColor> candidateColor = sgSeed.add(new ColorSetting.Builder()
        .name("candidate-color")
        .description("Color for unverified, seed-finder candidate positions.")
        .defaultValue(new SettingColor(255, 190, 35, 175)).build());

    private final Setting<SettingColor> highlightColor = sgRender.add(new ColorSetting.Builder()
        .name("highlight-color").defaultValue(new SettingColor(180, 50, 255, 210)).build());

    private final Setting<Boolean> tracer = sgRender.add(new BoolSetting.Builder()
        .name("tracers").description("Draw lines to remembered blocks, even outside your view distance.")
        .defaultValue(false).build());

    private final LinkedHashMap<BlockPos, Block> found = new LinkedHashMap<>();
    private final Set<Block> wanted = new HashSet<>();

    private int cursor;
    private int scanX, scanY, scanZ;
    private int lastRadius, lastVertical;
    private boolean lastFullHeight;
    private Set<Block> lastBlocks = new HashSet<>();
    private String lastCandidateCoords = "";
    private final Set<BlockPos> candidates = new HashSet<>();
    private Object lastLevel;

    public BlockESP() {
        super(AddonTemplate.CATEGORY, "block-esp", "Highlight chosen blocks in loaded chunks; optionally remember them after unloading.");
    }

    @Override
    public void onActivate() {
        reset();
    }

    @Override
    public void onDeactivate() {
        reset();
    }

    private void reset() {
        found.clear();
        cursor = 0;
        lastLevel = null;
        lastBlocks.clear();
        lastCandidateCoords = "";
        candidates.clear();
    }

    private void updateTargets() {
        Set<Block> next = new HashSet<>(blockIds.get());
        if (next.equals(lastBlocks)) return;
        lastBlocks = next;
        wanted.clear();
        wanted.addAll(next);
        found.clear();
        cursor = 0;
    }

    private void updateCandidates() {
        String value = candidateCoords.get();
        if (value.equals(lastCandidateCoords)) return;
        lastCandidateCoords = value;
        candidates.clear();
        int limit = 2048;
        for (String token : value.split(";")) {
            if (candidates.size() >= limit) break;
            String[] xyz = token.trim().split(",");
            if (xyz.length != 3) continue;
            try {
                int x = Integer.parseInt(xyz[0].trim());
                int y = Integer.parseInt(xyz[1].trim());
                int z = Integer.parseInt(xyz[2].trim());
                if (Math.abs((long)x) > 30000000 || Math.abs((long)z) > 30000000 || y < -128 || y > 512) continue;
                candidates.add(new BlockPos(x, y, z));
            } catch (NumberFormatException ignored) {
                // Ignore malformed entries, without crashing the game.
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.level == null || mc.player == null) return;
        if (mc.level != lastLevel) {
            reset();
            lastLevel = mc.level;
        }
        updateTargets();
        updateCandidates();
        if (wanted.isEmpty()) return;

        int r = scanRadius.get();
        int v = verticalRadius.get();
        boolean scanAllY = fullHeight.get();
        int lowY = scanAllY ? mc.level.getMinY() : mc.player.getBlockY() - v;
        int highY = scanAllY ? mc.level.getMaxY() - 1 : mc.player.getBlockY() + v;
        int height = highY - lowY + 1;
        if (height <= 0) return;
        int px = mc.player.getBlockX();
        int py = mc.player.getBlockY();
        int pz = mc.player.getBlockZ();

        // Restart a scan when the player moves substantially; no chunk loading is triggered.
        if (cursor == 0 || r != lastRadius || v != lastVertical || scanAllY != lastFullHeight
            || Math.abs(px - scanX) > Math.max(16, r / 2)
            || (!scanAllY && Math.abs(py - scanY) > Math.max(16, v / 2))
            || Math.abs(pz - scanZ) > Math.max(16, r / 2)) {
            scanX = px;
            scanY = py;
            scanZ = pz;
            lastRadius = r;
            lastVertical = v;
            lastFullHeight = scanAllY;
            cursor = 0;
        }

        int side = 2 * r + 1;
        int total = side * side * height;
        int count = 0;

        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        while (count++ < scanBudget.get() && cursor < total) {
            int index = cursor++;
            int yOffset = index % height;
            int column = index / height;
            int dx = column % side - r;
            int dz = column / side - r;
            if (dx * dx + dz * dz > r * r) {
                cursor += height - yOffset - 1;
                continue;
            }

            mutable.set(scanX + dx, lowY + yOffset, scanZ + dz);
            if (!mc.level.hasChunkAt(mutable)) {
                // Skip the rest of this column if its chunk was never sent to the client.
                cursor += height - yOffset - 1;
                continue;
            }
            BlockPos pos = mutable.immutable();
            Block actual = mc.level.getBlockState(mutable).getBlock();
            if (wanted.contains(actual)) {
                if (!found.containsKey(pos)) found.put(pos, actual);
                while (found.size() > maxMarkers.get()) {
                    Iterator<BlockPos> it = found.keySet().iterator();
                    if (!it.hasNext()) break;
                    it.next();
                    it.remove();
                }
            } else {
                found.remove(pos);
            }
        }
        if (cursor >= total) {
            cursor = 0;
            if (!remember.get()) {
                Iterator<BlockPos> it = found.keySet().iterator();
                while (it.hasNext()) {
                    BlockPos pos = it.next();
                    if (!mc.level.hasChunkAt(pos)) it.remove();
                }
            }
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.level == null || mc.player == null) return;
        if (showCandidates.get()) {
            for (BlockPos pos : candidates) {
                if (!mc.level.hasChunkAt(pos)) continue;
                // Confirmed blocks have their own distinct render color.
                if (found.containsKey(pos)) continue;
                event.renderer.box(new AABB(pos), candidateColor.get(), candidateColor.get(), ShapeMode.Lines, 0);
            }
        }
        for (BlockPos pos : found.keySet()) {
            event.renderer.box(new AABB(pos), highlightColor.get(), highlightColor.get(), ShapeMode.Both, 0);
            if (tracer.get()) {
                event.renderer.line(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ(),
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, highlightColor.get());
            }
        }
    }
}
