package org.rafalohaki.fireballs.listener;

import org.bukkit.block.Block;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Explosive;
import org.bukkit.entity.LargeFireball;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.rafalohaki.fireballs.Keys;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.Level;

/**
 * Event listener for custom fireball functionality.
 * Uses Folia's event system - all events run on region threads automatically.
 * No manual thread switching required for typical event handling.
 * 
 * FOLIA THREAD SAFETY:
 * - ConcurrentHashMap for cooldowns (thread-safe across region threads)
 * - Events automatically run on appropriate region threads
 * - Cleanup in onDisable prevents memory leaks on reload
 */
public class CustomFireballListener implements Listener {

    private static final String COOLDOWN_SECONDS_CONFIG = "cooldown-seconds";
    private static final String MAX_FLIGHT_TICKS_CONFIG = "max-flight-ticks";

    private final Plugin plugin;
    private final Keys keys;

    // Cooldown tracking - ConcurrentHashMap for thread safety on Folia
    // Stores UUID -> timestamp of last use
    private final ConcurrentHashMap<UUID, Long> cooldowns;

    // Initial velocity multiplier for the fireball
    private static final double VELOCITY_MULTIPLIER = 1.5;

    // Spawn offset distance in front of player (blocks)
    private static final double SPAWN_OFFSET = 1.5;

    public CustomFireballListener(Plugin plugin) {
        this.plugin = plugin;
        this.keys = new Keys(plugin);
        this.cooldowns = new ConcurrentHashMap<>();
    }

    /**
     * Handles STICK usage to spawn custom fireballs.
     * STICK is used as trigger because it triggers RIGHT_CLICK_AIR events reliably.
     * Event runs on region thread - safe to access player/world directly.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onUseFireCharge(PlayerInteractEvent event) {
        // Only main-hand to avoid duplication with off-hand
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        ItemStack item = event.getItem();
        if (item == null) {
            return;
        }

        if (item.getType() != Material.FIRE_CHARGE) {
            return;
        }

        Player player = event.getPlayer();

        if (isContainerInteraction(action, event.getClickedBlock())) {
            return;
        }

        // Cancel default interactions tylko dla "normalnych" kliknięć
        event.setCancelled(true);

        // Check cooldown
        int cooldownSeconds = plugin.getConfig().getInt(COOLDOWN_SECONDS_CONFIG, 3);
        if (cooldownSeconds > 0) {
            UUID playerId = player.getUniqueId();
            long currentTime = System.currentTimeMillis();

            // S3824: Use single get() instead of containsKey() + get()
            Long lastUse = cooldowns.get(playerId);
            if (lastUse != null) {
                long timePassed = currentTime - lastUse;
                long cooldownMillis = cooldownSeconds * 1000L;

                if (timePassed < cooldownMillis) {
                    long timeLeft = (cooldownMillis - timePassed) / 1000;
                    player.sendMessage(Component.text("Poczekaj jeszcze " + timeLeft + "s przed następnym użyciem!",
                            NamedTextColor.RED));
                    return;
                }
            }

            // Always update timestamp after successful use
            cooldowns.put(playerId, currentTime);
        }

        if (!consumeOneFireCharge(player)) {
            player.sendMessage(Component.text("Potrzebujesz Fire Charge jako amunicji!", NamedTextColor.RED));
            return;
        }

        spawnCustomFireball(player);
    }

    /**
     * Checks if the player is interacting with a container block.
     * Extracted to reduce cognitive complexity of onUseFireCharge.
     */
    private boolean isContainerInteraction(Action action, Block clickedBlock) {
        if (action != Action.RIGHT_CLICK_BLOCK) {
            return false;
        }
        if (clickedBlock == null) {
            return false;
        }
        return clickedBlock.getState() instanceof org.bukkit.inventory.InventoryHolder;
    }

    /**
     * Handles projectile collision to create custom explosion.
     * Event runs on region thread - safe to access entities/world directly.
     */
    @EventHandler(ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();

        // Filter to LargeFireball only
        if (!(projectile instanceof LargeFireball fireball)) {
            return;
        }

        PersistentDataContainer pdc = fireball.getPersistentDataContainer();
        NamespacedKey key = keys.customFireballKey();

        // Check if this is our custom fireball
        if (!pdc.has(key, PersistentDataType.BYTE)) {
            return; // Not our fireball
        }

        // Disable any default explosions on this fireball
        if (fireball instanceof Explosive explosive) {
            explosive.setYield(0.0f);
            explosive.setIsIncendiary(false);
        }

        Location loc = fireball.getLocation();
        World world = loc.getWorld();
        if (world == null) {
            fireball.remove();
            return;
        }

        // Remove fireball before creating explosion
        fireball.remove();

        // Read config values
        float power = (float) plugin.getConfig().getDouble("explosion-power", 4.0);
        boolean setFire = plugin.getConfig().getBoolean("set-fire", true);
        boolean breakBlocks = plugin.getConfig().getBoolean("break-blocks", false);

        // Create custom explosion with config values:
        // - setFire: configurable (default: true)
        // - breakBlocks: configurable (default: false)
        loc.createExplosion(power, setFire, breakBlocks);
    }

    /**
     * Optional: Prevent default explosion from occurring.
     * This ensures 100% control over explosion behavior.
     * Event runs on region thread - safe to access entities directly.
     */
    @EventHandler(ignoreCancelled = true)
    public void onExplosionPrime(ExplosionPrimeEvent event) {
        if (!(event.getEntity() instanceof Explosive)) {
            return;
        }

        if (!(event.getEntity() instanceof LargeFireball fireball)) {
            return;
        }

        PersistentDataContainer pdc = fireball.getPersistentDataContainer();
        if (!pdc.has(keys.customFireballKey(), PersistentDataType.BYTE)) {
            return;
        }

        // Cancel default explosion - we handle it in ProjectileHitEvent
        event.setCancelled(true);
    }

    /**
     * Remove cooldown when player quits to prevent memory accumulation.
     * Event runs on region thread - safe to modify ConcurrentHashMap.
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        cooldowns.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Clean up all cooldowns.
     * MUST be called in onDisable() to prevent memory leaks on plugin reload.
     * 
     * FOLIA SAFETY: This is called during plugin disable, safe to clear all data.
     */
    public void cleanup() {
        int size = cooldowns.size();
        cooldowns.clear();
        // S2629: Use built-in formatting instead of string concatenation
        plugin.getLogger().log(Level.INFO, "Cooldowns cleared: {0} entries removed", size);
    }

    /**
     * Remove expired cooldowns to prevent map from growing indefinitely.
     * Call this periodically if needed, but PlayerQuitEvent usually handles
     * cleanup.
     * 
     * FOLIA SAFETY: ConcurrentHashMap iterator is weakly consistent and
     * thread-safe.
     */
    public void removeExpiredCooldowns() {
        int cooldownSeconds = plugin.getConfig().getInt(COOLDOWN_SECONDS_CONFIG, 3);
        if (cooldownSeconds <= 0) {
            return; // No cooldowns enabled
        }

        long currentTime = System.currentTimeMillis();
        long cooldownMillis = cooldownSeconds * 1000L;
        int removed = 0;

        // ConcurrentHashMap iterator is safe for concurrent modifications
        Iterator<Map.Entry<UUID, Long>> iterator = cooldowns.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Long> entry = iterator.next();
            if (currentTime - entry.getValue() > cooldownMillis) {
                iterator.remove();
                removed++;
            }
        }

        if (removed > 0) {
            // S2629: Use built-in formatting instead of string concatenation
            plugin.getLogger().log(Level.FINE, "Removed {0} expired cooldown entries", removed);
        }
    }

    private boolean consumeOneFireCharge(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() == Material.FIRE_CHARGE) {
            int amount = hand.getAmount();
            if (amount <= 1) {
                player.getInventory().setItemInMainHand(null);
            } else {
                hand.setAmount(amount - 1);
                player.getInventory().setItemInMainHand(hand);
            }
            return true;
        }

        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType() != Material.FIRE_CHARGE) {
                continue;
            }
            int amount = stack.getAmount();
            if (amount <= 1) {
                player.getInventory().setItem(i, null);
            } else {
                stack.setAmount(amount - 1);
                player.getInventory().setItem(i, stack);
            }
            return true;
        }
        return false;
    }

    /**
     * Spawns a custom fireball with TTL (Time To Live) protection.
     * Uses EntityScheduler to automatically remove the fireball after
     * max-flight-ticks.
     * This prevents fireballs from flying infinitely if they don't hit anything.
     * 
     * FOLIA SAFETY: EntityScheduler always runs on the correct region thread for
     * the entity.
     * 
     * @param player The player shooting the fireball
     */
    private void spawnCustomFireball(Player player) {
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();

        // Offset spawn position in front of player to avoid self-damage
        eye.add(direction.multiply(SPAWN_OFFSET));

        World world = player.getWorld();
        world.spawn(eye, LargeFireball.class, fb -> {
            fb.setShooter(player); // Assign shooter for damage/knockback attribution
            fb.setIsIncendiary(false); // Fireball itself won't ignite blocks
            fb.setYield(0.0f); // Disable default explosion
            fb.setVelocity(direction.multiply(VELOCITY_MULTIPLIER));

            // Tag fireball using PersistentDataContainer
            PersistentDataContainer pdc = fb.getPersistentDataContainer();
            NamespacedKey key = keys.customFireballKey();
            pdc.set(key, PersistentDataType.BYTE, (byte) 1);

            // TTL protection - auto-remove after max flight time
            int maxFlightTicks = plugin.getConfig().getInt(MAX_FLIGHT_TICKS_CONFIG, 80);
            if (maxFlightTicks > 0) {
                // EntityScheduler is Folia-safe: always runs on entity's region thread
                fb.getScheduler().runDelayed(plugin, task -> {
                    // Double-check: only remove if still alive and still our custom fireball
                    if (!fb.isDead() && fb.isValid() && pdc.has(key, PersistentDataType.BYTE)) {
                        fb.remove();
                    }
                }, null, maxFlightTicks);
            }
        });
    }

    public void attemptFire(Player player) {
        int cooldownSeconds = plugin.getConfig().getInt(COOLDOWN_SECONDS_CONFIG, 3);
        if (cooldownSeconds > 0) {
            UUID playerId = player.getUniqueId();
            long currentTime = System.currentTimeMillis();
            Long lastUse = cooldowns.get(playerId);
            if (lastUse != null) {
                long timePassed = currentTime - lastUse;
                long cooldownMillis = cooldownSeconds * 1000L;
                if (timePassed < cooldownMillis) {
                    long timeLeft = (cooldownMillis - timePassed) / 1000;
                    player.sendMessage(Component.text("Poczekaj jeszcze " + timeLeft + "s przed następnym użyciem!",
                            NamedTextColor.RED));
                    return;
                }
            }
            cooldowns.put(playerId, currentTime);
        }

        if (!consumeOneFireCharge(player)) {
            player.sendMessage(Component.text("Potrzebujesz Fire Charge jako amunicji!", NamedTextColor.RED));
            return;
        }

        spawnCustomFireball(player);
    }
}
