package org.mtrpoint;

import java.util.*;
import org.mtrpoint.geometry.*;

/** Measure bar clearance against the emitted sleeper surface, including native models. */
final class StretcherHeightRegression {
    static void run() {
        Profile p=Profile.STANDARD;
        Mesh rails=new Mesh();rails.rail(new V3(0,0,0),new V3(0,0,1),1,1,p,PointSettings.DEFAULT,"rail");
        Mesh bearer=new Mesh();bearer.beam(new V3(-1,0,0),new V3(1,0,0),.24,.24,-.06,.06,p.sleeper(),"sleeper",0);
        var detail=new ModelDetail(rails.quads,bearer.quads,List.of(),0,p.top(),p.headWidth(),0,1,1,.06,false);
        Profile nativeProfile=new Profile(p.gauge(),p.top(),p.headWidth(),p.footWidth(),p.railHeight(),p.steel(),p.sleeper(),"bar-native",false,detail);
        int poses=0;
        for(Profile profile:List.of(p,nativeProfile))for(boolean three:new boolean[]{false,true}){
            var roads=new ArrayList<Track>();
            for(int branch=0;branch<(three?3:2);branch++){
                var points=new ArrayList<V3>();double factor=branch==0?-.012:branch==1?.016:.04;
                for(int i=0;i<=80;i++){double z=i*.25;points.add(new V3(factor*z*z,0,z));}
                roads.add(new Track("bar"+branch,"node","end"+branch,points));
            }
            Junction j=Detector.find(roads).stream().filter(v->v.kind()==(three?Junction.Kind.THREE:Junction.Kind.Y)).findFirst().orElseThrow();
            // Moving positions and a vertical appearance offset must preserve the same clearance.
            PointSettings settings=PointSettings.DEFAULT.with(11,.13);
            List<Mesh.Quad> first=null,last=null;
            for(double position:new double[]{0,.5,1}){
                Mesh mesh=PointMesh.build(j,settings,profile,position);
                var bars=mesh.quads.stream().filter(q->q.part().equals("stretcher")).toList();
                if(bars.size()!=(three?12:6))throw new AssertionError("Missing stretcher bars: "+bars.size());
                double sleeperTop=mesh.quads.stream().filter(q->q.part().equals("sleeper"))
                    .flatMap(q->List.of(q.a(),q.b(),q.c(),q.d()).stream()).mapToDouble(V3::y).max().orElseThrow();
                double bottom=Double.POSITIVE_INFINITY,top=Double.NEGATIVE_INFINITY;
                for(var q:bars)for(V3 v:List.of(q.a(),q.b(),q.c(),q.d())){bottom=Math.min(bottom,v.y());top=Math.max(top,v.y());}
                if(Math.abs(bottom-sleeperTop-.01)>1e-8||Math.abs(top-sleeperTop-.05)>1e-8)
                    throw new AssertionError("Stretcher clearance differs from emitted sleeper: "+(bottom-sleeperTop)+".."+(top-sleeperTop)+", native="+(profile.detail()!=null)+", three="+three);
                if(first==null)first=bars;last=bars;poses++;
            }
            if(first.equals(last))throw new AssertionError("Lowered bars stopped following the blades");
        }
        System.out.println("PASS: "+poses+" Y/three-way bar poses stay 10..50 mm above generic/native sleepers and follow the blades");
    }
}
