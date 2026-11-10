package com.example.addon;

import com.example.addon.modules.SoundESP;
import com.example.addon.modules.BlockESP;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class AddonTemplate extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Sound ESP");
    public static final HudGroup HUD_GROUP = new HudGroup("Sound ESP");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Sound ESP addon");
        Modules.get().add(new SoundESP());
        Modules.get().add(new BlockESP());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.example.addon";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("Yoursosalte", "SoundESP");
    }
}
