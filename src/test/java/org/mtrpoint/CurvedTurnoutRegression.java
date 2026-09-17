package org.mtrpoint;

import org.mtrpoint.geometry.*;
import java.util.*;

/** Curvature changes and unequal branch lengths exposed normal-intersection runaway ties. */
final class CurvedTurnoutRegression {
    static void run()throws Exception{
        for(int variant=0;variant<3;variant++){
            var a=new ArrayList<V3>();var b=new ArrayList<V3>();
            for(int i=0;i<=240;i++){
                double z=i/8D,t=z/30,bend=variant==0?0:variant==1?3*Math.sin(Math.PI*t):.009*z*z;
                double split=6*t*t*(3-2*t);
                a.add(new V3(bend,0,z));b.add(new V3(bend+split,0,z));
            }
            Track ta=new Track("curved-a","origin","a",a),tb=new Track("curved-b","origin","b",b);
            Junction j=Detector.find(List.of(ta,tb)).get(0);Mesh mesh=PointMesh.build(j,PointSettings.DEFAULT,Profile.STANDARD,0);
            checkTies(j,mesh);checkWings(j,mesh);
            var middle=new ArrayList<V3>();for(int i=0;i<a.size();i++)middle.add(a.get(i).lerp(b.get(i),.5));
            var three=Detector.find(List.of(ta,tb,new Track("curved-middle","origin","middle",middle))).get(0);
            checkTies(three,PointMesh.build(three,PointSettings.DEFAULT,Profile.STANDARD,.5));
            if(variant==1)Regression.export(mesh,"asymmetric-curved");
        }
        for(boolean curved:new boolean[]{false,true})for(Junction j:Detector.find(Regression.scissors(curved)))if(j.kind()==Junction.Kind.Y)
            checkTies(j,PointMesh.build(j,PointSettings.DEFAULT,Profile.STANDARD,0));
        checkGuardMerge();
        System.out.println("PASS: asymmetric/inflected V bearers stay inside the outer envelope; curved wing gaps and knee joints");
    }
    private static void checkGuardMerge(){
        Track road=Regression.line("shared","a","b",new V3(0,0,0),new V3(0,0,20));
        Profile p=Profile.STANDARD;PointSettings s=PointSettings.DEFAULT;
        var a=new GuardRails.Run(road,3,7,.62,true,true,p,s);
        var b=new GuardRails.Run(road.reverse(),10,14,-.62,true,true,p,s.with(4,.55));
        var joined=GuardRails.merge(List.of(b,a));
        if(joined.size()!=1||Math.abs(joined.get(0).start()-3)>1e-8||Math.abs(joined.get(0).end()-10)>1e-8)throw new AssertionError("Oppositely oriented neighboring guard intervals failed to merge");
        var run=joined.get(0);
        for(double d:new double[]{6,6.5,7})if(Math.abs(run.point(d).x()+.62)>1e-9)throw new AssertionError("Internal guard flare remains in overlap");
        var top=DiamondRegression.tops(run.mesh(),p.top());
        for(double d=3.5;d<9.5;d+=.137)if(DiamondRegression.coverage(top,new V3(-.62,0,d))!=1)throw new AssertionError("Merged guard has duplicate/missing top faces");
        if(GuardRails.merge(List.of(a,new GuardRails.Run(road,8,10,.62,true,true,p,s))).size()!=2)throw new AssertionError("Separated guards incorrectly bridged");
        if(GuardRails.merge(List.of(a,new GuardRails.Run(road,6,10,-.62,true,true,p,s))).size()!=2)throw new AssertionError("Guards on opposite sides incorrectly merged");
        if(GuardRails.merge(List.of(a,new GuardRails.Run(road,6,10,.62,true,true,p,s.with(11,.1)))).size()!=2)throw new AssertionError("Different guard heights incorrectly merged");
        System.out.println("PASS: overlapping/reversed guards merge once; outer flares retained; separate sides/heights and non-overlaps stay independent");
    }
    private static void checkTies(Junction j,Mesh mesh){
        double half=Profile.STANDARD.centerOffset()+PointSettings.DEFAULT.sleeperOverhang();
        var rows=new TreeMap<Integer,List<Mesh.Quad>>();
        for(var q:mesh.quads)if(q.part().equals("sleeper"))rows.computeIfAbsent(q.index(),k->new ArrayList<>()).add(q);
        for(var row:rows.values()){
            double min=Double.MAX_VALUE,max=-Double.MAX_VALUE;
            for(var q:row)for(V3 v:List.of(q.a(),q.b(),q.c(),q.d())){
                double da=j.a().nearest(v),db=j.b().nearest(v);V3 a=j.a().at(da),b=j.b().at(db);
                V3 na=j.a().tangent(da).lateral(),nb=j.b().tangent(db).lateral();
                double sideA=v.sub(a).dot(na),sideB=v.sub(b).dot(nb);
                // An outboard vertex must stay within the specified overhang. Vertices in
                // between separated roads are the joined inner arms, not runaway tails.
                if(Math.signum(sideA)==Math.signum(sideB)&&Math.min(Math.abs(sideA),Math.abs(sideB))>half+.025)
                    throw new AssertionError("V bearer extends beyond both roads at "+v);
                min=Math.min(min,(da+db)/2);max=Math.max(max,(da+db)/2);
            }
            if(j.kind()!=Junction.Kind.THREE&&max-min>.8)throw new AssertionError("V bearer steps along the track instead of meeting in one row: "+(max-min));
            if(j.kind()==Junction.Kind.THREE){
                // Once a side has separated, its ordinary ties no longer share a row
                // with the still-connected pair. Check each connected bearer instead.
                var remaining=new ArrayList<>(row);
                while(!remaining.isEmpty()){
                    var component=new ArrayList<Mesh.Quad>();component.add(remaining.remove(0));
                    for(int i=0;i<component.size();i++){var face=component.get(i);for(var it=remaining.iterator();it.hasNext();){var next=it.next();if(List.of(face.a(),face.b(),face.c(),face.d()).stream().anyMatch(v->List.of(next.a(),next.b(),next.c(),next.d()).stream().anyMatch(w->v.distance(w)<1e-6))){component.add(next);it.remove();}}}
                    double lo=Double.MAX_VALUE,hi=-lo;
                    for(var q:component)for(V3 v:List.of(q.a(),q.b(),q.c(),q.d())){double at=(j.a().nearest(v)+j.b().nearest(v))/2;lo=Math.min(lo,at);hi=Math.max(hi,at);}
                    if(hi-lo>.8)throw new AssertionError("Connected three-way bearer steps longitudinally: "+(hi-lo));
                }
            }
        }
    }
    private static V3 start(Mesh.Quad q){return q.a().lerp(q.d(),.5);}
    private static V3 end(Mesh.Quad q){return q.b().lerp(q.c(),.5);}
    private static void checkWings(Junction j,Mesh mesh){
        var faces=mesh.quads.stream().filter(q->q.part().equals("wing")).toList();
        double side=j.b().at(3).sub(j.a().at(3)).dot(j.a().tangent(0).lateral())>0?1:-1;
        for(int branch=0;branch<2;branch++){
            int base=branch*36*18;V3 incoming=end(faces.get(base+11*18+12)),working=start(faces.get(base+12*18+12));
            if(incoming.distance(working)>1e-5)throw new AssertionError("Wing knee is disconnected from the incoming curved rail: "+incoming.distance(working));
            Track road=branch==0?j.b():j.a();double sign=branch==0?-side:side;
            for(int k=12;k<24;k++)for(V3 v:List.of(start(faces.get(base+k*18+12)),end(faces.get(base+k*18+12)))){
                double d=road.nearest(v),lateral=v.sub(road.at(d)).dot(road.tangent(d).lateral())*sign;
                double expected=Profile.STANDARD.centerOffset()-Profile.STANDARD.headWidth()-PointSettings.DEFAULT.flangeway();
                if(Math.abs(lateral-expected)>.001)throw new AssertionError("Curved wing working gap drift: "+(lateral-expected));
            }
        }
    }
}

