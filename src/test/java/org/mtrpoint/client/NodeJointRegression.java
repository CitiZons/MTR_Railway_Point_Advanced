package org.mtrpoint.client;

import org.mtrpoint.geometry.*;
import org.mtrpoint.Regression;
import java.util.*;

public final class NodeJointRegression {
    public static void run(Profile p){
        V3 n=new V3(-1,0,0);PointSettings s=PointSettings.DEFAULT;
        Mesh nativeCell=new Mesh();nativeCell.rail(new V3(0,0,-1),V3.ZERO,n,n,1,1,p,s,"rail_left");
        Mesh continuation=new Mesh();continuation.rail(new V3(0,0,.002),new V3(0,0,1),n,n,1,1,p,s,"rail");
        Mesh all=new Mesh();all.quads.addAll(nativeCell.quads);all.quads.addAll(continuation.quads);
        Mesh joints=RailJoints.bridges(all,true);
        if(!ReviewFixRegression.at(joints,0,.001,p.top()))throw new AssertionError("Native-to-turnout node seam remains open");
        Mesh nativeOther=new Mesh();nativeOther.rail(new V3(0,0,.002),new V3(0,0,1),n,n,1,1,p,s,"rail_right");
        all=new Mesh();all.quads.addAll(nativeCell.quads);all.quads.addAll(nativeOther.quads);
        if(!ReviewFixRegression.at(RailJoints.bridges(all,true),0,.001,p.top()))throw new AssertionError("Two native node cells remain disconnected");
        Junction j=Detector.find(Regression.y()).stream().filter(a->a.kind()==Junction.Kind.Y).findFirst().orElseThrow();
        PointClient.View view=PointRenderer.view(j,s,p,null);Mesh staticSteel=view.jointSteel();int heels=0;
        for(double pose:new double[]{0,.5,1}){
            view.previewPosition=pose;Mesh drawn=view.mesh();
            if(view.jointSteel()!=staticSteel)throw new AssertionError("Joint input rebuilt during blade throw");
            var vertices=drawn.quads.stream().filter(q->q.part().equals("blade")).flatMap(q->java.util.stream.Stream.of(q.a(),q.b(),q.c(),q.d())).toList();
            for(var q:staticSteel.quads)if(q.part().equals("blade_heel")){
                var vs=List.of(q.a(),q.b(),q.c(),q.d());
                for(int index:new int[]{q.index(),(q.index()+1)%4})if(vertices.stream().noneMatch(v->v.distance(vs.get(index))<1e-8))throw new AssertionError("Moving blade edge included in fixed heel joint");
                heels++;
            }
        }
        if(heels<6)throw new AssertionError("Fixed blade heels were not inspected");
        if(p.detail()!=null){
            FrogGeometry frog=new FrogGeometry(j,s,p,PointMesh.extent(j,s));
            V3 tip=frog.fittingTip().add(0,-p.top(),0).add(frog.fittingForward().mul(.1));
            Mesh before=hardware(tip,n,frog,p,s,false),after=hardware(tip,n,frog,p,s,true);
            double old=hardwareArea(before),remaining=hardwareArea(after);
            if(remaining<1e-8||remaining>=old-1e-8)throw new AssertionError("V-tip hardware omission removed neighbours or missed the tip: area="+old+" -> "+remaining);
            if(!before.quads.stream().filter(q->!q.part().startsWith("fastener")).toList().equals(after.quads.stream().filter(q->!q.part().startsWith("fastener")).toList()))throw new AssertionError("V-tip suppression changed support blocks");
            outsideHardware(frog,p,s);
            Junction three=org.mtrpoint.ThreeWayShapeRegression.common();
            for(int a=0;a<3;a++)for(int b=a+1;b<3;b++){
                Junction pair=new Junction("outside"+a+b,Junction.Kind.Y,three.tracks().get(a),three.tracks().get(b),three.center(),0,0,three.extent());
                outsideHardware(new FrogGeometry(pair,s,p,PointMesh.extent(pair,s)),p,s);
            }
        }
        System.out.println("NODE_JOINTS: PASS "+p.source()+" native/node seams, fixed blade heels across throws and local V-tip hardware clearance");
    }
    private static Mesh hardware(V3 tip,V3 normal,FrogGeometry frog,Profile p,PointSettings s,boolean suppress){
        FittingSeats seats=new FittingSeats();seats.add(tip,normal,false);seats.add(tip.add(normal.mul(1.5)),normal,true);
        Mesh out=new Mesh();seats.emitTurnout(out,p,s,0,"frog");if(suppress)FrogFittings.clear(out,frog,p);return out;
    }
    private static double hardwareArea(Mesh mesh){
        return mesh.quads.stream().filter(q->q.part().startsWith("fastener")).mapToDouble(q->triangleArea(q.a(),q.b(),q.c())+triangleArea(q.a(),q.c(),q.d())).sum();
    }
    private static double triangleArea(V3 a,V3 b,V3 c){
        V3 u=b.sub(a),v=c.sub(a);
        return new V3(u.y()*v.z()-u.z()*v.y(),u.z()*v.x()-u.x()*v.z(),u.x()*v.y()-u.y()*v.x()).length()/2;
    }
    private static void outsideHardware(FrogGeometry frog,Profile p,PointSettings s){
        for(int branch=0;branch<2;branch++){
            var edge=frog.fittingEdge(branch);V3 row=frog.fittingTip().add(0,-p.top(),0).add(frog.fittingForward().mul(.2));
            V3 running=row.sub(edge.outward().mul(row.sub(edge.origin()).dot(edge.outward())+p.footWidth()/2));
            V3 guard=running.add(edge.outward().mul(p.headWidth()+.045));
            Mesh before=new Mesh();TurnoutFittings.guardPair(before,running,guard,edge.outward(),p,s,7);
            Mesh after=new Mesh();after.quads.addAll(before.quads);FrogFittings.clear(after,frog,p);
            var exterior=before.quads.stream().filter(q->q.part().startsWith("fastener"))
                .filter(q->List.of(q.a(),q.b(),q.c(),q.d()).stream().allMatch(v->v.sub(edge.origin()).dot(edge.outward())>1e-5)).toList();
            if(exterior.size()<10||!after.quads.containsAll(exterior))throw new AssertionError("Outside V-tip guard clips or brackets were removed");
            double foot=p.top()-p.detail().railTop()+p.detail().rails().stream().flatMap(q->java.util.stream.Stream.of(q.a(),q.b(),q.c(),q.d())).mapToDouble(V3::y).min().orElseThrow();
            if(!ReviewFixRegression.at(after,guard.x(),guard.z(),guard.y()+foot))throw new AssertionError("Outside V-tip guard lost its base plate");
            if(!before.quads.stream().filter(q->!q.part().startsWith("fastener")).toList().equals(after.quads.stream().filter(q->!q.part().startsWith("fastener")).toList()))throw new AssertionError("V interior cut changed support geometry");
        }
        System.out.println("V_OUTSIDE_FITTINGS: PASS "+p.source()+" both exterior guard fixtures and plates preserved");
    }
}
