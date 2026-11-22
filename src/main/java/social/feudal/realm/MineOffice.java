package social.feudal.realm;

import java.sql.SQLException;
import java.util.*;
import java.util.logging.Level;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataType;
import social.feudal.transport.*;
import static social.feudal.realm.RealmStore.require;
import static social.feudal.ui.Menus.*;

/** Each pit covers one cadastral chunk. A batch names an exposed face and one physical carrier. */
final class MineOffice {
    private final RealmService r;
    private final RealmStore s;
    private final NamespacedKey receipt;
    private final FootNavigation walking;
    MineOffice(RealmService r)throws SQLException{
        this.r=r;s=r.store;receipt=new NamespacedKey(r.plugin,"mining_receipt");walking=new FootNavigation(r.plugin);
        s.update("""
            CREATE TABLE IF NOT EXISTS pit(id TEXT PRIMARY KEY,title TEXT REFERENCES title(id),x INTEGER,y INTEGER,z INTEGER,
            cx INTEGER,cz INTEGER,UNIQUE(cx,cz))""");
        s.update("""
            CREATE TABLE IF NOT EXISTS pit_shift(id INTEGER PRIMARY KEY,pit TEXT REFERENCES pit(id),x INTEGER,y INTEGER,z INTEGER,
            kind INTEGER,stage INTEGER DEFAULT 0,status TEXT)""");
        s.update("CREATE TABLE IF NOT EXISTS pit_contract(pit TEXT PRIMARY KEY REFERENCES pit(id),issuer TEXT,operator TEXT)");
        s.update("CREATE UNIQUE INDEX IF NOT EXISTS pit_active ON pit_shift(pit) WHERE stage<3");
        Bukkit.getScheduler().runTaskTimer(r.plugin,this::tick,80,10);
    }
    private record Pit(String id,String title,Block bin){}
    private Pit pit(String id)throws SQLException{
        try(var q=s.sql("SELECT * FROM pit WHERE id=?",id);var row=q.executeQuery()){
            require(row.next(),"未知矿场");
            return new Pit(id,row.getString("title"),r.world.getBlockAt(row.getInt("x"),row.getInt("y"),row.getInt("z")));
        }
    }
    private Location approach(Block b){
        for(var face:List.of(BlockFace.NORTH,BlockFace.EAST,BlockFace.SOUTH,BlockFace.WEST)){
            var n=b.getRelative(face);
            if((n.getX()>>4)==(b.getX()>>4) && (n.getZ()>>4)==(b.getZ()>>4) && n.isPassable() && !n.isLiquid() && n.getRelative(BlockFace.UP).isPassable()
                    && n.getRelative(BlockFace.DOWN).getType().isSolid())return n.getLocation().add(.5,0,.5);
        }
        return null;
    }
    private void site(Pit pit)throws SQLException{
        var b=pit.bin();
        require(pit.title().equals(s.owner(b.getX()>>4,b.getZ()>>4)),"矿口辖权已变，须由新领重新接管");
        require(b.getState() instanceof Barrel && approach(b)!=null,"请修复坑口矿桶和装卸通路");
        boolean timber=false,bench=false;
        for(var face:List.of(BlockFace.NORTH,BlockFace.EAST,BlockFace.SOUTH,BlockFace.WEST)){
            var n=b.getRelative(face);if((n.getX()>>4)!=(b.getX()>>4) || (n.getZ()>>4)!=(b.getZ()>>4))continue;
            timber|=Tag.LOGS.isTagged(n.getType()) && Tag.LOGS.isTagged(n.getRelative(BlockFace.UP).getType());
            bench|=n.getType()==Material.CRAFTING_TABLE;
        }
        require(timber && bench,"坑口桶旁须有两格高木支柱、工作台和可通行的装卸位");
    }
    void run(Player p,String[] a)throws SQLException{
        r.inWorld(p);
        var actor=p.getUniqueId().toString();var target=p.getTargetBlockExact(6);
        if(a[0].equals("sample")){
            require(target!=null && MineralRegister.kind(target.getType())!=0,"请瞄准露出的铁、铜或金矿面");
            p.sendMessage(r.minerals.available(target)?"已验天然工作面，可采一份粗矿。":"工作面已采或未经天然来源核验。");return;
        }
        RealmService.usage(a.length==(a[0].equals("minegrant")?3:2),a[0]+" 矿场代号"+(a[0].equals("minegrant")?" 在线承包人|收回":""));
        if(a[0].equals("mineopen")){
            Politics.id(a[1]);require(target!=null && target.getType()==Material.BARREL,"请瞄准矿口仓桶");
            int cx=target.getX()>>4,cz=target.getZ()>>4;String title=s.owner(cx,cz);
            require(title!=null,"大型矿场须在已建领区块内");require(s.title(title).seal().equals(actor),"须由本领唯一实际持印人批准");
            var mine=new Pit(a[1],title,target);site(mine);
            require(s.number("SELECT count(*) FROM mineral_map WHERE cx=? AND cz=?",cx,cz)>0,"请在已核验地质的新矿区设场");
            require(s.number("SELECT count(*) FROM pit WHERE id=? OR (cx=? AND cz=?)",a[1],cx,cz)==0,"矿名或本区块已有登记");
            s.update("INSERT INTO pit VALUES(?,?,?,?,?,?,?)",a[1],title,target.getX(),target.getY(),target.getZ(),cx,cz);
        }
        var pit=pit(a[1]);
        if(a[0].equals("minegrant")){
            require(s.title(pit.title()).seal().equals(actor),"须由本领持印人签署特许");
            if(a[2].equals("收回"))s.update("DELETE FROM pit_contract WHERE pit=?",pit.id());
            else{
                var contractor=Bukkit.getPlayerExact(a[2]);require(contractor!=null,"请填写在线承包人名称");
                s.update("INSERT OR REPLACE INTO pit_contract VALUES(?,?,?)",pit.id(),actor,contractor.getUniqueId().toString());
            }
        }
        if(a[0].equals("minework")){
            site(pit);String holder=s.title(pit.title()).seal();
            require(holder.equals(actor) || s.number("SELECT count(*) FROM pit_contract WHERE pit=? AND issuer=? AND operator=?",pit.id(),holder,actor)>0,"须由本领持印人或在任持印人特许的承包人派班");
            require(target!=null && target.getChunk().equals(pit.bin().getChunk()) && r.minerals.available(target),"请瞄准本矿区内尚未开采的天然矿面");
            require(approach(target)!=null,"请在工作面旁开通步行矿道");
            require(s.number("SELECT count(*) FROM pit_shift WHERE pit=? AND stage<3",pit.id())==0,"请待本班交矿");
            s.update("INSERT INTO pit_shift(pit,x,y,z,kind,status) VALUES(?,?,?,?,?,'准备出工')",pit.id(),target.getX(),target.getY(),target.getZ(),MineralRegister.kind(target.getType()));
        }
        String state=s.value("SELECT status FROM pit_shift WHERE pit=? ORDER BY id DESC LIMIT 1",pit.id());
        show(p,"领地矿场 · "+pit.id(),List.of(text("所属领地　"+s.title(pit.title()).name()+"　｜　工作范围为坑口区块"),
                text("在矿口木桶放铁镐，瞄准本区露出的天然矿面派一班。"),text("最近班次　"+(state==null?"待派工":state))),
                List.of(),List.of(command("查验眼前矿面","realm sample"),command("派班采眼前矿面","realm minework "+pit.id())),2);
    }
    private void tick(){
        if(Bukkit.getOnlinePlayers().isEmpty())return;
        try{
            for(String key:r.diplomacy.column("SELECT id FROM pit_shift WHERE stage<3 ORDER BY id")){
                int id=Integer.parseInt(key);
                try{step(id);}catch(IllegalArgumentException ex){status(id,ex.getMessage());}
            }
        }catch(SQLException ex){r.plugin.getLogger().log(Level.WARNING,"矿场账务待复核",ex);}
    }
    private void status(int id,String msg)throws SQLException{s.update("UPDATE pit_shift SET status=? WHERE id=? AND status<>?",msg,id,msg);}
    private void step(int id)throws SQLException{
        Block face;int stage,kind;Pit pit;
        try(var q=s.sql("SELECT * FROM pit_shift WHERE id=?",id);var row=q.executeQuery()){
            if(!row.next())return;pit=pit(row.getString("pit"));stage=row.getInt("stage");kind=row.getInt("kind");
            face=r.world.getBlockAt(row.getInt("x"),row.getInt("y"),row.getInt("z"));
        }
        Block b=pit.bin();if(!r.world.isChunkLoaded(b.getX()>>4,b.getZ()>>4))return;
        site(pit);var bin=(Barrel)b.getState();String crew="pit/"+pit.id();
        if(!r.crews.registered(b.getChunk(),crew))r.crews.staff(b.getChunk(),crew,"矿工",approach(b));
        var team=r.crews.team(b.getChunk(),crew);require(team!=null,"等候原矿工");var npc=team.porter();
        var cargo=new PorterCargo(npc,null);
        if(stage==0){
            if(!r.minerals.available(face)){
                s.update("UPDATE pit_shift SET stage=3,status='工作面已采，撤班' WHERE id=?",id);return;
            }
            var at=approach(face);require(at!=null,"工作面通路中断");
            if(npc.getLocation().distanceSquared(at)>.85){walking.toward(npc,at,1);status(id,"步行前往工作面");return;}
            int slot=bin.getInventory().first(Material.IRON_PICKAXE);require(slot>=0,"坑口缺铁镐");
            var pick=bin.getInventory().getItem(slot);var wear=(Damageable)pick.getItemMeta();wear.setDamage(wear.getDamage()+1);pick.setItemMeta(wear);
            s.atomic(()->{r.minerals.claim(face);s.update("UPDATE pit_shift SET stage=1,status='采出待装' WHERE id=?",id);return null;});
            bin.getInventory().setItem(slot,wear.getDamage()>=Material.IRON_PICKAXE.getMaxDurability()?null:pick);
            npc.swingMainHand();face.getWorld().playSound(face.getLocation(),Sound.BLOCK_STONE_BREAK,1f,.8f);
            stage=1;
        }
        if(stage==1){
            if(MineralRegister.kind(face.getType())==kind)face.setType(Material.AIR);
            cargo.setItem(0,new ItemStack(MineralRegister.raw(kind)));r.world.save(true);
            s.update("UPDATE pit_shift SET stage=2,status='背矿回坑口' WHERE id=?",id);return;
        }
        if(bin.getPersistentDataContainer().getOrDefault(receipt,PersistentDataType.INTEGER,-1)!=id){
            var at=approach(b);
            if(npc.getLocation().distanceSquared(at)>.85){walking.toward(npc,at,1);return;}
            var held=cargo.getItem(0);require(held!=null && held.getType()==MineralRegister.raw(kind),"等候原班实货");
            require(bin.getSnapshotInventory().addItem(held.clone()).isEmpty(),"矿仓已满，等待卸货");
            bin.getPersistentDataContainer().set(receipt,PersistentDataType.INTEGER,id);
            require(bin.update(false,false),"请修复坑口仓");
        }
        cargo.setItem(0,null);r.world.save(true);s.update("UPDATE pit_shift SET stage=3,status='已交矿一份' WHERE id=?",id);
    }
}
