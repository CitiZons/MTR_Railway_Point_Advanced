package org.mtrpoint.geometry;

import java.util.*;

/** Fill the actual rail-seat gap left after V arms have been projected onto their joints. */
public final class EndSleepers {
    private EndSleepers(){}
    public static void finish(Mesh mesh,Junction j,PointSettings s,Profile p,PointMesh.YBoundary boundary){
        if(p.detail()!=null&&p.detail().siding())return; // This native model has fittings but no bearers.
        double[] lasts=j.third()==null?new double[]{boundary.aLast(),boundary.bLast()}:new double[]{boundary.aLast(),boundary.thirdLast(),boundary.bLast()};
        var supports=mesh.quads.stream().filter(q->q.part().equals("sleeper")).toList();
        int index=mesh.quads.stream().mapToInt(Mesh.Quad::index).max().orElse(0)+1;
        for(int branch=0;branch<j.tracks().size();branch++){
            Track road=j.tracks().get(branch);double last=lasts[branch];double[] coveredSeats={0,0};
            for(var q:supports){
                V3 center=q.center();double d=road.nearest(center);
                // A long bearer can cross a road far from its centre. Intersect its edges
                // with the centre line's local transverse coordinate instead.
                var vs=List.of(q.a(),q.b(),q.c(),q.d());V3 normal=road.tangent(d).lateral();
                for(int seat=0;seat<2;seat++)for(int k=0;k<4;k++){
                    V3 a=vs.get(k),b=vs.get((k+1)%4);double offset=(seat==0?-1:1)*p.centerOffset();double x=a.sub(road.at(d)).dot(normal)-offset,y=b.sub(road.at(d)).dot(normal)-offset;
                    if(x*y>0||Math.abs(x-y)<1e-9)continue;
                    double at=road.nearest(a.lerp(b,x/(x-y)));if(at<=last+s.sleeperWidth())coveredSeats[seat]=Math.max(coveredSeats[seat],at-s.sleeperWidth()/2);
                }
            }
            double covered=Math.min(coveredSeats[0],coveredSeats[1]);double gap=last-covered;if(gap<s.sleeperSpacing()*.8)continue;
            int count=Math.max(1,(int)Math.ceil(gap/s.sleeperSpacing()));
            for(int k=1;k<=count;k++){
                double d=covered+gap*k/count;V3 c=road.at(d),n=road.tangent(d).lateral();double half=p.centerOffset()+s.sleeperOverhang();
                if(p.detail()!=null){if(!p.detail().siding())p.detail().bearer(mesh,c,n,-half,half,s,p,index);for(int sign:new int[]{-1,1})p.detail().fitting(mesh,c.add(n.mul(sign*p.centerOffset())),n,s,p,index);}
                else {double top=p.top()-p.railHeight()+s.verticalOffset();mesh.beam(c.sub(n.mul(half)),c.add(n.mul(half)),s.sleeperWidth(),s.sleeperWidth(),top-s.sleeperHeight(),top,p.sleeper(),"sleeper",index);}
                index++;
            }
        }
    }
}
