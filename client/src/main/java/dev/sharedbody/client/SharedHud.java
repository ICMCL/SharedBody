package dev.sharedbody.client;

import dev.sharedbody.common.Vitals;
import dev.sharedbody.common.Wire;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;

public final class SharedHud {
    private static final FoodData FOOD = new FoodData();
    private static Vitals received;
    private static ClientLevel receivedWorld;
    private static Vitals sent;
    private static String sentWorld;
    private static byte[] packet;
    private static long revision;

    private SharedHud() {}

    static void clear() {
        received = sent = null;
        receivedWorld = null;
        sentWorld = null;
        packet = null;
        revision++;
    }

    static void publish(Minecraft mc, long generation, long tick) {
        var player = mc.player;
        int flags = mc.gameMode.canHurtPlayer() ? Vitals.SURVIVAL : 0;
        if (player.isEyeInFluid(FluidTags.WATER)) flags |= Vitals.UNDERWATER;
        if (player.hasEffect(MobEffects.POISON)) flags |= Vitals.POISON;
        if (player.hasEffect(MobEffects.WITHER)) flags |= Vitals.WITHER;
        if (player.isFullyFrozen()) flags |= Vitals.FROZEN;
        if (player.hasEffect(MobEffects.HUNGER)) flags |= Vitals.HUNGER;
        if (player.hasEffect(MobEffects.REGENERATION)) flags |= Vitals.REGENERATION;
        Vitals current = new Vitals(player.getHealth(), player.getMaxHealth(), player.getAbsorptionAmount(),
                player.getArmorValue(), player.getFoodData().getFoodLevel(), player.getFoodData().getSaturationLevel(),
                player.getAirSupply(), player.getMaxAirSupply(), player.damageCooldownTime, flags);
        String world = mc.level.dimension().identifier().toString();
        boolean changed = !current.equals(sent) || !world.equals(sentWorld);
        if (changed) {
            packet = Wire.packet(Wire.VITALS, generation, out -> {
                out.writeUTF(world);
                current.write(out);
            });
            sent = current;
            sentWorld = world;
        }
        if (changed || tick % 10 == 0) SharedBodyClient.send(packet);
    }

    static void accept(Vitals vitals) {
        if (received == null || receivedWorld != Minecraft.getInstance().level) revision++;
        received = vitals;
        receivedWorld = Minecraft.getInstance().level;
        FOOD.setFoodLevel(vitals.food());
        FOOD.setSaturation(vitals.saturation());
    }

    public static long revision() { return revision; }

    public static Vitals state(Player player) {
        return receivedWorld == Minecraft.getInstance().level
                && player != null && player == SharedBodyClient.visibleBody() ? received : null;
    }

    public static FoodData food(Player player) {
        return state(player) == null ? player.getFoodData() : FOOD;
    }

    public static boolean effect(Player player, Holder<MobEffect> effect) {
        Vitals state = state(player);
        if (state == null) return player.hasEffect(effect);
        if (effect.equals(MobEffects.POISON)) return state.has(Vitals.POISON);
        if (effect.equals(MobEffects.WITHER)) return state.has(Vitals.WITHER);
        if (effect.equals(MobEffects.HUNGER)) return state.has(Vitals.HUNGER);
        if (effect.equals(MobEffects.REGENERATION)) return state.has(Vitals.REGENERATION);
        return player.hasEffect(effect);
    }

    public static boolean frozen(Player player) {
        Vitals state = state(player);
        return state == null ? player.isFullyFrozen() : state.has(Vitals.FROZEN);
    }
}
