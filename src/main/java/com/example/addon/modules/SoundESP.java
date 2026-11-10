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

    private final Setting<Boolean> water = sgSounds.add(new BoolSetting.Builder()
        .name("water-flow").description("Detect water flow, splashes and bubble columns.").defaultValue(true).build());
    private final Setting<Boolean> containers = sgSounds.add(new BoolSetting.Builder()
        .name("storage").description("Detect chests, barrels and shulker boxes.").defaultValue(true).build());
    private final Setting<Boolean> baseActivity = sgSounds.add(new BoolSetting.Builder()
        .name("base-activity").description("Detect doors, gates, furnaces, brewing, anvils and similar activity.").defaultValue(true).build());
    private final Setting<Boolean> baseMobs = sgSounds.add(new BoolSetting.Builder()
        .name("base-mobs").description("Detect villagers and common farm animal sounds.").defaultValue(false).build());

    private final Setting<Integer> radius = sgGeneral.add(new IntSetting.Builder()
        .name("radius")
        .description("0 removes the addon's distance limit (but not the server sound limit).")
        .defaultValue(0).range(0, 512).sliderRange(0, 256).build());
    private final Setting<Boolean> ignoreSelf = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-own-sounds")
        .description("Ignore sounds very close to your player (also hides other nearby sounds).")
        .defaultValue(true).build());

    private final Setting<Double> selfRadius = sgGeneral.add(new DoubleSetting.Builder()
        .name("own-sound-radius")
        .description("Sounds within this many blocks of your player will be hidden.")
        .defaultValue(1.75).range(0.25, 5.0).sliderRange(0.5, 4.0).build());

    private final Setting<Double> lifetime = sgGeneral.add(new DoubleSetting.Builder()
        .name("marker-lifetime").description("Seconds a detected sound stays marked, even after leaving render distance.")
        .defaultValue(120.0).range(0.25, 3600.0).sliderRange(1.0, 600.0).build());
    private final Setting<Double> markerSize = sgRender.add(new DoubleSetting.Builder()
        .name("marker-size").description("3D box size.")
        .defaultValue(0.22).range(0.05, 1.0).sliderRange(0.1, 0.5).build());

    private final Setting<SettingColor> footstepColor = sgRender.add(new ColorSetting.Builder()
        .name("footstep-color").defaultValue(new SettingColor(255, 65, 65, 255)).build());
    private final Setting<SettingColor> redstoneColor = sgRender.add(new ColorSetting.Builder()
        .name("redstone-color").defaultValue(new SettingColor(255, 190, 45, 255)).build());
    private final Setting<SettingColor> breakingColor = sgRender.add(new ColorSetting.Builder()
        .name("block-breaking-color").defaultValue(new SettingColor(70, 160, 255, 255)).build());

    private final Setting<SettingColor> waterColor = sgRender.add(new ColorSetting.Builder()
        .name("water-color").defaultValue(new SettingColor(40, 175, 255, 255)).build());
    private final Setting<SettingColor> storageColor = sgRender.add(new ColorSetting.Builder()
        .name("storage-color").defaultValue(new SettingColor(185, 115, 255, 255)).build());
    private final Setting<SettingColor> activityColor = sgRender.add(new ColorSetting.Builder()
        .name("base-activity-color").defaultValue(new SettingColor(255, 135, 55, 255)).build());
    private final Setting<SettingColor> mobColor = sgRender.add(new ColorSetting.Builder()
        .name("base-mob-color").defaultValue(new SettingColor(100, 230, 105, 255)).build());

    private final ConcurrentLinkedDeque<SoundMarker> markers = new ConcurrentLinkedDeque<>();

    private final SoundEventListener soundListener = (sound, soundEvent, range) -> {
        SoundType type = classify(sound);
        if (type == null || !isEnabled(type)) return;
        if (ignoreSelf.get() && Minecraft.getInstance().player != null) {
            var player = Minecraft.getInstance().player;
            double dx = sound.getX() - player.getX();
            double dy = sound.getY() - player.getY();
            double dz = sound.getZ() - player.getZ();
            double distance = selfRadius.get();
            if (dx * dx + dy * dy + dz * dz <= distance * distance) return;
        }
        markers.addLast(new SoundMarker(sound.getX(), sound.getY(), sound.getZ(), type, System.currentTimeMillis()));
        while (markers.size() > 512) markers.pollFirst();
    };

    public SoundESP() {
        super(AddonTemplate.CATEGORY, "sound-esp", "Highlights received water, storage, base activity, mobs, footsteps and block sounds.");
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
                case WATER -> waterColor.get();
                case STORAGE -> storageColor.get();
                case BASE_ACTIVITY -> activityColor.get();
                case BASE_MOB -> mobColor.get();
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
            case WATER -> water.get();
            case STORAGE -> containers.get();
            case BASE_ACTIVITY -> baseActivity.get();
            case BASE_MOB -> baseMobs.get();
        };
    }

    private static SoundType classify(SoundInstance sound) {
        String id = sound.getIdentifier().toString().toLowerCase();
        if (id.contains("water.ambient") || id.contains("water.flow") || id.contains("water.splash")
            || id.contains("bubble_column.") || id.contains("drip_water") || id.contains("water.drip")
            || id.contains("bucket.empty") || id.contains("bucket.fill")) return SoundType.WATER;
        if (id.contains("chest.open") || id.contains("chest.close") || id.contains("barrel.open")
            || id.contains("barrel.close") || id.contains("shulker_box.open") || id.contains("shulker_box.close")
            || id.contains("ender_chest.open") || id.contains("ender_chest.close")) return SoundType.STORAGE;
        if (id.contains("door.open") || id.contains("door.close") || id.contains("trapdoor.open")
            || id.contains("trapdoor.close") || id.contains("fence_gate.open") || id.contains("fence_gate.close")
            || id.contains("furnace.fire_crackle") || id.contains("smoker.smoke")
            || id.contains("brewing_stand.brew") || id.contains("anvil.use") || id.contains("grindstone.use")
            || id.contains("stonecutter") || id.contains("enchantment_table") || id.contains("portal.ambient")
            || id.contains("beacon.ambient") || id.contains("bell.use")) return SoundType.BASE_ACTIVITY;
        if (id.contains("entity.villager.") || id.contains("entity.cow.") || id.contains("entity.sheep.")
            || id.contains("entity.chicken.") || id.contains("entity.pig.") || id.contains("entity.horse.")
            || id.contains("entity.cat.") || id.contains("entity.wolf.")) return SoundType.BASE_MOB;
        if (id.contains(".step") || id.contains("footstep")) return SoundType.FOOTSTEP;
        if (id.contains("redstone") || id.contains("repeater") || id.contains("comparator")
            || id.contains("piston") || id.contains("lever.click") || id.contains("button.click")
            || id.contains("pressure_plate") || id.contains("tripwire") || id.contains("dispenser")
            || id.contains("dropper") || id.contains("note_block")) return SoundType.REDSTONE;
        if (id.endsWith(".break") || id.contains(".block.break")) return SoundType.BLOCK_BREAK;
        return null;
    }

    private enum SoundType { FOOTSTEP, REDSTONE, BLOCK_BREAK, WATER, STORAGE, BASE_ACTIVITY, BASE_MOB }
    private record SoundMarker(double x, double y, double z, SoundType type, long time) {}
}
