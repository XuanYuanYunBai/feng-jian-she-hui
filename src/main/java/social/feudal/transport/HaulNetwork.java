package social.feudal.transport;

import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

/** Survey the physical surface. Points are deck blocks (or water-surface blocks for boats). */
public final class HaulNetwork {
    public record Point(int x,int y,int z) {
        public static Point of(Block b){return new Point(b.getX(),b.getY(),b.getZ());}
        public Point offset(int dx,int dy,int dz){return new Point(x+dx,y+dy,z+dz);}
        public Location feet(World w){return new Location(w,x+.5,y+1,z+.5);}
        public int distance(Point p){return Math.abs(x-p.x)+Math.abs(y-p.y)+Math.abs(z-p.z);}
    }
    public record Gate(String id,Point post,int nx,int nz){}
    static final int[][] DIR={{1,0},{-1,0},{0,1},{0,-1}};
    private final Map<Point,Gate> gates=new HashMap<>();
    private final World world;
    public HaulNetwork(World world){this.world=world;}
    public Gate gate(Point p){return gates.get(p);}
    public void remember(Gate g){
        for(int across=1;across<=3;across++)for(int along=-3;along<=3;along++)
            gates.put(deck(g,across,along),g);
    }
    private static Point deck(Gate g,int across,int along){
        return g.post.offset(g.nx*across-g.nz*along,-1,g.nz*across+g.nx*along);
    }
    public boolean loaded(Point p){
        return p.y>=world.getMinHeight()+1 && p.y+3<world.getMaxHeight() && world.isChunkLoaded(p.x>>4,p.z>>4);
    }
    public Block block(Point p){return world.getBlockAt(p.x,p.y,p.z);}
    public static boolean paving(Material m){
        return Tag.PLANKS.isTagged(m) || switch(m){
            case DIRT_PATH,GRAVEL,COBBLESTONE,MOSSY_COBBLESTONE,STONE_BRICKS,BRICKS,SMOOTH_STONE,
                    POLISHED_ANDESITE,DEEPSLATE_BRICKS,PACKED_MUD,MUD_BRICKS -> true;
            default -> false;
        };
    }
    private boolean bareDeck(Point p){
        if(!loaded(p))return false;
        Block b=block(p);
        if(b.getType()==Material.GRAVEL && (b.getRelative(BlockFace.DOWN).getType()==Material.GRAVEL || !paving(b.getRelative(BlockFace.DOWN).getType())))return false;
        return paving(b.getType()) && clear(b,3);
    }
    private static boolean clear(Block b,int height){
        for(int i=1;i<=height;i++){
            Block at=b.getRelative(0,i,0);
            if(!at.isPassable() || at.isLiquid())return false;
        }
        return true;
    }
    public boolean road(Point p){
        Gate g=gates.get(p);
        if(g!=null)return open(g);
        if(!bareDeck(p))return false;
        if(supported(p))return true;
        return bridgeSpan(p);
    }
    private boolean supported(Point p){
        return block(p).getRelative(BlockFace.DOWN).getType().isSolid();
    }
    // An unchartered bridge needs two continuous deck lanes between abutments or piers.
    private boolean bridgeSpan(Point p){
        for(int[] side:DIR){
            var lane=p.offset(side[0],0,side[1]);
            if(!bareDeck(lane))continue;
            int dx=-side[1],dz=side[0];
            int first=supportDistance(p,lane,dx,dz,16);
            if(first>0 && supportDistance(p,lane,-dx,-dz,16-first)>0)return true;
        }
        return false;
    }
    private int supportDistance(Point a,Point b,int dx,int dz,int limit){
        for(int step=1;step<=limit;step++){
            a=a.offset(dx,0,dz);b=b.offset(dx,0,dz);
            if(!bareDeck(a) || !bareDeck(b))break;
            if(supported(a) && supported(b))return step;
        }
        return -1;
    }
    public boolean open(Gate g){
        if(!loaded(g.post))return false;
        Block post=block(g.post);
        if(post.getType()!=Material.LECTERN || post.getRelative(-g.nx,0,-g.nz).getType()!=Material.BARREL)return false;
        for(int i=1;i<=3;i++)for(int j=-3;j<=3;j++)if(!bareDeck(deck(g,i,j)))return false;
        for(int j:new int[]{-4,4}){
            Point approach=deck(g,2,j);
            if(!bareDeck(approach) || !block(approach).getRelative(BlockFace.DOWN).getType().isSolid())return false;
        }
        return true;
    }
    public Gate surveyGate(String id,Block post){
        for(var dir:DIR){
            Gate g=new Gate(id,Point.of(post),dir[0],dir[1]);
            if(open(g)){
                for(int i=1;i<=3;i++)for(int j=-3;j<=3;j++)
                    if(gates.containsKey(deck(g,i,j)))throw new IllegalArgumentException("该路段已有收费亭");
                return g;
            }
        }
        throw new IllegalArgumentException("收费亭须紧邻三宽七长的铺装路段，两端各接实路，讲台背向道路的一侧放收费桶");
    }
    public boolean water(Point p){
        return loaded(p) && block(p).getType()==Material.WATER && clear(block(p),2);
    }
    /** A quay has a warehouse barrel, two planks of pier and a fence bollard beside it. */
    public boolean port(Point store,Point berth){
        if(!loaded(store) || block(store).getType()!=Material.BARREL || !water(berth))return false;
        int dx=Integer.signum(berth.x-store.x),dz=Integer.signum(berth.z-store.z);
        if(Math.abs(dx)+Math.abs(dz)!=1 || !store.offset(3*dx,-1,3*dz).equals(berth))return false;
        for(int i=1;i<=2;i++){
            Block pier=block(store.offset(i*dx,0,i*dz));
            if(!Tag.PLANKS.isTagged(pier.getType()) || !clear(pier,2))return false;
        }
        return Tag.FENCES.isTagged(block(store.offset(2*dx-dz,1,2*dz+dx)).getType())
                || Tag.FENCES.isTagged(block(store.offset(2*dx+dz,1,2*dz-dx)).getType());
    }
    public Point berth(Point station){
        for(int[] d:DIR){
            var b=station.offset(3*d[0],-1,3*d[1]);
            if(port(station,b))return b;
        }
        throw new IllegalArgumentException("港仓须连接两格木栈桥、系缆桩和开放的水面泊位");
    }
    public List<Point> survey(Point start,Point end,boolean sail,boolean stationEnds){
        if(start.equals(end) || start.distance(end)>512)return List.of();
        record Node(Point point,int steps,int score){}
        var queue=new PriorityQueue<Node>(Comparator.comparingInt(Node::score).thenComparingInt(Node::steps));
        var cost=new HashMap<Point,Integer>();var prev=new HashMap<Point,Point>();
        queue.add(new Node(start,0,start.distance(end)));cost.put(start,0);
        while(!queue.isEmpty() && cost.size()<12000){
            var node=queue.remove();Point p=node.point();
            if(node.steps()!=cost.get(p))continue;
            if(p.equals(end)){
                var path=new ArrayList<Point>();
                for(Point cell=end;cell!=null;cell=prev.get(cell))path.add(cell);
                Collections.reverse(path);return List.copyOf(path);
            }
            if(node.steps()>=512)continue;
            for(int[] d:DIR)for(int dy:new int[]{0,1,-1}){
                if(sail && dy!=0)continue;
                Point n=p.offset(d[0],dy,d[1]);int next=node.steps()+1;
                if(cost.getOrDefault(n,Integer.MAX_VALUE)<=next || !loaded(n))continue;
                boolean atStation=stationEnds && n.equals(end) && block(n).getType()==Material.BARREL && clear(block(n),2);
                if(!atStation && !(sail?water(n):road(n)))continue;
                cost.put(n,next);prev.put(n,p);queue.add(new Node(n,next,next+Math.abs(n.x-end.x)+Math.abs(n.z-end.z)));
            }
        }
        return List.of();
    }
}
