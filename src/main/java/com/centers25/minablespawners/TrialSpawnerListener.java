package com.centers25.minablespawners;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Effect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TrialSpawner;
import org.bukkit.block.data.BlockData;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.spawner.TrialSpawnerConfiguration;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

public final class TrialSpawnerListener implements Listener {

    private static final long RESTORE_DELAY_TICKS = 2L;
    private static final int DEFAULT_COOLDOWN_TICKS = 36000;

    private final TrialMinerPlugin plugin;
    private final NamespacedKey idKey;
    private final NamespacedKey cooldownRemainingKey;
    private final NamespacedKey cooldownLengthKey;

    private final Map<UUID, TrialSpawner> stateCache = new ConcurrentHashMap<>();

    public TrialSpawnerListener(TrialMinerPlugin plugin) {
        this.plugin = plugin;
        this.idKey = new NamespacedKey(plugin, "spawner_state_id");
        this.cooldownRemainingKey = new NamespacedKey(plugin, "spawner_cooldown_remaining");
        this.cooldownLengthKey = new NamespacedKey(plugin, "spawner_cooldown_length");
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onLeftClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.TRIAL_SPAWNER) {
            return;
        }

        Player player = event.getPlayer();
        if (!mayMine(player)) {
            return;
        }

        if (!isSafeToMine(block)) {
            event.setCancelled(true);
            player.sendMessage(Component.text("[Mineable Spawners] ", NamedTextColor.DARK_PURPLE)
                    .append(Component.text("This spawner cannot be mined while its trial is active.", NamedTextColor.GRAY)));
            return;
        }

        BlockState state = block.getState();
        if (!(state instanceof TrialSpawner spawnerState)) {
            return;
        }

        event.setCancelled(true);

        World world = block.getWorld();

        long now = world.getGameTime();
        long cooldownEnd = 0L;
        try {
            cooldownEnd = spawnerState.getCooldownEnd();
        } catch (Throwable ignored) {
        }
        long cooldownRemaining = Math.max(0L, cooldownEnd - now);

        int cooldownLength = DEFAULT_COOLDOWN_TICKS;
        try {
            int len = spawnerState.getCooldownLength();
            if (len > 0) {
                cooldownLength = len;
            }
        } catch (Throwable ignored) {
        }

        UUID id = UUID.randomUUID();
        stateCache.put(id, spawnerState);

        ItemStack drop = buildSpawnerItem(spawnerState, id, cooldownRemaining, cooldownLength);

        Location center = block.getLocation().add(0.5, 0.5, 0.5);

        if (plugin.getConfig().getBoolean("break-effect", true)) {
            world.playEffect(block.getLocation(), Effect.STEP_SOUND, block.getType());
        }

        block.setType(Material.AIR, false);

        boolean creative = player.getGameMode() == GameMode.CREATIVE;
        if (!creative || plugin.getConfig().getBoolean("drop-in-creative", true)) {
            world.dropItemNaturally(center, drop);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (block.getType() != Material.TRIAL_SPAWNER) {
            return;
        }

        ItemStack item = event.getItemInHand();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }

        var pdc = meta.getPersistentDataContainer();
        String idStr = pdc.get(idKey, PersistentDataType.STRING);
        Long remaining = pdc.get(cooldownRemainingKey, PersistentDataType.LONG);

        if (idStr == null && remaining == null) {
            return;
        }

        TrialSpawner source = null;
        if (idStr != null) {
            try {
                source = stateCache.get(UUID.fromString(idStr));
            } catch (IllegalArgumentException ex) {
                source = null;
            }
        }

        if (source == null && meta instanceof BlockStateMeta blockStateMeta
                && blockStateMeta.hasBlockState()
                && blockStateMeta.getBlockState() instanceof TrialSpawner itemState) {
            source = itemState;
        }

        Long storedLength = pdc.get(cooldownLengthKey, PersistentDataType.LONG);

        final TrialSpawner src = source;
        final long cooldownRemaining = remaining != null ? remaining : 0L;
        final int cooldownLength = storedLength != null && storedLength > 0
                ? (int) Math.min(storedLength, Integer.MAX_VALUE)
                : DEFAULT_COOLDOWN_TICKS;
        final Location loc = block.getLocation();
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> restoreState(loc, src, cooldownRemaining, cooldownLength), RESTORE_DELAY_TICKS);
    }

    private void restoreState(Location loc, TrialSpawner stored, long cooldownRemaining, int cooldownLength) {
        Block block = loc.getBlock();
        if (block.getType() != Material.TRIAL_SPAWNER) {
            return;
        }

        boolean preserveOminous = plugin.getConfig().getBoolean("preserve-ominous", true);

        BlockData data = block.getBlockData();
        boolean ominous;
        if (stored != null) {
            ominous = stored.isOminous();
        } else if (data instanceof org.bukkit.block.data.type.TrialSpawner sd) {
            ominous = sd.isOminous();
        } else {
            ominous = false;
        }

        boolean onCooldown = cooldownRemaining > 0L;
        if (data instanceof org.bukkit.block.data.type.TrialSpawner spawnerData) {
            if (preserveOminous && ominous) {
                spawnerData.setOminous(true);
            }
            if (onCooldown) {
                spawnerData.setTrialSpawnerState(org.bukkit.block.data.type.TrialSpawner.State.COOLDOWN);
            }
            block.setBlockData(spawnerData, false);
        }

        if (!(block.getState() instanceof TrialSpawner live)) {
            return;
        }

        if (stored != null) {
            copyConfiguration(stored.getNormalConfiguration(), live.getNormalConfiguration());
            copyConfiguration(stored.getOminousConfiguration(), live.getOminousConfiguration());
            trySet(() -> live.setRequiredPlayerRange(stored.getRequiredPlayerRange()));
        }

        final int length = cooldownLength > 0 ? cooldownLength : DEFAULT_COOLDOWN_TICKS;
        trySet(() -> live.setCooldownLength(length));
        if (onCooldown) {
            final long cooldownEnd = loc.getWorld().getGameTime() + cooldownRemaining;
            trySet(() -> live.setCooldownEnd(cooldownEnd));
        }
        trySet(() -> live.setNextSpawnAttempt(0));

        if (preserveOminous) {
            trySet(() -> live.setOminous(ominous));
        }

        live.update(true, false);
    }

    private void copyConfiguration(TrialSpawnerConfiguration from, TrialSpawnerConfiguration to) {
        if (from == null || to == null) {
            return;
        }
        trySet(() -> to.setBaseSpawnsBeforeCooldown(from.getBaseSpawnsBeforeCooldown()));
        trySet(() -> to.setAdditionalSpawnsBeforeCooldown(from.getAdditionalSpawnsBeforeCooldown()));
        trySet(() -> to.setBaseSimultaneousEntities(from.getBaseSimultaneousEntities()));
        trySet(() -> to.setAdditionalSimultaneousEntities(from.getAdditionalSimultaneousEntities()));
        trySet(() -> to.setSpawnRange(from.getSpawnRange()));
        trySet(() -> {
            var rewards = from.getPossibleRewards();
            if (rewards != null && !rewards.isEmpty()) {
                to.setPossibleRewards(rewards);
            }
        });
        trySet(() -> {
            var spawns = from.getPotentialSpawns();
            if (spawns != null && !spawns.isEmpty()) {
                to.setPotentialSpawns(spawns);
            }
        });
        trySet(() -> {
            var entity = from.getSpawnedEntity();
            if (entity != null) {
                to.setSpawnedEntity(entity);
            } else if (from.getSpawnedType() != null) {
                to.setSpawnedType(from.getSpawnedType());
            }
        });
    }

    private ItemStack buildSpawnerItem(TrialSpawner state, UUID id, long cooldownRemaining, int cooldownLength) {
        ItemStack drop = new ItemStack(Material.TRIAL_SPAWNER);
        ItemMeta meta = drop.getItemMeta();
        if (meta == null) {
            return drop;
        }
        if (meta instanceof BlockStateMeta blockStateMeta) {
            blockStateMeta.setBlockState(state);
        }
        var pdc = meta.getPersistentDataContainer();
        pdc.set(idKey, PersistentDataType.STRING, id.toString());
        pdc.set(cooldownRemainingKey, PersistentDataType.LONG, cooldownRemaining);
        pdc.set(cooldownLengthKey, PersistentDataType.LONG, (long) cooldownLength);

        boolean ominous = false;
        try {
            ominous = state.isOminous();
        } catch (Throwable ignored) {
        }

        List<Component> lore = new ArrayList<>();
        EntityType mob = getSpawnedType(state, ominous);
        String mobName = mob != null ? prettify(mob.name()) : "Unknown";
        String itemName = mob == null ? "Trial Spawner" : mobName + " Trial Spawner";
        meta.displayName(itemText(itemName, NamedTextColor.LIGHT_PURPLE));
        lore.add(itemField("Mob", mobName, NamedTextColor.WHITE));
        lore.add(itemField("Variant", ominous ? "Ominous" : "Standard",
                ominous ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.WHITE));
        meta.lore(lore);

        drop.setItemMeta(meta);
        return drop;
    }

    private static Component itemText(String value, NamedTextColor color) {
        return Component.text(value, color).decoration(TextDecoration.ITALIC, false);
    }

    private static Component itemField(String label, String value, NamedTextColor valueColor) {
        return itemText("• ", NamedTextColor.DARK_GRAY)
                .append(itemText(label + ": ", NamedTextColor.GRAY))
                .append(itemText(value, valueColor));
    }

    private EntityType getSpawnedType(TrialSpawner state, boolean ominous) {
        try {
            TrialSpawnerConfiguration config = ominous
                    ? state.getOminousConfiguration()
                    : state.getNormalConfiguration();
            if (config == null) {
                return null;
            }
            var entity = config.getSpawnedEntity();
            if (entity != null) {
                return entity.getEntityType();
            }
            return config.getSpawnedType();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String prettify(String raw) {
        String[] parts = raw.toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    private boolean mayMine(Player player) {
        if (plugin.getConfig().getBoolean("require-permission", true)
                && !player.hasPermission("mineablespawners.mine")) {
            return false;
        }
        if (plugin.getConfig().getBoolean("require-silk-touch", true)
                && !hasSilkTouch(player.getInventory().getItemInMainHand())) {
            return false;
        }
        return true;
    }

    private boolean hasSilkTouch(ItemStack tool) {
        if (tool == null || tool.getType().isAir()) {
            return false;
        }
        for (Enchantment enchantment : tool.getEnchantments().keySet()) {
            if (enchantment.getKey().getKey().equals("silk_touch")) {
                return true;
            }
        }
        return false;
    }

    private boolean isSafeToMine(Block block) {
        BlockData data = block.getBlockData();
        if (!(data instanceof org.bukkit.block.data.type.TrialSpawner spawnerData)) {
            return false;
        }

        org.bukkit.block.data.type.TrialSpawner.State state = spawnerData.getTrialSpawnerState();
        return state == org.bukkit.block.data.type.TrialSpawner.State.INACTIVE
                || state == org.bukkit.block.data.type.TrialSpawner.State.COOLDOWN;
    }

    private void trySet(Runnable setter) {
        try {
            setter.run();
        } catch (Throwable ignored) {
        }
    }
}
