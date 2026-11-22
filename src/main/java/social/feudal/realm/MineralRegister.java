package social.feudal.realm;

import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.world.ChunkPopulateEvent;
import org.bukkit.inventory.ItemStack;
import static social.feudal.realm.RealmStore.require;

/** A survey taken only at generation. The immutable sample and depletion ledger survive regeneration. */
final class MineralRegister implements Listener {
    private final RealmService realm;
    private final RealmStore s;
    MineralRegister(RealmService realm)throws SQLException{
        this.realm=realm;s=realm.store;
        s.update("CREATE TABLE IF NOT EXISTS mineral_map(cx INTEGER,cz INTEGER,sample BLOB NOT NULL,PRIMARY KEY(cx,cz)) WITHOUT ROWID");
        s.update("CREATE TABLE IF NOT EXISTS mineral_spent(x INTEGER,y INTEGER,z INTEGER,PRIMARY KEY(x,y,z)) WITHOUT ROWID");
        Bukkit.getPluginManager().registerEvents(this,realm.plugin);
    }
    static int kind(Material type){
        return switch(type){
            case IRON_ORE,DEEPSLATE_IRON_ORE -> 1;
            case COPPER_ORE,DEEPSLATE_COPPER_ORE -> 2;
            case GOLD_ORE,DEEPSLATE_GOLD_ORE -> 3;
            default -> 0;
        };
    }
    static Material raw(int kind){
        return switch(kind){case 1->Material.RAW_IRON;case 2->Material.RAW_COPPER;case 3->Material.RAW_GOLD;
            default->throw new IllegalArgumentException("未知矿种");};
    }
    static int key(int x,int y,int z,int bottom,int kind){
        return ((((y-bottom)*16+(z&15))*16+(x&15))*4)+kind;
    }
    @EventHandler public void generated(ChunkPopulateEvent event){
        if(event.getWorld()!=realm.world)return;
        var chunk=event.getChunk();
        try{
            if(s.number("SELECT count(*) FROM mineral_map WHERE cx=? AND cz=?",chunk.getX(),chunk.getZ())!=0)return;
            var sample=chunk.getChunkSnapshot(false,false,false,false);
            int[] ores=new int[512];int size=0;
            for(int y=realm.world.getMinHeight();y<realm.world.getMaxHeight();y++){
                if(sample.isSectionEmpty((y-realm.world.getMinHeight())>>4)){y|=15;continue;}
                for(int z=0;z<16;z++)for(int x=0;x<16;x++){
                    int kind=kind(sample.getBlockType(x,y,z));if(kind==0)continue;
                    if(size==ores.length)ores=Arrays.copyOf(ores,size*2);
                    ores[size++]=key(x,y,z,realm.world.getMinHeight(),kind);
                }
            }
            ByteBuffer data=ByteBuffer.allocate(size*4);data.asIntBuffer().put(ores,0,size);
            s.update("INSERT OR IGNORE INTO mineral_map VALUES(?,?,?)",chunk.getX(),chunk.getZ(),data.array());
        }catch(SQLException ex){realm.plugin.getLogger().log(Level.WARNING,"新矿区未登记，暂缓采掘",ex);}
    }
    boolean natural(Block b)throws SQLException{
        int kind=kind(b.getType());if(kind==0 || b.getWorld()!=realm.world)return false;
        try(var q=s.sql("SELECT sample FROM mineral_map WHERE cx=? AND cz=?",b.getX()>>4,b.getZ()>>4);var r=q.executeQuery()){
            if(!r.next())return false;
            var data=ByteBuffer.wrap(r.getBytes(1)).asIntBuffer();
            int target=key(b.getX(),b.getY(),b.getZ(),realm.world.getMinHeight(),kind);
            int lo=0,hi=data.limit();
            while(lo<hi){int mid=(lo+hi)>>>1;if(data.get(mid)<target)lo=mid+1;else hi=mid;}
            return lo<data.limit() && data.get(lo)==target;
        }
    }
    boolean available(Block b)throws SQLException{
        return natural(b) && s.number("SELECT count(*) FROM mineral_spent WHERE x=? AND y=? AND z=?",b.getX(),b.getY(),b.getZ())==0;
    }
    void claim(Block b)throws SQLException{
        require(natural(b) && s.update("INSERT OR IGNORE INTO mineral_spent VALUES(?,?,?)",b.getX(),b.getY(),b.getZ())==1,
                "此坐标没有可采的已核矿量");
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void mine(BlockBreakEvent e){
        Block b=e.getBlock();int k=kind(b.getType());
        if(k==0 || b.getWorld()!=realm.world)return;
        e.setExpToDrop(0);e.setDropItems(false);
        try{
            if(!available(b)){e.getPlayer().sendMessage("此矿位已采或来源未核，清理后无矿产。");return;}
            if(!b.isPreferredTool(e.getPlayer().getInventory().getItemInMainHand()) && e.getPlayer().getGameMode()!=GameMode.CREATIVE){
                e.setCancelled(true);e.getPlayer().sendMessage("请使用能够采掘这种矿石的镐。");return;
            }
            claim(b);
            if(e.getPlayer().getGameMode()!=GameMode.CREATIVE)b.getWorld().dropItemNaturally(b.getLocation(),new ItemStack(raw(k)));
        }catch(SQLException|IllegalArgumentException ex){e.setCancelled(true);e.getPlayer().sendMessage("矿账待复核，采掘暂停。");}
    }
    // Placed legacy ore keeps its smelting value in inventories; it cannot seed a new deposit.
    @EventHandler(ignoreCancelled=true) public void place(BlockPlaceEvent e){
        if(e.getBlock().getWorld()==realm.world && kind(e.getBlock().getType())!=0){
            e.setCancelled(true);e.getPlayer().sendMessage("旧矿石可直接入炉；矿场仅认天然工作面。");
        }
    }
    private boolean affected(List<Block> blocks){return blocks.stream().anyMatch(b->b.getWorld()==realm.world && kind(b.getType())!=0);}
    @EventHandler(ignoreCancelled=true) public void push(BlockPistonExtendEvent e){if(affected(e.getBlocks()))e.setCancelled(true);}
    @EventHandler(ignoreCancelled=true) public void pull(BlockPistonRetractEvent e){if(affected(e.getBlocks()))e.setCancelled(true);}
    @EventHandler(ignoreCancelled=true) public void blast(EntityExplodeEvent e){e.blockList().removeIf(b->b.getWorld()==realm.world && kind(b.getType())!=0);}
    @EventHandler(ignoreCancelled=true) public void blastBlock(BlockExplodeEvent e){e.blockList().removeIf(b->b.getWorld()==realm.world && kind(b.getType())!=0);}
    @EventHandler(ignoreCancelled=true) public void mobLoot(EntityDeathEvent e){
        if(e.getEntity().getWorld()!=realm.world)return;
        if(e.getEntity() instanceof IronGolem || e.getEntity() instanceof Zombie)
            e.getDrops().removeIf(i->switch(i.getType()){
                case IRON_INGOT,GOLD_INGOT,GOLD_NUGGET,COPPER_INGOT -> true;default -> false;});
    }
}
