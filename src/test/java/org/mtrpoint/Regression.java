package org.mtrpoint;

import org.mtrpoint.geometry.*;
import java.util.*;
import java.nio.file.*;

public final class Regression {
    public static Track line(String id,String start,String end,V3 a,V3 b){var p=new ArrayList<V3>();for(int i=0;i<=100;i++)p.add(a.lerp(b,i/100D));return new Track(id,start,end,p);}
    public static List<Track> y(){var a=new ArrayList<V3>();var b=new ArrayList<V3>();for(int i=0;i<=120;i++){double z=i*.25,x=.008*z*z;a.add(new V3(-x,0,z));b.add(new V3(x,0,z));}return List.of(new Track("a","0,0,0","-7,0,30",a),new Track("b","0,0,0","7,0,30",b),line("in","0,0,-20","0,0,0",new V3(0,0,-20),new V3(0,0,0)));}
    private static void require(boolean pass,String why){if(!pass)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception{
        List<Track> rails=y();var points=Detector.find(rails);require(points.size()==1&&points.get(0).kind()==Junction.Kind.Y,"Y classification");
        Junction y=points.get(0);Mesh left=PointMesh.build(y,PointSettings.DEFAULT,Profile.STANDARD,0),right=PointMesh.build(y,PointSettings.DEFAULT,Profile.STANDARD,1);
        require(!left.quads.equals(right.quads),"Blade movement changes geometry");
        var moving=PointSettings.DEFAULT.flags(true,true);
        var frog0=PointMesh.build(y,moving,Profile.STANDARD,0).quads.stream().filter(q->q.part().equals("frog")).toList();
        var frog1=PointMesh.build(y,moving,Profile.STANDARD,1).quads.stream().filter(q->q.part().equals("frog")).toList();
        var frogHalf=PointMesh.build(y,moving,Profile.STANDARD,.5).quads.stream().filter(q->q.part().equals("frog")).toList();
        require(!frog0.equals(frog1)&&!frogHalf.equals(frog0)&&!frogHalf.equals(frog1),"Movable frog has an intermediate position");
        var top=left.quads.stream().filter(q->q.part().equals("sleeper")).findFirst().orElseThrow();
        V3 edge=top.b().sub(top.a()),diagonal=top.c().sub(top.a());
        require(edge.z()*diagonal.x()-edge.x()*diagonal.z()>0,"Sleeper upper face points upward for correct lighting");
        require(left.quads.stream().filter(q->q.part().equals("sleeper")).toList().equals(right.quads.stream().filter(q->q.part().equals("sleeper")).toList()),"Sleepers must not move with blades");
        var ids=left.quads.stream().filter(q->q.part().equals("sleeper")).map(Mesh.Quad::index).distinct().toList();require(ids.size()>10&&ids.size()<100,"One bearer family");
        Mesh edited=PointMesh.build(y,PointSettings.DEFAULT.sleeper(3,.15),Profile.STANDARD,0);
        require(!edited.quads.stream().filter(q->q.index()==3).toList().equals(left.quads.stream().filter(q->q.index()==3).toList()),"Individual tie adjustment");
        require(edited.quads.stream().filter(q->q.index()==4).toList().equals(left.quads.stream().filter(q->q.index()==4).toList()),"Other ties stable");
        Track crossA=line("c","-20,0,0","20,0,0",new V3(-20,0,0),new V3(20,0,0)),crossB=line("d","0,0,-20","0,0,20",new V3(0,0,-20),new V3(0,0,20));
        var crossings=Detector.find(List.of(crossA,crossB));require(crossings.size()==1&&crossings.get(0).kind()==Junction.Kind.DIAMOND,"Disconnected diamond");
        Track elevated=line("e","0,3,-20","0,3,20",new V3(0,3,-20),new V3(0,3,20));require(Detector.find(List.of(crossA,elevated)).isEmpty(),"Overpass is not a diamond");
        Track connected=line("f",crossA.startNode,"0,0,20",new V3(0,0,-20),new V3(0,0,20));require(Detector.find(List.of(crossA,connected)).stream().noneMatch(j->j.kind()==Junction.Kind.DIAMOND),"Connected tracks excluded");
        Mesh diamond=PointMesh.build(crossings.get(0),PointSettings.DEFAULT,Profile.STANDARD,0);require(diamond.quads.stream().noneMatch(q->q.part().equals("blade")),"Diamond has no switching blades");
        for(Mesh mesh:List.of(left,right,diamond))for(var q:mesh.quads)for(V3 v:List.of(q.a(),q.b(),q.c(),q.d()))require(Double.isFinite(v.x()+v.y()+v.z()),"Finite mesh");
        try{PointSettings.DEFAULT.with(2,Double.NaN);throw new AssertionError("NaN accepted");}catch(IllegalArgumentException expected){}
        try{PointSettings.DEFAULT.with(8,-1);throw new AssertionError("Negative gauge accepted");}catch(IllegalArgumentException expected){}
        require(rails.get(0).points.equals(y.a().points),"Source geometry unchanged");
        Files.createDirectories(Path.of("build/previews"));export(left,"y-left");export(right,"y-right");export(diamond,"diamond");
        System.out.println("PASS: Y/diamond detection, overpass exclusion, animation, bearer edits, immutable inputs and parameter validation");
    }
    public static void export(Mesh mesh,String name)throws Exception{
        var out=new StringBuilder("# MTR Point procedural mesh / metres\n");int index=1;String last="";
        for(var q:mesh.quads){if(!last.equals(q.part())){last=q.part();out.append("g ").append(last).append('\n');}for(var v:List.of(q.a(),q.b(),q.c(),q.d()))out.append(String.format(Locale.ROOT,"v %.6f %.6f %.6f%n",v.x(),v.y(),v.z()));out.append("f ").append(index).append(' ').append(index+1).append(' ').append(index+2).append(' ').append(index+3).append('\n');index+=4;}
        Files.writeString(Path.of("build/previews/"+name+".obj"),out);
    }
}
