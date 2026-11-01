package org.mtrpoint;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.mtrpoint.geometry.Detector;
import java.util.*;

public final class NetworkRegression {
    public static void run(){
        var junction=Detector.find(Regression.y()).get(0);
        var center=BlockPos.containing(junction.center().x(),junction.center().y(),junction.center().z());
        require(ServerJunctions.matches(List.of(junction),junction.id(),center),"Actual junction rejected");
        require(!ServerJunctions.matches(List.of(junction),junction.id()+"forged",center),"Forged junction accepted");
        require(!ServerJunctions.matches(List.of(junction),junction.id(),center.offset(1,0,0)),"Wrong junction position accepted");

        var appearances=new LinkedHashMap<String,AppearanceData.Entry>();
        for(int i=0;i<100;i++)appearances.put("y:"+i,new AppearanceData.Entry(org.mtrpoint.geometry.PointSettings.DEFAULT,i));
        var cursor=new PointNetwork.SnapshotCursor("minecraft:overworld",appearances);
        int received=0,batches=0;
        for(PointNetwork.Snapshot batch;(batch=cursor.next())!=null;){
            require(!batch.clear()&&batch.entries().size()<=32,"Unbounded snapshot batch");
            var wire=new FriendlyByteBuf(Unpooled.buffer());PointNetwork.writeSnapshot(batch,wire);
            var decoded=PointNetwork.readSnapshot(wire);
            require(decoded.equals(batch),"Snapshot wire round trip");
            received+=decoded.entries().size();batches++;
        }
        require(received==100&&batches>=4,"Snapshot dropped entries");
        require(new PointNetwork.SnapshotCursor("minecraft:overworld",Map.of()).next()==null,"Empty sync created data batch");
        System.out.println("NETWORK: PASS authoritative junction identity and bounded appearance snapshots");
    }
    private static void require(boolean condition,String reason){if(!condition)throw new AssertionError(reason);}
}
