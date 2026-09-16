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
    private static final SimpleChannel CHANNEL=NetworkRegistry.newSimpleChannel(new ResourceLocation(PointMod.ID,"appearance"),()->"2","2"::equals,"2"::equals);
    public record Edit(String id,BlockPos center,long revision,String json) {}
    public record State(String dimension,boolean clear,String id,long revision,String json,String message) {}
    public record Movement(String node,String from,String to,boolean occupied,long vehicle,double distance) {}
    public record Motion(String dimension,boolean brPresent,long timestamp,List<Movement> entries) {}
    public static void init(){
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
    }
    public static void send(Edit edit){CHANNEL.sendToServer(edit);}
    private static void edit(Edit m,ServerPlayer p){
        if(p==null)return;var level=p.serverLevel();var data=AppearanceData.get(level);
        var old=data.entries.getOrDefault(m.id,new AppearanceData.Entry(PointSettings.DEFAULT,0));String message="";
        if(!p.hasPermissions(2)&&!p.isCreative())message="mtrpoint.denied";
        else if(p.distanceToSqr(m.center.getX(),m.center.getY(),m.center.getZ())>4096||!level.hasChunkAt(m.center)||!level.mayInteract(p,m.center))message="mtrpoint.too_far";
        else if(old.revision()!=m.revision)message="mtrpoint.stale";
        else if(!(m.id.startsWith("y:")||m.id.startsWith("x:"))||data.entries.size()>=4096&&!data.entries.containsKey(m.id))message="mtrpoint.invalid";
        else try{var updated=new AppearanceData.Entry(AppearanceData.decode(m.json),old.revision()+1);data.entries.put(m.id,updated);data.setDirty();CHANNEL.send(PacketDistributor.DIMENSION.with(level::dimension),new State(level.dimension().location().toString(),false,m.id,updated.revision(),AppearanceData.JSON.toJson(updated.value()),"mtrpoint.saved"));return;}catch(RuntimeException ex){message="mtrpoint.invalid";}
        CHANNEL.send(PacketDistributor.PLAYER.with(()->p),new State(level.dimension().location().toString(),false,m.id,old.revision(),AppearanceData.JSON.toJson(old.value()),message));
    }
    public static void full(ServerPlayer p){String dimension=p.serverLevel().dimension().location().toString();CHANNEL.send(PacketDistributor.PLAYER.with(()->p),new State(dimension,true,"",0,"",""));AppearanceData.get(p.serverLevel()).entries.forEach((id,e)->CHANNEL.send(PacketDistributor.PLAYER.with(()->p),new State(dimension,false,id,e.revision(),AppearanceData.JSON.toJson(e.value()),"")));}
    public static void flushMotion(){var server=ServerLifecycleHooks.getCurrentServer();if(server==null||server.getTickCount()%4!=0)return;for(var level:server.getAllLevels()){var motion=BrObserver.latest.get(level.dimension().location().toString());if(motion!=null)CHANNEL.send(PacketDistributor.DIMENSION.with(level::dimension),motion);}}
}
