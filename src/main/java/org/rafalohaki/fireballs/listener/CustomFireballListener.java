package org.rafalohaki.fireballs.listener;

import org.bukkit.block.Block;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.entity.Item;
import org.bukkit.inventory.Inventory;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;

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

    // Cooldown time in milliseconds (cached from config)
    private volatile long cooldownMillis;

    // Whether to rename Fire Charge items to custom name
    private volatile boolean renameEnabled;

    // Custom name for Fire Charge items (parsed from MiniMessage)
    // S3077 suppressed: Component is immutable, volatile ensures visibility of reference assignment
    @SuppressWarnings("java:S3077")
    private volatile Component customFireballName;

    // Cached explosion config values (read once, not on every explosion)
    private volatile float explosionPower;
    private volatile boolean explosionSetFire;
    private volatile boolean explosionBreakBlocks;
    private volatile int maxFlightTicks;

    // Cached NamespacedKey for performance (avoid method call overhead)
    private NamespacedKey cachedFireballKey;

    // Default name if config parsing fails
    private static final Component DEFAULT_FIREBALL_NAME = Component.text("Fireball", NamedTextColor.GOLD)
            .decoration(TextDecoration.ITALIC, false);

    public CustomFireballListener(Plugin plugin) {
        this.plugin = plugin;
        this.keys = new Keys(plugin);
        this.cachedFireballKey = keys.customFireballKey();
        this.cooldowns = new ConcurrentHashMap<>();
        loadConfigValues();
    }

    /**
     * Loads config values into cached fields.
     */
    private void loadConfigValues() {
        this.cooldownMillis = plugin.getConfig().getInt(COOLDOWN_SECONDS_CONFIG, 3) * 1000L;
        this.renameEnabled = plugin.getConfig().getBoolean("rename-fire-charge", true);
        
        // Cache explosion and flight settings
        this.explosionPower = (float) plugin.getConfig().getDouble("explosion-power", 4.0);
        this.explosionSetFire = plugin.getConfig().getBoolean("set-fire", true);
        this.explosionBreakBlocks = plugin.getConfig().getBoolean("break-blocks", false);
        this.maxFlightTicks = plugin.getConfig().getInt(MAX_FLIGHT_TICKS_CONFIG, 80);
        
        String customNameStr = plugin.getConfig().getString("custom-name", "<gold>Fireball</gold>");
        try {
            this.customFireballName = MiniMessage.miniMessage().deserialize(customNameStr)
                    .decoration(TextDecoration.ITALIC, false);
        } catch (Exception e) {
            plugin.getLogger().warning("Invalid custom-name in config, using default: " + e.getMessage());
            this.customFireballName = DEFAULT_FIREBALL_NAME;
        }
    }

    /**
     * Handles FIRE_CHARGE usage to spawn custom fireballs.
     * This is a fallback handler - primary handling is via PacketEvents USE_ITEM.
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

        // Check cooldown using atomic operation
        if (!tryAcquireCooldown(player)) {
            return;
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

        // Check if this is our custom fireball (using cached key)
        if (!pdc.has(cachedFireballKey, PersistentDataType.BYTE)) {
            return; // Not our fireball
        }

        // Disable any default explosions on this fireball
        // LargeFireball always implements Explosive
        fireball.setYield(0.0f);
        fireball.setIsIncendiary(false);

        Location loc = fireball.getLocation();
        World world = loc.getWorld();
        if (world == null) {
            fireball.remove();
            return;
        }

        // Remove fireball before creating explosion
        fireball.remove();

        // Use cached config values (no config reads per explosion)
        loc.createExplosion(explosionPower, explosionSetFire, explosionBreakBlocks);
    }

    /**
     * Optional: Prevent default explosion from occurring.
     * This ensures 100% control over explosion behavior.
     * Event runs on region thread - safe to access entities directly.
     */
    @EventHandler(ignoreCancelled = true)
    public void onExplosionPrime(ExplosionPrimeEvent event) {
        // LargeFireball always implements Explosive, no need to check separately
        if (!(event.getEntity() instanceof LargeFireball fireball)) {
            return;
        }

        PersistentDataContainer pdc = fireball.getPersistentDataContainer();
        if (!pdc.has(cachedFireballKey, PersistentDataType.BYTE)) {
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

    // ==================== FIRE CHARGE RENAMING ====================

    /**
     * Renames Fire Charge to custom name when crafted.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraftFireCharge(CraftItemEvent event) {
        if (!renameEnabled) {
            return;
        }

        ItemStack result = event.getRecipe().getResult();
        if (result.getType() != Material.FIRE_CHARGE) {
            return;
        }

        // Modify the result in the crafting inventory
        ItemStack current = event.getCurrentItem();
        if (current != null && current.getType() == Material.FIRE_CHARGE) {
            renameToFireball(current);
        }
    }

    /**
     * Renames Fire Charge to custom name when it spawns in the world (drops).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (!renameEnabled) {
            return;
        }

        Item item = event.getEntity();
        ItemStack stack = item.getItemStack();

        if (stack.getType() != Material.FIRE_CHARGE) {
            return;
        }

        // Only rename if it doesn't have a custom name already
        if (!stack.hasItemMeta() || !stack.getItemMeta().hasDisplayName()) {
            renameToFireball(stack);
            item.setItemStack(stack);
        }
    }

    /**
     * Renames Fire Charge to custom name when player interacts with inventory.
     * Catches items from chests, hoppers, trading, etc.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!renameEnabled) {
            return;
        }

        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();

        // Rename cursor item if it's an unnamed Fire Charge
        if (shouldRenameItem(cursor)) {
            renameToFireball(cursor);
        }

        // Rename clicked item if it's an unnamed Fire Charge
        if (shouldRenameItem(current)) {
            renameToFireball(current);
        }
    }

    /**
     * Renames all Fire Charges in player inventory when they join.
     * This catches items that existed before the plugin was installed.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!renameEnabled) {
            return;
        }

        Player player = event.getPlayer();
        renameAllFireChargesInInventory(player.getInventory());
    }

    /**
     * Renames all Fire Charges when a container (chest, hopper, etc.) is opened.
     * This catches items in chests that existed before the plugin was installed.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!renameEnabled) {
            return;
        }

        Inventory inventory = event.getInventory();
        renameAllFireChargesInInventory(inventory);
    }

    /**
     * Renames all Fire Charges in the given inventory.
     * 
     * @param inventory The inventory to scan and rename items in
     */
    private void renameAllFireChargesInInventory(Inventory inventory) {
        for (ItemStack item : inventory.getContents()) {
            if (shouldRenameItem(item)) {
                renameToFireball(item);
            }
        }
    }

    /**
     * Checks if an item should be renamed to Fireball.
     * Returns true if item is a Fire Charge without custom name.
     * Optimized to avoid double meta access.
     */
    private boolean shouldRenameItem(ItemStack item) {
        if (item == null || item.getType() != Material.FIRE_CHARGE) {
            return false;
        }
        // Optimized: single meta access instead of hasItemMeta() + getItemMeta()
        var meta = item.getItemMeta();
        return meta == null || !meta.hasDisplayName();
    }

    /**
     * Renames a Fire Charge ItemStack to the configured custom name.
     * 
     * @param item The ItemStack to rename
     */
    private void renameToFireball(ItemStack item) {
        if (item == null || item.getType() != Material.FIRE_CHARGE) {
            return;
        }
        Component name = customFireballName;
        item.editMeta(meta -> meta.displayName(name));
    }

    /**
     * Attempts to acquire cooldown for a player using atomic ConcurrentHashMap operation.
     * Returns true if player can fire, false if still on cooldown.
     * Thread-safe across Folia region threads.
     * 
     * @param player The player attempting to fire
     * @return true if cooldown acquired successfully, false if on cooldown
     */
    private boolean tryAcquireCooldown(Player player) {
        if (cooldownMillis <= 0) {
            return true; // No cooldown configured
        }

        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();

        // Atomic check-and-set using compute()
        // This prevents race conditions between get() and put()
        boolean[] canFire = {false};
        cooldowns.compute(playerId, (uuid, lastUse) -> {
            if (lastUse == null || (currentTime - lastUse) >= cooldownMillis) {
                canFire[0] = true;
                return currentTime; // Update timestamp
            }
            // Still on cooldown - keep old value
            long timeLeft = (cooldownMillis - (currentTime - lastUse)) / 1000;
            player.sendMessage(Component.text("Poczekaj jeszcze " + timeLeft + "s przed następnym użyciem!",
                    NamedTextColor.RED));
            return lastUse;
        });

        return canFire[0];
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
     * Reloads cached config values. Call after config reload.
     */
    public void reloadConfig() {
        loadConfigValues();
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
        if (cooldownMillis <= 0) {
            return; // No cooldowns enabled
        }

        long currentTime = System.currentTimeMillis();
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

        // Clone direction for offset calculation - Vector.multiply() modifies in-place!
        Vector offset = direction.clone().multiply(SPAWN_OFFSET);
        eye.add(offset);

        // Clone direction for velocity - original direction remains unmodified
        Vector velocity = direction.clone().multiply(VELOCITY_MULTIPLIER);

        World world = player.getWorld();
        world.spawn(eye, LargeFireball.class, fb -> {
            fb.setShooter(player); // Assign shooter for damage/knockback attribution
            fb.setIsIncendiary(false); // Fireball itself won't ignite blocks
            fb.setYield(0.0f); // Disable default explosion
            fb.setVelocity(velocity);

            // Tag fireball using PersistentDataContainer (using cached key)
            PersistentDataContainer pdc = fb.getPersistentDataContainer();
            pdc.set(cachedFireballKey, PersistentDataType.BYTE, (byte) 1);

            // TTL protection - auto-remove after max flight time (using cached value)
            int flightTicks = maxFlightTicks; // Use cached config value
            if (flightTicks > 0) {
                // EntityScheduler is Folia-safe: always runs on entity's region thread
                fb.getScheduler().runDelayed(plugin, task -> {
                    // Double-check: only remove if still alive and still our custom fireball
                    if (!fb.isDead() && fb.isValid() && pdc.has(cachedFireballKey, PersistentDataType.BYTE)) {
                        fb.remove();
                    }
                }, null, flightTicks);
            }
        });
    }

    /**
     * Attempts to fire a fireball from packet listener context.
     * Uses the same cooldown logic as event-based firing.
     * 
     * @param player The player attempting to fire
     */
    public void attemptFire(Player player) {
        // Use unified cooldown logic
        if (!tryAcquireCooldown(player)) {
            return;
        }

        if (!consumeOneFireCharge(player)) {
            player.sendMessage(Component.text("Potrzebujesz Fire Charge jako amunicji!", NamedTextColor.RED));
            return;
        }

        spawnCustomFireball(player);
    }
}
