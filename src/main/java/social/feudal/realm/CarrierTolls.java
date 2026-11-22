package social.feudal.realm;

import java.sql.SQLException;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Barrel;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import social.feudal.transport.TransportCrew.Team;
import static social.feudal.realm.RealmStore.require;

/** Debit the porter's physical off-hand purse and credit the till at this gate, each once. */
final class CarrierTolls {
    private final RealmService realm;
    private final RealmStore s;

    CarrierTolls(RealmService realm)throws SQLException{
        this.realm=realm;s=realm.store;
        s.update("""
            CREATE TABLE IF NOT EXISTS carrier_toll(id TEXT PRIMARY KEY,bridge TEXT NOT NULL,bill TEXT NOT NULL,
            owner TEXT NOT NULL,currency TEXT NOT NULL,fee INTEGER NOT NULL,actor TEXT NOT NULL,at INTEGER NOT NULL,
            UNIQUE(bridge,bill))""");
    }
    void fund(Player p){
        var target=p.getTargetEntity(5);
        Mannequin porter;
        if(target instanceof Mannequin npc && realm.crews.isCarrier(npc))porter=npc;
        else{
            var nearby=p.getWorld().getNearbyEntitiesByType(Mannequin.class,p.getLocation(),3).stream()
                    .filter(realm.crews::isCarrier).filter(p::hasLineOfSight)
                    .filter(worker->worker.getLocation().distanceSquared(p.getLocation())<=9).toList();
            require(nearby.size()==1,"请到可见的运输队三格内，有多队时瞄准指定脚夫");
            porter=nearby.getFirst();
        }
        var hand=p.getInventory().getItemInMainHand();var purse=porter.getEquipment().getItemInOffHand();
        boolean gold=hand.isSimilar(TollCoin.GOLD.stack(1));
        require(gold || hand.isSimilar(TollCoin.SILVER.stack(1)),"请手持金粒或本服银币");
        require(purse.isEmpty() || purse.isSimilar(hand),"钱袋已有另一种货币");
        int before=purse.isEmpty()?0:purse.getAmount(),add=Math.min(64-before,hand.getAmount());
        require(add>0,"路费钱袋已满");
        porter.getEquipment().setItemInOffHand(hand.asQuantity(before+add));
        p.getInventory().setItemInMainHand(hand.getAmount()==add?null:hand.asQuantity(hand.getAmount()-add));
        realm.world.save(true);
        p.sendMessage("已交路费，运输队现携 "+(before+add)+" "+(gold?"金粒":"银币"));
    }
    boolean settle(String bridge,String bill,Team team,Barrel till)throws SQLException{
        var porter=team.porter();var purse=porter.getEquipment().getItemInOffHand();
        String ref=s.value("SELECT id FROM carrier_toll WHERE bridge=? AND bill=?",bridge,bill);
        if(ref==null){
            TollCoin coin=TollCoin.parse(s.value("SELECT currency FROM crossing WHERE id=?",bridge));
            int fee=s.number("SELECT fee FROM crossing WHERE id=?",bridge);
            if(!purse.isSimilar(coin.stack(1)) || purse.getAmount()<fee)return false;
            ref=UUID.randomUUID().toString();
            s.update("""
                INSERT INTO carrier_toll(id,bridge,bill,owner,currency,fee,actor,at)
                SELECT ?,id,?,owner,currency,fee,?,unixepoch() FROM crossing WHERE id=?""",
                    ref,bill,porter.getUniqueId().toString(),bridge);
        }
        require(porter.getUniqueId().toString().equals(s.value("SELECT actor FROM carrier_toll WHERE id=?",ref)),"请等候原承运人");
        var coin=TollCoin.parse(s.value("SELECT currency FROM carrier_toll WHERE id=?",ref));
        int fee=s.number("SELECT fee FROM carrier_toll WHERE id=?",ref);
        var debit=new NamespacedKey(realm.plugin,"debit/"+ref);
        var credit=new NamespacedKey(realm.plugin,"credit/"+ref);
        var data=porter.getPersistentDataContainer();
        if(!ref.equals(data.get(debit,PersistentDataType.STRING))){
            require(purse.isSimilar(coin.stack(1)) && purse.getAmount()>=fee,"等待补足已议定路费");
            porter.getEquipment().setItemInOffHand(purse.getAmount()==fee?null:purse.asQuantity(purse.getAmount()-fee));
            data.set(debit,PersistentDataType.STRING,ref);realm.world.save(true);
        }
        if(!ref.equals(till.getPersistentDataContainer().get(credit,PersistentDataType.STRING))){
            var inv=till.getSnapshotInventory();int slot=1;
            for(;slot<inv.getSize();slot++){
                var pile=inv.getItem(slot);
                if(pile==null || pile.isSimilar(coin.stack(1)) && pile.getAmount()+fee<=64)break;
            }
            require(slot<inv.getSize(),"收费柜已满，请腾出收款位");
            var pile=inv.getItem(slot);inv.setItem(slot,coin.stack(fee+(pile==null?0:pile.getAmount())));
            till.getPersistentDataContainer().set(credit,PersistentDataType.STRING,ref);
            require(till.update(),"收费柜已改建");realm.world.save(true);
        }
        s.update("""
            INSERT OR IGNORE INTO passage(bridge,owner,payer,paid,at,currency,bill)
            SELECT bridge,owner,actor,fee,at,currency,bill FROM carrier_toll WHERE id=?""",ref);
        data.remove(debit);
        var settled=(Barrel)till.getBlock().getState();
        settled.getPersistentDataContainer().remove(credit);settled.update();
        return true;
    }
}
