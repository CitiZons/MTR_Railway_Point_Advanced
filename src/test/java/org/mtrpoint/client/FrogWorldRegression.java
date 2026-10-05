package org.mtrpoint.client;

import org.mtrpoint.geometry.*;
import org.mtrpoint.ThreeWayShapeRegression;
import org.mtrpoint.AppearanceData;
import com.google.gson.*;
import java.util.*;
import java.nio.file.*;

/** Reproduce frog noses and knees in the submitted world mesh, rather than the preview. */
public final class FrogWorldRegression {
    public static void main(String[] args)throws Exception{
        Junction j=ThreeWayShapeRegression.common();Profile p=Profile.STANDARD;
        Path assets=Path.of("../MTR_Citizons_Railway/resourcepacks/Citizons_Railway/assets");
        var descriptor=JsonParser.parseString(Files.readString(assets.resolve("citizons_railway/rail_profiles/citizons_mainline_1435.json"))).getAsJsonObject();
        var detail=ProfileModel.read(descriptor,descriptor.get("model").getAsString(),"",true,id->{var split=id.split(":",2);return Files.readString(assets.resolve(split[0]).resolve(split[1]));}).detail();
        p=new Profile(p.gauge(),p.top(),p.headWidth(),p.footWidth(),p.railHeight(),p.steel(),p.sleeper(),"mainline",true,detail);
        run(p);
        var data=new LinkedHashMap<String,Object>();
        data.put("three",tops(PointRenderer.worldForTest(PointRenderer.viewsForTest(List.of(j),PointSettings.DEFAULT,p)),p));
        for(int a=0;a<3;a++)for(int b=a+1;b<3;b++){
            var pair=new Junction("pair"+a+b,Junction.Kind.Y,j.tracks().get(a),j.tracks().get(b),j.center(),0,0,j.extent());
            data.put("pair"+a+b,tops(PointRenderer.worldForTest(PointRenderer.viewsForTest(List.of(pair),PointSettings.DEFAULT,p)),p));
        }
        Files.writeString(Path.of("build/frog-world-"+(args.length==0?"before":args[0])+".json"),AppearanceData.JSON.toJson(data));
    }
    private static Object tops(Mesh mesh,Profile p){
        return mesh.quads.stream().filter(q->Set.of("rail","frog","wing","blade","guard","rail_joint").contains(q.part()))
            .filter(q->List.of(q.a(),q.b(),q.c(),q.d()).stream().allMatch(v->Math.abs(v.y()-p.top())<1e-7))
            .map(q->Map.of("part",q.part(),"points",List.of(q.a(),q.b(),q.c(),q.d()))).toList();
    }
    public static void run(){run(Profile.STANDARD);}
    public static void run(Profile p){
        joints(p);
        Junction j=ThreeWayShapeRegression.common();PointSettings s=PointSettings.DEFAULT;
        Mesh world=PointRenderer.worldForTest(PointRenderer.viewsForTest(List.of(j),s,p));
        int samples=0;
        for(int a=0;a<3;a++)for(int b=a+1;b<3;b++){
            Junction pair=new Junction("tip"+a+b,Junction.Kind.Y,j.tracks().get(a),j.tracks().get(b),j.center(),0,0,j.extent());
            FrogGeometry frog=new FrogGeometry(pair,s,p,PointMesh.extent(pair,s));
            V3 forward=pair.a().tangent(frog.sa).add(pair.b().tangent(frog.sb)).unit();
            var head=frog.fixedHeart().quads.stream().flatMap(q->java.util.stream.Stream.of(q.a(),q.b(),q.c(),q.d()))
                .filter(v->Math.abs(v.y()-p.top())<1e-8).toList();
            V3 tip=head.stream().min(Comparator.comparingDouble(v->v.dot(forward))).orElseThrow();
            for(double d:new double[]{.005,.015,.04}){
                V3 point=tip.add(forward.mul(d));
                if(!ReviewFixRegression.at(world,point.x(),point.z(),point.y()))throw new AssertionError("Three-way V nose truncated compared with ordinary turnout: pair="+a+b+" point="+point);
                samples++;
            }
        }
        long bridges=world.quads.stream().filter(q->q.part().equals("rail_joint")).count();
        Mesh original=new Mesh();for(var q:world.quads)if(!q.part().equals("rail_joint"))original.quad(q);
        int closed=0;
        for(var q:world.quads)if(q.part().equals("rail_joint")){
            V3 ab=q.b().sub(q.a()),ac=q.c().sub(q.a());
            V3 cross=new V3(ab.y()*ac.z()-ab.z()*ac.y(),ab.z()*ac.x()-ab.x()*ac.z(),ab.x()*ac.y()-ab.y()*ac.x());
            double length=cross.length()/Math.max(q.a().distance(q.b()),Math.max(q.a().distance(q.c()),q.b().distance(q.c())));
            if(length>.0060001)throw new AssertionError("Rail joint filled a real rail gap");
            V3 point=q.center();
            if(Math.abs(point.y()-p.top())<1e-8&&!ReviewFixRegression.at(original,point.x(),point.z(),point.y()))closed++;
        }
        if(closed==0)throw new AssertionError("World joint check did not reproduce any open rail-head seam");
        System.out.println("FROG_WORLD: PASS "+p.source()+" all three V noses match ordinary turnout tips; samples="+samples+" joint faces="+bridges+" reproduced head gaps closed="+closed);
    }
    private static void joints(Profile p){
        V3 normal=new V3(1,0,0);PointSettings s=PointSettings.DEFAULT;
        for(double gap:new double[]{.002,.04}){
            Mesh steel=new Mesh();
            steel.rail(new V3(0,0,-1),V3.ZERO,normal,normal,1,1,p,s,"rail");
            steel.rail(new V3(0,0,gap),new V3(0,0,1),normal,normal,1,1,p,s,"wing");
            Mesh bridges=RailJoints.bridges(steel);
            if(gap<.006){
                if(bridges.quads.isEmpty()||!ReviewFixRegression.at(bridges,0,gap/2,p.top()))throw new AssertionError("Final rail-head hairline remains open");
            }else if(!bridges.quads.isEmpty())throw new AssertionError("Real flange clearance was filled");
        }
    }
}
