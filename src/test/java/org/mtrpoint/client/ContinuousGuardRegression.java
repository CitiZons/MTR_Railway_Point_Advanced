package org.mtrpoint.client;

import com.google.gson.*;
import org.mtrpoint.Regression;
import org.mtrpoint.geometry.*;
import java.nio.file.*;
import java.util.*;

public final class ContinuousGuardRegression {
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void run()throws Exception{
        ProfileModel model=descriptor();connections(model);bodyCuts(model);orientation();terminalDispatch();packEndpoints();
        System.out.println("CONTINUOUS_GUARDS: PASS descriptor, turnout fallback, endpoint directions, joins, mixed styles and short tracks");
    }
    private static ProfileModel descriptor()throws Exception{
        JsonObject d=JsonParser.parseString("""
            {"model":"test:guard.obj","modelGroups":{"rail":["rail"],"sleeper":["tie"],"fastener":["clip"],"preserve":["bed"],"supports":["tie","clip","guard_support"]},
             "continuousGuard":{"groups":["guard"],"supportGroups":["guard_support"],"endpointModel":"test:end.obj","endpointLength":1.8,
               "turnout":{"model":"test:ordinary.obj","modelGroups":{"rail":["rail"],"sleeper":["tie"],"fastener":["ordinary_clip"],"preserve":["bed"],"supports":["tie","ordinary_clip"]}}}}
            """).getAsJsonObject();
        Map<String,String> files=Map.of("test:guard.obj",obj("rail","tie","clip","bed","guard","guard_support"),"test:end.obj",endpoint(),"test:ordinary.obj",obj("rail","tie","ordinary_clip","bed"),"test:m.mtl","newmtl m\nmap_Kd test:t.png\n");
        ProfileModel m=ProfileModel.read(d,"test:guard.obj","test:t.png",false,files::get);
        require(m.continuousGuard()!=null&&m.continuousGuard().rails().size()==1&&m.continuousGuard().supports().size()==1,"Continuous roles not separated");
        require(m.fixed().stream().noneMatch(q->q.part().equals("guard")),"Repeating guard remained in fixed model");
        require(m.supports().stream().noneMatch(q->q.part().equals("guard_support")),"Guard hardware remained in ordinary supports");
        require(m.detail().fittings().stream().allMatch(q->q.part().equals("ordinary_clip")),"Turnout did not use ordinary full-detail template");
        require(Math.abs(m.continuousGuard().zMax()-1.8)<1e-9,"Endpoint physical length lost");
        JsonObject invalid=d.deepCopy();invalid.getAsJsonObject("continuousGuard").addProperty("endpointLength",1.7);
        try{ProfileModel.read(invalid,"test:guard.obj","test:t.png",false,files::get);throw new AssertionError("Mismatched endpoint range accepted");}catch(IllegalArgumentException expected){}
        return m;
    }
    private static void connections(ProfileModel model){
        Track a=Regression.line("a","west","node",new V3(0,0,0),new V3(0,0,1)),same=Regression.line("same","node","east",new V3(0,0,1),new V3(0,0,2)),other=Regression.line("other","node","north",new V3(0,0,1),new V3(1,0,1));
        var joined=ContinuousGuards.exposed(a,"style",List.of(a,same));require(joined.start()&&!joined.end(),"Same-style continuation did not join");
        var mixed=ContinuousGuards.exposed(a,"style",List.of(a));require(mixed.start()&&mixed.end(),"Mixed style incorrectly joined");
        require(ContinuousGuards.exposed(same,"style",List.of(a,same)).start()==false,"Reverse endpoint orientation did not join");
        require(ContinuousGuards.exposed(other,"other",List.of(other)).start()&&ContinuousGuards.exposed(other,"other",List.of(other)).end(),"Independent style endpoint suppressed");
        Track shortTrack=Regression.line("short","s","e",new V3(0,0,0),new V3(0,0,1));var both=new ContinuousGuards.Ends(true,true);
        require(Math.abs(ContinuousGuards.reach(shortTrack,1.8,both,true)-.5)<1e-9&&Math.abs(ContinuousGuards.reach(shortTrack,1.8,both,false)-.5)<1e-9,"Short terminals were not compressed to meet at midpoint");
        RailSweep first=new RailSweep(new V3(0,0,0),new V3(0,0,.6),new V3(0,0,1),new V3(0,0,1),0,0);
        require(ContinuousGuards.body(model.continuousGuard().rails(),model,shortTrack,first,both,false).isEmpty(),"Short-track straight guard overlaps terminal");
    }
    private static void orientation(){
        var surface=Profile.STEEL;List<Mesh.Quad> slab=List.of(new Mesh.Quad(new V3(-1,0,-.3),new V3(1,0,-.3),new V3(1,0,.3),new V3(-1,0,.3),surface,"test",-1));
        List<Mesh.Quad> cut=ContinuousGuards.clip(slab,-.1,.2);require(!cut.isEmpty(),"Clipped geometry vanished");double[] z=range(cut);require(Math.abs(z[0]+.1)<1e-9&&Math.abs(z[1]-.2)<1e-9,"Clip retained wrong z range: "+Arrays.toString(z));
        V3 tip=new V3(0,2,0),inside=new V3(0,2.2,1.8);RailSweep forward=new RailSweep(tip,inside,new V3(0,.1,1).unit(),new V3(0,.1,1).unit(),.1,.2);
        RailSweep reverse=new RailSweep(inside,tip,new V3(0,-.1,-1).unit(),new V3(0,-.1,-1).unit(),-.2,-.1);
        require(forward.model(new V3(.39,0,0),false,0,0,1.8).distance(tip)>0,"Forward endpoint tip was not mapped");
        require(reverse.model(new V3(.39,0,1.8),true,0,0,1.8).distance(tip)>.1,"Reverse endpoint collapsed");
        for(RailSweep sweep:List.of(forward,reverse))for(double station:new double[]{0,.55,1.8}){
            V3 p=sweep.model(new V3(.07,.12,station),false,0,0,1.8);require(Double.isFinite(p.x()+p.y()+p.z()),"Banked endpoint produced nonfinite geometry");}
    }
    private static void bodyCuts(ProfileModel model){
        Track track=Regression.line("body","s","e",new V3(0,0,0),new V3(0,0,4));var ends=new ContinuousGuards.Ends(true,false);
        RailSweep forward=new RailSweep(new V3(0,0,1.6),new V3(0,0,2.2),new V3(0,0,1),new V3(0,0,1),0,0);
        double[] normal=range(ContinuousGuards.body(model.continuousGuard().rails(),model,track,forward,ends,false));
        double[] flipped=range(ContinuousGuards.body(model.continuousGuard().rails(),model,track,forward,ends,true));
        require(Math.abs(normal[0]+.1)<1e-8&&Math.abs(normal[1]-.3)<1e-8,"Forward body cut wrong: "+Arrays.toString(normal));
        require(Math.abs(flipped[0]+.3)<1e-8&&Math.abs(flipped[1]-.1)<1e-8,"Flipped body cut wrong: "+Arrays.toString(flipped));
        RailSweep reverse=new RailSweep(forward.b(),forward.a(),new V3(0,0,-1),new V3(0,0,-1),0,0);
        double[] reversed=range(ContinuousGuards.body(model.continuousGuard().rails(),model,track,reverse,ends,false));
        require(Math.abs(reversed[0]+.3)<1e-8&&Math.abs(reversed[1]-.1)<1e-8,"Reverse cell covers a different world interval: "+Arrays.toString(reversed));
    }
    private static void terminalDispatch(){
        ContinuousGuards.begin();
        require(!ContinuousGuards.claimTerminal("rail/start",2.2,1.8,.6),"Distant cell claimed terminal");
        require(ContinuousGuards.claimTerminal("rail/start",.47,1.8,.6),"Endpoint depends on a cell centre inside half a repeat interval");
        require(!ContinuousGuards.claimTerminal("rail/start",.1,1.8,.6),"Endpoint submitted twice in one frame");
        require(ContinuousGuards.claimTerminal("rail/end",.47,1.8,.6),"Opposite endpoint was suppressed");
        ContinuousGuards.begin();
        require(ContinuousGuards.claimTerminal("rail/start",.47,1.8,.6),"Endpoint remained suppressed in the next frame");
    }
    private static double[] range(List<Mesh.Quad> faces){double lo=Double.POSITIVE_INFINITY,hi=Double.NEGATIVE_INFINITY;for(var q:faces)for(V3 v:List.of(q.a(),q.b(),q.c(),q.d())){lo=Math.min(lo,v.z());hi=Math.max(hi,v.z());}return new double[]{lo,hi};}
    private static void packEndpoints()throws Exception{
        Path assets=Path.of("../MTR_Citizons_Railway/resourcepacks/Citizons_Railway/assets");if(!Files.isDirectory(assets))return;
        for(String style:List.of("outer","center")){
            JsonObject d=JsonParser.parseString(Files.readString(assets.resolve("citizons_railway/rail_profiles/citizons_"+style+"_guard_1435.json"))).getAsJsonObject();
            ObjTemplate.Reader reader=id->{String[] p=id.split(":",2);return Files.readString(assets.resolve(p[0]).resolve(p[1]));};
            ProfileModel model=ProfileModel.read(d,d.get("model").getAsString(),"",true,reader);Mesh endpoint=new Mesh();endpoint.quads.addAll(model.continuousGuard().endpoint());
            for(String side:List.of("left","right")){
                var steel=endpoint.quads.stream().filter(q->q.part().equals("terminal_"+side)).toList();
                require(!steel.isEmpty()&&!ContinuousGuards.clip(steel,style.equals("center")?.55:0,lengthFor(style)).isEmpty(),style+" terminal "+side+" steel missing after clipping");
            }
            double top=endpoint.quads.stream().flatMap(q->java.util.stream.Stream.of(q.a(),q.b(),q.c(),q.d())).mapToDouble(V3::y).max().orElseThrow();
            require(top<=d.get("top").getAsDouble()+1e-8,style+" endpoint rises above running rail top");
            Regression.export(endpoint,"continuous-"+style+"-endpoint");
        }
    }
    private static double lengthFor(String style){return style.equals("center")?1.8:.4;}
    private static String endpoint(){return "mtllib test:m.mtl\ng endpoint\nusemtl m\nv -.1 0 0\nv .1 0 0\nv .1 .1 1.8\nv -.1 .1 1.8\nvt 0 0\nvt 1 0\nvt 1 1\nvt 0 1\nf 1/1 2/2 3/3 4/4\n";}
    private static String obj(String... groups){
        StringBuilder s=new StringBuilder("mtllib test:m.mtl\n");int v=1;
        for(String g:groups){s.append("g ").append(g).append("\nusemtl m\n").append("v -.1 0 -.3\nv .1 0 -.3\nv .1 .1 .3\nv -.1 .1 .3\nvt 0 0\nvt 1 0\nvt 1 1\nvt 0 1\nf ").append(v).append('/').append(v).append(' ').append(v+1).append('/').append(v+1).append(' ').append(v+2).append('/').append(v+2).append(' ').append(v+3).append('/').append(v+3).append('\n');v+=4;}
        return s.toString();
    }
}
