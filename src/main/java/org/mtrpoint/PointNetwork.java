package org.mtrpoint;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.mtrpoint.geometry.*;
import org.mtrpoint.compat.BrObserver;
import java.util.*;

public final class PointNetwork {
    private static final SimpleChannel CHANNEL=NetworkRegistry.newSimpleChannel(new ResourceLocation(PointMod.ID,"appearance"),()->"4","4"::equals,"4"::equals);
    public record Edit(String id,BlockPos center,long revision,String json) {}
    public record State(String dimension,boolean clear,String id,long revision,String json,String message) {}
    public record Entry(String id,long revision,String json) {}
    public record Batch(String dimension,List<Entry> entries) {}
    public record Movement(String node,String from,String to,boolean occupied,long vehicle,double distance) {}
    public record Motion(String dimension,boolean brPresent,long timestamp,List<Movement> entries) {}
    /** The settings JSON plus a safety margin. The client clamps every manual list, but a maximal
     * point is close enough to the old 16384 cap that an edit broadcast could throw EncoderException
     * on the server thread instead of reporting a rejected edit. */
    private static final int JSON_LIMIT=65536;
    public static void init(){
        CHANNEL.messageBuilder(Edit.class,0,NetworkDirection.PLAY_TO_SERVER)
            .encoder((m,b)->{b.writeUtf(m.id,512);b.writeBlockPos(m.center);b.writeLong(m.revision);b.writeUtf(m.json,JSON_LIMIT);})
            .decoder(b->new Edit(b.readUtf(512),b.readBlockPos(),b.readLong(),b.readUtf(JSON_LIMIT)))
            .consumerMainThread((m,c)->edit(m,c.get().getSender())).add();
        CHANNEL.messageBuilder(State.class,1,NetworkDirection.PLAY_TO_CLIENT)
            .encoder((m,b)->{b.writeUtf(m.dimension);b.writeBoolean(m.clear);b.writeUtf(m.id,512);b.writeLong(m.revision);b.writeUtf(m.json,JSON_LIMIT);b.writeUtf(m.message,128);})
            .decoder(b->new State(b.readUtf(),b.readBoolean(),b.readUtf(512),b.readLong(),b.readUtf(JSON_LIMIT),b.readUtf(128)))
            .consumerMainThread((m,c)->org.mtrpoint.client.PointClient.receive(m)).add();
        CHANNEL.messageBuilder(Motion.class,2,NetworkDirection.PLAY_TO_CLIENT)
            .encoder(MotionCodec::encode)
            .decoder(MotionCodec::decode)
            .consumerMainThread((m,c)->org.mtrpoint.client.PointClient.motion(m)).add();
        CHANNEL.messageBuilder(Batch.class,3,NetworkDirection.PLAY_TO_CLIENT)
            .encoder((m,b)->{b.writeUtf(m.dimension());b.writeInt(m.entries().size());for(var e:m.entries()){b.writeUtf(e.id(),512);b.writeLong(e.revision());b.writeUtf(e.json(),JSON_LIMIT);}})
            .decoder(b->{String dimension=b.readUtf();int count=b.readInt();var entries=new ArrayList<Entry>(Math.min(count,4096));for(int i=0;i<count;i++)entries.add(new Entry(b.readUtf(512),b.readLong(),b.readUtf(JSON_LIMIT)));return new Batch(dimension,List.copyOf(entries));})
            .consumerMainThread((m,c)->org.mtrpoint.client.PointClient.receiveBatch(m)).add();
    }
    public static void send(Edit edit){CHANNEL.sendToServer(edit);}
    private static void edit(Edit m,ServerPlayer p){
        if(p==null)return;var level=p.serverLevel();var data=AppearanceData.get(level);
        var old=data.entries.getOrDefault(m.id,new AppearanceData.Entry(PointSettings.DEFAULT,0));String message="";
        if(!p.hasPermissions(2)&&!p.isCreative())message="mtrpoint.denied";
        else if(p.distanceToSqr(m.center.getX(),m.center.getY(),m.center.getZ())>4096||!level.hasChunkAt(m.center)||!level.mayInteract(p,m.center))message="mtrpoint.too_far";
        else if(old.revision()!=m.revision)message="mtrpoint.stale";
        else if(!(m.id.startsWith("y:")||m.id.startsWith("t:")||m.id.startsWith("x:"))||data.entries.size()>=4096&&!data.entries.containsKey(m.id))message="mtrpoint.invalid";
        else try{var updated=new AppearanceData.Entry(AppearanceData.decode(m.json),old.revision()+1);data.entries.put(m.id,updated);data.setDirty();CHANNEL.send(PacketDistributor.DIMENSION.with(level::dimension),new State(level.dimension().location().toString(),false,m.id,updated.revision(),AppearanceData.JSON.toJson(updated.value()),"mtrpoint.saved"));return;}catch(RuntimeException ex){message="mtrpoint.invalid";}
        CHANNEL.send(PacketDistributor.PLAYER.with(()->p),new State(level.dimension().location().toString(),false,m.id,old.revision(),AppearanceData.JSON.toJson(old.value()),message));
    }
    /** Send the whole plan of one level. The batch is one message instead of one message per entry:
     * a busy world used to make a joining client receive thousands of packets, each serialized on
     * its own and each making the client scan every view it tracks. */
    public static void full(ServerPlayer p){
        String dimension=p.serverLevel().dimension().location().toString();
        CHANNEL.send(PacketDistributor.PLAYER.with(()->p),new State(dimension,true,"",0,"",""));
        var entries=new ArrayList<Entry>();
        AppearanceData.get(p.serverLevel()).entries.forEach((id,e)->entries.add(new Entry(id,e.revision(),AppearanceData.JSON.toJson(e.value()))));
        if(!entries.isEmpty())CHANNEL.send(PacketDistributor.PLAYER.with(()->p),new Batch(dimension,List.copyOf(entries)));
    }
    /** How far a movement may be from a player and still matter to them. The client only builds
     * junctions for rails within 96 blocks of its player and a junction reaches at most its own
     * extent beyond that, so this covers every node a local client can look up. */
    private static final double MOTION_RANGE=256;
    /** Broadcast the BR snapshots to the players they can affect. Every queued movement carries the
     * node it crosses and each client only ever looks up the node of a junction near itself, yet the
     * whole snapshot (up to 8192 entries, three strings each) used to go to the entire dimension
     * five times a second: that was the largest cost this mod added to a server and its network.
     * Each player now receives only the movements within {@link #MOTION_RANGE}; a player without any
     * still gets the (empty) snapshot, so its client never mistakes silence for a stale feed. */
    public static void flushMotion(){
        var server=ServerLifecycleHooks.getCurrentServer();if(server==null||server.getTickCount()%4!=0)return;
        for(var level:server.getAllLevels()){
            var motion=BrObserver.latest.get(level.dimension().location().toString());if(motion==null)continue;
            var players=level.players();if(players.isEmpty())continue;
            var near=new LinkedHashMap<ServerPlayer,List<Movement>>();
            for(var player:players)near.put(player,new ArrayList<>());
            for(var movement:motion.entries()){
                String node=movement.node();int first=node.indexOf(','),second=node.indexOf(',',first+1);if(first<0||second<0)continue;
                double x,y,z;
                try{x=Double.parseDouble(node.substring(0,first));y=Double.parseDouble(node.substring(first+1,second));z=Double.parseDouble(node.substring(second+1));}catch(NumberFormatException ex){continue;}
                for(var player:players)if(player.distanceToSqr(x,y,z)<=MOTION_RANGE*MOTION_RANGE)near.get(player).add(movement);
            }
            for(var entry:near.entrySet())CHANNEL.send(PacketDistributor.PLAYER.with(()->entry.getKey()),new Motion(motion.dimension(),motion.brPresent(),motion.timestamp(),List.copyOf(entry.getValue())));
        }
    }
}
