package org.mtrpoint.client;

import java.util.*;
import org.mtrpoint.geometry.*;

/** Inspect the outer edge, not a 250 mm search window that conceals a 25 mm step. */
public final class StockApproachRegression {
    public static void run(){
        int samples=0;
        for(boolean three:new boolean[]{false,true})for(boolean mirror:new boolean[]{false,true})for(boolean reverse:new boolean[]{false,true})for(double approach:new double[]{0,18}){
            var roads=new ArrayList<Track>();
            for(int branch=0;branch<(three?3:2);branch++){
                var points=new ArrayList<V3>();
                for(int i=0;i<=200;i++){double z=i*.2,after=Math.max(0,z-approach);points.add(new V3((mirror?-1:1)*branch*.016*after*after,0,z));}
                Track road=new Track("approach"+branch,"shared","end"+branch,points);roads.add(reverse?road.reverse():road);
            }
            Junction j=Detector.find(roads).stream().filter(v->v.kind()==(three?Junction.Kind.THREE:Junction.Kind.Y)).findFirst().orElseThrow();
            double blade=TurnoutFrame.start(j,PointMesh.extent(j,PointSettings.DEFAULT));
            if(blade-approach<.6)throw new AssertionError("Fixture has no pre-blade divergence");
            for(Profile p:List.of(Profile.STANDARD,ChannelCutRegression.modelled())){
                var views=PointRenderer.viewsForTest(List.of(j),PointSettings.DEFAULT,p);
                Mesh world=PointRenderer.worldForTest(views);
                for(Mesh mesh:List.of(views.get(0).mesh(),world)){
                    double side=-TurnoutFrame.side(j,PointMesh.extent(j,PointSettings.DEFAULT));
                    Track road=j.b();
                    for(double d=approach+.35;d<blade-.25;d+=.017){
                        // Stay a millimetre inside the head. The previous first-road substitute
                        // misses this entire edge before the second road starts being drawn.
                        V3 q=road.at(d).add(road.tangent(d).lateral().mul(side*(p.centerOffset()+p.headWidth()/2-.001)));
                        if(!ReviewFixRegression.at(mesh,q.x(),q.z(),p.top()))throw new AssertionError("Pre-blade stock edge missing at "+d+", mirror="+mirror+", reversed="+reverse+", approach="+approach+", native="+(p.detail()!=null));
                        samples++;
                    }
                }
            }
        }
        if(samples<200)throw new AssertionError("Too few pre-blade stock edge samples: "+samples);
        System.out.println("PASS: "+samples+" pre-blade stock-edge samples stay continuous through common/remote approaches, mirrored/reversed, generic/native, preview/world");
    }
}
