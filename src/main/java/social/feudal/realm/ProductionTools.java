package social.feudal.realm;

import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerItemMendEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

/** Keep vanilla wear on the original item so existing damage, trading and material repairs still work. */
final class ProductionTools implements Listener {
    static int wear(Material tool) {
        String name = tool.name();
        if (!(name.endsWith("_PICKAXE") || name.endsWith("_AXE")
                || name.endsWith("_SHOVEL") || name.endsWith("_HOE"))) return 1;
        return switch (name.substring(0, name.indexOf('_'))) {
            case "WOODEN" -> 4;
            case "STONE", "COPPER" -> 8;
            case "IRON" -> 10;
            case "GOLDEN" -> 2;
            case "DIAMOND", "NETHERITE" -> 32;
            default -> 1;
        };
    }

    static int damage(int original, int multiplier) {
        return (int) Math.min(Integer.MAX_VALUE, (long) original * multiplier);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void playerWorks(PlayerItemDamageEvent event) {
        int multiplier = wear(event.getItem().getType());
        if (multiplier > 1) event.setDamage(damage(event.getDamage(), multiplier));
    }

    @EventHandler(ignoreCancelled = true)
    public void mend(PlayerItemMendEvent event) {
        if (wear(event.getItem().getType()) > 1) event.setCancelled(true);
    }

    /** Miners return the same pick. As with a player, Unbreaking can spare the whole wear event. */
    static ItemStack afterMineShift(ItemStack pick) {
        var meta = (Damageable) pick.getItemMeta();
        if (meta.isUnbreakable()) return pick;
        int unbreaking = pick.getEnchantmentLevel(Enchantment.UNBREAKING);
        if (unbreaking > 0 && ThreadLocalRandom.current().nextInt(unbreaking + 1) != 0) return pick;
        int max = meta.hasMaxDamage() ? meta.getMaxDamage() : pick.getType().getMaxDurability();
        long next = (long) meta.getDamage() + wear(pick.getType());
        if (next >= max) return null;
        meta.setDamage((int) next);
        pick.setItemMeta(meta);
        return pick;
    }
}
