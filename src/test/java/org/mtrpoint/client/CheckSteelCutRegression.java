package org.mtrpoint.client;

import org.mtrpoint.geometry.*;
import org.mtrpoint.*;
import java.util.*;

/**
 * Check steel in the final assembled mesh.
 *
 * <p>PointRenderer.assemble() is the one mesh the world renderer submits for a whole connected
 * component: it pools every turnout's guard run, copies each view's wings and owns the sleepers and
 * fasteners. Check steel only reaches the world through that mesh, so this regression drives
 * assemble() itself and asserts on its quads.
 *
 * <p>The positive requirement is that a flange channel of any road in the component is free of
 * steel wherever another owner's check rail or wing crosses it, and the negative requirement is
 * that neither a parallel neighbour nor a crossing at a different running height may remove steel.
 */
public final class CheckSteelCutRegression {
    private static void require(boolean pass,String why){if(!pass)throw new AssertionError(why);}

    public static void run()throws Exception{
        Profile raw=Profile.STANDARD;PointSettings s=PointSettings.DEFAULT;Profile p=raw.tune(s);
        // A turnout whose check rails lie across a second crossing road, exactly like the reported
        // scissors: the pooled guard of the turnout and the crossing road's flange channels meet.
        Track a=Regression.line("cut-a","a0","a1",new V3(0,0,-25),new V3(0,0,25));
        Track b=new Track("cut-b","a0","b1",branch(-25,25,.0025));
        Track c=Regression.line("cut-c","c0","c1",new V3(-9,0,-1),new V3(9,0,-1));
        var junctions=Detector.find(List.of(a,b,c));
        require(junctions.stream().anyMatch(j->j.kind()==Junction.Kind.Y),"Fixture lost its turnout");
        require(junctions.size()>=3,"Fixture lost the crossing junctions: "+junctions.size());
        var views=new ArrayList<PointClient.View>();
        for(var j:junctions)views.add(PointRenderer.view(j,s,raw,null));
        long started=System.nanoTime();
        Mesh assembly=PointRenderer.assembleForTest(views);
        long buildMillis=(System.nanoTime()-started)/1000000;
        require(buildMillis<60000,"Assembling one component took "+buildMillis+" ms");
        double top=p.top()+s.verticalOffset();
        var heads=headTops(assembly,top);
        require(heads.size()>500,"Final assembly carries almost no running-surface steel: "+heads.size());

        // Every road of the component, at its own flange channel: no steel may block a wheel flange.
        var roads=new ArrayList<Track>();for(var j:junctions)for(Track road:j.tracks())if(roads.stream().noneMatch(t->t.id.equals(road.id)))roads.add(road);
        require(roads.size()==3,"Fixture lost component roads: "+roads.size());
        int blocked=0;StringBuilder first=new StringBuilder();
        for(Track road:roads)for(int sign:new int[]{-1,1})for(double d=0;d<=road.length;d+=.02)for(double across:new double[]{-.02,0,.02}){
            V3 q=channelPoint(road,d,sign,p,s,across);
            var hit=cover(heads,q);
            if(hit.isEmpty())continue;
            blocked++;
            if(first.length()<400)first.append(" [").append(road.id).append(" sign=").append(sign).append(" ").append(hit.get(0).part()).append(" at ").append(q).append("]");
        }
        require(blocked==0,"Final assembly blocks "+blocked+" flange-channel samples with steel:"+first);
        System.out.println("PASS: 3 component roads keep every flange channel clear of pooled guards and wings ("+buildMillis+" ms per component assembly)");
        System.out.println("PASS: final assembly carries "+heads.size()+" running-surface faces over "+roads.size()+" roads");

        // The check steel itself must survive where nothing crosses it: sweep each pooled run's own
        // head band and require exactly one owner face, so a cut may not eat steel or leave it
        // doubled. Runs are allowed to yield only inside a channel or under a crossing rail.
        var runs=new ArrayList<GuardRails.Run>();
        for(var j:junctions)if(j.kind()==Junction.Kind.Y)runs.addAll(GuardRails.assembled(j,s,raw,null,null));
        require(runs.size()>=2,"Turnout produced no pooled guard runs");
        int swept=0;
        for(var run:runs)for(double d=run.start()+.05;d<run.end()-.05;d+=.02)for(int step=-1;step<=1;step++){
            V3 q=run.point(d).add(run.road().tangent(d).lateral().mul(step*.01));
            if(blockedByChannel(roads,q,p,s)||underForeignRail(roads,run.road().id,q,p))continue;
            var hit=cover(heads,q);
            require(hit.size()==1,"Pooled check steel must have exactly one owner face at "+q+", found "+hit.stream().map(Mesh.Quad::part).toList());
            swept++;
        }
        require(swept>400,"Pooled guard sweep too thin to prove anything: "+swept);
        System.out.println("PASS: "+swept+" pooled check-rail samples keep exactly one owner face outside every channel");

        // A crossing that runs at a different height cannot overlap the guard section, so it must
        // not remove any steel: the same fixture with the turnout raised by twice a rail height.
        PointSettings raised=s.with(11,.3);
        var raisedViews=new ArrayList<PointClient.View>();
        for(var j:junctions)raisedViews.add(PointRenderer.view(j,j.kind()==Junction.Kind.Y?raised:s,raw,null));
        Mesh lifted=PointRenderer.assembleForTest(raisedViews);
        var liftedHeads=headTops(lifted,p.top()+.3+s.verticalOffset());
        require(!liftedHeads.isEmpty(),"Raised turnout carries no running-surface steel");
        int kept=0;
        for(var run:runs)for(double d=run.start()+.05;d<run.end()-.05;d+=.02){
            V3 q=run.point(d).add(0,.3,0);
            if(blockedByChannel(roads,q,p,s))continue;
            require(!cover(liftedHeads,q).isEmpty(),"An elevated crossing removed check steel at "+q);
            kept++;
        }
        require(kept>200,"Raised guard sweep too thin to prove anything: "+kept);
        System.out.println("PASS: "+kept+" check-rail samples survive a crossing one section higher");

        // A parallel neighbour and an elevated road are offered to the same cut as real crossings:
        // neither may remove a single face. This is the guard against cutting by proximity alone.
        Mesh guard=new Mesh();guard.quads.addAll(runs.get(0).mesh().quads);
        require(!guard.quads.isEmpty(),"Pooled guard run built no steel");
        for(double lift:new double[]{0,.3}){
            Track side=Regression.line("cut-side","s0","s1",new V3(runs.get(0).center((runs.get(0).start()+runs.get(0).end())/2).x()+.69,0,runs.get(0).start()-4),
                new V3(runs.get(0).center((runs.get(0).start()+runs.get(0).end())/2).x()+.69,0,runs.get(0).end()+4));
            Mesh sideMesh=new Mesh();sideMesh.rail(side.at(0),side.at(side.length),1,1,p,s,"rail");
            Mesh cut=DiamondGeometry.cutSteel(guard,List.of(new DiamondGeometry.Steel(sideMesh,top+lift,p,s,List.of(side))),p,s,top);
            require(cut.quads.equals(guard.quads),"A parallel neighbour at lift "+lift+" removed check steel: "+cut.quads.size()+" of "+guard.quads.size()+" faces left");
        }
        System.out.println("PASS: a parallel neighbour and a raised crossing leave the pooled guard untouched, quad for quad");
    }

    /** Sample point inside a road's flange channel; `across` moves within the channel width. */
    private static V3 channelPoint(Track road,double d,int sign,Profile p,PointSettings s,double across){
        double gap=Math.max(.02,s.flangeway()+s.wingGapDelta());
        return road.at(d).add(road.tangent(d).lateral().mul(sign*(p.centerOffset()-p.headWidth()/2-gap/2)+across));
    }
    private static boolean blockedByChannel(List<Track> roads,V3 q,Profile p,PointSettings s){
        double gap=Math.max(.02,s.flangeway()+s.wingGapDelta()),inner=p.centerOffset()-p.headWidth()/2-gap,outer=p.centerOffset()-p.headWidth()/2;
        for(Track road:roads){
            double d=road.nearest(q);if(d<=0||d>=road.length)continue;
            double lateral=Math.abs(q.sub(road.at(d)).dot(road.tangent(d).lateral()));
            if(lateral>inner-.02&&lateral<outer+.02)return true;
        }
        return false;
    }
    /** True when a crossing road's running rail passes over this point, where check steel yields. */
    private static boolean underForeignRail(List<Track> roads,String own,V3 q,Profile p){
        for(Track road:roads){
            if(road.id.equals(own))continue;
            double d=road.nearest(q);if(d<=0||d>=road.length)continue;
            double lateral=q.sub(road.at(d)).dot(road.tangent(d).lateral());
            for(int sign:new int[]{-1,1})if(Math.abs(lateral-sign*p.centerOffset())<p.headWidth()+.05)return true;
        }
        return false;
    }
    private static List<Mesh.Quad> headTops(Mesh mesh,double height){
        var result=new ArrayList<Mesh.Quad>();
        for(var q:mesh.quads){
            boolean flat=true;
            for(V3 v:List.of(q.a(),q.b(),q.c(),q.d()))if(Math.abs(v.y()-height)>1e-8||!Double.isFinite(v.x()+v.z())){flat=false;break;}
            if(flat)result.add(q);
        }
        return result;
    }
    private static List<Mesh.Quad> cover(List<Mesh.Quad> faces,V3 point){
        var result=new ArrayList<Mesh.Quad>();
        for(var q:faces)if(triangle(point,q.a(),q.b(),q.c())||triangle(point,q.a(),q.c(),q.d()))result.add(q);
        return result;
    }
    private static boolean triangle(V3 p,V3 a,V3 b,V3 c){
        double area=V3.crossXZ(b.sub(a),c.sub(a));if(Math.abs(area)<1e-12)return false;
        double u=V3.crossXZ(p.sub(a),c.sub(a))/area,v=V3.crossXZ(b.sub(a),p.sub(a))/area;
        return u>=-1e-9&&v>=-1e-9&&u+v<=1+1e-9;
    }
    private static List<V3> branch(double from,double to,double curve){
        var points=new ArrayList<V3>();int count=(int)Math.ceil((to-from)/.25);
        for(int i=0;i<=count;i++){double z=from+(to-from)*i/count,dz=z-from;points.add(new V3(curve*dz*dz,0,z));}
        return points;
    }
}
