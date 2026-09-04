package com.centers25.minablespawners;

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

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(new TrialSpawnerListener(this), this);
        getLogger().info("TrialMiner enabled. Trial spawners can now be mined with their state preserved.");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!command.getName().equalsIgnoreCase("trialminer")) {
            return false;
        }
        if (args.length == 0) {
            if (sender.hasPermission("trialminer.admin")) {
                sender.sendMessage("§e/trialminer reload §7- reload config");
                sender.sendMessage("§e/trialminer give §7- give yourself a trial spawner");
                sender.sendMessage("§e/trialminer debug §7- dump the targeted spawner's state");
            } else {
                sender.sendMessage("§7TrialMiner: mine trial spawners with Silk Touch.");
            }
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                if (!sender.hasPermission("trialminer.admin")) {
                    sender.sendMessage("§cYou don't have permission to do that.");
                    return true;
                }
                reloadConfig();
                sender.sendMessage("§aTrialMiner config reloaded.");
                return true;
            }
            case "give" -> {
                if (!sender.hasPermission("trialminer.admin")) {
                    sender.sendMessage("§cYou don't have permission to do that.");
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cOnly players can use /trialminer give.");
                    return true;
                }
                player.getInventory().addItem(new ItemStack(Material.TRIAL_SPAWNER));
                player.sendMessage("§aGave you a trial spawner. Place it, configure it, mine it back.");
                return true;
            }
            case "debug" -> {
                if (!sender.hasPermission("trialminer.admin")) {
                    sender.sendMessage("§cYou don't have permission to do that.");
                    return true;
                }
                dumpSpawner(sender, args);
                return true;
            }
            default -> {
                sender.sendMessage("§cUnknown subcommand. Use /trialminer for help.");
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
                sender.sendMessage("§cUsage: /trialminer debug [<x> <y> <z>]");
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
            sender.sendMessage("§cFrom console, use: /trialminer debug <x> <y> <z>");
            return;
        }

        if (block == null || block.getType() != Material.TRIAL_SPAWNER) {
            out(sender, "§cNo trial spawner there. Look at/stand on one, or pass coords.");
            return;
        }

        long gameTime = block.getWorld().getGameTime();
        out(sender, "§6=== TrialMiner debug ===");
        out(sender, "§7Location: §f" + block.getX() + ", " + block.getY() + ", " + block.getZ()
                + " §7(" + block.getWorld().getName() + ")");
        out(sender, "§7World game-time: §f" + gameTime);

        BlockData data = block.getBlockData();
        if (data instanceof org.bukkit.block.data.type.TrialSpawner sd) {
            out(sender, "§7BlockData state: §f" + sd.getTrialSpawnerState()
                    + " §7| ominous(data): §f" + sd.isOminous());
        } else {
            out(sender, "§7BlockData: §cnot a TrialSpawner block data");
        }

        BlockState state = block.getState();
        if (!(state instanceof TrialSpawner live)) {
            out(sender, "§cBlockState is not a TrialSpawner (no block entity?).");
            return;
        }

        out(sender, "ominous(live): " + safe(live::isOminous));
        long cooldownEnd = safeLong(live::getCooldownEnd);
        out(sender, "cooldownEnd: " + cooldownEnd
                + (cooldownEnd > 0 ? " (" + (cooldownEnd - gameTime) + " ticks left)" : ""));
        out(sender, "cooldownLength: " + safeLong(live::getCooldownLength));
        out(sender, "nextSpawnAttempt: " + safeLong(live::getNextSpawnAttempt));
        out(sender, "requiredPlayerRange: " + safeLong(live::getRequiredPlayerRange));
        out(sender, "trackedPlayers: " + safe(() -> live.getTrackedPlayers().size()));
        out(sender, "trackedEntities: " + safe(() -> live.getTrackedEntities().size()));

        dumpConfig(sender, "normal", safe(live::getNormalConfiguration));
        dumpConfig(sender, "ominous", safe(live::getOminousConfiguration));
    }

    private void dumpConfig(CommandSender sender, String label, Object cfgObj) {
        if (!(cfgObj instanceof TrialSpawnerConfiguration cfg)) {
            out(sender, "[" + label + "] unavailable");
            return;
        }
        out(sender, "[" + label + " config]");
        out(sender, "  spawnedType: " + safe(() -> cfg.getSpawnedType()));
        out(sender, "  spawnedEntity: " + safe(() -> cfg.getSpawnedEntity() != null ? "set" : "null"));
        out(sender, "  potentialSpawns: " + safe(() -> {
            var s = cfg.getPotentialSpawns();
            return s == null ? "null" : String.valueOf(s.size());
        }));
        out(sender, "  possibleRewards: " + safe(() -> {
            var r = cfg.getPossibleRewards();
            return r == null ? "null" : String.valueOf(r.size());
        }));
        out(sender, "  baseSpawns: " + safe(cfg::getBaseSpawnsBeforeCooldown));
        out(sender, "  simultaneous: " + safe(cfg::getBaseSimultaneousEntities));
        out(sender, "  spawnRange: " + safe(cfg::getSpawnRange));
    }

    private void out(CommandSender sender, String msg) {
        if (sender instanceof Player) {
            sender.sendMessage(msg);
        }
        getLogger().info("[debug] " + org.bukkit.ChatColor.stripColor(msg));
    }

    private Object safe(java.util.concurrent.Callable<?> getter) {
        try {
            Object v = getter.call();
            return v == null ? "null" : v;
        } catch (Throwable t) {
            return "§c<err: " + t.getClass().getSimpleName() + ">";
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
}
