package org.mtrpoint;

import org.mtrpoint.geometry.*;
import java.util.*;

/** Independent head-footprint oracle: no chunk-sized holes, blunt noses or overlapping tops. */
final class DiamondRegression {
    static void run()throws Exception{
        for(double degrees:new double[]{15,30,60,90,150}){
            double angle=Math.toRadians(degrees);V3 a=new V3(0,0,1),b=new V3(Math.sin(angle),0,Math.cos(angle));
            Track ta=Regression.line("da","a0","a1",a.mul(-35),a.mul(35)),tb=Regression.line("db","b0","b1",b.mul(-35),b.mul(35));
            Junction j=Detector.find(List.of(ta,tb)).get(0);Profile p=Profile.STANDARD;PointSettings s=PointSettings.DEFAULT;
            Mesh mesh=PointMesh.build(j,s,p,0);List<Mesh.Quad> tops=tops(mesh,p.top());
            Random random=new Random(403);int checked=0;
            for(int sa:new int[]{-1,1})for(int sb:new int[]{-1,1}){
                V3 na=a.lateral(),nb=b.lateral();double cross=V3.crossXZ(na,nb),oa=sa*p.centerOffset(),ob=sb*p.centerOffset();
                V3 center=new V3((oa*nb.z()-ob*na.z())/cross,0,(na.x()*ob-nb.x()*oa)/cross);
                for(int k=0;k<650;k++){
                    V3 point=center.add((random.nextDouble()-.5)*.6,0,(random.nextDouble()-.5)*.6);
                    double da=Math.abs(point.dot(na)),db=Math.abs(point.dot(nb));
                    boolean expected=(head(da,p,s)||head(db,p,s))&&!channel(da,p,s)&&!channel(db,p,s);
                    int actual=coverage(tops,point);
                    if((actual>0)!=expected||actual>1)throw new AssertionError("Diamond "+degrees+" deg footprint/union at "+point+": expected="+expected+", faces="+actual);
                    checked++;
                }
            }
            if(mesh.quads.stream().noneMatch(q->q.part().equals("frog_wall")))throw new AssertionError("Crossing noses have no solid cut walls");
            Mesh reverse=PointMesh.build(new Junction(j.id(),j.kind(),ta.reverse(),tb,j.center(),ta.length-j.sa(),j.sb(),j.extent()),s,p,0);
            List<Mesh.Quad> reversed=tops(reverse,p.top());
            for(int i=0;i<250;i++){V3 point=new V3((random.nextDouble()-.5)*3,0,(random.nextDouble()-.5)*3);if(coverage(tops,point)!=coverage(reversed,point))throw new AssertionError("Crossing changed after reversing a track");}
            if(degrees==30)Regression.export(mesh,"diamond-acute");
            if(degrees==30)for(double height:new double[]{-40,64,180}){
                Track raisedA=new Track(ta.id,ta.startNode,ta.endNode,ta.points.stream().map(v->v.add(0,height,0)).toList());
                Track raisedB=new Track(tb.id,tb.startNode,tb.endNode,tb.points.stream().map(v->v.add(0,height,0)).toList());
                Mesh raised=PointMesh.build(new Junction(j.id(),j.kind(),raisedA,raisedB,j.center().add(0,height,0),j.sa(),j.sb(),j.extent()),s,p,0);
                if(raised.quads.size()!=mesh.quads.size())throw new AssertionError("Crossing topology depends on world altitude");
                for(int k=0;k<mesh.quads.size();k++){
                    var q=mesh.quads.get(k);var r=raised.quads.get(k);
                    if(q.a().add(0,height,0).distance(r.a())>1e-8||q.b().add(0,height,0).distance(r.b())>1e-8||q.c().add(0,height,0).distance(r.c())>1e-8||q.d().add(0,height,0).distance(r.d())>1e-8)throw new AssertionError("Frog walls/flange depth depend on world altitude");
                }
            }
            System.out.println("PASS: diamond "+degrees+" deg, "+checked+" head/flangeway/overlap samples and track reversal");
        }
    }
    private static boolean head(double d,Profile p,PointSettings s){return Math.abs(d-p.centerOffset())<p.headWidth()/2||Math.abs(d-(p.centerOffset()-p.headWidth()-s.flangeway()))<p.headWidth()/2;}
    private static boolean channel(double d,Profile p,PointSettings s){return d<p.centerOffset()-p.headWidth()/2&&d>p.centerOffset()-p.headWidth()/2-s.flangeway();}
    static void scissors(ScissorsLayout group,Mesh center,Mesh assembled){
        Junction j=group.crossing();Profile p=Profile.STANDARD;PointSettings s=PointSettings.DEFAULT;
        List<Mesh.Quad> tops=tops(center,p.top()),all=tops(assembled,p.top());Random random=new Random(12403);
        for(int sa:new int[]{-1,1})for(int sb:new int[]{-1,1}){
            double da=j.sa(),db=j.sb();
            for(int i=0;i<20;i++){
                V3 a=offset(j.a(),da,sa*p.centerOffset()),b=offset(j.b(),db,sb*p.centerOffset()),u=j.a().tangent(da),v=j.b().tangent(db);
                double den=V3.crossXZ(u,v);da+=V3.crossXZ(b.sub(a),v)/den;db+=V3.crossXZ(b.sub(a),u)/den;
            }
            V3 nose=offset(j.a(),da,sa*p.centerOffset());
            double projection=nose.sub(j.center()).dot(group.axis());
            if(projection<group.lo()+.2||projection>group.hi()-.2)throw new AssertionError("Shared region cuts off an outer diamond nose");
            for(int k=0;k<450;k++){
                V3 q=nose.add((random.nextDouble()-.5)*.4,0,(random.nextDouble()-.5)*.4);
                double a=lateral(j.a(),q),b=lateral(j.b(),q);
                // Curves are sampled polylines. Stay 2 mm off analytic boundaries so the
                // oracle does not mistake chord approximation for missing/overlapping steel.
                if(edge(a,p,s)||edge(b,p,s))continue;
                boolean expected=(head(a,p,s)||head(b,p,s))&&!channel(a,p,s)&&!channel(b,p,s);
                int actual=coverage(all,q);
                if((actual>0)!=expected||actual>1)throw new AssertionError("Scissors frog/guard footprint at "+q+" expected="+expected+", faces="+actual);
            }
        }
        for(Track road:group.tracks())for(int sign:new int[]{-1,1})for(double plane:new double[]{group.lo(),group.hi()}){
            double d=group.intersection(road,plane);
            for(int i=0;i<8;i++){V3 at=offset(road,d,sign*p.centerOffset());d-=(at.sub(j.center()).dot(group.axis())-plane)/road.tangent(d).dot(group.axis());}
            for(double side:new double[]{-.012,.012}){
                V3 q=offset(road,d+side,sign*p.centerOffset());
                boolean flange=group.tracks().stream().anyMatch(t->channel(lateral(t,q),p,s));
                if(!flange&&coverage(all,q)==0)throw new AssertionError("Offset rail gap on shared scissors seam "+road.id+" sign="+sign+" plane="+plane+" side="+side+" point="+q);
            }
        }
        int channels=0;
        var channelRoads=new java.util.LinkedHashMap<String,Integer>();
        for(Track road:group.tracks())for(int sign:new int[]{-1,1})for(double d=.1;d<road.length;d+=.07){
            V3 q=offset(road,d,sign*(p.centerOffset()-p.headWidth()/2-s.flangeway()/2));
            if(!group.central(q))continue;
            if(coverage(all,q)>0)throw new AssertionError("Scissors through-road flange blocked: "+road.id+" at "+q);
            channels++;channelRoads.merge(road.id,1,Integer::sum);
        }
        if(channels==0)throw new AssertionError("Scissors through-road flange sweep sampled nothing");
        for(Track road:group.tracks())if(channelRoads.getOrDefault(road.id,0)==0)throw new AssertionError("Scissors through-road flange sweep skipped "+road.id);
        System.out.println("PASS: "+channels+" flange samples on all four scissors roads");
        int guards=0;
        var guardRoads=new java.util.LinkedHashMap<String,Integer>();
        var seenRoads=new java.util.LinkedHashMap<String,Integer>();
        var checkRuns=new ArrayList<GuardRails.Run>();
        for(Junction y:group.turnouts())checkRuns.addAll(GuardRails.forJunction(y,s,p));
        for(var run:GuardRails.merge(checkRuns)){
            if(group.through().stream().noneMatch(t->t.id.equals(run.road().id)))continue;
            for(double d=run.start()+.02;d<run.end()-.02;d+=.027){
                V3 q=run.point(d);
                // Presence is counted over the whole run: an asymmetric crossing generates its own
                // check rails, and they do not all fall inside the shared centre of the layout.
                seenRoads.merge(run.road().id,1,Integer::sum);
                if(!group.central(q))continue;
                if(group.tracks().stream().anyMatch(t->channel(lateral(t,q),p,s)))continue;
                if(coverage(all,q)==0)throw new AssertionError("Guard disconnected/reflared at shared seam: "+q+" road="+run.road().id+" distance="+d+" range="+run.start()+","+run.end()+" laterals="+group.tracks().stream().map(t->t.id+":"+lateral(t,q)).toList());
                guards++;guardRoads.merge(run.road().id,1,Integer::sum);
            }
        }
        // An asymmetric crossing labels its roads differently, and its own through road keeps its
        // check rails outside the shared centre. Presence is therefore required per road over the
        // whole run, while the coverage oracle stays where pooled steel is guaranteed: the old
        // sweep skipped any road it never matched, so it could verify nothing and still pass.
        for(Track road:group.through())if(seenRoads.getOrDefault(road.id,0)==0)throw new AssertionError("Through-road check-rail sweep skipped "+road.id);
        int seen=seenRoads.values().stream().mapToInt(Integer::intValue).sum();
        System.out.println("PASS: "+guards+" original through-road check-rail samples inside the shared centre, "+seen+" over the whole run of "+group.through().size()+" through roads");
        System.out.println("PASS: "+guards+" original through-road check-rail samples including shared seams");
        System.out.println("PASS: all four scissors noses/check rails and both sides of every offset rail seam");
    }
    private static V3 offset(Track t,double d,double offset){return t.at(d).add(t.tangent(d).lateral().mul(offset));}
    private static double lateral(Track t,V3 q){double d=t.nearest(q);return Math.abs(q.sub(t.at(d)).dot(t.tangent(d).lateral()));}
    private static boolean edge(double d,Profile p,PointSettings s){for(double center:new double[]{p.centerOffset(),p.centerOffset()-p.headWidth()-s.flangeway()})if(Math.abs(Math.abs(d-center)-p.headWidth()/2)<.002)return true;return false;}
    static List<Mesh.Quad> tops(Mesh m,double height){return m.quads.stream().filter(q->List.of(q.a(),q.b(),q.c(),q.d()).stream().allMatch(v->Math.abs(v.y()-height)<1e-8)).toList();}
    static int coverage(List<Mesh.Quad> faces,V3 point){int count=0;for(var q:faces)if(triangle(point,q.a(),q.b(),q.c())||triangle(point,q.a(),q.c(),q.d()))count++;return count;}
    private static boolean triangle(V3 p,V3 a,V3 b,V3 c){
        double area=V3.crossXZ(b.sub(a),c.sub(a));if(Math.abs(area)<1e-12)return false;
        double u=V3.crossXZ(p.sub(a),c.sub(a))/area,v=V3.crossXZ(b.sub(a),p.sub(a))/area;
        return u>=-1e-9&&v>=-1e-9&&u+v<=1+1e-9;
    }
}
