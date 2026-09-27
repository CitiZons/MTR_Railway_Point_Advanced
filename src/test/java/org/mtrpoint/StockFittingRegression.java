package org.mtrpoint;

import org.mtrpoint.geometry.*;
import java.util.*;

/** Stock rails must retain their own seats when adjacent route seats are deduplicated. */
final class StockFittingRegression {
    static void run(ModelDetail source){
        V3 marker=new V3(source.railCenter(),source.railTop(),0);
        var face=new Mesh.Quad(marker,marker,marker,marker,Profile.STEEL,"template",-1);
        var detail=new ModelDetail(List.of(),source.bearers(),List.of(face),source.railCenter(),source.railTop(),source.headWidth(),source.zMin(),source.zMax(),source.halfBearer(),source.bearerTop(),false,false,source.endSteel());
        var p=new Profile(1.435,source.railTop(),.068,.14,.165,Profile.STEEL,Profile.TIMBER,"seat-test",true,detail);
        int checked=0;double worst=0;
        for(boolean mirrored:new boolean[]{false,true})for(boolean swapped:new boolean[]{false,true})for(int mode:new int[]{0,4}){
            var a=new ArrayList<V3>();var b=new ArrayList<V3>();
            for(int i=0;i<=120;i++){double z=i*.25;a.add(new V3(0,0,z));b.add(new V3((mirrored?-1:1)*.008*z*z,0,z));}
            Track ta=new Track("a","toe","a-end",a),tb=new Track("b","toe","b-end",b);
            Junction j=new Junction("seats",Junction.Kind.Y,swapped?tb:ta,swapped?ta:tb,V3.ZERO,0,0,22);
            PointSettings s=PointSettings.DEFAULT.with(16,mode);double side=TurnoutFrame.side(j,PointMesh.extent(j,s));
            Mesh mesh=PointMesh.build(j,s,p,0);
            for(int row=0;row<8;row++){
                int index=row;
                var seats=mesh.quads.stream().filter(q->q.part().equals("fastener")&&q.index()==index).map(q->q.center().add(0,-p.top(),0)).toList();
                for(int branch=0;branch<2;branch++){
                    Track road=branch==0?j.a():j.b();double offset=(branch==0?-side:side)*p.centerOffset(),best=Double.MAX_VALUE;
                    for(V3 seat:seats){double d=TurnoutFrame.nearestOffset(road,seat,offset);V3 stock=road.at(d).add(road.tangent(d).lateral().mul(offset));best=Math.min(best,seat.distance(stock));}
                    if(best>1e-6)throw new AssertionError("Outer stock rail lost its fitting: row="+row+" branch="+branch+" mode="+mode+" mirrored="+mirrored+" swapped="+swapped+" distance="+best);
                    worst=Math.max(worst,best);checked++;
                }
            }
        }
        System.out.println("STOCK_FITTINGS: PASS outer stock seats retained across close-route deduplication, mirrored and swapped branches; seats="+checked+" maxError="+worst);
    }
}
