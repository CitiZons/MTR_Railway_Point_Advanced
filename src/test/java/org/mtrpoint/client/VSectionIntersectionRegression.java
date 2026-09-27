package org.mtrpoint.client;

import java.util.*;
import org.mtrpoint.geometry.*;

/** Cut surfaces must stay in the actual head/web/foot, including both sides of each V. */
public final class VSectionIntersectionRegression {
    public static void run(){
        int samples=0;
        for(int degrees:new int[]{15,30,60,90})for(boolean reversed:new boolean[]{false,true}){
            double angle=Math.toRadians(degrees);V3 u=new V3(0,0,1),v=new V3(Math.sin(angle),0,Math.cos(angle));
            Track a=new Track("section-a","a0","a1",List.of(u.mul(-20),u.mul(20)));
            Track b=new Track("section-b","b0","b1",List.of(v.mul(-20),v.mul(20)));
            var roads=List.of(reversed?a.reverse():a,reversed?b.reverse():b);
            for(Profile p:List.of(Profile.STANDARD,ChannelCutRegression.modelled())){
                Junction j=Detector.find(roads).get(0);
                var views=PointRenderer.viewsForTest(List.of(j),PointSettings.DEFAULT,p);
                for(Mesh mesh:List.of(views.get(0).mesh(),PointRenderer.worldForTest(views))){
                double y=p.top()-.045;
                // Cross-sections through every vertical cut face in the rail-web band.
                for(var q:mesh.quads){
                    if(q.part().equals("sleeper")||q.part().equals("fastener"))continue;
                    List<V3> vertices=List.of(q.a(),q.b(),q.c(),q.d());
                    for(int[] tri:new int[][]{{0,1,2},{0,2,3}}){
                        var hits=new ArrayList<V3>();
                        for(int k=0;k<3;k++){
                            V3 x=vertices.get(tri[k]),z=vertices.get(tri[(k+1)%3]);
                            if((x.y()-y)*(z.y()-y)<0)hits.add(x.lerp(z,(y-x.y())/(z.y()-x.y())));
                        }
                        if(hits.size()!=2||hits.get(0).distance(hits.get(1))<1e-7)continue;
                        for(double t:new double[]{.2,.5,.8}){
                            // Inspect the crossing itself; the remote check-rail mouths
                            // intentionally bend away from these straight section axes.
                            V3 point=hits.get(0).lerp(hits.get(1),t);if(Math.hypot(point.x(),point.z())>1.1)continue;
                            boolean solid=false;
                            for(Track road:roads){
                                double offset=Math.abs(point.dot(road.tangent(road.length/2).lateral()));
                                double running=Math.abs(offset-p.centerOffset()),guard=Math.abs(offset-(p.centerOffset()-p.headWidth()-PointSettings.DEFAULT.flangeway()));
                                if(Math.min(running,guard)<.01101)solid=true;
                            }
                            if(!solid)throw new AssertionError("Cut face fills an I-section web notch at "+point+", angle="+degrees+", part="+q.part()+", native="+(p.detail()!=null)+", face="+vertices);
                            samples++;
                        }
                    }
                }
                }
            }
        }
        if(samples<100)throw new AssertionError("Too few real cut web samples: "+samples);
        System.out.println("PASS: "+samples+" V-intersection web-face samples preserve the actual I-section notches (15/30/60/90 degrees, reverse, generic/native, preview/world)");
    }
}
