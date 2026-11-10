package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.world.phys.AABB;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedDeque;

/** Tracks sounds sent to the Minecraft client; cannot reveal server-suppressed sounds. */
public class SoundESP extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSounds = settings.createGroup("Sounds");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> footsteps = sgSounds.add(new BoolSetting.Builder()
        .name("footsteps").description("Detect footsteps.").defaultValue(true).build());
    private final Setting<Boolean> redstone = sgSounds.add(new BoolSetting.Builder()
        .name("redstone").description("Detect redstone-related sounds.").defaultValue(true).build());
    private final Setting<Boolean> blockBreaking = sgSounds.add(new BoolSetting.Builder()
        .name("block-breaking").description("Detect block-breaking sounds.").defaultValue(true).build());

    private final Setting<Integer> radius = sgGeneral.add(new IntSetting.Builder()
        .name("radius")
        .description("0 removes the addon's distance limit (but not the server sound limit).")
        .defaultValue(0).range(0, 512).sliderRange(0, 256).build());
    private final Setting<Double> lifetime = sgGeneral.add(new DoubleSetting.Builder()
        .name("marker-lifetime").description("Seconds a marker stays visible.")
        .defaultValue(2.5).range(0.25, 10.0).sliderRange(0.5, 6.0).build());
    private final Setting<Double> markerSize = sgRender.add(new DoubleSetting.Builder()
        .name("marker-size").description("3D box size.")
        .defaultValue(0.22).range(0.05, 1.0).sliderRange(0.1, 0.5).build());

    private final Setting<SettingColor> footstepColor = sgRender.add(new ColorSetting.Builder()
        .name("footstep-color").defaultValue(new SettingColor(255, 65, 65, 255)).build());
    private final Setting<SettingColor> redstoneColor = sgRender.add(new ColorSetting.Builder()
        .name("redstone-color").defaultValue(new SettingColor(255, 190, 45, 255)).build());
    private final Setting<SettingColor> breakingColor = sgRender.add(new ColorSetting.Builder()
        .name("block-breaking-color").defaultValue(new SettingColor(70, 160, 255, 255)).build());

    private final ConcurrentLinkedDeque<SoundMarker> markers = new ConcurrentLinkedDeque<>();

    private final SoundEventListener soundListener = (sound, soundEvent, range) -> {
        SoundType type = classify(sound);
        if (type == null || !isEnabled(type)) return;
        markers.addLast(new SoundMarker(sound.getX(), sound.getY(), sound.getZ(), type, System.currentTimeMillis()));
        while (markers.size() > 512) markers.pollFirst();
    };

    public SoundESP() {
        super(AddonTemplate.CATEGORY, "sound-esp", "Highlights client-received footsteps, redstone and block-breaking sounds.");
    }

    @Override
    public void onActivate() {
        markers.clear();
        Minecraft.getInstance().getSoundManager().addListener(soundListener);
    }

    @Override
    public void onDeactivate() {
        Minecraft.getInstance().getSoundManager().removeListener(soundListener);
        markers.clear();
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null || mc.level == null) return;
        long now = System.currentTimeMillis();
        long maxAge = (long) (lifetime.get() * 1000.0);
        double maxDistanceSquared = radius.get() == 0 ? Double.POSITIVE_INFINITY : (double) radius.get() * radius.get();
        double half = markerSize.get();

        for (SoundMarker marker : markers) {
            if (now - marker.time() > maxAge) {
                markers.remove(marker);
                continue;
            }
            double dx = marker.x() - mc.player.getX();
            double dy = marker.y() - mc.player.getY();
            double dz = marker.z() - mc.player.getZ();
            if (dx * dx + dy * dy + dz * dz > maxDistanceSquared) continue;

            Color color = switch (marker.type()) {
                case FOOTSTEP -> footstepColor.get();
                case REDSTONE -> redstoneColor.get();
                case BLOCK_BREAK -> breakingColor.get();
            };
            AABB box = new AABB(marker.x() - half, marker.y() - half, marker.z() - half,
                marker.x() + half, marker.y() + half, marker.z() + half);
            event.renderer.box(box, color, color, ShapeMode.Both, 0);
            event.renderer.line(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ(),
                marker.x(), marker.y(), marker.z(), color);
        }
    }

    private boolean isEnabled(SoundType type) {
        return switch (type) {
            case FOOTSTEP -> footsteps.get();
            case REDSTONE -> redstone.get();
            case BLOCK_BREAK -> blockBreaking.get();
        };
    }

    private static SoundType classify(SoundInstance sound) {
        String id = sound.getIdentifier().toString().toLowerCase();
        if (id.contains(".step") || id.contains("footstep")) return SoundType.FOOTSTEP;
        if (id.contains("redstone") || id.contains("repeater") || id.contains("comparator")
            || id.contains("piston") || id.contains("lever.click") || id.contains("button.click")
            || id.contains("pressure_plate") || id.contains("tripwire") || id.contains("dispenser")
            || id.contains("dropper") || id.contains("note_block")) return SoundType.REDSTONE;
        if (id.endsWith(".break") || id.contains(".block.break")) return SoundType.BLOCK_BREAK;
        return null;
    }

    private enum SoundType { FOOTSTEP, REDSTONE, BLOCK_BREAK }
    private record SoundMarker(double x, double y, double z, SoundType type, long time) {}
}
