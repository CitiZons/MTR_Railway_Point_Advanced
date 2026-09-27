package org.mtrpoint;

import java.util.*;
import org.mtrpoint.geometry.*;

/** Compare actual head edge vertices: centre-line coverage alone misses hairline cracks. */
public final class StockSectionSeamRegression {
    public static void run() {
        Mesh model=new Mesh();model.rail(new V3(0,0,0),new V3(0,0,1),1,1,Profile.STANDARD,PointSettings.DEFAULT,"rail");
        Profile base=Profile.STANDARD;
        ModelDetail detail=new ModelDetail(model.quads,List.of(),List.of(),0,base.top(),base.headWidth(),0,1,1,base.top()-base.railHeight(),false);
        Profile nativeProfile=new Profile(base.gauge(),base.top(),base.headWidth(),base.footWidth(),base.railHeight(),base.steel(),base.sleeper(),"seam-native",false,detail);
        int seams=0;double worst=0;
        for(Profile p:List.of(base,nativeProfile))for(boolean three:new boolean[]{false,true})for(boolean mirror:new boolean[]{false,true}){
            var roads=new ArrayList<Track>();
            for(int branch=0;branch<(three?3:2);branch++){
                var points=new ArrayList<V3>();double factor=branch==0?-.008:branch==1?.011:.026;
                for(int i=0;i<=120;i++){double z=i*.25;points.add(new V3((mirror?-1:1)*factor*z*z,0,z));}
                roads.add(new Track("seam"+branch,"node","end"+branch,points));
            }
            Junction j=Detector.find(roads).stream().filter(v->v.kind()==(three?Junction.Kind.THREE:Junction.Kind.Y)).findFirst().orElseThrow();
            Mesh mesh=PointMesh.build(j,PointSettings.DEFAULT,p,0);
            for(Track road:j.tracks())for(int sign:new int[]{-1,1}){
                var edges=new HashMap<Long,List<V3[]>>();
                for(var q:mesh.quads){
                    if(!q.part().equals("rail"))continue;
                    var vertices=List.of(q.a(),q.b(),q.c(),q.d());
                    if(vertices.stream().anyMatch(v->Math.abs(v.y()-p.top())>1e-8))continue;
                    for(int k=0;k<4;k++){V3 a=vertices.get(k),b=vertices.get((k+1)%4),c=a.lerp(b,.5);
                        double at=road.nearest(new V3(c.x(),0,c.z()));if(at<.3||at>4)continue;
                        if(Math.abs(a.distance(b)-p.headWidth())>1e-6)continue;
                        V3 center=road.at(at).add(road.tangent(at).lateral().mul(sign*p.centerOffset()));
                        if(c.distance(center.add(0,p.top(),0))>.003)continue;
                        edges.computeIfAbsent(Math.round(at*1e6),key->new ArrayList<>()).add(new V3[]{a,b});
                    }
                }
                for(var pair:edges.values())if(pair.size()==2){
                    V3[] a=pair.get(0),b=pair.get(1);double error=Math.min(Math.max(a[0].distance(b[0]),a[1].distance(b[1])),Math.max(a[0].distance(b[1]),a[1].distance(b[0])));
                    worst=Math.max(worst,error);seams++;
                    if(error>1e-8)throw new AssertionError("Stock rail section edges disagree by "+error+" m, three="+three+" mirror="+mirror+" native="+(p.detail()!=null));
                }
            }
        }
        if(seams<100)throw new AssertionError("Too few shared stock-rail edges: "+seams);
        System.out.println("PASS: "+seams+" stock head seams share full section edges; worst="+worst+" m (Y/three-way, mirrored, generic/native)");
    }
}
