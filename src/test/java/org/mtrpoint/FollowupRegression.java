package org.mtrpoint;

import org.mtrpoint.geometry.*;
import java.util.*;

final class FollowupRegression {
    static List<Track> fan(int count,int direction){
        var roads=new ArrayList<Track>();
        for(int branch=0;branch<count;branch++){
            var points=new ArrayList<V3>();
            for(int i=0;i<=320;i++){double z=i*.125,split=branch==0?-.009*z*z:branch==1?.002*z*z:.022*Math.pow(Math.max(0,z-8),2);points.add(new V3(direction*split,0,direction*z));}
            roads.add(new Track("fan"+direction+":"+branch,"node","end"+direction+":"+branch,points));
        }
        return roads;
    }
    static void run()throws Exception{
        for(int a:new int[]{2,3})for(int b:new int[]{2,3}){
            var roads=new ArrayList<>(fan(a,1));roads.addAll(fan(b,-1));var found=Detector.find(roads).stream().filter(j->j.kind()!=Junction.Kind.DIAMOND).toList();
            if(found.size()!=2||found.stream().filter(j->j.kind()==Junction.Kind.THREE).count()!=(a==3?1:0)+(b==3?1:0))throw new AssertionError("Dual-sided node failed "+a+"+"+b);
        }
        var wide=new ArrayList<Track>();for(int direction:new int[]{-1,1})for(int side:new int[]{-1,1})wide.add(Regression.line("wide"+direction+":"+side,"node","end"+direction+":"+side,new V3(0,0,0),new V3(direction*side*10,0,direction*25)));
        if(Detector.find(wide).stream().filter(j->j.kind()==Junction.Kind.Y).count()!=2)throw new AssertionError("45 degree dual Y rejected");
        Junction j=Detector.find(fan(3,1)).stream().filter(x->x.kind()==Junction.Kind.THREE).findFirst().orElseThrow();
        Profile p=Profile.STANDARD;PointSettings s=PointSettings.DEFAULT;Mesh mesh=PointMesh.build(j,s,p,0);
        int count=mesh.quads.size();for(double position:new double[]{.5,1})if(PointMesh.build(j,s,p,position).quads.size()!=count)throw new AssertionError("Asymmetric three-way animation topology changed");
        var steel=DiamondRegression.tops(mesh,p.top());Random random=new Random(912403);int tested=0;
        double minFrog=Double.MAX_VALUE;for(int a=0;a<3;a++)for(int b=a+1;b<3;b++){
            var pair=new Junction("pair",Junction.Kind.Y,j.tracks().get(a),j.tracks().get(b),j.center(),0,0,j.extent());var frog=new FrogGeometry(pair,s,p,PointMesh.extent(j,s));minFrog=Math.min(minFrog,Math.min(frog.sa,frog.sb));
        }
        for(int i=0;i<14000;i++){
            V3 q=new V3(-7+random.nextDouble()*17,0,minFrog-.5+random.nextDouble()*(PointMesh.extent(j,s)-minFrog+.5));
            int hits=DiamondRegression.coverage(steel,q);if(hits>1)throw new AssertionError("Asymmetric three-way duplicated rail heads at "+q+" hits="+hits);tested++;
        }
        for(int mode:new int[]{0,1,2,3,4}){
            PointSettings settings=s.with(16,mode);Mesh end=PointMesh.build(j,settings,p,0);double last=PointMesh.YBoundary.nominal(j,settings).aLast();
            var sleepers=DiamondRegression.tops(end,p.top()-p.railHeight());
            for(Track road:j.tracks())for(int side:new int[]{-1,1}){
                double found=0;for(double d=Math.max(0,last-4);d<=last+.1;d+=.01)if(DiamondRegression.coverage(sleepers,road.at(d).add(road.tangent(d).lateral().mul(side*p.centerOffset())))>0)found=d;
                if(last-found>s.sleeperSpacing()*.8)throw new AssertionError("Missing terminal sleeper after arm projection "+road.id+" mode="+mode+" gap="+(last-found));
            }
        }
        var support=DiamondRegression.tops(mesh,p.top()-p.railHeight());double[] joinedEnd={0,0};
        for(int pair=0;pair<2;pair++)for(double d=2;d<PointMesh.extent(j,s);d+=.02){
            Track a=j.tracks().get(pair),b=j.tracks().get(pair+1);V3 x=a.at(d),y=b.at(b.nearest(x));V3 mid=x.lerp(y,.5);
            if(DiamondRegression.coverage(support,mid)>0)joinedEnd[pair]=d;
            if(x.distance(y)>2*(p.centerOffset()+s.sleeperOverhang())+.3&&DiamondRegression.coverage(support,mid)>0)throw new AssertionError("Separated branch still tied to middle at "+d);
        }
        if(Math.abs(joinedEnd[0]-joinedEnd[1])<2)throw new AssertionError("Asymmetric three-way forced to a single bearer termination: "+Arrays.toString(joinedEnd));
        System.out.println("PASS: independent left/right shared-bearer ends "+Arrays.toString(joinedEnd));
        Regression.export(mesh,"three-asymmetric-fixed");
        PointSettings movable=s.flags(true,true);int movingCount=-1;
        for(double position:new double[]{0,.5,1}){
            Mesh moving=PointMesh.build(j,movable,p,position);
            if(movingCount!=-1&&moving.quads.size()!=movingCount)throw new AssertionError("Movable three-way topology");movingCount=moving.quads.size();
            var heads=DiamondRegression.tops(moving,p.top());Random sample=new Random(912403);
            for(int i=0;i<14000;i++){
                V3 q=new V3(-7+sample.nextDouble()*17,0,minFrog-.5+sample.nextDouble()*(PointMesh.extent(j,s)-minFrog+.5));
                if(DiamondRegression.coverage(heads,q)>1)throw new AssertionError("Movable three-way duplicated heads at "+q+" position="+position+" faces="+heads.stream().filter(f->DiamondRegression.coverage(List.of(f),q)>0).map(f->f.part()+"/"+f.center()).toList());
            }
            Regression.export(moving,"three-asymmetric-moving-"+(int)(position*2));
        }
        System.out.println("PASS: dual Y/three nodes 4/5/6 roads; wide Y; asymmetric three-way "+tested+" head samples; terminal rail-seat coverage");
    }
}
