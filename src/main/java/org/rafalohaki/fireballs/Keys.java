package org.rafalohaki.fireballs;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/**
 * Helper class for managing PersistentDataContainer keys.
 * Used to tag custom fireballs for identification in events.
 */
public final class Keys {

    private final NamespacedKey customFireballKey;

    public Keys(Plugin plugin) {
        this.customFireballKey = new NamespacedKey(plugin, "custom_fireball");
    }

    public NamespacedKey customFireballKey() {
        return customFireballKey;
    }
}
