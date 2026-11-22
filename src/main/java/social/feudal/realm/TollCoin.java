package social.feudal.realm;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.Component;
import static social.feudal.realm.RealmStore.require;

enum TollCoin {
    GOLD("金粒"), SILVER("银币");
    static final int SILVER_PER_GOLD=12;
    final String label;
    TollCoin(String label){this.label=label;}
    static TollCoin parse(String input){
        return switch(input){case "金","金粒","GOLD"->GOLD;case "银","银币","SILVER"->SILVER;
            default->throw new IllegalArgumentException("币种请选择金或银");};
    }
    ItemStack stack(int amount){
        if(this==GOLD)return new ItemStack(Material.GOLD_NUGGET,amount);
        var coin=new ItemStack(Material.IRON_NUGGET,amount);
        coin.editMeta(m->{m.displayName(Component.text("银币"));m.getPersistentDataContainer()
                .set(new NamespacedKey("feudalsurvey","silver_coin"),PersistentDataType.BYTE,(byte)1);});
        return coin;
    }
    static void exchange(Player p,TollCoin desired){
        var inv=p.getInventory();var hand=inv.getItemInMainHand();
        var price=desired==SILVER?GOLD.stack(1):SILVER.stack(SILVER_PER_GOLD);
        var change=desired==SILVER?SILVER.stack(SILVER_PER_GOLD):GOLD.stack(1);
        require(hand.isSimilar(price) && hand.getAmount()>=price.getAmount(),"主手须持有 "+price.getAmount()+" 枚可兑换货币");
        if(hand.getAmount()==price.getAmount()){inv.setItemInMainHand(change);return;}
        require(inv.firstEmpty()>=0,"请空出一个背包格接收兑换的货币");
        inv.setItemInMainHand(hand.asQuantity(hand.getAmount()-price.getAmount()));inv.addItem(change);
    }
}
