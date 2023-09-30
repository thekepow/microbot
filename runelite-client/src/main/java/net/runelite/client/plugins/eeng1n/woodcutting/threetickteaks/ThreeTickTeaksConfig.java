package net.runelite.client.plugins.eeng1n.woodcutting.threetickteaks;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("threeTickTeaks")
public interface ThreeTickTeaksConfig extends Config {
    @ConfigItem(
            keyName = "guide",
            name = "How to use",
            description = "How to use this plugin",
            position = 0
    )
    default String GUIDE() {
        return "1. Have your inventory setup for 3T Teaks \n2. Enable Plugin";
    }

    @ConfigItem(
            keyName = "overlay",
            name = "Enable Overlay",
            description = "Enable Overlay?",
            position = 1
    )
    default boolean overlay() { return true; }
}
