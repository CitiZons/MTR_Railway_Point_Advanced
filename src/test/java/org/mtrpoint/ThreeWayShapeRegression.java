package org.mtrpoint;

import org.mtrpoint.geometry.*;
import java.util.*;

/** Long common approaches reproduce the half-sleeper loss hidden by short fan fixtures. */
public final class ThreeWayShapeRegression {
    private static void require(boolean pass,String why){if(!pass)throw new AssertionError(why);}
    public static Junction common(){
        var roads=new ArrayList<Track>();
        for(int branch=-1;branch<=1;branch++){
            var points=new ArrayList<V3>();
            for(int i=0;i<=200;i++){double z=i*.25,div=Math.max(0,z-14);points.add(new V3(branch*.018*div*div,0,z));}
            roads.add(new Track("common-"+branch,"common-start","end-"+branch,points));
        }
        return Detector.find(roads).stream().filter(j->j.kind()==Junction.Kind.THREE).findFirst().orElseThrow();
    }
    public static void run(){
        Junction j=common();Profile p=Profile.STANDARD;PointSettings s=PointSettings.DEFAULT;
        double start=TurnoutFrame.start(j,PointMesh.extent(j,s)),length=9,oldContact=TurnoutFrame.contact(length,p,s),contact=ThreeWayMesh.tipContact(length,p,s);
        require(contact>oldContact,"Three-way planing must start farther back from the tip");
        for(double d:new double[]{oldContact*.5,oldContact})require(TurnoutFrame.taper(d,contact)<TurnoutFrame.taper(d,oldContact),"Three-way tip did not narrow earlier");
        for(int[] seat:new int[][]{{0,1},{1,-1},{1,1},{2,-1}})for(double open:new double[]{0,.5,1})for(int k=0;k<=20;k++){
            double d=start+length*k/20;int branch=seat[0],sign=seat[1];
            V3 actual=ThreeWayMesh.bladePoint(j,branch,sign,d,open,start,length,contact,p,s);
            V3 expected=TurnoutFrame.seatedBlade(j.tracks().get(branch),j.tracks().get(branch+sign),d,sign,open,start,length,contact,p,s);
            require(actual.equals(expected),"Three-way route was bent into a nested blade stack");
        }
        var checks=GuardRails.assembled(j,s,p,null,null);
        require(checks.stream().noneMatch(r->r.road().id.equals(j.third().id)&&r.part().equals("guard")),"Middle road must not draw redundant guards");
        require(checks.stream().anyMatch(r->r.road().id.equals(j.third().id)&&r.part().equals("wing")),"Middle road lost its wings");
        require(GuardRails.selectable(j,s,p,null,null).size()==12,"Existing check edit indices changed");
        for(int mode:new int[]{0,4}){
            Mesh mesh=PointMesh.build(j,s.with(16,mode),p,0);
            var tops=mesh.quads.stream().filter(q->q.part().equals("sleeper")&&List.of(q.a(),q.b(),q.c(),q.d()).stream().allMatch(v->Math.abs(v.y()-(p.top()-p.railHeight()))<1e-7)).toList();
            var rows=tops.stream().map(Mesh.Quad::index).distinct().toList();int checked=0;
            for(int row:rows){var faces=tops.stream().filter(q->q.index()==row).toList();double z=faces.stream().mapToDouble(q->q.center().z()).average().orElseThrow();
                if(z<1||z>12)continue;
                for(double x:new double[]{-.9,.9}){
                    V3 point=new V3(x,p.top()-p.railHeight(),z);
                    require(faces.stream().anyMatch(q->inside(point,q.a(),q.b(),q.c())||inside(point,q.a(),q.c(),q.d())),"Common three-way approach missing half sleeper: mode="+mode+" row="+row+" x="+x);
                }checked++;
            }require(checked>10,"Sleeper regression did not exercise long common approach");
        }
        System.out.println("THREE_WAY_SHAPE: PASS longer planing with restored route shape, middle wings without guards and both sleeper halves");
    }
    private static boolean inside(V3 p,V3 a,V3 b,V3 c){double area=V3.crossXZ(b.sub(a),c.sub(a));if(Math.abs(area)<1e-12)return false;double u=V3.crossXZ(p.sub(a),c.sub(a))/area,v=V3.crossXZ(b.sub(a),p.sub(a))/area;return u>=-1e-8&&v>=-1e-8&&u+v<=1+1e-8;}
}
