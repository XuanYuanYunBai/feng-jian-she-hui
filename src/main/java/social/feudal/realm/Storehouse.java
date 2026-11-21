package social.feudal.realm;

import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Stairs;

/** A timber granary on stone piers. Keep the entire structure inside its registered chunk. */
final class Storehouse {
    record Site(int x, int floor, int z) {
        Location counter(World world) { return new Location(world, x, floor+1, z+1); }
    }
    private static final Set<Material> GROUND = EnumSet.of(Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT,
            Material.PODZOL, Material.MYCELIUM, Material.ROOTED_DIRT, Material.STONE, Material.ANDESITE, Material.DIORITE,
            Material.GRANITE, Material.GRAVEL, Material.CLAY, Material.SAND, Material.RED_SAND, Material.SANDSTONE,
            Material.RED_SANDSTONE, Material.MUD, Material.SNOW_BLOCK, Material.ICE, Material.PACKED_ICE,
            Material.MOSS_BLOCK, Material.WATER);
    private static boolean soft(Block block) {
        Material m = block.getType();
        if (Tag.LEAVES.isTagged(m)) return !((Leaves)block.getBlockData()).isPersistent();
        return m.isAir() || m==Material.SHORT_GRASS || m==Material.TALL_GRASS || m==Material.FERN
                || m==Material.LARGE_FERN || m==Material.SNOW || m==Material.LEAF_LITTER || Tag.FLOWERS.isTagged(m);
    }

    static Site find(Chunk chunk, boolean mill) {
        World w = chunk.getWorld();
        int ox = chunk.getX()*16, oz = chunk.getZ()*16;
        // Cache the terrain sample shared by all candidate footprints.
        int[][] ground = new int[16][16];
        for (int x=0;x<16;x++) for (int z=0;z<16;z++) {
            int y = w.getHighestBlockYAt(ox+x, oz+z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            ground[x][z] = y;
        }
        Site best = null;
        int bestCost = Integer.MAX_VALUE;
        for (int x=3;x<=12;x++) for (int z=3;z<=(mill?11:12);z++) {
            int floor = w.getMinHeight(), low = w.getMaxHeight();
            for (int dx=-2;dx<=2;dx++) for (int dz=-2;dz<=2;dz++) {
                int h=ground[x+dx][z+dz];
                floor=Math.max(floor,h);low=Math.min(low,h);
            }
            int cost = (floor-low)*2 + Math.abs(x-8)+Math.abs(z-8);
            if (cost>=bestCost || floor-low>6 || floor+8>=w.getMaxHeight()) continue;
            var site = new Site(ox+x, floor+1, oz+z);
            if (fits(w, site, ground, x, z, mill)) { best=site; bestCost=cost; }
        }
        return best;
    }

    private static boolean fits(World world, Site s, int[][] ground, int cx, int cz, boolean mill) {
        for (int x=-3;x<=3;x++) for (int z=-3;z<=3;z++) {
            boolean inside=Math.abs(x)<=2 && Math.abs(z)<=2;
            int bottom=inside || (x==0 && z==3) ? 0 : 3;
            if (inside || (x==0 && z==3)) {
                int h=ground[cx+x][cz+z];
                if (h<world.getMinHeight() || !GROUND.contains(world.getBlockAt(s.x+x,h,s.z+z).getType())) return false;
                if(x==0 && z==3 && Math.abs(h-s.floor)>3) return false;
            }
            int top = 6-Math.abs(x);
            for (int y=bottom;y<=top;y++)
                if (!soft(world.getBlockAt(s.x+x,s.floor+y,s.z+z))) return false;
        }
        if (mill) {
            for(int x=-2;x<=2;x++) for(int y=3;y<=7;y++)
                if(!soft(world.getBlockAt(s.x+x,s.floor+y,s.z+4)))return false;
        }
        return true;
    }

    static void build(World world, Site s, RealmStore.Depot depot) {
        for (int x=-3;x<=3;x++) for (int z=-3;z<=3;z++) {
            boolean inside=Math.abs(x)<=2 && Math.abs(z)<=2;
            int roof=6-Math.abs(x);
            for(int y=(inside || (z==3 && x==0))?0:3;y<roof;y++) {
                Block b=world.getBlockAt(s.x+x,s.floor+y,s.z+z);
                if(!b.isEmpty()) b.setType(Material.AIR,false);
            }
            Block top=world.getBlockAt(s.x+x,s.floor+roof,s.z+z);
            if(x==0) top.setType(Material.DARK_OAK_PLANKS,false);
            else {
                var stair=(Stairs)Material.SPRUCE_STAIRS.createBlockData();
                stair.setFacing(x<0?BlockFace.EAST:BlockFace.WEST);
                top.setBlockData(stair,false);
            }
            if (!inside) continue;
            place(world,s,x,0,z,Material.SPRUCE_PLANKS);
            if (Math.abs(x)==2 && Math.abs(z)==2) {
                for(int y=-1;y>=-8;y--){
                    Block b=world.getBlockAt(s.x+x,s.floor+y,s.z+z);
                    if(!soft(b) && b.getType()!=Material.WATER) break;
                    b.setType(Material.COBBLESTONE,false);
                }
                for(int y=1;y<roof;y++) place(world,s,x,y,z,Material.STRIPPED_SPRUCE_LOG);
            } else if (Math.abs(x)==2 || z==-2) {
                place(world,s,x,1,z,Material.STONE_BRICKS);
                place(world,s,x,2,z,z==0 || (x==0 && z==-2)?Material.OAK_FENCE:Material.WHITE_TERRACOTTA);
                for(int y=3;y<roof;y++) place(world,s,x,y,z,Material.SPRUCE_PLANKS);
            } else if (z==2) {
                for(int y=3;y<roof;y++) place(world,s,x,y,z,x==0 || y==3 ? Material.DARK_OAK_PLANKS : Material.WHITE_TERRACOTTA);
            }
        }
        place(world,s,-1,1,-1,Material.HAY_BLOCK);
        place(world,s,-1,2,-1,Material.HAY_BLOCK);
        place(world,s,0,1,-1,Material.HAY_BLOCK);
        place(world,s,1,1,-1,Material.CRAFTING_TABLE);
        place(world,s,0,1,1,Material.LECTERN);
        place(world,s,1,1,1,Material.OAK_PLANKS);
        place(world,s,1,2,1,Material.LANTERN);
        if (depot.id().equals("mill")) {
            // Four canvas sails on the outer face of the gable.
            place(world,s,0,5,3,Material.STRIPPED_SPRUCE_LOG);
            for(int x=-2;x<=2;x++) for(int y=3;y<=7;y++){
                Block clearance=world.getBlockAt(s.x+x,s.floor+y,s.z+4);
                if(!clearance.isEmpty())clearance.setType(Material.AIR,false);
            }
            for(int offset=-2;offset<=2;offset++){
                place(world,s,offset,5,4,Material.STRIPPED_SPRUCE_LOG);
                place(world,s,0,5+offset,4,Material.STRIPPED_SPRUCE_LOG);
            }
            place(world,s,-2,6,4,Material.WHITE_WOOL);
            place(world,s,-1,6,4,Material.WHITE_WOOL);
            place(world,s,1,7,4,Material.WHITE_WOOL);
            place(world,s,1,6,4,Material.WHITE_WOOL);
            place(world,s,2,4,4,Material.WHITE_WOOL);
            place(world,s,1,4,4,Material.WHITE_WOOL);
            place(world,s,-1,3,4,Material.WHITE_WOOL);
            place(world,s,-1,4,4,Material.WHITE_WOOL);
        } else {
            Material trim=depot.id().equals("glebe")?Material.BLUE_WOOL:Material.RED_WOOL;
            for(int x=-2;x<=2;x++) if(x!=0)place(world,s,x,3,3,Math.abs(x)==1?Material.WHITE_WOOL:trim);
        }
        var plaque=(org.bukkit.block.data.Directional)Material.DARK_OAK_WALL_SIGN.createBlockData();
        plaque.setFacing(BlockFace.SOUTH);
        var signBlock=world.getBlockAt(depot.id().equals("mill")?s.x+2:s.x,s.floor+3,s.z+3);
        signBlock.setBlockData(plaque,false);
        var sign=(org.bukkit.block.Sign)signBlock.getState();
        var front=sign.getSide(org.bukkit.block.sign.Side.FRONT);
        front.line(1,net.kyori.adventure.text.Component.text(depot.name()));
        front.line(2,net.kyori.adventure.text.Component.text("税务账房"));
        front.setColor(DyeColor.WHITE);front.setGlowingText(true);
        sign.setWaxed(true);sign.update();
        var step=(Stairs)Material.COBBLESTONE_STAIRS.createBlockData();
        step.setFacing(BlockFace.NORTH);
        world.getBlockAt(s.x,s.floor,s.z+3).setBlockData(step,false);
    }
    private static void place(World w,Site s,int x,int y,int z,Material m){
        w.getBlockAt(s.x+x,s.floor+y,s.z+z).setType(m,false);
    }
}
