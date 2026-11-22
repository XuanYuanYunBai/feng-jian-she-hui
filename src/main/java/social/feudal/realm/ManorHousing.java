package social.feudal.realm;

import java.util.*;
import org.bukkit.World;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Door;

/** Inspect rooms at their bed level. A closed door is a boundary even when opened by a resident. */
final class ManorHousing {
    private static final List<BlockFace> SIDES=List.of(BlockFace.NORTH,BlockFace.EAST,BlockFace.SOUTH,BlockFace.WEST);

    static int capacity(World world, SettlementRegister.Manor manor) {
        return capacity(world,manor,null);
    }
    static int capacity(World world, SettlementRegister.Manor manor,Block privateBed) {
        var bounds=manor.estate();
        var visited=new HashSet<Block>();
        int beds=0;
        for(int x=bounds.west()+1;x<bounds.east();x++)for(int z=bounds.north()+1;z<bounds.south();z++)
            for(int y=Math.max(world.getMinHeight()+1,manor.y()-2);y<=Math.min(world.getMaxHeight()-3,manor.y()+2);y++){
                var block=world.getBlockAt(x,y,z);
                if(Tag.BEDS.isTagged(block.getType()) && !visited.contains(block))beds+=room(block,bounds,visited,privateBed);
            }
        return Math.multiplyExact(100,beds);
    }

    private static int room(Block start, Charters.Bounds bounds, Set<Block> visited,Block privateBed) {
        var queue=new ArrayDeque<Block>();
        queue.add(start);
        visited.add(start);
        var exits=new HashSet<Block>();
        var interior=new HashSet<Block>();
        boolean enclosed=true;
        int beds=0;
        while(!queue.isEmpty()){
            var b=queue.remove();
            interior.add(b);
            boolean isBed=Tag.BEDS.isTagged(b.getType());
            if(isBed && !b.equals(privateBed) && ((Bed)b.getBlockData()).getPart()==Bed.Part.HEAD)beds++;
            if(!b.getRelative(BlockFace.DOWN).getType().isSolid() || !b.getRelative(BlockFace.UP).isPassable())enclosed=false;
            boolean roof=false;
            for(int dy=2;dy<=8 && b.getY()+dy<b.getWorld().getMaxHeight();dy++)
                if(b.getRelative(0,dy,0).getType().isSolid()){roof=true;break;}
            if(!roof)enclosed=false;
            for(var side:SIDES){
                var n=b.getRelative(side);
                if(n.getBlockData() instanceof Door entrance){
                    var axis=entrance.getFacing();
                    if(side==axis || side==axis.getOppositeFace()){
                        var outside=n.getRelative(side);
                        if(outside.isPassable() && outside.getRelative(BlockFace.UP).isPassable()
                                && outside.getRelative(BlockFace.DOWN).getType().isSolid())exits.add(outside);
                    }
                    continue;
                }
                if(n.getType().isSolid() && !Tag.BEDS.isTagged(n.getType())){
                    if(!n.getRelative(BlockFace.UP).getType().isSolid())enclosed=false;
                    continue;
                }
                if(n.getX()<=bounds.west() || n.getX()>=bounds.east() || n.getZ()<=bounds.north() || n.getZ()>=bounds.south()){
                    enclosed=false;continue;
                }
                if(visited.add(n))queue.add(n);
            }
        }
        exits.removeAll(interior);
        return enclosed && !exits.isEmpty()?beds:0;
    }
}
