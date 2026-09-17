package org.mtrpoint;

import org.mtrpoint.geometry.*;
import java.util.*;

final class AssemblyRegression {
    static void run()throws Exception{
        Profile p=Profile.STANDARD;PointSettings s=PointSettings.DEFAULT;
        Track a=Regression.line("guard-a","a","b",new V3(0,0,0),new V3(0,0,12));
        var points=new ArrayList<V3>();for(int i=0;i<=120;i++){double z=i*.1;points.add(new V3(z<6?0:.012*(z-6)*(z-6),0,z));}
        Track b=new Track("guard-b","c","d",points);
        var runs=List.of(new GuardRails.Run(a,2,10,.6,true,true,p,s),new GuardRails.Run(b,4,11,.6,true,true,p,s));
        Mesh guards=DiamondGeometry.guards(runs);var tops=DiamondRegression.tops(guards,p.top());
        int samples=0;
        for(double z=4.5;z<10;z+=.053)for(double x=-.72;x<-.27;x+=.0071){
            V3 q=new V3(x,0,z);int hits=DiamondRegression.coverage(tops,q);
            if(hits>1)throw new AssertionError("Partially coincident guards have overlapping heads at "+q);samples++;
        }
        for(double z=4.5;z<5.5;z+=.073)if(DiamondRegression.coverage(tops,new V3(-.6,0,z))!=1)throw new AssertionError("Coincident guard union removed both rails");
        Regression.export(guards,"guards-partial-union");
        Mesh supports=new Mesh();supports.beam(new V3(-2,0,0),new V3(2,0,0),.3,.3,0,.12,p.sleeper(),"sleeper",1);
        supports.beam(new V3(-1,0,-.6),new V3(1,0,.6),.3,.3,0,.12,p.sleeper(),"sleeper",2);
        var original=DiamondRegression.tops(supports,.12);Mesh clean=SurfaceUnion.build(supports);var cleanTops=DiamondRegression.tops(clean,.12);
        for(double x=-2;x<2;x+=.029)for(double z=-.8;z<.8;z+=.031){V3 q=new V3(x,0,z);int before=DiamondRegression.coverage(original,q),after=DiamondRegression.coverage(cleanTops,q);if((before>0)!=(after>0)||after>1)throw new AssertionError("Support union changed coverage or retained duplicate faces");}
        Mesh duplicate=new Mesh();duplicate.quads.addAll(supports.quads);duplicate.quads.addAll(supports.quads);
        if(!SurfaceUnion.build(duplicate).quads.equals(clean.quads))throw new AssertionError("Duplicate support assembly is drawn twice");
        var group=ScissorsLayout.find(Detector.find(Regression.scissors(false))).get(0);
        Mesh center=group.centerMesh(s,p);boolean diagonal=false;
        for(var q:center.quads)if(q.part().equals("sleeper"))for(V3 edge:List.of(q.b().sub(q.a()),q.d().sub(q.a())))
            if(edge.length()>.15&&Math.abs(edge.unit().dot(group.axis()))>.08&&Math.abs(edge.unit().dot(group.axis()))<.5)diagonal=true;
        if(!diagonal)throw new AssertionError("Scissors V mode ignores diagonal roads");
        Track crossA=Regression.line("pitch-a","pa","pb",new V3(0,0,-20),new V3(0,0,20));
        Track crossB=Regression.line("pitch-b","pc","pd",new V3(-20,0,0),new V3(20,0,0));
        Mesh ties=new Mesh();VSleepers.diamond(ties,new Junction("pitch",Junction.Kind.DIAMOND,crossA,crossB,new V3(0,0,0),20,20,8),s,p,8);
        var tieTops=DiamondRegression.tops(ties,p.top()-p.railHeight());boolean covered=false;var starts=new ArrayList<Double>();
        for(double z=2;z<7;z+=.005){int hits=DiamondRegression.coverage(tieTops,new V3(.01,0,z));if(hits>1)throw new AssertionError("Independent diamond has overlapping bearer tops");if(hits>0&&!covered)starts.add(z);covered=hits>0;}
        if(starts.size()<6)throw new AssertionError("Diamond sleepers missing along a road");
        for(int i=2;i<starts.size();i++)if(Math.abs(starts.get(i)-starts.get(i-1)-s.sleeperSpacing())>.015)throw new AssertionError("Diamond sleeper pitch is compressed");
        var graded=new ArrayList<V3>();for(int i=0;i<=200;i++){double z=i*.1;graded.add(new V3(.006*z*z,.2*z,z));}
        Track grade=new Track("grade","g0","g1",graded);V3 origin=grade.at(4),forward=grade.tangent(4);
        V3 horizontal=new V3(forward.x(),0,forward.z()).unit();V3 first=null,last=null;
        for(double pos:new double[]{0,.25,.5,.75,1}){
            V3 contact=TurnoutFrame.contact(grade,4,1,p.centerOffset(),pos,0,8,s,origin,forward);
            if(Math.abs(contact.sub(origin).dot(horizontal))>1e-7)throw new AssertionError("Graded moving stretcher is skewed in plan");
            if(first==null)first=contact;last=contact;
        }
        if(first.distance(last)<.02)throw new AssertionError("Stretcher does not follow the blade");
        System.out.println("PASS: partial guard union "+samples+" samples; final support coverage/duplicates; diagonal V arms; graded moving stretcher");
    }
}
