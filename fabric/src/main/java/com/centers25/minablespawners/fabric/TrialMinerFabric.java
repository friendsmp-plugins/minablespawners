package com.centers25.minablespawners.fabric;

import java.util.List;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TrialSpawnerBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerState;
import net.minecraft.world.level.block.state.BlockState;

public final class TrialMinerFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        AttackBlockCallback.EVENT.register(this::onAttackBlock);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("trialminer")
                        .executes(context -> showHelp(context.getSource()))
                        .then(Commands.literal("give")
                                .executes(context -> giveSpawner(context.getSource())))
                        .then(Commands.literal("debug")
                                .executes(context -> debugSpawner(context.getSource())))));
    }

    private int showHelp(CommandSourceStack source) {
        source.sendSuccess(() -> message("Commands", ChatFormatting.WHITE), false);
        source.sendSuccess(() -> command("/trialminer give", "Receive a trial spawner"), false);
        source.sendSuccess(() -> command("/trialminer debug", "View spawner data"), false);
        return 1;
    }

    private int giveSpawner(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            player.getInventory().add(new ItemStack(Blocks.TRIAL_SPAWNER));
            source.sendSuccess(() -> message("Trial spawner added to your inventory.",
                    ChatFormatting.LIGHT_PURPLE), false);
            return 1;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) {
            source.sendFailure(message("This command can only be used by a player.", ChatFormatting.GRAY));
            return 0;
        }
    }

    private int debugSpawner(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            ServerLevel level = source.getLevel();
            BlockPos pos = player.blockPosition().below();
            BlockState state = level.getBlockState(pos);
            if (!state.is(Blocks.TRIAL_SPAWNER)) {
                source.sendFailure(message("Stand on a trial spawner to view its data.", ChatFormatting.GRAY));
                return 0;
            }

            source.sendSuccess(() -> message("Debug information", ChatFormatting.WHITE), false);
            source.sendSuccess(() -> detail("Location", pos.toShortString()), false);
            source.sendSuccess(() -> detail("State", state.getValue(TrialSpawnerBlock.STATE).toString()), false);
            source.sendSuccess(() -> detail("Ominous", String.valueOf(state.getValue(TrialSpawnerBlock.OMINOUS))), false);
            return 1;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) {
            source.sendFailure(message("This command can only be used by a player.", ChatFormatting.GRAY));
            return 0;
        }
    }

    private InteractionResult onAttackBlock(Player player, Level level, InteractionHand hand,
            BlockPos pos, net.minecraft.core.Direction direction) {
        BlockState state = level.getBlockState(pos);
        if (!state.is(Blocks.TRIAL_SPAWNER)) {
            return InteractionResult.PASS;
        }

        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        TrialSpawnerState trialState = state.getValue(TrialSpawnerBlock.STATE);
        if (trialState != TrialSpawnerState.INACTIVE && trialState != TrialSpawnerState.COOLDOWN) {
            if (player instanceof ServerPlayer serverPlayer) {
                serverPlayer.sendSystemMessage(message(
                        "This spawner cannot be mined while its trial is active.", ChatFormatting.GRAY));
            }
            return InteractionResult.FAIL;
        }

        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof TrialSpawnerBlockEntity spawner)
                || !hasSilkTouch(level, player.getItemInHand(hand))) {
            return InteractionResult.PASS;
        }

        ItemStack drop = new ItemStack(Blocks.TRIAL_SPAWNER);
        drop.set(DataComponents.BLOCK_ENTITY_DATA, TypedEntityData.of(
                BlockEntityType.TRIAL_SPAWNER,
                spawner.saveWithoutMetadata(level.registryAccess())));
        addSpawnerLore(drop, spawner, state);

        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        level.addFreshEntity(new ItemEntity(level, pos.getX() + 0.5D, pos.getY() + 0.5D,
                pos.getZ() + 0.5D, drop));
        return InteractionResult.SUCCESS;
    }

    private boolean hasSilkTouch(Level level, ItemStack tool) {
        return EnchantmentHelper.getItemEnchantmentLevel(
                level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                        .getOrThrow(Enchantments.SILK_TOUCH),
                tool) > 0;
    }

    private void addSpawnerLore(ItemStack item, TrialSpawnerBlockEntity spawner, BlockState state) {
        String mob = "Unknown";
        var potentials = spawner.getTrialSpawner().activeConfig().spawnPotentialsDefinition().unwrap();
        if (!potentials.isEmpty()) {
            String entityId = potentials.getFirst().value().getEntityToSpawn()
                    .getStringOr("id", "unknown");
            mob = prettifyEntityId(entityId);
        }

        boolean ominous = state.getValue(TrialSpawnerBlock.OMINOUS);
        item.set(DataComponents.CUSTOM_NAME, itemText("Trial Spawner", ChatFormatting.LIGHT_PURPLE));
        item.set(DataComponents.LORE, new ItemLore(List.of(
                itemText("Mob: ", ChatFormatting.GRAY)
                        .copy().append(itemText(mob, ChatFormatting.WHITE)),
                itemText("Type: ", ChatFormatting.GRAY)
                        .copy().append(itemText(ominous ? "Ominous" : "Normal",
                                ominous ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.GRAY)))));
    }

    private Component message(String value, ChatFormatting color) {
        return Component.literal("[Trial Miner] ").withStyle(ChatFormatting.DARK_PURPLE)
                .append(Component.literal(value).withStyle(color));
    }

    private Component command(String value, String description) {
        return Component.literal("• ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(value).withStyle(ChatFormatting.LIGHT_PURPLE))
                .append(Component.literal(" — " + description).withStyle(ChatFormatting.GRAY));
    }

    private Component detail(String label, String value) {
        return Component.literal("• ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(label + ": ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(value).withStyle(ChatFormatting.WHITE));
    }

    private Component itemText(String value, ChatFormatting color) {
        return Component.literal(value).withStyle(style -> style.withColor(color).withItalic(false));
    }

    private String prettifyEntityId(String entityId) {
        String path = entityId.contains(":") ? entityId.substring(entityId.indexOf(':') + 1) : entityId;
        StringBuilder result = new StringBuilder();
        for (String word : path.split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.isEmpty() ? "Unknown" : result.toString();
    }
}
