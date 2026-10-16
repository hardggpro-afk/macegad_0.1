package com.hardgg.maceevolution;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.component.ComponentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.MaceItem;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import com.mojang.serialization.Codec;
import net.minecraft.network.codec.PacketCodecs;
import static net.minecraft.server.command.CommandManager.literal;

public final class MaceEvolution implements ModInitializer {
    public static final String MOD_ID = "maceevolution";
    public static final Identifier MACE_ID = Identifier.of(MOD_ID, "worldbreaker_mace");
    public static final ComponentType<Integer> LEVEL = Registry.register(Registries.DATA_COMPONENT_TYPE,
            Identifier.of(MOD_ID, "level"), ComponentType.<Integer>builder().codec(Codec.intRange(1, 20)).packetCodec(PacketCodecs.VAR_INT).build());
    public static final ComponentType<Integer> KILLS = Registry.register(Registries.DATA_COMPONENT_TYPE,
            Identifier.of(MOD_ID, "kills"), ComponentType.<Integer>builder().codec(Codec.intRange(0, 999999)).packetCodec(PacketCodecs.VAR_INT).build());
    public static final Item WORLD_BREAKER = Registry.register(Registries.ITEM, MACE_ID,
            new MaceItem(new Item.Settings().registryKey(RegistryKey.of(RegistryKeys.ITEM, MACE_ID)).maxDamage(900).component(LEVEL, 1).component(KILLS, 0)));
    private static final String[] NAMES = {
        "Дерев'яний пробуджувач", "Кам'яний громовержець", "Залізний руйнівник", "Діамантова кара", "Обсидіановий колапс",
        "Аметистовий резонанс", "Золоте сонце", "Ендерний розлом", "Незеритова лють", "Серце дракона",
        "Буря стихій", "Пекельний руйнівник", "Грім небес", "Вічна мерзлота", "Титан землі",
        "Пожирач зірок", "Володар безодні", "Бог війни", "Передвісник кінця", "Кара Світів"
    };
    public static final int[] REQUIRED_KILLS = {1,1,1,1,1,2,2,2,2,2,3,3,3,3,4,5,6,9,12,0};
    public static int level(ItemStack stack) { return MathHelper.clamp(stack.getOrDefault(LEVEL, 1), 1, 20); }
    public static int kills(ItemStack stack) { return Math.max(0, stack.getOrDefault(KILLS, 0)); }

    @Override public void onInitialize() {
        // Keep the holder's buffs active while the mace is in either hand.
        ServerTickEvents.END_SERVER_TICK.register((MinecraftServer server) -> {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                ItemStack held = findMace(player);
                if (held.isEmpty()) continue;
                int lvl = level(held);
                if (lvl >= 20 && !player.getAbilities().allowFlying) {
                    player.getAbilities().allowFlying = true;
                    player.sendAbilitiesUpdate();
                }
                int amp = Math.min(4, (lvl - 1) / 5);
                player.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 45, amp, true, false, true));
                player.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 45, Math.min(3, (lvl - 1) / 7), true, false, true));
                if (lvl >= 4) player.addStatusEffect(new StatusEffectInstance(StatusEffects.HASTE, 45, Math.min(2, (lvl - 4) / 6), true, false, true));
                if (lvl >= 7) player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 45, Math.min(2, (lvl - 7) / 6), true, false, true));
                if (lvl >= 10) player.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 45, Math.min(2, (lvl - 10) / 5), true, false, true));
                if (lvl >= 15) player.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, 45, 0, true, false, true));
                applyLevelEnchantments(player, held, lvl);
            }
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((LivingEntity victim, DamageSource source) -> {
            if (!(victim instanceof ServerPlayerEntity dead)) return;
            if (!(source.getAttacker() instanceof ServerPlayerEntity killer)) return;
            ItemStack mace = findMace(killer);
            if (mace.isEmpty()) return;
            int level = level(mace);
            if (level >= 20) { applyFinalKillReward(killer, dead); return; }
            int count = kills(mace) + 1;
            if (count >= REQUIRED_KILLS[level - 1]) {
                level++;
                count = 0;
                mace.set(LEVEL, level);
                killer.sendMessage(Text.literal("§6✦ Булава еволюціонувала: §e" + NAMES[level - 1] + " §7[" + level + "/20]"), true);
                killer.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 100, Math.min(2, level / 7)));
                killer.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 200, Math.min(2, level / 8)));
                if (level == 20) killer.sendMessage(Text.literal("§c§lКАРА СВІТІВ ПРОБУДИЛАСЯ!"));
            }
            mace.set(KILLS, count);
            updateName(mace);
        });
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClient() || !(player instanceof ServerPlayerEntity attacker) || !(entity instanceof LivingEntity target)) return ActionResult.PASS;
            ItemStack mace = attacker.getStackInHand(hand);
            if (!isMace(mace)) return ActionResult.PASS;
            int level = level(mace);
            // Refresh enchantments on the exact item stack used for the hit.
            applyLevelEnchantments(attacker, mace, level);
            attacker.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 80, Math.min(4, (level - 1) / 5)));
            attacker.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 80, Math.min(3, (level - 1) / 7)));
            if (level >= 2) attacker.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 50, 0));
            if (level >= 3) attacker.addStatusEffect(new StatusEffectInstance(StatusEffects.HASTE, 60, 0));
            if (level >= 5) attacker.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 60, 0));
            if (level >= 6) target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 80, Math.min(4, (level - 6) / 3)));
            if (level >= 7) target.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 100, Math.min(2, (level - 7) / 5)));
            if (level >= 8) target.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 100, 0));
            if (level >= 9) target.setOnFireFor(3 + level / 3);
            if (level >= 10) target.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 35, 0));
            if (level >= 11) target.addStatusEffect(new StatusEffectInstance(StatusEffects.LEVITATION, 12, 0));
            if (level >= 12) {
                target.setOnFireFor(6);
                if (world instanceof ServerWorld serverWorld) for (int dx=-1; dx<=1; dx++) for (int dz=-1; dz<=1; dz++)
                    serverWorld.spawnParticles(ParticleTypes.FLAME, target.getX()+dx, target.getY()+0.2, target.getZ()+dz, 10, 0.12, 0.35, 0.12, 0.01);
            }
            if (level >= 13 && world instanceof ServerWorld serverWorld) {
                serverWorld.spawnParticles(ParticleTypes.ELECTRIC_SPARK, target.getX(), target.getY()+1, target.getZ(), 32, 0.8, 0.8, 0.8, 0.15);
                target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 70, 2)); target.setOnFireFor(4);
            }
            if (level >= 14) { target.setFrozenTicks(Math.max(target.getFrozenTicks(),240)); target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS,100,6)); target.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE,100,2)); }
            if (level >= 15) target.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS,120,2));
            if (level >= 16) attacker.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE,200,0));
            if (level >= 17) target.addStatusEffect(new StatusEffectInstance(StatusEffects.WITHER,100,1));
            if (level >= 18) { attacker.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION,200,2)); attacker.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE,100,1)); target.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS,80,0)); }
            if (level >= 19 && world instanceof ServerWorld serverWorld) { serverWorld.spawnParticles(ParticleTypes.PORTAL,target.getX(),target.getY()+1,target.getZ(),60,0.8,1,0.8,0.4); target.addStatusEffect(new StatusEffectInstance(StatusEffects.WITHER,160,2)); target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS,120,4)); }
            if (level >= 20) { target.addStatusEffect(new StatusEffectInstance(StatusEffects.WITHER,200,4)); target.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS,200,4)); }
            return ActionResult.PASS;
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(literal("maceevolution")
            .then(literal("give").requires(CommandManager.requirePermissionLevel(CommandManager.ADMINS_CHECK)).executes(context -> {
                ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                ItemStack stack = new ItemStack(WORLD_BREAKER);
                updateName(stack);
                if (!player.getInventory().insertStack(stack)) player.dropItem(stack, false);
                context.getSource().sendFeedback(() -> Text.literal("Видано Булаву Еволюції."), false);
                return 1;
            }))));
    }
    private static void applyLevelEnchantments(ServerPlayerEntity player, ItemStack stack, int level) {
        try {
            RegistryWrapper.Impl<Enchantment> registry = player.getEntityWorld().getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
            ItemEnchantmentsComponent current = stack.getOrDefault(DataComponentTypes.ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT);
            ItemEnchantmentsComponent.Builder builder = new ItemEnchantmentsComponent.Builder(current);
            setEnchantment(builder, registry, "density", Math.min(5, 1 + (level - 1) / 4));
            setEnchantment(builder, registry, "breach", level >= 8 ? Math.min(4, 1 + (level - 8) / 4) : 0);
            setEnchantment(builder, registry, "wind_burst", level >= 13 ? Math.min(3, 1 + (level - 13) / 3) : 0);
            setEnchantment(builder, registry, "unbreaking", Math.min(3, 1 + (level - 1) / 7));
            setEnchantment(builder, registry, "mending", level >= 6 ? 1 : 0);
            setEnchantment(builder, registry, "fire_aspect", level >= 9 ? Math.min(2, 1 + (level - 9) / 6) : 0);
            setEnchantment(builder, registry, "looting", level >= 11 ? Math.min(3, 1 + (level - 11) / 4) : 0);
            ItemEnchantmentsComponent updated = builder.build();
            if (!updated.equals(current)) stack.set(DataComponentTypes.ENCHANTMENTS, updated);
        } catch (RuntimeException ignored) {
            // A missing optional enchantment must never crash a server.
        }
    }

    private static void setEnchantment(ItemEnchantmentsComponent.Builder builder,
            RegistryWrapper.Impl<Enchantment> registry, String id, int level) {
        net.minecraft.registry.RegistryKey<Enchantment> key =
            net.minecraft.registry.RegistryKey.of(RegistryKeys.ENCHANTMENT, Identifier.ofVanilla(id));
        java.util.Optional<net.minecraft.registry.entry.RegistryEntry.Reference<Enchantment>> entry = registry.getOptional(key);
        if (entry.isPresent()) {
            if (level > 0) builder.set(entry.get(), level);
            else builder.remove(existing -> existing.matchesKey(key));
        }
    }

    private static ItemStack findMace(ServerPlayerEntity player) {
        ItemStack main = player.getMainHandStack();
        if (isMace(main)) return main;
        ItemStack off = player.getOffHandStack();
        return isMace(off) ? off : ItemStack.EMPTY;
    }
    private static boolean isMace(ItemStack stack) { return !stack.isEmpty() && stack.isOf(WORLD_BREAKER); }
    private static void updateName(ItemStack stack) {
        int level = level(stack);
        stack.set(DataComponentTypes.CUSTOM_MODEL_DATA, new CustomModelDataComponent(java.util.List.of((float) level), java.util.List.of(), java.util.List.of(), java.util.List.of()));
        stack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, level >= 6);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§l§6" + NAMES[level - 1]));
        stack.set(DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(java.util.List.of(
            Text.literal("§7Рівень: §e" + level + "§7/20"),
            Text.literal("§7Убивств на цьому рівні: §c" + kills(stack) + "§7/" + (level == 20 ? "MAX" : REQUIRED_KILLS[level - 1])),
            Text.literal("§8Прогрес зберігається в самій булаві."))));
    }
    private static void applyFinalKillReward(ServerPlayerEntity killer, ServerPlayerEntity dead) {
        killer.sendMessage(Text.literal("§4§lКара Світів поглинула " + dead.getName().getString() + "!"), false);
    }
}