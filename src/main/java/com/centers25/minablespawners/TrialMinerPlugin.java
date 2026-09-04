package com.centers25.minablespawners;

import com.centers25.core.logging.PluginLogger;
import com.centers25.core.logging.PluginLogs;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TrialSpawner;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.spawner.TrialSpawnerConfiguration;
import org.jetbrains.annotations.NotNull;

public final class TrialMinerPlugin extends JavaPlugin {
    private PluginLogger log;

    @Override
    public void onEnable() {
        log = PluginLogs.get(this);
        saveDefaultConfig();
        TrialSpawnerListener listener = new TrialSpawnerListener(this);
        getServer().getPluginManager().registerEvents(listener, this);
        getServer().getScheduler().runTask(this, listener::migrateLoadedItems);
        log.info("Mineable Spawners enabled. Trial spawner state will be preserved.");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!command.getName().equalsIgnoreCase("mineablespawners")) {
            return false;
        }
        if (args.length == 0) {
            if (sender.hasPermission("mineablespawners.admin")) {
                sender.sendMessage("§5[Mineable Spawners] §fCommands");
                sender.sendMessage("§8• §d/mineablespawners reload §7— Reload configuration");
                sender.sendMessage("§8• §d/mineablespawners give §7— Receive a trial spawner");
                sender.sendMessage("§8• §d/mineablespawners debug §7— View targeted spawner data");
            } else {
                sender.sendMessage("§5[Mineable Spawners] §7Mine trial spawners with Silk Touch.");
            }
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                if (!sender.hasPermission("mineablespawners.admin")) {
                    sender.sendMessage("§5[Mineable Spawners] §7Administrator permission is required.");
                    return true;
                }
                reloadConfig();
                sender.sendMessage("§5[Mineable Spawners] §dConfiguration reloaded.");
                return true;
            }
            case "give" -> {
                if (!sender.hasPermission("mineablespawners.admin")) {
                    sender.sendMessage("§5[Mineable Spawners] §7Administrator permission is required.");
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§5[Mineable Spawners] §7This command can only be used by a player.");
                    return true;
                }
                player.getInventory().addItem(new ItemStack(Material.TRIAL_SPAWNER));
                player.sendMessage("§5[Mineable Spawners] §dTrial spawner added to your inventory.");
                return true;
            }
            case "debug" -> {
                if (!sender.hasPermission("mineablespawners.admin")) {
                    sender.sendMessage("§5[Mineable Spawners] §7Administrator permission is required.");
                    return true;
                }
                dumpSpawner(sender, args);
                return true;
            }
            default -> {
                sender.sendMessage("§5[Mineable Spawners] §7Unknown command. Use /mineablespawners for help.");
                return true;
            }
        }
    }

    private void dumpSpawner(CommandSender sender, String[] args) {
        Block block = null;
        if (args.length >= 4) {
            try {
                int x = Integer.parseInt(args[1]);
                int y = Integer.parseInt(args[2]);
                int z = Integer.parseInt(args[3]);
                org.bukkit.World world = sender instanceof Player p
                        ? p.getWorld()
                        : getServer().getWorlds().get(0);
                block = world.getBlockAt(x, y, z);
            } catch (NumberFormatException ex) {
                sender.sendMessage("§5[Mineable Spawners] §7Usage: /mineablespawners debug [<x> <y> <z>]");
                return;
            }
        } else if (sender instanceof Player player) {
            block = player.getTargetBlockExact(10);
            if (block == null || block.getType() != Material.TRIAL_SPAWNER) {
                Block below = player.getLocation().getBlock().getRelative(0, -1, 0);
                if (below.getType() == Material.TRIAL_SPAWNER) {
                    block = below;
                }
            }
        } else {
            sender.sendMessage("§5[Mineable Spawners] §7Console usage: /mineablespawners debug <x> <y> <z>");
            return;
        }

        if (block == null || block.getType() != Material.TRIAL_SPAWNER) {
            out(sender, "§5[Mineable Spawners] §7No trial spawner was found at the selected location.");
            return;
        }

        long gameTime = block.getWorld().getGameTime();
        out(sender, "§5[Mineable Spawners] §fDebug information");
        out(sender, "§7Location: §f" + block.getX() + ", " + block.getY() + ", " + block.getZ()
                + " §7(" + block.getWorld().getName() + ")");
        out(sender, "§7World time: §f" + gameTime);

        BlockData data = block.getBlockData();
        if (data instanceof org.bukkit.block.data.type.TrialSpawner sd) {
            out(sender, "§7State: §f" + sd.getTrialSpawnerState()
                    + " §8• §7Ominous data: §f" + sd.isOminous());
        } else {
            out(sender, "§7Block data: §fUnavailable");
        }

        BlockState state = block.getState();
        if (!(state instanceof TrialSpawner live)) {
            out(sender, "§7Block entity: §fUnavailable");
            return;
        }

        out(sender, "§8• §7Ominous: §f" + safe(live::isOminous));
        long cooldownEnd = safeLong(live::getCooldownEnd);
        out(sender, "§8• §7Cooldown end: §f" + cooldownEnd
                + (cooldownEnd > 0 ? " (" + (cooldownEnd - gameTime) + " ticks left)" : ""));
        out(sender, "§8• §7Cooldown length: §f" + safeLong(live::getCooldownLength));
        out(sender, "§8• §7Next spawn attempt: §f" + safeLong(live::getNextSpawnAttempt));
        out(sender, "§8• §7Required player range: §f" + safeLong(live::getRequiredPlayerRange));
        out(sender, "§8• §7Tracked players: §f" + safe(() -> live.getTrackedPlayers().size()));
        out(sender, "§8• §7Tracked entities: §f" + safe(() -> live.getTrackedEntities().size()));

        dumpConfig(sender, "normal", safe(live::getNormalConfiguration));
        dumpConfig(sender, "ominous", safe(live::getOminousConfiguration));
    }

    private void dumpConfig(CommandSender sender, String label, Object cfgObj) {
        if (!(cfgObj instanceof TrialSpawnerConfiguration cfg)) {
            out(sender, "§8[" + title(label) + " configuration] §7Unavailable");
            return;
        }
        out(sender, "§8[" + title(label) + " configuration]");
        out(sender, "§8• §7Spawned type: §f" + safe(() -> cfg.getSpawnedType()));
        out(sender, "§8• §7Spawned entity: §f" + safe(() -> cfg.getSpawnedEntity() != null ? "Set" : "None"));
        out(sender, "§8• §7Potential spawns: §f" + safe(() -> {
            var s = cfg.getPotentialSpawns();
            return s == null ? "None" : String.valueOf(s.size());
        }));
        out(sender, "§8• §7Possible rewards: §f" + safe(() -> {
            var r = cfg.getPossibleRewards();
            return r == null ? "None" : String.valueOf(r.size());
        }));
        out(sender, "§8• §7Base spawns: §f" + safe(cfg::getBaseSpawnsBeforeCooldown));
        out(sender, "§8• §7Simultaneous entities: §f" + safe(cfg::getBaseSimultaneousEntities));
        out(sender, "§8• §7Spawn range: §f" + safe(cfg::getSpawnRange));
    }

    private String title(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private void out(CommandSender sender, String msg) {
        sender.sendMessage(msg);
        log.debug(org.bukkit.ChatColor.stripColor(msg));
    }

    private Object safe(java.util.concurrent.Callable<?> getter) {
        try {
            Object v = getter.call();
            return v == null ? "None" : v;
        } catch (Throwable t) {
            return "Unavailable (" + t.getClass().getSimpleName() + ")";
        }
    }

    private long safeLong(java.util.concurrent.Callable<? extends Number> getter) {
        try {
            Number v = getter.call();
            return v == null ? -1L : v.longValue();
        } catch (Throwable t) {
            return -1L;
        }
    }

    PluginLogger log() {
        return log;
    }
}
