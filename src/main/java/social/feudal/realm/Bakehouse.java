package social.feudal.realm;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.papermc.paper.event.server.ServerResourcesReloadedEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.type.Slab;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;

/** Physical hand mill, kneading board and hearth. Unfinished strokes reserve no inventory. */
final class Bakehouse implements Listener {
    private static final NamespacedKey VANILLA_BREAD=NamespacedKey.minecraft("bread");
    private final RealmService realm;
    final ItemStack flour;
    final ItemStack dough;
    private final NamespacedKey hearth,oldLoaf;
    private Recipe replacedBread;
    private record Batch(Block board,int slot,int strokes,long tick){}
    private final Map<UUID,Batch> batches=new HashMap<>();

    Bakehouse(RealmService realm){
        this.realm=realm;
        flour=new ItemStack(Material.SUGAR);
        flour.editMeta(meta->{
            meta.displayName(Component.text("小麦粉"));
            meta.getPersistentDataContainer().set(new NamespacedKey(realm.plugin,"flour"),PersistentDataType.BYTE,(byte)1);
        });
        dough=new ItemStack(Material.DISC_FRAGMENT_5);
        dough.editMeta(meta->{
            meta.displayName(Component.text("生面团"));
            meta.setItemModel(NamespacedKey.minecraft("clay_ball"));
            meta.getPersistentDataContainer().set(new NamespacedKey(realm.plugin,"dough"),PersistentDataType.BYTE,(byte)1);
        });
        hearth=new NamespacedKey(realm.plugin,"hearth_bread");
        oldLoaf=new NamespacedKey(realm.plugin,"milled_bread");
        install();
        Bukkit.getPluginManager().registerEvents(this,realm.plugin);
    }
    private void install(){
        var original=Bukkit.getRecipe(VANILLA_BREAD);
        if(original!=null)replacedBread=original;
        Bukkit.removeRecipe(VANILLA_BREAD,false);
        Bukkit.removeRecipe(oldLoaf,false);
        Bukkit.removeRecipe(hearth,false);
        var recipe=new CampfireRecipe(hearth,new ItemStack(Material.BREAD),new RecipeChoice.ExactChoice(dough),0,160);
        if(!Bukkit.addRecipe(recipe,false))throw new IllegalStateException("无法登记炉火烤面包工艺");
        Bukkit.updateRecipes();
    }
    @EventHandler public void reload(ServerResourcesReloadedEvent event){install();}
    void close(){
        if(Bukkit.isStopping())return;
        Bukkit.removeRecipe(hearth,false);
        if(replacedBread!=null && !Bukkit.hasRecipe(VANILLA_BREAD))Bukkit.addRecipe(replacedBread,false);
        Bukkit.updateRecipes();
    }

    /** Credit one item and debit from the selected stack in a single server-thread interaction. */
    private boolean exchange(Player player,int count,ItemStack result){
        var inv=player.getInventory();
        var held=inv.getItemInMainHand();
        if(held.getAmount()<count)return false;
        if(held.getAmount()==count)inv.setItemInMainHand(result.clone());
        else{
            if(!inv.addItem(result.clone()).isEmpty()){
                player.sendActionBar(Component.text("请腾出一个物品格，再继续作业"));return false;
            }
            inv.setItemInMainHand(held.asQuantity(held.getAmount()-count));
        }
        return true;
    }

    void handMill(Player p,Block wheel){
        Material base=wheel.getRelative(BlockFace.DOWN).getType();
        if(!(wheel.getBlockData() instanceof FaceAttachable mounted) || mounted.getAttachedFace()!=FaceAttachable.AttachedFace.FLOOR ||
                (base!=Material.STONE && base!=Material.COBBLESTONE)){
            p.sendActionBar(Component.text("手磨要把磨石立在圆石或石头基座上"));return;
        }
        if(p.hasCooldown(Material.WHEAT) || !exchange(p,1,flour))return;
        p.setCooldown(Material.WHEAT,30);
        animateGrinding(p,wheel);
        p.sendActionBar(Component.text("磨得一份小麦粉 · 三份面粉可在水边木案揉面"));
    }

    void animateGrinding(LivingEntity worker,Block wheel){
        if(!(worker instanceof Player)){
            var facing=wheel.getLocation().add(.5,.8,.5).subtract(worker.getEyeLocation()).toVector();
            var rotation=worker.getLocation().setDirection(facing);
            worker.setRotation(rotation.getYaw(),rotation.getPitch());
        }
        worker.swingMainHand();
        var world=wheel.getWorld();
        var center=wheel.getLocation().add(.5,.91,.5);
        world.playSound(center,Sound.BLOCK_GRINDSTONE_USE,.55f,.82f);
        for(int frame=0;frame<6;frame++){
            double angle=frame*Math.PI/3;
            var point=center.clone().add(.31*Math.cos(angle),.04,.31*Math.sin(angle));
            final int phase=frame;
            Bukkit.getScheduler().runTaskLater(realm.plugin,()->{
                if(!worker.isValid() || !world.isChunkLoaded(wheel.getX()>>4,wheel.getZ()>>4) ||
                        wheel.getType()!=Material.GRINDSTONE || worker.getWorld()!=world || worker.getLocation().distanceSquared(center)>25)return;
                if(phase==3)worker.swingMainHand();
                world.spawnParticle(Particle.ITEM,point,5,.05,.045,.05,.035,phase<3?new ItemStack(Material.WHEAT):flour);
            },frame*2L);
        }
    }

    private boolean besideWater(Block board){
        for(var face:List.of(BlockFace.NORTH,BlockFace.SOUTH,BlockFace.EAST,BlockFace.WEST)){
            Block side=board.getRelative(face);
            if(side.getType()==Material.WATER || side.getRelative(BlockFace.DOWN).getType()==Material.WATER)return true;
        }
        return false;
    }
    boolean boardReady(Block board){
        return Tag.WOODEN_SLABS.isTagged(board.getType()) && board.getRelative(BlockFace.UP).isEmpty() && besideWater(board);
    }
    @EventHandler(ignoreCancelled=true) public void knead(PlayerInteractEvent e){
        if(e.getHand()!=EquipmentSlot.HAND || e.getAction()!=Action.RIGHT_CLICK_BLOCK)return;
        var p=e.getPlayer();var board=e.getClickedBlock();
        if(board==null || board.getWorld()!=realm.world || !Tag.WOODEN_SLABS.isTagged(board.getType()))return;
        var held=p.getInventory().getItemInMainHand();
        if(!held.isSimilar(flour))return;
        e.setCancelled(true);
        if(!boardReady(board)){
            p.sendActionBar(Component.text("案面上方留空，旁边一格留水（可低一格）"));return;
        }
        if(held.getAmount()<3){p.sendActionBar(Component.text("请把三份小麦粉放在同一个主手格"));return;}
        long tick=realm.world.getGameTime();int slot=p.getInventory().getHeldItemSlot();
        var id=p.getUniqueId();var previous=batches.get(id);
        int stroke=1;
        if(previous!=null && previous.board().equals(board) && previous.slot()==slot && tick-previous.tick()<=100){
            if(tick-previous.tick()<8)return;
            stroke=previous.strokes()+1;
        }
        if(stroke==3 && !exchange(p,3,dough))return;
        if(stroke==3)batches.remove(id);else batches.put(id,new Batch(board,slot,stroke,tick));
        var slab=(Slab)board.getBlockData();
        var point=board.getLocation().add(.25+.13*stroke,slab.getType()==Slab.Type.BOTTOM?.55:1.05,.5);
        p.swingMainHand();
        board.getWorld().spawnParticle(Particle.ITEM,point,12,.12,.05,.08,.04,flour);
        board.getWorld().playSound(point,Sound.BLOCK_MUD_HIT,.65f,.8f+.1f*stroke);
        p.sendActionBar(Component.text(stroke==3?"生面团已揉好 · 手持面团右键营火烤制":"揉面 "+stroke+"/3 · 继续按揉"));
    }
    @EventHandler public void leave(PlayerQuitEvent event){batches.remove(event.getPlayer().getUniqueId());}

    @EventHandler(ignoreCancelled=true) public void baked(BlockCookEvent event){
        if(event.getRecipe()==null || !hearth.equals(event.getRecipe().getKey()))return;
        var point=event.getBlock().getLocation().add(.5,.55,.5);
        point.getWorld().spawnParticle(Particle.WHITE_SMOKE,point,8,.16,.1,.16,.02);
        point.getWorld().playSound(point,Sound.BLOCK_FIRE_EXTINGUISH,.35f,1.55f);
    }
}
