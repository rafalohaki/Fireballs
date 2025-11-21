package org.rafalohaki.fireballs;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import org.bukkit.plugin.java.JavaPlugin;
import org.rafalohaki.fireballs.listener.CustomFireballListener;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.logging.Level;

/**
 * Custom Fireballs Plugin for Folia 1.21.8+
 * 
 * Features:
 * - Event-based system (no continuous scanning)
 * - Uses PlayerInteractEvent for fire charge usage
 * - Uses ProjectileHitEvent for collision detection
 * - PersistentDataContainer for fireball tagging
 * - Custom explosions with configurable effects
 * - Cooldown system (ConcurrentHashMap for thread safety)
 * - Thread-safe on Folia's region threads
 */
public final class Fireballs extends JavaPlugin {

    private CustomFireballListener listener;
    private PacketListener packetListener;

    @Override
    public void onEnable() {
        // Save default config if not exists
        saveDefaultConfig();
        
        // Register event listener
        // Events in Folia are automatically called on the appropriate region threads
        listener = new CustomFireballListener(this);
        getServer().getPluginManager().registerEvents(listener, this);

        packetListener = new PacketListener() {
            @Override
            public void onPacketReceive(PacketReceiveEvent event) {
                var type = event.getPacketType();
                if (type != PacketType.Play.Client.USE_ITEM) {
                    return;
                }

                Player player = event.getPlayer();
                if (player == null) {
                    return;
                }

                ItemStack hand = player.getInventory().getItemInMainHand();
                if (hand == null || hand.getType() != Material.FIRE_CHARGE) {
                    return;
                }

                event.setCancelled(true);
                player.getScheduler().run(Fireballs.this, task -> {
                    listener.attemptFire(player);
                }, null);
            }
        };

        PacketEvents.getAPI().getEventManager().registerListener(packetListener, PacketListenerPriority.NORMAL);
        PacketEvents.getAPI().init();


        getLogger().info("Custom Fireballs plugin enabled! (Folia 1.21.8+)");
        getLogger().log(Level.INFO, "Set fire: {0}", getConfig().getBoolean("set-fire", true));
        getLogger().log(Level.INFO, "Explosion power: {0}", getConfig().getDouble("explosion-power", 4.0));
    }

    @Override
    public void onDisable() {
        // CRITICAL: Clean up cooldowns to prevent memory leaks on reload
        if (listener != null) {
            listener.cleanup();
        }
        if (packetListener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener((PacketListenerCommon) packetListener);
            packetListener = null;
        }
        getLogger().info("Custom Fireballs plugin disabled.");
    }
}
