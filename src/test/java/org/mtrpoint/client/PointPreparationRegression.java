package org.mtrpoint.client;

import org.mtrpoint.geometry.*;
import java.util.*;

public final class PointPreparationRegression {
    private static void require(boolean pass,String message){if(!pass)throw new AssertionError(message);}
    public static void run(){
        require(PointPreparation.lead(0,3)==24,"Stationary look-ahead vanished");
        require(PointPreparation.lead(10,3)>=30,"Look-ahead cannot cover a 10 m/s cold start");
        require(PointPreparation.lead(1000,1000)==96,"Look-ahead is not bounded");
        var still=new Mesh.Quad(V3.ZERO,new V3(1,0,0),new V3(1,0,1),new V3(0,0,1),Profile.END_STEEL,"rail",-1);
        var moved=new Mesh.Quad(new V3(2,0,0),new V3(3,0,0),new V3(3,0,1),new V3(2,0,1),Profile.END_STEEL,"blade",-1);
        Mesh first=new Mesh(),second=new Mesh();first.quads.add(still);first.quads.add(moved);second.quads.add(still);
        int[] faces={0};var banked=PointClient.bankFrames(List.of(first,second),input->{faces[0]+=input.quads.size();Mesh output=new Mesh();for(var q:input.quads)output.quads.add(new Mesh.Quad(q.a().add(0,1,0),q.b().add(0,1,0),q.c().add(0,1,0),q.d().add(0,1,0),q.surface(),q.part(),q.index(),q.uv()));return output;});
        require(faces[0]==2,"Static geometry was banked again for another pose");
        require(banked.get(0).quads.get(0)==banked.get(1).quads.get(0),"Banked stationary quads do not share their prepared result");
        require(banked.get(0).quads.get(1).a().equals(moved.a().add(0,1,0)),"Moving pose banking changed geometry/order");
        PointPreparation.clear();System.out.println("POINT_PREPARATION: PASS bounded speed look-ahead and shared stationary frame banking");
    }
}
