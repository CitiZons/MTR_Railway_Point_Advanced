package org.mtrpoint;

import java.util.*;
import org.mtrpoint.geometry.*;

/** The closure rail bends into the check-side wing, without a straight third arm. */
public final class FrogWingConnectionRegression {
    public static void run() {
        Junction j=Detector.find(Regression.y().subList(0,2)).stream()
            .filter(v->v.kind()==Junction.Kind.Y).findFirst().orElseThrow();
        PointSettings s=PointSettings.DEFAULT;Profile p=Profile.STANDARD;
        Mesh mesh=PointMesh.build(j,s,p,0);
        double top=p.top()+s.verticalOffset(), extent=PointMesh.extent(j,s);
        FrogGeometry frog=new FrogGeometry(j,s,p,extent);
        double side=TurnoutFrame.side(j,extent);
        int samples=0;
        for(int branch=0;branch<2;branch++) {
            Track road=branch==0?j.a():j.b(); double sign=branch==0?side:-side;
            var wing=frog.checkWing(branch);
            V3 knee=road.at(frog.knee(branch)).add(road.tangent(frog.knee(branch)).lateral().mul(sign*p.centerOffset()));
            if(knee.distance(wing.center(wing.start()))>1e-5)throw new AssertionError("Closure and wing do not meet at their knee");
            for(int k=0;k<=20;k++)for(boolean incoming:new boolean[]{true,false}) {
                double d=incoming?frog.knee(branch)-k*.02:wing.start()+k*.02;
                V3 q=incoming?road.at(d).add(road.tangent(d).lateral().mul(sign*p.centerOffset())):wing.center(d);
                int bodies=0;
                for(var face:mesh.quads) {
                    if(!face.part().equals("frog")&&!face.part().equals("wing"))continue;
                    if(!flat(face,top)||!inside(face,q))continue;
                    bodies++;
                }
                if(bodies==0)throw new AssertionError("Wing/heart hand-over has a gap at branch "+branch+" station "+d);
                samples++;
            }
            double mid=(frog.knee(branch)+frog.crossingStation(branch))/2;
            V3 extra=road.at(mid).add(road.tangent(mid).lateral().mul(sign*p.centerOffset()));
            if(mesh.quads.stream().anyMatch(face->face.part().equals("wing")&&flat(face,top)&&inside(face,extra)))
                throw new AssertionError("Wing knee has an unwanted straight third arm toward the heart");
            double heel=frog.heel(branch)-.02;
            V3 tail=road.at(heel).add(road.tangent(heel).lateral().mul(sign*p.centerOffset()));
            if(mesh.quads.stream().noneMatch(face->face.part().equals("frog")&&flat(face,top)&&inside(face,tail)))
                throw new AssertionError("Fixed heart does not reach its heel");
        }
        if(samples<20)throw new AssertionError("Wing/heart hand-over sampled too few stations: "+samples);
        System.out.println("PASS: closure bends continuously into wing ("+samples+" samples); no third arm; fixed heart reaches both heels");
    }
    private static boolean flat(Mesh.Quad q,double y){return List.of(q.a(),q.b(),q.c(),q.d()).stream().allMatch(v->Math.abs(v.y()-y)<1e-8);}
    private static boolean inside(Mesh.Quad q,V3 p){return tri(p,q.a(),q.b(),q.c())||tri(p,q.a(),q.c(),q.d());}
    private static boolean tri(V3 p,V3 a,V3 b,V3 c){double area=V3.crossXZ(b.sub(a),c.sub(a));if(Math.abs(area)<1e-12)return false;double u=V3.crossXZ(p.sub(a),c.sub(a))/area,v=V3.crossXZ(b.sub(a),p.sub(a))/area;return u>=-1e-8&&v>=-1e-8&&u+v<=1+1e-8;}
}
