package social.feudal.transport;

import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

/** Mannequins have no mob navigation. Plan short walks on loaded, supported blocks and let entity physics move the worker. */
final class FootNavigation {
    private record Cell(int x,int y,int z){
        static Cell at(Location l){return new Cell(l.getBlockX(),l.getBlockY(),l.getBlockZ());}
        int distance(Cell other){return Math.abs(x-other.x)+Math.abs(y-other.y)+Math.abs(z-other.z);}
    }
    private record Node(Cell cell,int spent,int cost){}
    private static final int[][] DIRECTIONS={{1,0},{-1,0},{0,1},{0,-1}};
    private static final class Walk {
        Location goal;
        double speed;
        int expires,refresh;
        boolean road;
        final Deque<Cell> path=new ArrayDeque<>();
    }
    private final Map<LivingEntity,Walk> walks=new HashMap<>();
    FootNavigation(Plugin plugin){Bukkit.getScheduler().runTaskTimer(plugin,this::tick,1,1);}
    void toward(LivingEntity body,Location goal,double speed){
        target(body,goal,speed,false);
    }
    void onRoad(LivingEntity body,Location goal,double speed){target(body,goal,speed,true);}
    private void target(LivingEntity body,Location goal,double speed,boolean road){
        var walk=walks.computeIfAbsent(body,k->new Walk());
        if(walk.goal==null || !Cell.at(goal).equals(Cell.at(walk.goal)))walk.refresh=0;
        walk.goal=goal.clone();walk.speed=speed;walk.road=road;walk.expires=road?7:30;
    }
    void stop(LivingEntity body){
        walks.remove(body);
        if(body.isValid()){Vector v=body.getVelocity();body.setVelocity(new Vector(0,Math.min(0,v.getY()),0));}
    }
    private static boolean floor(World w,Cell c){
        if(c.y<w.getMinHeight()+1 || c.y+1>=w.getMaxHeight() || !w.isChunkLoaded(c.x>>4,c.z>>4))return false;
        var feet=w.getBlockAt(c.x,c.y,c.z);
        var below=feet.getRelative(0,-1,0);
        return feet.isPassable() && !feet.isLiquid() && feet.getRelative(0,1,0).isPassable()
                && !below.isPassable() && below.getCollisionShape().getBoundingBoxes().stream().anyMatch(b->b.getMaxY()<=1.01 && b.getMaxY()>=.45);
    }
    private static void route(World world,Cell start,Cell goal,Deque<Cell> path){
        path.clear();var queue=new PriorityQueue<Node>(Comparator.comparingInt(Node::cost).thenComparingInt(n->-n.spent()));
        Map<Cell,Integer> costs=new HashMap<>();Map<Cell,Cell> parents=new HashMap<>();
        queue.add(new Node(start,0,start.distance(goal)));costs.put(start,0);Cell best=start;
        for(int left=512;left>0 && !queue.isEmpty();left--){
            Node node=queue.remove();Cell current=node.cell;
            if(node.spent!=costs.get(current))continue;
            if(current.distance(goal)<best.distance(goal))best=current;
            if(current.equals(goal) || node.spent>=32 && !best.equals(start))break;
            for(int[] dir:DIRECTIONS)for(int height:new int[]{0,1,-1}){
                var next=new Cell(current.x+dir[0],current.y+height,current.z+dir[1]);
                if(!floor(world,next))continue;
                if(height>0 && !world.getBlockAt(current.x,current.y+2,current.z).isPassable())continue;
                int spent=node.spent+1+Math.abs(height);
                if(spent<costs.getOrDefault(next,Integer.MAX_VALUE)){
                    costs.put(next,spent);parents.put(next,current);queue.add(new Node(next,spent,spent+next.distance(goal)));
                }
                break;
            }
        }
        for(Cell c=best;!c.equals(start);c=parents.get(c))path.addFirst(c);
    }
    private void tick(){
        var it=walks.entrySet().iterator();
        while(it.hasNext()){
            var entry=it.next();LivingEntity body=entry.getKey();Walk walk=entry.getValue();
            if(!body.isValid() || --walk.expires<=0 || Bukkit.getOnlinePlayers().isEmpty()){
                if(body.isValid())body.setVelocity(new Vector(0,Math.min(0,body.getVelocity().getY()),0));it.remove();continue;
            }
            Location here=body.getLocation();
            if(!walk.road && --walk.refresh<=0){
                route(here.getWorld(),Cell.at(here),Cell.at(walk.goal),walk.path);walk.refresh=20;
            }
            Cell next=walk.road?null:walk.path.peekFirst();
            if(next!=null && Math.hypot(next.x+.5-here.getX(),next.z+.5-here.getZ())<.22 && Math.abs(next.y-here.getY())<.65){
                walk.path.removeFirst();next=walk.path.peekFirst();
            }
            if(!walk.road && next==null && !Cell.at(here).equals(Cell.at(walk.goal)))continue;
            if(next!=null && !floor(here.getWorld(),next)){walk.refresh=0;continue;}
            double x=(next==null?walk.goal.getX():next.x+.5)-here.getX();
            double z=(next==null?walk.goal.getZ():next.z+.5)-here.getZ();
            double length=Math.hypot(x,z);if(length<.05)continue;
            double velocity=Math.min(length,walk.speed*.16);
            double y=body.getVelocity().getY();
            if(next!=null && next.y>here.getY()+.5 && body.isOnGround())y=.42;
            float yaw=(float)Math.toDegrees(Math.atan2(-x,z));body.setRotation(yaw,0);body.setBodyYaw(yaw);
            body.setVelocity(new Vector(x/length*velocity,y,z/length*velocity));
        }
    }
}
