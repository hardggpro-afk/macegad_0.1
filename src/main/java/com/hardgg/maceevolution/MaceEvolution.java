package com.hardgg.maceevolution;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.component.DataComponentType;
import net.minecraft.component.DataComponentTypes;
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
import net.minecraft.server.network.ServerPlayerEntity;
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
    public static final DataComponentType<Integer> LEVEL = Registry.register(Registries.DATA_COMPONENT_TYPE,
            Identifier.of(MOD_ID, "level"), DataComponentType.<Integer>builder().codec(Codec.intRange(1, 20)).packetCodec(PacketCodecs.VAR_INT).build());
    public static final DataComponentType<Integer> KILLS = Registry.register(Registries.DATA_COMPONENT_TYPE,
            Identifier.of(MOD_ID, "kills"), DataComponentType.<Integer>builder().codec(Codec.intRange(0, 999999)).packetCodec(PacketCodecs.VAR_INT).build());
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
            if (level >= 6) target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 50 + level * 3, Math.min(3, (level - 6) / 5)));
            if (level >= 10) target.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 80, Math.min(2, (level - 10) / 4)));
            if (level >= 12) target.setOnFireFor(3 + level / 4);
            if (level >= 14) target.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE, 60, 2));
            if (level >= 16) attacker.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 60, 1));
            if (level >= 20) target.addStatusEffect(new StatusEffectInstance(StatusEffects.WITHER, 100, 4));
            return ActionResult.PASS;
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(literal("maceevolution")
            .then(literal("give").requires(source -> source.hasPermissionLevel(2)).executes(context -> {
                ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                ItemStack stack = new ItemStack(WORLD_BREAKER);
                updateName(stack);
                if (!player.getInventory().insertStack(stack)) player.dropItem(stack, false);
                context.getSource().sendFeedback(() -> Text.literal("Видано Булаву Еволюції."), false);
                return 1;
            }))));
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