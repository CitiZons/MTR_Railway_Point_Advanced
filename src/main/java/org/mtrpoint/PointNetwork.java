package org.mtrpoint;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.mtrpoint.geometry.*;
import org.mtrpoint.compat.BrObserver;
import java.util.*;
import java.nio.charset.StandardCharsets;

public final class PointNetwork {
    public static final int PROTOCOL_VERSION=4;
    public static final int MODEL_FORMAT_VERSION=1;
    public static final int FEATURE_ENTITY_RAIL=1;
    public static final int FEATURE_END_FACE=1<<1;
    public static final int FEATURE_SLEEPER_MODEL=1<<2;
    public static final int FEATURE_DETAIL_LEVELS=1<<3;
    public static final int SERVER_FEATURES=FEATURE_ENTITY_RAIL|FEATURE_END_FACE|FEATURE_SLEEPER_MODEL|FEATURE_DETAIL_LEVELS;
    private static SimpleChannel CHANNEL;
    public record Edit(String id,BlockPos center,long revision,String json) {}
    public record State(String dimension,boolean clear,String id,long revision,String json,String message) {}
    public record SnapshotEntry(String id,long revision,String json) {}
    public record Snapshot(String dimension,boolean clear,List<SnapshotEntry> entries) {}
    private static final int SNAPSHOT_BYTES=64*1024,SNAPSHOT_ENTRIES=32;
    static final class SnapshotCursor {
        final String dimension;
        final Iterator<Map.Entry<String,AppearanceData.Entry>> source;
        SnapshotEntry carry;
        SnapshotCursor(String dimension,Map<String,AppearanceData.Entry> appearances){this.dimension=dimension;source=Map.copyOf(appearances).entrySet().iterator();}
        Snapshot next(){
            var entries=new ArrayList<SnapshotEntry>();int bytes=0;
            while(carry!=null||source.hasNext()){
                SnapshotEntry entry=carry;
                if(entry!=null)carry=null;
                else{var item=source.next();entry=new SnapshotEntry(item.getKey(),item.getValue().revision(),AppearanceData.JSON.toJson(item.getValue().value()));}
                int size=size(entry.id,entry.json);
                if(!entries.isEmpty()&&(entries.size()>=SNAPSHOT_ENTRIES||bytes+size>SNAPSHOT_BYTES)){carry=entry;break;}
                entries.add(entry);bytes+=size;
            }
            return entries.isEmpty()?null:new Snapshot(dimension,false,List.copyOf(entries));
        }
    }
    private record FullSync(ServerPlayer player,SnapshotCursor cursor) {}
    private static final Map<UUID,FullSync> FULL_SYNCS=new HashMap<>();
    public record Movement(String node,String from,String to,boolean occupied,long vehicle,double distance) {}
    public record Motion(String dimension,boolean brPresent,long timestamp,List<Movement> entries) {}
    public record Handshake(int protocol,int features,int modelFormat) {}
    public record HandshakeAck(int protocol,int features,int modelFormat) {}
    public static void init(){
        CHANNEL=NetworkRegistry.newSimpleChannel(new ResourceLocation(PointMod.ID,"appearance"),()->"4","4"::equals,"4"::equals);
        CHANNEL.messageBuilder(Handshake.class,3,NetworkDirection.PLAY_TO_CLIENT)
            .encoder((m,b)->{b.writeVarInt(m.protocol);b.writeVarInt(m.features);b.writeVarInt(m.modelFormat);})
            .decoder(b->new Handshake(b.readVarInt(),b.readVarInt(),b.readVarInt()))
            .consumerMainThread((m,c)->org.mtrpoint.client.PointClient.handshake(m)).add();
        CHANNEL.messageBuilder(HandshakeAck.class,4,NetworkDirection.PLAY_TO_SERVER)
            .encoder((m,b)->{b.writeVarInt(m.protocol);b.writeVarInt(m.features);b.writeVarInt(m.modelFormat);})
            .decoder(b->new HandshakeAck(b.readVarInt(),b.readVarInt(),b.readVarInt()))
            .consumerMainThread((m,c)->ack(m,c.get().getSender())).add();
        CHANNEL.messageBuilder(Edit.class,0,NetworkDirection.PLAY_TO_SERVER)
            .encoder((m,b)->{b.writeUtf(m.id,512);b.writeBlockPos(m.center);b.writeLong(m.revision);b.writeUtf(m.json,16384);})
            .decoder(b->new Edit(b.readUtf(512),b.readBlockPos(),b.readLong(),b.readUtf(16384)))
            .consumerMainThread((m,c)->edit(m,c.get().getSender())).add();
        CHANNEL.messageBuilder(State.class,1,NetworkDirection.PLAY_TO_CLIENT)
            .encoder((m,b)->{b.writeUtf(m.dimension);b.writeBoolean(m.clear);b.writeUtf(m.id,512);b.writeLong(m.revision);b.writeUtf(m.json,16384);b.writeUtf(m.message,128);})
            .decoder(b->new State(b.readUtf(),b.readBoolean(),b.readUtf(512),b.readLong(),b.readUtf(16384),b.readUtf(128)))
            .consumerMainThread((m,c)->org.mtrpoint.client.PointClient.receive(m)).add();
        CHANNEL.messageBuilder(Motion.class,2,NetworkDirection.PLAY_TO_CLIENT)
            .encoder(MotionCodec::encode)
            .decoder(MotionCodec::decode)
            .consumerMainThread((m,c)->org.mtrpoint.client.PointClient.motion(m)).add();
        CHANNEL.messageBuilder(Snapshot.class,5,NetworkDirection.PLAY_TO_CLIENT)
            .encoder(PointNetwork::writeSnapshot)
            .decoder(PointNetwork::readSnapshot)
            .consumerMainThread((m,c)->org.mtrpoint.client.PointClient.receiveSnapshot(m)).add();
    }
    static void writeSnapshot(Snapshot m,FriendlyByteBuf b){
        b.writeUtf(m.dimension);b.writeBoolean(m.clear);b.writeVarInt(m.entries.size());
        for(var entry:m.entries){b.writeUtf(entry.id,512);b.writeLong(entry.revision);b.writeUtf(entry.json,16384);}
    }
    static Snapshot readSnapshot(FriendlyByteBuf b){
        String dimension=b.readUtf();boolean clear=b.readBoolean();int count=b.readVarInt();
        if(count<0||count>SNAPSHOT_ENTRIES)throw new IllegalArgumentException("Appearance snapshot size");
        var entries=new ArrayList<SnapshotEntry>(count);int bytes=0;
        for(int i=0;i<count;i++){
            String id=b.readUtf(512);long revision=b.readLong();String json=b.readUtf(16384);
            bytes+=size(id,json);if(bytes>SNAPSHOT_BYTES)throw new IllegalArgumentException("Appearance snapshot bytes");
            entries.add(new SnapshotEntry(id,revision,json));
        }
        return new Snapshot(dimension,clear,List.copyOf(entries));
    }
    public static void send(Edit edit){CHANNEL.sendToServer(edit);}
    public static void sendHandshakeAck(HandshakeAck ack){CHANNEL.sendToServer(ack);}
    private static void ack(HandshakeAck m,ServerPlayer p){
        if(p==null)return;
        if(m.protocol()!=PROTOCOL_VERSION||m.modelFormat()<0||m.modelFormat()>MODEL_FORMAT_VERSION){
            PointMod.LOG.warn("Rejected incompatible MTR point client handshake from {}: protocol={}, modelFormat={}",p.getGameProfile().getName(),m.protocol(),m.modelFormat());
            return;
        }
        int accepted=m.features()&SERVER_FEATURES;
        PointMod.LOG.debug("MTR point client handshake from {}: features=0x{} accepted=0x{} modelFormat={}",p.getGameProfile().getName(),Integer.toHexString(m.features()),Integer.toHexString(accepted),m.modelFormat());
    }
    private static void edit(Edit m,ServerPlayer p){
        edit(m,p,null);
    }
    private static void edit(Edit m,ServerPlayer p,Boolean verified){
        if(p==null)return;var level=p.serverLevel();var data=AppearanceData.get(level);
        var old=data.entries.getOrDefault(m.id,new AppearanceData.Entry(PointSettings.DEFAULT,0));String message="";
        if(!p.hasPermissions(2)&&!p.isCreative())message="mtrpoint.denied";
        else if(p.distanceToSqr(m.center.getX(),m.center.getY(),m.center.getZ())>4096||!level.hasChunkAt(m.center)||!level.mayInteract(p,m.center))message="mtrpoint.too_far";
        else if(old.revision()!=m.revision)message="mtrpoint.stale";
        else if(!(m.id.startsWith("y:")||m.id.startsWith("t:")||m.id.startsWith("x:"))||data.entries.size()>=4096&&!data.entries.containsKey(m.id))message="mtrpoint.invalid";
        else if(Boolean.FALSE.equals(verified))message="mtrpoint.invalid";
        else if(verified==null){
            String dimension=level.dimension().location().getNamespace()+"/"+level.dimension().location().getPath();
            var simulator=BrObserver.simulator(dimension);
            if(simulator!=null){simulator.run(()->{
                boolean valid;try{valid=ServerJunctions.valid(simulator,m.id,m.center);}catch(RuntimeException ex){PointMod.LOG.warn("Could not validate point edit {}",m.id,ex);valid=false;}
                boolean accepted=valid;p.server.execute(()->{if(p.serverLevel()==level)edit(m,p,accepted);});
            });return;}
            message="mtrpoint.invalid";
        }
        else try{var updated=new AppearanceData.Entry(AppearanceData.decode(m.json),old.revision()+1);data.entries.put(m.id,updated);data.setDirty();CHANNEL.send(PacketDistributor.DIMENSION.with(level::dimension),new State(level.dimension().location().toString(),false,m.id,updated.revision(),AppearanceData.JSON.toJson(updated.value()),"mtrpoint.saved"));return;}catch(RuntimeException ex){message="mtrpoint.invalid";}
        CHANNEL.send(PacketDistributor.PLAYER.with(()->p),new State(level.dimension().location().toString(),false,m.id,old.revision(),AppearanceData.JSON.toJson(old.value()),message));
    }
    private static int size(String id,String json){return id.getBytes(StandardCharsets.UTF_8).length+json.getBytes(StandardCharsets.UTF_8).length+16;}
    public static void full(ServerPlayer p){
        String dimension=p.serverLevel().dimension().location().toString();var target=PacketDistributor.PLAYER.with(()->p);
        CHANNEL.send(target,new Handshake(PROTOCOL_VERSION,SERVER_FEATURES,MODEL_FORMAT_VERSION));
        CHANNEL.send(target,new Snapshot(dimension,true,List.of()));
        FULL_SYNCS.put(p.getUUID(),new FullSync(p,new SnapshotCursor(dimension,AppearanceData.get(p.serverLevel()).entries)));
    }
    public static void flushFull(){
        var server=ServerLifecycleHooks.getCurrentServer();if(server==null)return;
        for(var it=FULL_SYNCS.entrySet().iterator();it.hasNext();){
            var sync=it.next();ServerPlayer player=sync.getValue().player();SnapshotCursor cursor=sync.getValue().cursor();
            if(server.getPlayerList().getPlayer(sync.getKey())!=player||!player.serverLevel().dimension().location().toString().equals(cursor.dimension)){it.remove();continue;}
            var target=PacketDistributor.PLAYER.with(()->player);
            for(int i=0;i<2;i++){Snapshot batch=cursor.next();if(batch==null){it.remove();break;}CHANNEL.send(target,batch);}
        }
    }
    public static void clearFull(){FULL_SYNCS.clear();}
    public static void flushMotion(){var server=ServerLifecycleHooks.getCurrentServer();if(server==null||server.getTickCount()%4!=0)return;for(var level:server.getAllLevels()){var motion=BrObserver.latest.get(level.dimension().location().toString());if(motion!=null)CHANNEL.send(PacketDistributor.DIMENSION.with(level::dimension),motion);}}
}
