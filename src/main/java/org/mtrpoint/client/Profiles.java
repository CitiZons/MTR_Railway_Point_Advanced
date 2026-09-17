package org.mtrpoint.client;

import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.mtr.mod.client.CustomResourceLoader;
import org.mtr.mod.resource.RailResource;
import org.mtrpoint.geometry.*;
import org.mtrpoint.mixin.RailResourceAccess;
import java.io.*;
import java.util.*;

/** Uses the currently enabled pack stack, including arbitrary namespaces and MTR legacy rail styles. */
public final class Profiles {
    public record Adapted(Profile profile,boolean track,String note) {}
    private static final Map<String,Adapted> CACHE=new HashMap<>();
    private static final Map<String,List<Mesh.Quad>> ATTACHMENTS=new HashMap<>();
    public static List<Mesh.Quad> attachments(String id){get(id);return ATTACHMENTS.getOrDefault(id,List.of());}
    public static void clear(){CACHE.clear();ATTACHMENTS.clear();}
    public static Adapted get(String id){return CACHE.computeIfAbsent(id,Profiles::load);}
    public static Profile forced(String style){Adapted a=get(style);return a.profile!=null?a.profile:new Profile(1.435,.26428,.068,.14,.165,Profile.STEEL,Profile.TIMBER,style,false);}
    private static Adapted load(String id){
        if(id.equals("default"))return new Adapted(Profile.STANDARD,true,"mtrpoint.profile_native");
        RailResource[] resource={null};CustomResourceLoader.getRailById(RailResource.getIdWithoutDirection(id),r->resource[0]=r);
        if(resource[0]==null)return new Adapted(null,false,"mtrpoint.profile_missing");
        RailResource r=resource[0];RailResourceAccess access=(RailResourceAccess)(Object)r;String model=access.point$model();
        try{
            // Explicit profile descriptors can refine/override inference without depending on a particular pack.
            for(var entry:Minecraft.getInstance().getResourceManager().listResources("rail_profiles",p->p.getPath().endsWith(".json")).entrySet()){
                try(var reader=entry.getValue().openAsReader()){
                    JsonObject o=JsonParser.parseReader(reader).getAsJsonObject();if(!o.has("style")||!o.get("style").getAsString().equals(id))continue;
                    if(o.has("track")&&!o.get("track").getAsBoolean())return new Adapted(null,false,"mtrpoint.profile_attachment");
                    var steel=o.has("steelTexture")?new Profile.Surface(o.get("steelTexture").getAsString(),0,0,1,1,-1):Profile.STEEL;
                    var sleeper=o.has("sleeperTexture")?new Profile.Surface(o.get("sleeperTexture").getAsString(),0,0,1,1,-1):Profile.TIMBER;
                    return new Adapted(new Profile(number(o,"gauge",1.435),number(o,"top",.26428),number(o,"headWidth",.068),number(o,"footWidth",.14),number(o,"railHeight",.165),steel,sleeper,id,true),true,"mtrpoint.profile_descriptor");
                }
            }
            String content=read(model);var vertices=new ArrayList<V3>();var uv=new ArrayList<float[]>();var faces=new ArrayList<int[]>();
            var materialFaces=new ArrayList<String>();Map<String,String> textures=new HashMap<>();String material="";
            String texture=access.point$texture();if(texture==null||texture.isBlank())texture=model.substring(0,model.lastIndexOf('.'))+".png";
            if(model.endsWith(".obj"))for(String line:content.split("\\R")){
                String[] t=line.trim().split("\\s+");if(t.length<2)continue;
                switch(t[0]){
                    case "v" -> vertices.add(new V3(Double.parseDouble(t[1]),Double.parseDouble(t[2]),Double.parseDouble(t[3])));
                    case "vt" -> uv.add(new float[]{Float.parseFloat(t[1]),Float.parseFloat(t[2])});
                    case "usemtl" -> material=t[1];
                    case "mtllib" -> {String mtl=read(relative(model,t[1]));String current="";for(String ml:mtl.split("\\R")){String[] mt=ml.trim().split("\\s+",2);if(mt.length<2)continue;if(mt[0].equals("newmtl"))current=mt[1];else if(mt[0].equals("map_Kd"))textures.put(current,relative(model,mt[1]));}}
                    case "f" -> {int[] face=new int[(t.length-1)*2];for(int i=1;i<t.length;i++){String[] idx=t[i].split("/");int vi=Integer.parseInt(idx[0]);face[(i-1)*2]=vi<0?vertices.size()+vi:vi-1;face[(i-1)*2+1]=idx.length>1&&!idx[1].isEmpty()?Integer.parseInt(idx[1])-1:-1;}faces.add(face);materialFaces.add(material);}
                }
            }
            else if(model.endsWith(".bbmodel")){
                JsonObject root=JsonParser.parseString(content).getAsJsonObject();
                for(JsonElement el:root.getAsJsonArray("elements")){
                    JsonObject o=el.getAsJsonObject();if(!o.has("from")||!o.has("to"))continue;JsonArray a=o.getAsJsonArray("from"),b=o.getAsJsonArray("to");
                    for(int x=0;x<2;x++)for(int y=0;y<2;y++)for(int z=0;z<2;z++)vertices.add(new V3((x==0?a:b).get(0).getAsDouble()/16,-(y==0?a:b).get(1).getAsDouble()/16,(z==0?a:b).get(2).getAsDouble()/16));
                }
            }
            if(vertices.isEmpty())return new Adapted(null,false,"mtrpoint.profile_manual");
            double top=vertices.stream().mapToDouble(V3::y).max().orElse(0),min=vertices.stream().mapToDouble(V3::y).min().orElse(0);
            List<V3> heads=vertices.stream().filter(v->v.y()>top-.015).toList();
            double left=heads.stream().filter(v->v.x()<-.15).mapToDouble(V3::x).average().orElse(Double.NaN),right=heads.stream().filter(v->v.x()>.15).mapToDouble(V3::x).average().orElse(Double.NaN);
            double width=heads.stream().filter(v->v.x()>0).mapToDouble(V3::x).max().orElse(0)-heads.stream().filter(v->v.x()>0).mapToDouble(V3::x).min().orElse(0);
            boolean track=Double.isFinite(left)&&Double.isFinite(right)&&right-left>.4&&right-left<4&&width>.01&&width<.4&&top<1;
            if(!track)return new Adapted(null,false,"mtrpoint.profile_manual");
            Profile.Surface steel=Profile.STEEL,sleeper=Profile.TIMBER;
            double headTop=top,bestSleeperY=-Double.MAX_VALUE;
            var preserved=new Mesh();
            boolean nativeModel=(id.equals("default_3d")||id.equals("default_3d_siding"))&&model.endsWith(".obj");
            boolean siding=id.equals("default_3d_siding");
            var railFaces=new ArrayList<Mesh.Quad>();var bearerFaces=new ArrayList<Mesh.Quad>();var fittings=new ArrayList<Mesh.Quad>();
            double zMin=vertices.stream().mapToDouble(V3::z).min().orElse(-.3),zMax=vertices.stream().mapToDouble(V3::z).max().orElse(.3);
            double halfBearer=vertices.stream().mapToDouble(v->Math.abs(v.x())).max().orElse(1.116023);
            for(int f=0;f<faces.size();f++){
                int[] face=faces.get(f);double y=0,xmin=Double.MAX_VALUE,xmax=-Double.MAX_VALUE;float umin=1,vmin=1,umax=0,vmax=0;boolean hasUv=true;
                for(int i=0;i<face.length;i+=2){V3 v=vertices.get(face[i]);y+=v.y()/(face.length/2);xmin=Math.min(xmin,v.x());xmax=Math.max(xmax,v.x());int ti=face[i+1];if(ti<0||ti>=uv.size()){hasUv=false;continue;}float[] tex=uv.get(ti);umin=Math.min(umin,tex[0]);umax=Math.max(umax,tex[0]);vmin=Math.min(vmin,tex[1]);vmax=Math.max(vmax,tex[1]);}
                String tex=textures.getOrDefault(materialFaces.get(f),texture);if(!exists(tex)||!hasUv)continue;
                if(access.point$flipV()){float vv=vmin;vmin=1-vmax;vmax=1-vv;}
                var surface=new Profile.Surface(tex,umin,vmin,umax,vmax,-1);
                // Keep ballast/bed geometry below the sleepers and accessories wholly outside
                // the track envelope. Only the actual rail-and-bearer envelope is replaced.
                double highest=-Double.MAX_VALUE,lowest=Double.MAX_VALUE;for(int k=0;k<face.length;k+=2){double yy=vertices.get(face[k]).y();highest=Math.max(highest,yy);lowest=Math.min(lowest,yy);}
                if(y>headTop-.02&&highest-lowest<.005)steel=surface;
                if(xmax-xmin>right-left&&y<headTop-.05&&y>-.03&&highest-lowest<.005&&y>bestSleeperY){sleeper=surface;bestSleeperY=y;}
                if(nativeModel){
                    double fzMin=Double.MAX_VALUE,fzMax=-Double.MAX_VALUE;
                    for(int k=0;k<face.length;k+=2){double z=vertices.get(face[k]).z();fzMin=Math.min(fzMin,z);fzMax=Math.max(fzMax,z);}
                    List<Mesh.Quad> target=null;
                    if(xmin>.3&&fzMax-fzMin>(zMax-zMin)*.98&&lowest>.09)target=railFaces;
                    else if(!siding&&highest<.098)target=bearerFaces;
                    else if(xmin>.3&&fzMax-fzMin<(zMax-zMin)*.9)target=fittings;
                    if(target!=null){
                        int[] order=face.length==8?new int[]{0,2,4,6}:null;
                        if(order!=null)target.add(template(face,order,vertices,uv,surface,access.point$flipV()));
                        else for(int k=2;k+2<face.length;k+=2)target.add(template(face,new int[]{0,k,k+2,k+2},vertices,uv,surface,access.point$flipV()));
                    }
                    continue;
                }
                if(highest<-.005||lowest>top+.04||xmin>right+.65||xmax<left-.65){
                    V3 a=vertices.get(face[0]);for(int k=2;k+2<face.length;k+=2){V3 b=vertices.get(face[k]),c=vertices.get(face[k+2]);preserved.quad(a,b,c,c,surface,"attachment",-1);}
                }
            }
            ATTACHMENTS.put(id,List.copyOf(preserved.quads));
            double base=vertices.stream().filter(v->v.y()>=0&&Math.abs(Math.abs(v.x())-right)<.12).mapToDouble(V3::y).min().orElse(top-.165);
            if(id.equals("default_3d_siding")&&sleeper==Profile.TIMBER)sleeper=new Profile.Surface("mtr_railway_point_advanced:textures/concrete.png",0,0,1,1,-1);
            return new Adapted(new Profile(right-left-width,top+r.getModelYOffset(),width,Math.max(.12,width*2),Math.min(.25,Math.max(.08,top-base)),steel,sleeper,id,true,nativeModel?new ModelDetail(railFaces,bearerFaces,fittings,right,top,width,zMin,zMax,halfBearer,siding?.099422:.097111,siding):null),true,"mtrpoint.profile_inferred");
        }catch(Exception ex){return new Adapted(null,false,"mtrpoint.profile_manual");}
    }
    private static Mesh.Quad template(int[] face,int[] order,List<V3> vertices,List<float[]> uv,Profile.Surface surface,boolean flip){
        var tex=new ArrayList<Float>();var points=new ArrayList<V3>();
        for(int i:order){points.add(vertices.get(face[i]));float[] t=uv.get(face[i+1]);tex.add(t[0]);tex.add(flip?1-t[1]:t[1]);}
        return new Mesh.Quad(points.get(0),points.get(1),points.get(2),points.get(3),surface,"template",-1,List.copyOf(tex));
    }
    private static double number(JsonObject o,String key,double fallback){return o.has(key)?o.get(key).getAsDouble():fallback;}
    private static boolean exists(String id){try{return Minecraft.getInstance().getResourceManager().getResource(new ResourceLocation(id)).isPresent();}catch(Exception ex){return false;}}
    private static String read(String id)throws IOException{try(var r=Minecraft.getInstance().getResourceManager().getResourceOrThrow(new ResourceLocation(id)).openAsReader()){return r.lines().collect(java.util.stream.Collectors.joining("\n"));}}
    private static String relative(String model,String path){if(path.contains(":"))return path;return model.substring(0,model.lastIndexOf('/')+1)+path.replace('\\','/');}
}
