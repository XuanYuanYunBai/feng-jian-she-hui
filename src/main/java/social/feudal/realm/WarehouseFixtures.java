package social.feudal.realm;

import java.sql.SQLException;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import static social.feudal.realm.RealmStore.require;

/** Both ends of a binding must agree. Breaking a fixture revokes it immediately. */
final class WarehouseFixtures {
    private final RealmService realm;
    private final NamespacedKey tag;
    WarehouseFixtures(RealmService realm) {
        this.realm=realm;
        tag=new NamespacedKey(realm.plugin,"fixture");
    }
    private NamespacedKey key(String role,String id){return new NamespacedKey(realm.plugin,"pos/"+role+"/"+id);}
    boolean custom(Chunk chunk,String id){return chunk.getPersistentDataContainer().has(key("custom",id));}
    Block lookup(int x,int z,String role,String id){
        if(!realm.world.isChunkLoaded(x,z))return null;
        var chunk=realm.world.getChunkAt(x,z);
        int[] xyz=chunk.getPersistentDataContainer().get(key(role,id),PersistentDataType.INTEGER_ARRAY);
        if(xyz==null)return null;
        Block b=realm.world.getBlockAt(xyz[0],xyz[1],xyz[2]);
        return b.getState(false) instanceof TileState tile && (role+"/"+id).equals(tile.getPersistentDataContainer().get(tag,PersistentDataType.STRING))?b:null;
    }
    void bind(Block target,String role,String id){
        var tile=(TileState)target.getState();
        String name=role+"/"+id;
        String other=tile.getPersistentDataContainer().get(tag,PersistentDataType.STRING);
        require(other==null || name.equals(other),"这个方块已经登记为其他仓务设施");
        var chunk=target.getChunk();Block old=lookup(chunk.getX(),chunk.getZ(),role,id);
        if(old!=null && !old.equals(target)){
            var previous=(TileState)old.getState();previous.getPersistentDataContainer().remove(tag);previous.update();
        }
        tile.getPersistentDataContainer().set(tag,PersistentDataType.STRING,name);tile.update();
        chunk.getPersistentDataContainer().set(key(role,id),PersistentDataType.INTEGER_ARRAY,new int[]{target.getX(),target.getY(),target.getZ()});
    }
    void starter(Chunk chunk,Storehouse.Site site,RealmStore.Depot depot)throws SQLException{
        var data=chunk.getPersistentDataContainer();
        var once=key("starter",depot.id());
        if(data.has(once))return;
        Block desk=site.counter(realm.world).getBlock();
        if(desk.getType()==Material.LECTERN && !data.has(key("desk",depot.id())))bind(desk,"desk",depot.id());
        defaultBin(depot.id(),"stock",desk.getRelative(-1,0,0));
        var owner=realm.store.title(realm.store.owner(depot.x(),depot.z()));
        if(owner.x()==depot.x() && owner.z()==depot.z())defaultBin(owner.id(),"vault",desk.getRelative(1,0,-1));
        data.set(once,PersistentDataType.BYTE,(byte)1);
    }
    private void defaultBin(String id,String role,Block at){
        if(!at.isEmpty() || at.getChunk().getPersistentDataContainer().has(key(role,id)))return;
        at.setType(Material.BARREL,false);bind(at,role,id);
    }
    Container bin(int x,int z,String role,String id){
        Block b=lookup(x,z,role,id);
        return b!=null && b.getState() instanceof Container c?c:null;
    }
    static org.bukkit.inventory.Inventory inventory(Container c){
        return c instanceof Chest chest?chest.getBlockInventory():c.getInventory();
    }
    void register(Player p,String role,String id)throws SQLException{
        realm.inWorld(p);
        var block=p.getTargetBlockExact(5);
        require(block!=null,"请在五格内瞄准要登记的方块");
        int x,z;String seal;
        if(role.equals("vault")){
            var title=realm.store.title(id);x=title.x();z=title.z();seal=title.seal();
        }else{
            var d=realm.taxes.ledger.depot(id);x=d.x();z=d.z();seal=realm.store.title(realm.store.owner(x,z)).seal();
        }
        require(p.hasPermission("feudal.realm.admin") || p.getUniqueId().toString().equals(seal),"请由持印人登记仓务设施");
        require(block.getChunk().getX()==x && block.getChunk().getZ()==z,"新址须在原登记区块内");
        require(role.equals("desk")?block.getType()==Material.LECTERN:block.getType()==Material.BARREL || block.getType()==Material.CHEST,"账台使用讲台，仓箱使用箱子或木桶");
        bind(block,role,id);
        if(!role.equals("vault"))block.getChunk().getPersistentDataContainer().set(key("custom",id),PersistentDataType.BYTE,(byte)1);
        p.sendMessage("已登记"+switch(role){case "desk"->"新账台";case "stock"->"产地仓箱";default->"领地公库";}+" · "+block.getX()+"，"+block.getY()+"，"+block.getZ()+"。原箱物资留在原处。");
    }
}
