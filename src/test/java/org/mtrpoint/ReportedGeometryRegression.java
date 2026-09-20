package org.mtrpoint;

import org.mtrpoint.geometry.*;
import java.util.*;

final class ReportedGeometryRegression {
    static void run()throws Exception{
        var a=new ArrayList<V3>();var b=new ArrayList<V3>();
        for(int i=0;i<=400;i++){double z=i*.125,split=.018*Math.pow(Math.max(0,z-22),2);a.add(new V3(0,0,z));b.add(new V3(-split,0,z));}
        Track ta=new Track("long-a","origin","a",a),tb=new Track("long-b","origin","b",b);
        Junction y=Detector.find(List.of(ta,tb)).stream().filter(j->j.kind()==Junction.Kind.Y).findFirst().orElseThrow();
        double extent=PointMesh.extent(y,PointSettings.DEFAULT);FrogGeometry frog=new FrogGeometry(y,PointSettings.DEFAULT,Profile.STANDARD,extent);
        if(frog.sa<29||frog.sb<29)throw new AssertionError("Frog stranded on the common approach");
        for(double position:new double[]{0,.5,1}){
            Mesh mesh=PointMesh.build(y,PointSettings.DEFAULT,Profile.STANDARD,position);
            var bar=mesh.quads.stream().filter(q->q.part().equals("stretcher")).findFirst().orElseThrow();
            V3 axis=bar.b().sub(bar.a()).unit();
            if(Math.abs(axis.dot(ta.tangent(ta.nearest(bar.center()))))>1e-5||bar.center().z()<22)throw new AssertionError("Stretcher is oblique or before visual divergence");
            if(mesh.quads.stream().filter(q->q.part().equals("blade")).anyMatch(q->q.center().z()<21.9))throw new AssertionError("Blade starts at remote node");
            if(position==0)Regression.export(mesh,"long-approach");
        }
        Track shallow=Regression.line("shallow","s0","s1",new V3(-5,0,-100),new V3(5,0,100));
        Track straight=Regression.line("straight","t0","t1",new V3(0,0,-100),new V3(0,0,100));
        Junction x=Detector.find(List.of(shallow,straight)).stream().filter(j->j.kind()==Junction.Kind.DIAMOND).findFirst().orElseThrow();
        if(x.extent()<25)throw new AssertionError("Shallow crossing truncated");
        Track recross=new Track("recross",straight.startNode,"r",List.of(straight.at(0),new V3(-5,0,-50),new V3(5,0,50)));
        if(Detector.find(List.of(straight,recross)).stream().noneMatch(j->j.kind()==Junction.Kind.DIAMOND))throw new AssertionError("Shared-node interior crossing lost");
        Mesh v=PointMesh.build(x,PointSettings.DEFAULT,Profile.STANDARD,0),parallel=PointMesh.build(x,PointSettings.DEFAULT.with(16,1),Profile.STANDARD,0);
        if(v.quads.stream().filter(q->q.part().equals("sleeper")).toList().equals(parallel.quads.stream().filter(q->q.part().equals("sleeper")).toList()))throw new AssertionError("Diamond ignores V sleepers");
        Track clone=new Track("independent-id","clone0","clone1",ta.points);
        var merged=GuardRails.merge(List.of(new GuardRails.Run(ta,3,7,.6,true,true,Profile.STANDARD,PointSettings.DEFAULT),new GuardRails.Run(clone,6,10,.6,true,true,Profile.STANDARD,PointSettings.DEFAULT)));
        if(merged.size()!=1||Math.abs(merged.get(0).end()-merged.get(0).start()-7)>1e-8)throw new AssertionError("Coincident guards with different rail IDs did not merge");
        var roads=new ArrayList<Track>();String[] starts={"sw","se","sw","se"},ends={"nw","ne","ne","nw"};
        V3[] nodes={new V3(-3,0,-34),new V3(3,0,-20),new V3(-1,0,30),new V3(5,0,24)};
        int[] from={0,1,0,1},to={2,3,3,2};
        for(int r=0;r<4;r++){var points=new ArrayList<V3>();V3 first=nodes[from[r]],last=nodes[to[r]];
            for(int i=0;i<=320;i++){double t=i/320D,u=1-t;points.add(first.mul(u*u*u).add(first.add(0,0,14).mul(3*u*u*t)).add(last.add(0,0,-14).mul(3*u*t*t)).add(last.mul(t*t*t)));}
            roads.add(new Track("as"+r,starts[r],ends[r],points));
        }
        var groups=ScissorsLayout.find(Detector.find(roads));if(groups.size()!=1)throw new AssertionError("Asymmetric scissors group lost");
        var group=groups.get(0);Mesh center=group.centerMesh(PointSettings.DEFAULT,Profile.STANDARD),combined=new Mesh();combined.quads.addAll(center.quads);
        for(var branch:group.turnouts())combined.quads.addAll(group.clip(PointMesh.build(branch,PointSettings.DEFAULT,Profile.STANDARD,0,group.boundary(branch,PointSettings.DEFAULT)),branch,PointSettings.DEFAULT).quads);
        DiamondRegression.scissors(group,center,combined);Regression.export(combined,"scissors-asymmetric");
        System.out.println("PASS: remote node divergence and perpendicular moving bars; shallow/interior crossings; diamond V mode; cross-ID guard union");
    }
}
