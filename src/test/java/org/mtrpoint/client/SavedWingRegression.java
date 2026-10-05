package org.mtrpoint.client;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.mtrpoint.AppearanceData;
import org.mtrpoint.geometry.*;

/** The nine dense-layout roads and saved edits behind the red/blue arrow report. */
public final class SavedWingRegression {
    private record Road(String id,String startNode,String endNode,List<V3> points){}
    private record Fixture(List<Road> roads,Map<String,AppearanceData.Entry> appearances){}
    private static long cell(double x,double z){return ((long)(int)Math.floor(x)<<32)^((int)Math.floor(z)&0xffffffffL);}
    private static boolean covered(Map<Long,Mesh> bins,V3 q){return ReviewFixRegression.at(bins.getOrDefault(cell(q.x(),q.z()),new Mesh()),q.x(),q.z(),q.y());}
    public static void run(){
        Fixture fixture;
        try(var stream=SavedWingRegression.class.getResourceAsStream("/screenshot-layout.json")){
            if(stream==null)throw new AssertionError("Missing screenshot layout fixture");
            fixture=AppearanceData.JSON.fromJson(new InputStreamReader(stream,StandardCharsets.UTF_8),Fixture.class);
        }catch(IOException ex){throw new AssertionError(ex);}
        var tracks=fixture.roads.stream().map(r->new Track(r.id,r.startNode,r.endNode,r.points)).toList();
        var leftCurve=new ArrayList<V3>();var rightCurve=new ArrayList<V3>();
        for(int z=0;z<=40;z++){leftCurve.add(new V3(-.002*z*z,0,z));rightCurve.add(new V3(.005*z*z,0,z));}
        for(var junction:Detector.find(List.of(new Track("knee-left","origin","left",leftCurve),new Track("knee-right","origin","right",rightCurve))))if(junction.kind()==Junction.Kind.Y){
            var isolated=PointRenderer.viewsForTest(List.of(junction),PointSettings.DEFAULT,Profile.STANDARD);
            Mesh source=isolated.get(0).mesh(),finalMesh=PointRenderer.worldForTest(isolated);
            var outgoing=new ArrayList<V3>();var incoming=new ArrayList<V3>();
            for(var q:source.quads)if(q.part().equals("wing"))for(var v:List.of(q.a(),q.b(),q.c(),q.d()))
                if(Math.abs(v.y()-Profile.STANDARD.top())<1e-8)(q.index()==-2?outgoing:incoming).add(v);
            int seam=0;
            var frog=new FrogGeometry(junction,PointSettings.DEFAULT,Profile.STANDARD,PointMesh.extent(junction,PointSettings.DEFAULT));
            var knees=List.of(frog.checkWing(0),frog.checkWing(1)).stream().map(r->r.center(r.start()).add(0,Profile.STANDARD.top(),0)).toList();
            for(var v:incoming)if(outgoing.stream().anyMatch(w->w.distance(v)<1e-8)){
                seam++;
                V3 center=knees.stream().min(Comparator.comparingDouble(v::distance)).orElseThrow();
                V3 edge=center.lerp(v,.95),forward=v.sub(center).lateral();
                for(double advance:new double[]{-.0001,0,.0001}){
                    V3 sample=edge.add(forward.mul(advance));
                    if(!ReviewFixRegression.at(finalMesh,sample.x(),sample.z(),sample.y()))
                        throw new AssertionError("Final wing assembly opens its head edge at the knee: "+sample);
                }
            }
            if(seam<4)throw new AssertionError("Missing full-width knee section fixture");
            break;
        }
        var p=Profile.STANDARD;var views=PointRenderer.viewsForTest(Detector.find(tracks),PointSettings.DEFAULT,p);
        for(var view:views)if(fixture.appearances.containsKey(view.junction.id()))view.settings=fixture.appearances.get(view.junction.id()).value();
        PointClient.refreshCrossings(views,tracks);Mesh world=PointRenderer.worldForTest(views);
        var bins=new HashMap<Long,Mesh>();
        for(var q:world.quads){
            if(q.part().equals("sleeper")||q.part().equals("fastener"))continue;
            var vertices=List.of(q.a(),q.b(),q.c(),q.d());
            int x0=(int)Math.floor(vertices.stream().mapToDouble(V3::x).min().orElse(0)),x1=(int)Math.floor(vertices.stream().mapToDouble(V3::x).max().orElse(0));
            int z0=(int)Math.floor(vertices.stream().mapToDouble(V3::z).min().orElse(0)),z1=(int)Math.floor(vertices.stream().mapToDouble(V3::z).max().orElse(0));
            for(int x=x0;x<=x1;x++)for(int z=z0;z<=z1;z++)bins.computeIfAbsent(cell(x,z),k->new Mesh()).quad(q);
        }
        int joints=0,channels=0;
        for(var view:views)if(view.junction.kind()!=Junction.Kind.DIAMOND){
            var roads=view.junction.tracks();var s=view.settings;
            double channel=p.centerOffset()-p.headWidth()/2-Math.max(.02,s.flangeway()+s.wingGapDelta())/2;
            for(int a=0;a<roads.size();a++)for(int b=a+1;b<roads.size();b++){
                var pair=new Junction(view.junction.id(),Junction.Kind.Y,roads.get(a),roads.get(b),view.junction.center(),0,0,view.junction.extent());
                var frog=new FrogGeometry(pair,s,p,PointMesh.extent(pair,s));double side=TurnoutFrame.side(pair,PointMesh.extent(pair,s));
                for(int branch=0;branch<2;branch++){
                    var road=branch==0?pair.a():pair.b();
                    for(int sign:new int[]{-1,1})for(double d=frog.toe(branch)+.1;d<frog.heel(branch)-.1;d+=.04){
                        V3 q=road.at(d).add(road.tangent(d).lateral().mul(sign*channel)).add(0,p.top(),0);channels++;
                        if(covered(bins,q))throw new AssertionError("Saved frog channel blocked at "+q+" node="+pair.center());
                    }
                    var wing=frog.checkWing(branch);double knee=frog.knee(branch);
                    for(int k=0;k<=15;k++)for(boolean before:new boolean[]{true,false}){
                        double d=before?knee-k*.02:wing.start()+k*.02;
                        V3 q=before?road.at(d).add(road.tangent(d).lateral().mul((branch==0?side:-side)*p.centerOffset())):wing.center(d);
                        q=q.add(0,p.top(),0);V3 normal=(before?road:wing.road()).tangent(d).lateral();joints++;
                        // Chorded native sections may shift the top within eight millimetres;
                        // an actual detached wing has a missing interval across its full width.
                        if(!covered(bins,q)&&!covered(bins,q.add(normal.mul(.008)))&&!covered(bins,q.sub(normal.mul(.008))))
                            throw new AssertionError("Saved closure rail detached from wing at "+q+" node="+pair.center());
                    }
                }
            }
        }
        if(joints<500||channels<2000)throw new AssertionError("Saved layout test lost its frog coverage");
        System.out.println("PASS: saved arrow layout keeps "+joints+" closure-to-wing samples continuous and "+channels+" frog channel samples clear, including legacy manual merges");
    }
}
