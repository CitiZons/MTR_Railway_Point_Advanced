package org.mtrpoint.client;

import java.util.*;
import org.mtrpoint.Regression;
import org.mtrpoint.geometry.*;

public final class EndcapPatchRegression {
    public static void main(String[] args) {
        if(args.length>0&&args[0].equals("patches")){patches();return;}
        handovers();
        StockApproachRegression.run();
    }
    static Profile actualNative(){
        return org.mtrpoint.AppearanceData.JSON.fromJson(new java.io.InputStreamReader(
            EndcapPatchRegression.class.getResourceAsStream("/native-rail-profile.json"),java.nio.charset.StandardCharsets.UTF_8),Profile.class);
    }
    public static void patches(){
        int samples=0;
        Profile nativeProfile=actualNative();
        for(Profile p:List.of(Profile.STANDARD,ChannelCutRegression.modelled(),nativeProfile))
        for(int degrees:new int[]{15,30,60,90}) {
            double angle=Math.toRadians(degrees);
            Track cross=Regression.line("patch-cross","c0","c1",new V3(-8*Math.sin(angle),0,-8*Math.cos(angle)),new V3(8*Math.sin(angle),0,8*Math.cos(angle)));
            Mesh source=new Mesh();source.rail(new V3(0,0,-8),new V3(0,0,8),1,1,p,PointSettings.DEFAULT,"wing");
            Mesh result=DiamondGeometry.cutSteel(source,List.of(new DiamondGeometry.Steel(new Mesh(),p.top(),p,PointSettings.DEFAULT,List.of(cross))),p,PointSettings.DEFAULT,p.top(),List.of(),false);
            int caps=0;
            for(var q:result.quads)if(q.surface().equals(Profile.END_STEEL)){
                caps++;
                for(V3 v:List.of(q.a().lerp(q.b(),.5),q.b().lerp(q.c(),.5),q.center())){
                    double width;
                    if(p==nativeProfile)width=nativeWidth(p,v.y());
                    else width=v.y()<=p.top()-p.railHeight()+.025+1e-7?p.footWidth()/2:v.y()>=p.top()-.036-1e-7?p.headWidth()/2:.011;
                    if(Math.abs(v.x())>width+2e-5)throw new AssertionError("Gray cap escapes original section: angle="+degrees+" profile="+p.source()+" point="+v+" width="+width);
                    samples++;
                }
            }
            if(caps==0)throw new AssertionError("Actual crossing cuts lost all caps");
        }
        System.out.println("PASS: "+samples+" cut-cap samples stay inside the original rail section, including actual MTR native model");
    }
    private static double nativeWidth(Profile p,double y){
        var d=p.detail();double local=y-p.top()+d.railTop();
        double width=0;
        for(var q:d.rails()){
            var vs=List.of(q.a(),q.b(),q.c(),q.d());
            for(int i=0;i<4;i++){
                V3 a=vs.get(i),b=vs.get((i+1)%4);
                if(Math.abs(a.y()-b.y())<1e-10)continue;
                double t=(local-a.y())/(b.y()-a.y());
                if(t>=0&&t<=1)width=Math.max(width,Math.abs(a.lerp(b,t).x()-d.railCenter()));
            }
        }
        // Native OBJ omits the tiny underside connecting head to web.
        if(local>.263)width=.034192;
        return width*p.headWidth()/d.headWidth();
    }
    public static void handovers() {
        int checked=0;
        var a=Regression.line("ha","a0","a1",new V3(0,0,-20),new V3(0,0,20));
        var b=Regression.line("hb","b0","b1",new V3(-20,0,0),new V3(20,0,0));
        for(Profile p:List.of(Profile.STANDARD,ChannelCutRegression.modelled()))
        for(Junction j:List.of(Detector.find(Regression.y()).get(0),Detector.find(List.of(a,b)).get(0))) {
            var s=PointSettings.DEFAULT;
            Mesh mesh=PointMesh.build(j,s,p,0);
            double extent=PointMesh.extent(j,s);
            for(Track road:j.tracks())for(double d:j.kind()==Junction.Kind.DIAMOND
                    ?new double[]{road.nearest(j.center())-extent,road.nearest(j.center())+extent}:new double[]{0,extent}) {
                V3 at=road.at(d),axis=road.tangent(d);
                for(var q:mesh.quads)if(q.part().equals("rail")||q.part().equals("frog")) {
                    if(List.of(q.a(),q.b(),q.c(),q.d()).stream().allMatch(v->Math.abs(v.sub(at).dot(axis))<1e-7))
                        throw new AssertionError("Rendering handover carries an end plate: "+j.kind()+" native="+(p.detail()!=null)+" d="+d);
                }
                checked++;
            }
            if(mesh.quads.stream().noneMatch(q->q.surface().equals(Profile.END_STEEL)&&q.part().equals("wing"))&&p.detail()!=null)
                throw new AssertionError("Real exposed wing caps disappeared");
        }
        System.out.println("PASS: "+checked+" rendering boundaries have no end plates; free wing ends remain capped");
    }
}
