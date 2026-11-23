package social.feudal.transport;

import org.bukkit.Material;
import org.bukkit.Tag;

/** Player load is per person. A porter's load remains limited by the team's physical equipment. */
public enum RoadSurface {
    EARTH("夯土路",64,5), GRAVEL("碎石路",96,12), WOOD("木栈道",128,20),
    COBBLE("圆石路",128,16), MASONRY("砖石大道",160,28);
    public final String label;
    public final int load, bonus;
    RoadSurface(String label,int load,int bonus){this.label=label;this.load=load;this.bonus=bonus;}
    public String description(){return label+"　"+load+" 份，步速 +"+bonus+"%";}
    public double crewPace(){return (100.0+bonus)/120.0;}

    public static RoadSurface of(Material material){
        return switch(material){
            case DIRT_PATH,PACKED_MUD -> EARTH;
            case GRAVEL -> GRAVEL;
            case COBBLESTONE,MOSSY_COBBLESTONE,COBBLESTONE_SLAB,MOSSY_COBBLESTONE_SLAB,
                    COBBLESTONE_STAIRS,MOSSY_COBBLESTONE_STAIRS -> COBBLE;
            case STONE_BRICKS,BRICKS,SMOOTH_STONE,POLISHED_ANDESITE,DEEPSLATE_BRICKS,MUD_BRICKS -> MASONRY;
            default -> {
                if(Tag.PLANKS.isTagged(material) || Tag.WOODEN_SLABS.isTagged(material) || Tag.WOODEN_STAIRS.isTagged(material))yield WOOD;
                yield Tag.SLABS.isTagged(material) || Tag.STAIRS.isTagged(material)?MASONRY:null;
            }
        };
    }
}
