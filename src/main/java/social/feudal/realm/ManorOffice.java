package social.feudal.realm;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import java.sql.SQLException;
import java.util.*;
import java.util.logging.Level;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import static social.feudal.realm.RealmStore.require;
import static social.feudal.realm.RealmService.usage;
import static social.feudal.ui.Menus.*;

final class ManorOffice implements Listener {
    final RealmService realm;
    final SettlementRegister register;
    final RealmStore s;
    private record SurveyCorner(String manor,int x,int z){}
    private final Map<UUID,SurveyCorner> corners=new HashMap<>();
    private final int seasonSeconds;
    private final long noticeSeconds;
    ManorOffice(RealmService realm)throws SQLException{
        this.realm=realm;s=realm.store;register=new SettlementRegister(s);
        seasonSeconds=Math.max(60,realm.plugin.getConfig().getInt("realm.season-seconds",21600));
        noticeSeconds=Math.max(60,realm.plugin.getConfig().getLong("realm.notice-seconds",86400));
        Bukkit.getPluginManager().registerEvents(this,realm.plugin);
        Bukkit.getScheduler().runTaskTimer(realm.plugin,this::advance,200,200);
    }
    @EventHandler public void leave(org.bukkit.event.player.PlayerQuitEvent event){
        corners.remove(event.getPlayer().getUniqueId());
    }
    private static String dimensions(Charters.Bounds b){
        return (b.east()-b.west()+1)+" × "+(b.south()-b.north()+1)+" 格，共 "+b.area()+" 格（含边）";
    }
    private void measure(Player p,SettlementRegister.Manor m,String action){
        var actor=p.getUniqueId();var b=m.estate();
        var body=new ArrayList<DialogBody>();var buttons=new ArrayList<ActionButton>();
        body.add(text("现有庄界　"+dimensions(b)+"\n西 "+b.west()+"，北 "+b.north()+"，东 "+b.east()+"，南 "+b.south()));
        body.add(text("沿新围界选两个对角。瞄准角上的方块再点测量，走到另一角后可双手留空、潜行按交换副手键重开经营簿。最后回庄厅验收。"));
        if(action.equals("cornerclear"))corners.remove(actor);
        var first=corners.get(actor);
        if(action.equals("cornerfirst") || action.equals("cornerlast")){
            var target=p.getTargetBlockExact(5);require(target!=null,"请在五格内瞄准院界的角");
            if(action.equals("cornerfirst")){
                first=new SurveyCorner(m.id(),target.getX(),target.getZ());corners.put(actor,first);
            }else{
                require(first!=null && first.manor().equals(m.id()),"请先实测第一角");
                var area=new Charters.Bounds(Math.min(first.x(),target.getX()),Math.min(first.z(),target.getZ()),
                        Math.max(first.x(),target.getX()),Math.max(first.z(),target.getZ()));
                require(area.contains(b.west(),b.north()) && area.contains(b.east(),b.south()) && !area.equals(b),"新界须包含原庄并向外扩展，请重选第二角");
                require((long)area.east()-area.west()<96 && (long)area.south()-area.north()<96,"庄园长宽各不能超过 96 格");
                int[] measured={area.west(),area.north(),area.east(),area.south()};
                p.getPersistentDataContainer().set(new NamespacedKey(realm.plugin,"yard_draft_"+m.id()),PersistentDataType.INTEGER_ARRAY,measured);
                corners.remove(actor);first=null;
            }
        }
        var key=new NamespacedKey(realm.plugin,"yard_draft_"+m.id());
        if(action.equals("cornerfirst") || action.equals("cornerclear"))p.getPersistentDataContainer().remove(key);
        var draft=p.getPersistentDataContainer().get(key,PersistentDataType.INTEGER_ARRAY);
        if(draft!=null){
            var area=new Charters.Bounds(draft[0],draft[1],draft[2],draft[3]);
            body.add(text("拟扩至　"+dimensions(area)+"\n西 "+draft[0]+"，北 "+draft[1]+"，东 "+draft[2]+"，南 "+draft[3]));
            buttons.add(command("在庄厅验收新界","realm expand "+m.id()+" "+draft[0]+" "+draft[1]+" "+draft[2]+" "+draft[3]));
        }
        if(first!=null && first.manor().equals(m.id())){
            body.add(text("第一角　X "+first.x()+"，Z "+first.z()));
            buttons.add(command("量眼前第二角","realm cornerlast "+m.id()));
        }
        buttons.add(command("量眼前第一角","realm cornerfirst "+m.id()));
        buttons.add(command("清除本次测量","realm cornerclear "+m.id()));
        buttons.add(command("返回庄档","realm manor "+m.id()));
        show(p,"实地测量 · "+m.name(),body,List.of(),buttons,2);
    }

    private void advance(){
        if(Bukkit.getOnlinePlayers().isEmpty())return;
        try{
            if(!s.ready())return;
            int elapsed=s.number("SELECT v FROM setting WHERE k='season-seconds'")+10;
            boolean newSeason=elapsed>=seasonSeconds;
            int season=register.season();
            s.atomic(()->{
                if(newSeason){
                    s.update("INSERT OR REPLACE INTO setting VALUES('season',?)",season+1);
                    s.politics.expiry(season+1);
                }
                s.update("INSERT OR REPLACE INTO setting VALUES('season-seconds',?)",newSeason?0:elapsed);
                // Keep the continuous public-service rule independent of eating the last loaf.
                // For an unloaded hall only the last observed eligibility can continue.
                for(String id:realm.diplomacy.column("SELECT id FROM manor")){
                    var m=register.manor(id);
                    boolean supplied=s.number("SELECT settled FROM manor WHERE id=?",id)>0;
                    boolean pantryLoaded=loaded(m.estate());
                    if(newSeason && !pantryLoaded){
                        // Only the hall and its possible adjacent pantry chunks are needed at the common cutoff.
                        pantryLoaded=true;
                        for(var side:List.of(BlockFace.SELF,BlockFace.NORTH,BlockFace.SOUTH,BlockFace.EAST,BlockFace.WEST)){
                            int cx=(m.x()+side.getModX())>>4,cz=(m.z()+side.getModZ())>>4;
                            if(!realm.world.isChunkLoaded(cx,cz) && !realm.world.loadChunk(cx,cz,false))pantryLoaded=false;
                        }
                    }
                    if(pantryLoaded){
                        var bin=foodBarrel(m);
                        if(newSeason && bin!=null){
                            int stock=0;
                            for(var item:bin.getInventory().getContents())if(ReliefOffice.plain(item))stock+=item.getAmount();
                            s.update("INSERT OR IGNORE INTO pantry_snapshot VALUES(?,?,?)",id,season+1,stock);
                        }
                        supplied=bin!=null && stocked(bin.getInventory(),Math.ceilDiv(m.residents(),100));
                    }
                    if(supplied)s.update("UPDATE manor SET settled=settled+10 WHERE id=?",id);
                    else s.update("UPDATE manor SET settled=0,service=0 WHERE id=?",id);
                }
                return null;
            });
            drain();
        }catch(SQLException ex){realm.plugin.getLogger().log(Level.WARNING,"庄园季账失败",ex);}
    }
    // Process only one estate per tick and restart from its durable season number.
    private final ArrayDeque<String> pendingSeasons=new ArrayDeque<>();
    private void drain()throws SQLException{
        if(!pendingSeasons.isEmpty())return;
        pendingSeasons.addAll(realm.diplomacy.column("SELECT id FROM manor WHERE last_season<? ORDER BY last_season,id",register.season()));
        processNext();
    }
    private void processNext(){
        if(Bukkit.getOnlinePlayers().isEmpty()){pendingSeasons.clear();return;}
        String id=pendingSeasons.pollFirst();
        if(id==null)return;
        try{
            var m=register.manor(id);
            boolean ready=true;
            var area=m.estate();
            for(int x=area.west()>>4;x<=area.east()>>4;x++)for(int z=area.north()>>4;z<=area.south()>>4;z++)
                if(!realm.world.isChunkLoaded(x,z) && !realm.world.loadChunk(x,z,false))ready=false;
            if(ready)settle(m,s.number("SELECT last_season+1 FROM manor WHERE id=?",id));
        }catch(SQLException ex){realm.plugin.getLogger().log(Level.WARNING,id+" 居民季账待复核",ex);}
        if(!pendingSeasons.isEmpty())Bukkit.getScheduler().runTask(realm.plugin,this::processNext);
    }

    private void settle(SettlementRegister.Manor m,int season)throws SQLException{
        int housing=capacity(m);
        int reserved=s.number("SELECT coalesce(sum(people),0) FROM migration WHERE dest=? AND arrived=0",m.id());
        int places=Math.max(0,housing-reserved);
        if(season<=s.number("SELECT grace_until FROM manor WHERE id=?",m.id()))places=Math.max(places,m.residents());
        int credit=s.number("SELECT ration_credit FROM manor WHERE id=?",m.id());
        int carry=s.number("SELECT growth_remainder FROM manor WHERE id=?",m.id());
        int streak=s.number("SELECT shortages FROM manor WHERE id=?",m.id());
        var barrel=foodBarrel(m);
        int stock=0;
        var stamp=new NamespacedKey(realm.plugin,"season_meal");
        String prefix=m.id()+"/"+season+"/";
        boolean replay=false;
        if(barrel!=null){
            var inventory=barrel.getSnapshotInventory();
            for(var item:inventory.getContents())if(ReliefOffice.plain(item))stock+=item.getAmount();
            var receipt=barrel.getPersistentDataContainer().get(stamp,PersistentDataType.STRING);
            if(receipt!=null && receipt.startsWith(prefix)){
                stock=Integer.parseInt(receipt.substring(prefix.length()));
                replay=true;
            }
        }
        int observed=s.number("SELECT stock FROM pantry_snapshot WHERE manor=? AND season=?",m.id(),season);
        var meal=ResidentSeason.close(m.residents(),places,stock,credit,carry,streak,observed);
        if(!replay && meal.bread()>0){
            var inv=barrel.getSnapshotInventory();
            int remaining=meal.bread();
            for(int slot=0;slot<inv.getSize() && remaining>0;slot++){
                var stack=inv.getItem(slot);
                if(!ReliefOffice.plain(stack))continue;
                int take=Math.min(stack.getAmount(),remaining);
                stack.setAmount(stack.getAmount()-take);
                inv.setItem(slot,stack.getAmount()==0?null:stack);
                remaining-=take;
            }
            barrel.getPersistentDataContainer().set(stamp,PersistentDataType.STRING,prefix+stock);
            if(!barrel.update(false,false))throw new SQLException("口粮仓已变动，等待复核");
            // The stock and receipt share one block entity. Persist them before advancing SQLite.
            realm.world.save(true);
        }
        int turnover=ResidentSeason.annualReplacements(season,m.residents(),realm.families.life.freeResidents(m.id()),
                Math.max(0,housing-reserved),meal.shortfall());
        int births=meal.births()+turnover;
        int closing=m.residents()+meal.births()-meal.deaths();
        final int surplus=stock-meal.bread();
        s.atomic(()->{
            s.update("DELETE FROM pantry_snapshot WHERE manor=? AND season<=?",m.id(),season);
            s.update("INSERT INTO population_season VALUES(?,?,?,?,?,?,?,?,?)",m.id(),season,m.residents(),housing,
                    meal.bread(),meal.shortfall(),births,meal.deaths()+turnover,closing);
            s.update("INSERT INTO service_audit SELECT id,?,?,settled>=? FROM manor WHERE id=?",season,surplus>=3,seasonSeconds,m.id());
            if(turnover>0)s.update("INSERT INTO population_turnover VALUES(?,?,?)",m.id(),season,turnover);
            s.update("""
                UPDATE manor SET residents=?,housing=?,last_season=?,ration_credit=?,growth_remainder=?,shortages=?,
                service=CASE WHEN ? AND settled>=? THEN service+1 ELSE 0 END,settled=0 WHERE id=?""",
                    closing,housing,season,meal.credit(),meal.growthRemainder(),meal.shortages(),
                    meal.shortfall()==0 && surplus>=3,seasonSeconds,m.id());
            if(meal.deaths()>0)realm.families.book.famine(m.id(),closing,season);
            realm.families.life.yearAt(m.id(),season,births);
            return null;
        });
        realm.changed();
    }
    private boolean loaded(Charters.Bounds b){
        for(int x=b.west()>>4;x<=b.east()>>4;x++)for(int z=b.north()>>4;z<=b.south()>>4;z++)
            if(!realm.world.isChunkLoaded(x,z))return false;
        return true;
    }
    private static boolean stocked(Inventory inv,int households){return inv.containsAtLeast(new ItemStack(Material.BREAD),households+3);}
    Container foodBarrel(SettlementRegister.Manor m){
        var hall=realm.world.getBlockAt(m.x(),m.y(),m.z());
        if(hall.getType()!=Material.LECTERN && !(m.cottage() && hall.getType()==Material.CRAFTING_TABLE))return null;
        for(var face:List.of(BlockFace.NORTH,BlockFace.EAST,BlockFace.SOUTH,BlockFace.WEST)){
            var bin=hall.getRelative(face);
            if(m.estate().contains(bin.getX(),bin.getZ()) && bin.getType()==Material.BARREL && bin.getState() instanceof Container c)return c;
        }
        return null;
    }
    private boolean edge(int x,int y,int z){
        for(int dy=-2;dy<=2;dy++){
            var mat=realm.world.getBlockAt(x,y+dy,z).getType();
            if(Tag.FENCES.isTagged(mat)||Tag.FENCE_GATES.isTagged(mat)||Tag.WALLS.isTagged(mat)||Tag.LEAVES.isTagged(mat)||mat==Material.WATER)return true;
        }return false;
    }
    int capacity(SettlementRegister.Manor m)throws SQLException{
        return ManorHousing.capacity(realm.world,m,realm.rooms.site(m.id(),"私床"));
    }
    int inspect(SettlementRegister.Manor m)throws SQLException{
        var b=m.estate();
        require(b.east()>=b.west() && b.south()>=b.north() && (long)b.east()-b.west()<96 && (long)b.south()-b.north()<96
                && b.contains(m.x(),m.z()),"请在厅内登记不超过 96 格长宽的庄园");
        require(loaded(b),"请走近庄园，待围界区块加载");
        var barrel=foodBarrel(m);
        Inventory food=barrel==null?null:barrel.getInventory();
        require(food!=null,"在登记的厅位放"+(m.cottage()?"工作台或讲台":"讲台")+"，紧贴其水平一侧放赈济木桶");
        require(stocked(food,Math.ceilDiv(m.residents(),100)),"赈济桶须存 "+(Math.ceilDiv(m.residents(),100)+3)+" 个面包（每百人一份及三份公粮）");
        boolean gate=false;
        for(int x=b.west();x<=b.east();x++)for(int z=b.north();z<=b.south();z++){
            if(x!=b.west() && x!=b.east() && z!=b.north() && z!=b.south())continue;
            require(edge(x,m.y(),z),"围界缺口位于 "+x+"，"+z);
            if(!gate)for(int h=-2;h<=2;h++)if(Tag.FENCE_GATES.isTagged(realm.world.getBlockAt(x,m.y()+h,z).getType()))gate=true;
        }
        require(gate,"请为庄园设一座可通行的栅栏门");
        int capacity=capacity(m);
        require(capacity>=m.residents(),"民房可住 "+capacity+" 人，需要 "+m.residents()+"。床旁留两格高通道，四面设墙和可进出的门，上方封顶。");
        return capacity;
    }
    private static Charters.Bounds bounds(String[] a,int i){
        return new Charters.Bounds(Integer.parseInt(a[i]),Integer.parseInt(a[i+1]),Integer.parseInt(a[i+2]),Integer.parseInt(a[i+3]));
    }
    private void hall(Player p,SettlementRegister.Manor m){
        require(p.getLocation().distanceSquared(new Location(realm.world,m.x()+.5,m.y()+.5,m.z()+.5))<=64,"请到庄宅办事厅八格内");
    }
    void serviceAudit(Player p,String id,int page)throws SQLException{
        realm.inWorld(p);
        var m=register.manor(id);
        int size=s.number("SELECT count(*) FROM population_season WHERE manor=?",id);
        int pages=Math.max(1,Math.ceilDiv(size,6));
        require(page>=1 && page<=pages,"没有这页季账");
        var body=new ArrayList<DialogBody>();var buttons=new ArrayList<ActionButton>();
        body.add(text(m.name()+"　｜　连续公共供养 "+m.service()+" 季"));
        body.add(text("足额口粮、季末至少三份公粮、全季供养时间三项俱足，才计入连续服务。"));
        try(var q=s.sql("""
            SELECT p.season,p.opening,p.bread,p.shortfall,p.closing,a.reserve_ok,a.full_time
            FROM population_season p LEFT JOIN service_audit a USING(manor,season)
            WHERE p.manor=? ORDER BY p.season DESC LIMIT 6 OFFSET ?""",id,(page-1)*6);var r=q.executeQuery()){
            while(r.next()){
                String finding=r.getObject(6)==null?"旧季未留公粮及时间凭据":
                    (r.getInt(4)==0 && r.getBoolean(6) && r.getBoolean(7)?"供养成立":"未达标")+
                    "　公粮"+(r.getBoolean(6)?"足":"不足")+"　"+(r.getBoolean(7)?"已满一季":"未满一季");
                body.add(text("第 "+r.getInt(1)+" 季　"+r.getInt(2)+" → "+r.getInt(5)+" 人　开粮 "+r.getInt(3)+" 个　缺 "+r.getInt(4)+" 人份\n"+finding));
            }
        }
        if(size==0)body.add(text("首季尚未结账。"));
        if(page>1)buttons.add(command("较新季报","realm serviceaudit "+id+" "+(page-1)));
        if(page<pages)buttons.add(command("较早季报","realm serviceaudit "+id+" "+(page+1)));
        buttons.add(command("返回庄档","realm manor "+id));
        show(p,"公共供养季账　"+page+"/"+pages,body,List.of(),buttons,2);
    }
    void archive(Player p,String id)throws SQLException{
        var m=register.manor(id);var buttons=new ArrayList<ActionButton>();var body=new ArrayList<DialogBody>();
        buttons.add(command("建筑图谱","realm buildings"));
        buttons.add(command("历季公共供养账","realm serviceaudit "+id));
        buttons.add(command("跨庄赈济","realm relief "+id));
        int housing=s.number("SELECT housing FROM manor WHERE id=?",id);
        body.add(text("在住 "+m.residents()+" 人　｜　可住 "+housing+" 人　｜　"+(housing>=m.residents()?"空位 "+(housing-m.residents()):"拥挤 "+(m.residents()-housing))+" 人"));
        var estate=m.estate();body.add(text("庄界　"+dimensions(estate)+"\n西 "+estate.west()+"，北 "+estate.north()+"，东 "+estate.east()+"，南 "+estate.south()));
        body.add(text("业主　"+realm.display(m.owner())+"　｜　每张室内床提供 100 个住位"));
        body.add(text("厅堂　"+m.x()+"，"+m.y()+"，"+m.z()+"　｜　完成赈济 "+m.service()+" 季"));
        if(s.number("SELECT grace_until FROM manor WHERE id=?",id)>register.season())
            body.add(text("旧庄改建宽限至下一次结季，请到厅堂验收民房。"));
        if(register.manages(m,p.getUniqueId().toString())){
            buttons.add(command("铁匠铺与工单","realm smith "+id));
            buttons.add(command("扩建庄界","realm expandform "+id));
            buttons.add(command("登记所在大厅","realm room "+id+" 大厅"));
            buttons.add(command("入住所在私室","realm room "+id+" 私室"));
            buttons.add(command("登记所在议事厅","realm room "+id+" 议事厅"));
            boolean open=s.number("SELECT count(*) FROM asylum WHERE manor=?",id)>0;
            buttons.add(command(open?"暂停接纳迁居":"开放余房收容","realm asylum "+id+(open?" 停止":" 接纳")));
            for(var line:realm.diplomacy.column("""
                    SELECT j.people||' 人 · '||o.name||' → '||d.name||' · '||j.status FROM migration j
                    JOIN manor o ON j.source=o.id JOIN manor d ON j.dest=d.id
                    WHERE j.source=? OR j.dest=? ORDER BY j.id DESC LIMIT 3""",id,id))body.add(text(line));
            buttons.add(command("现场复验住房","realm housing "+id));
            var report=s.value("""
                SELECT '第 '||season||' 季　期初 '||opening||' 人，吃粮 '||bread||' 个面包，缺 '||shortfall||
                ' 人份，出生 '||births||' 人，死亡 '||deaths||' 人。'||coalesce(
                (SELECT '其中自然更替 '||people||' 人。' FROM population_turnover t WHERE t.manor=p.manor AND t.season=p.season),'')
                FROM population_season p
                WHERE manor=? ORDER BY season DESC LIMIT 1""",id);
            var pantry=loaded(m.estate())?foodBarrel(m):null;
            if(pantry!=null){
                int bread=0;
                for(var stack:pantry.getInventory().getContents())if(ReliefOffice.plain(stack))bread+=stack.getAmount();
                int credit=s.number("SELECT ration_credit FROM manor WHERE id=?",id);
                int reserved=s.number("SELECT coalesce(sum(people),0) FROM migration WHERE dest=? AND arrived=0",id);
                var forecast=ResidentSeason.close(m.residents(),Math.max(0,housing-reserved),bread,credit,
                        s.number("SELECT growth_remainder FROM manor WHERE id=?",id),0);
                body.add(text("按现有房粮预计自然净增 "+forecast.births()+" 人　｜　季末以可用床位和实仓验收"));
                int deficit=Math.max(0,m.residents()-bread*100-credit);
                body.add(text("仓内面包 "+bread+" 个　｜　上季余下 "+credit+" 人份　｜　下季预计缺 "+deficit+" 人份"));
            }
            body.add(text(report==null?"首季尚未结账，口粮请放厅旁木桶。":report));
        }
        String nation=s.value("SELECT nation FROM capital WHERE manor=?",id);
        if(nation!=null)body.add(text("首都　"+s.title(nation).name()));
        for(String mill:realm.diplomacy.column("SELECT id FROM mill WHERE manor=?",id))buttons.add(command("水磨 · "+mill,"realm mill "+mill));
        if(m.owner().equals(p.getUniqueId().toString()) && s.value("SELECT id FROM nation WHERE id=?",id)==null){
            buttons.add(command("申请建领","realm surveyclaim "+id));
        }
        for(String n:realm.diplomacy.column("SELECT id FROM claim WHERE manor=? ORDER BY id DESC LIMIT 8",id))
            buttons.add(command("边界公示 #"+n,"realm hearing "+n));
        buttons.add(command("公议与庄园","realm estates"));show(p,m.name(),body,List.of(),buttons,2);
    }
    void directory(Player p)throws SQLException{
        var buttons=new ArrayList<ActionButton>();
        for(String id:realm.diplomacy.column("SELECT id FROM manor ORDER BY name"))buttons.add(command(register.manor(id).name(),"realm manor "+id));
        for(String n:realm.diplomacy.column("SELECT id FROM claim WHERE state='公示中' ORDER BY id DESC"))buttons.add(command("待审边界 #"+n,"realm hearing "+n));
        buttons.add(command("测量并立庄","realm estateform"));buttons.add(command("返回纹章院","realm atlas"));
        show(p,"地籍与地方公议",List.of(text("在当地围建庄园，设门、讲台、赈济木桶和床位。稳定居住 200 人并赈济一季后，可将庄宅登记为新领邦首都。")),List.of(),buttons,2);
    }
    @EventHandler(ignoreCancelled=true) public void use(PlayerInteractEvent e)throws SQLException{
        if(e.getHand()!=EquipmentSlot.HAND || e.getAction()!=Action.RIGHT_CLICK_BLOCK || e.getClickedBlock()==null)return;
        var b=e.getClickedBlock();
        if(b.getWorld()!=realm.world || (b.getType()!=Material.LECTERN && !(b.getType()==Material.CRAFTING_TABLE && e.getPlayer().isSneaking())))return;
        String id=s.value("SELECT id FROM manor WHERE x=? AND y=? AND z=?",b.getX(),b.getY(),b.getZ());
        if(id!=null){e.setCancelled(true);archive(e.getPlayer(),id);}
    }
    void run(Player p,String[] a)throws SQLException{
        realm.inWorld(p);String actor=p.getUniqueId().toString();var l=p.getLocation();
        switch(a[0]){
            case "expandform","cornerfirst","cornerlast","cornerclear" -> {
                usage(a.length==2,"请选择庄园");
                var m=register.manor(a[1]);require(m.owner().equals(actor),"须由庄主扩院");measure(p,m,a[0]);
            }
            case "expand" -> {
                usage(a.length==6,"expand 庄园 西 北 东 南");
                var m=register.manor(a[1]);hall(p,m);require(m.owner().equals(actor),"须由庄主扩院");
                var old=m.estate();
                var b=bounds(a,2);
                require(b.contains(old.west(),old.north()) && b.contains(old.east(),old.south()) && !old.equals(b),"新院界须包含完整原院并向外扩展");
                realm.firstHolding.border(b);
                var expanded=new SettlementRegister.Manor(m.id(),m.name(),m.owner(),m.x(),m.y(),m.z(),b,m.residents(),m.service(),m.cottage());
                int housing=inspect(expanded);register.available(actor,b,m.id());
                s.update("UPDATE manor SET west=?,north=?,east=?,south=?,housing=? WHERE id=?",b.west(),b.north(),b.east(),b.south(),housing,m.id());
                corners.remove(p.getUniqueId());p.getPersistentDataContainer().remove(new NamespacedKey(realm.plugin,"yard_draft_"+m.id()));
                realm.changed();archive(p,m.id());
            }
            case "room" -> {
                usage(a.length==3 || (a.length==4 && a[3].equals("确认")),"room 庄园 大厅|私室|议事厅");
                realm.rooms.register(p,register.manor(a[1]),a[2],a.length==4);
            }
            case "asylum" -> {
                usage(a.length==3 && Set.of("接纳","停止").contains(a[2]),"asylum 庄园 接纳|停止");
                realm.refuge.policy(p,a[1],a[2].equals("接纳"));archive(p,a[1]);
            }
            case "people" -> {
                usage(a.length==2 || a.length==3,"people 国家代号 [页码]");
                var country=register.census().stream().filter(row->row.id().equals(a[1])).findFirst()
                        .orElseThrow(()->new IllegalArgumentException("人口簿中没有这处辖地"));
                var ids=realm.diplomacy.column("""
                    SELECT m.id FROM manor m JOIN dwelling_jurisdiction j USING(id) WHERE j.district=?
                    ORDER BY m.name,m.id""",country.id());
                int page=a.length==3?Integer.parseInt(a[2]):1;
                int pages=Math.max(1,Math.ceilDiv(ids.size(),7));
                require(page>=1 && page<=pages,"没有这页聚落");
                var body=new ArrayList<DialogBody>();var buttons=new ArrayList<ActionButton>();
                body.add(text("居民 "+country.population()+" 人　｜　住房 "+country.capacity()+" 人　｜　聚落 "+ids.size()+" 处"));
                if(!country.id().equals("@"))body.add(text("直辖 "+country.direct()+" 人　｜　封臣辖下 "+(country.population()-country.direct())+" 人"));
                for(var id:ids.subList((page-1)*7,Math.min(ids.size(),page*7))){
                    var m=register.manor(id);
                    String local=s.owner(m.x()>>4,m.z()>>4);
                    body.add(text(m.name()+"　"+m.residents()+" 人　｜　"+(local==null?"未建领":s.title(local).name())));
                    buttons.add(command(m.name()+" · 庄档","realm manor "+id));
                }
                if(page>1)buttons.add(command("上一页","realm people "+a[1]+" "+(page-1)));
                if(page<pages)buttons.add(command("下一页","realm people "+a[1]+" "+(page+1)));
                buttons.add(command("各国人口簿","realm census"));
                show(p,country.name()+" · 民生",body,List.of(),buttons,2);
            }
            case "census" -> {
                var rows=register.census();
                int page=a.length>1?Integer.parseInt(a[1]):1;
                int pages=Math.max(1,Math.ceilDiv(rows.size(),7));
                require(page>=1 && page<=pages,"没有这页人口簿");
                var body=new ArrayList<DialogBody>();var buttons=new ArrayList<ActionButton>();
                body.add(text("全境登记 "+rows.stream().mapToInt(SettlementRegister.Census::population).sum()+" 人　｜　第 "+page+" / "+pages+" 页"));
                int transit=s.number("SELECT coalesce(sum(people),0) FROM migration WHERE arrived=0");
                if(transit>0)body.add(text("另有 "+transit+" 人正在迁居，抵达后登记入庄。"));
                for(var row:rows.subList((page-1)*7,Math.min(page*7,rows.size()))){
                    body.add(text(row.name()+"　"+row.population()+" 人　｜　"+row.villages()+" 处聚落　｜　住房 "+row.capacity()+" 人"));
                    buttons.add(command(row.name(),"realm people "+row.id()));
                }
                if(page>1)buttons.add(command("上一页","realm census "+(page-1)));
                if(page<pages)buttons.add(command("下一页","realm census "+(page+1)));
                buttons.add(command("查看各庄","realm estates"));buttons.add(command("返回纹章院","realm atlas"));
                show(p,"各国人口簿",body,List.of(),buttons,2);
            }
            case "housing" -> {
                usage(a.length==2,"housing 庄园");var m=register.manor(a[1]);hall(p,m);
                require(register.manages(m,actor),"须由庄主或公产持印人验房");
                require(loaded(m.estate()),"请待庄园区块加载");
                s.update("UPDATE manor SET housing=? WHERE id=?",capacity(m),m.id());
                realm.changed();archive(p,m.id());
            }
            case "estates" -> directory(p);
            case "estateform" -> {
                var fields=new ArrayList<DiplomacyDesk.Field>();fields.add(new DiplomacyDesk.Field("id","庄园代号","",24));fields.add(new DiplomacyDesk.Field("name","庄园名","",30));
                fields.add(new DiplomacyDesk.Field("bounds","围界方块坐标 西 北 东 南","",60));
                DiplomacyDesk.form(p,"立庄测量","瞄准庄宅讲台，填写实际围界。每百人一张室内床，讲台旁设赈济桶。","realm settle",fields);
            }
            case "settle" -> {
                usage(a.length==7,"settle 代号 名称 西 北 东 南（方块）");
                var block=p.getTargetBlockExact(6);require(block!=null && block.getType()==Material.LECTERN,"请瞄准庄宅讲台");
                var m=new SettlementRegister.Manor(a[1],a[2],actor,block.getX(),block.getY(),block.getZ(),bounds(a,3),s.number("SELECT count(*) FROM manor WHERE owner=?",actor)>0?300:100,0,false);
                int capacity=inspect(m);register.estate(m,capacity);realm.changed();archive(p,m.id());
            }
            case "manor" -> {usage(a.length==2,"manor 庄园");archive(p,a[1]);}
            case "surveyclaim" -> {
                usage(a.length==2,"surveyclaim 庄园");var m=register.manor(a[1]);hall(p,m);var b=m.estate();
                DiplomacyDesk.form(p,"拟定建领公示","首都设在本庄。选区须含庄宅，单案最多 36 区块。","realm found "+m.id(),List.of(
                        new DiplomacyDesk.Field("status","政治地位",s.politics.choiceOpen()?"独立":"入帝",4),
                        new DiplomacyDesk.Field("bounds","区块坐标 西 北 东 南",(b.west()>>4)+" "+(b.north()>>4)+" "+(b.east()>>4)+" "+(b.south()>>4),70)));
            }
            case "found","frontier" -> {
                usage(a.length==7,a[0]+" 庄园 入帝|独立（开拓填头衔ID） 西 北 东 南");
                var m=register.manor(a[1]);hall(p,m);inspect(m);boolean founding=a[0].equals("found");
                if(founding)realm.rooms.council(m);
                int n=register.propose(actor,m.id(),founding?m.id():a[2],founding?a[2]:"开拓",bounds(a,3),System.currentTimeMillis()/1000,noticeSeconds);
                realm.changed();p.sendMessage("边界公示 #"+n+" 已张贴");hearing(p,n);
            }
            case "hearing" -> {usage(a.length==2,"hearing 公示号");hearing(p,Integer.parseInt(a[1]));}
            case "object" -> {usage(a.length>=3,"object 公示号 理由");register.object(Integer.parseInt(a[1]),actor,l.getBlockX()>>4,l.getBlockZ()>>4,String.join(" ",Arrays.copyOfRange(a,2,a.length)));p.sendMessage("已记入本区块异议");}
            case "retract" -> {usage(a.length==2,"retract 公示号");s.update("DELETE FROM land_objection WHERE claim=? AND actor=?",Integer.parseInt(a[1]),actor);p.sendMessage("已撤回本人异议");}
            case "cancelclaim","enact" -> {
                usage(a.length==2,a[0]+" 公示号");var c=register.claim(Integer.parseInt(a[1]));var m=register.manor(c.manor());hall(p,m);
                require(m.owner().equals(actor),"须由申请人办理");
                if(a[0].equals("enact")){inspect(m);if(!c.type().equals("开拓"))realm.rooms.council(m);register.resolve(c.id(),actor,System.currentTimeMillis()/1000);}
                else require(s.update("UPDATE claim SET state='撤案' WHERE id=? AND state='公示中'",c.id())==1,"此案已结");
                realm.council.succession.sync();realm.changed();hearing(p,c.id());
            }
            case "permit" -> {usage(a.length==6,"permit 受许头衔 西 北 东 南");register.permit(actor,a[1],bounds(a,2));p.sendMessage("边境开拓封契已签发");}
            case "capital" -> {
                usage(a.length==3,"capital 国家 新庄园");var m=register.manor(a[2]);hall(p,m);inspect(m);
                var bin=realm.fixtures.bin(m.x()>>4,m.z()>>4,"vault",a[1]);
                require(bin!=null,"请先瞄准新公库箱子，使用 /realm seatvault 国家 庄园");
                register.moveCapital(a[1],a[2],actor);realm.changed();realm.dialogs.brief(p,s.title(a[1]));
            }
            case "seatvault" -> {
                usage(a.length==3,"seatvault 国家 庄园");var m=register.manor(a[2]);hall(p,m);
                s.politics.represents(a[1],actor);
                require(m.owner().equals(actor) && a[1].equals(s.owner(m.x()>>4,m.z()>>4)),"须在本国直辖的自有庄园办理");
                var b=p.getTargetBlockExact(6);require(b!=null && m.estate().contains(b.getX(),b.getZ()) && (b.getX()>>4)==(m.x()>>4) && (b.getZ()>>4)==(m.z()>>4),"请瞄准庄宅所在区块的库箱");
                require(b.getType()==Material.BARREL || b.getType()==Material.CHEST,"公库使用桶或箱子");
                realm.fixtures.bind(b,"vault",a[1]);p.sendMessage("已登记治所公库，粮食须实际搬运");
            }
            case "peer" -> {
                usage(a.length>=4,"peer 领邦 申请|批准|驳回 理由");register.peer(actor,a[1],a[2],String.join(" ",Arrays.copyOfRange(a,3,a.length)));
                if(a[2].equals("申请") && s.title("crown").seal().equals("npc-皇廷署理"))
                    register.peer("npc-皇廷署理",a[1],"批准","核准本庄公议与建领文书");
                realm.dialogs.brief(p,s.title(a[1]));
            }
            default -> throw new IllegalArgumentException("未知地籍操作");
        }
    }
    private void hearing(Player p,int id)throws SQLException{
        var c=register.claim(id);var b=c.area();var buttons=new ArrayList<ActionButton>();
        var body=new ArrayList<DialogBody>();
        body.add(text(register.manor(c.manor()).name()+" · "+c.type()+" · "+c.state()));
        body.add(text("区块边界　"+b.west()+"，"+b.north()+" 至 "+b.east()+"，"+b.south()+"　｜　"+b.area()+" 块"));
        body.add(text("公示至 "+java.time.Instant.ofEpochSecond(c.until())+"，且须跨过一个生产季。当前异议 "+s.number("SELECT count(*) FROM land_objection WHERE claim=?",id)+" 件。"));
        if(c.state().equals("公示中")){
            buttons.add(command("登记所在区块异议","realm object "+id+" 请求复核当地权属"));
            buttons.add(command("撤回本人异议","realm retract "+id));
            if(register.manor(c.manor()).owner().equals(p.getUniqueId().toString())){
                buttons.add(command("公示期满裁定","realm enact "+id));buttons.add(command("撤回申请","realm cancelclaim "+id));
            }
        }
        buttons.add(command("庄园档案","realm manor "+c.manor()));
        show(p,"边界公示 #"+id,body,List.of(),buttons,2);
    }
}
