package org.mtrpoint.client;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import org.mtr.core.serializer.JsonReader;
import org.mtr.mod.resource.RailResource;
import org.mtr.mod.render.StoredMatrixTransformations;
import org.mtrpoint.geometry.*;
import org.mtrpoint.mixin.RailResourceAccess;
import java.util.*;

/** Real distance-selected native OBJ resources. Models are loaded once and reuse MTR's GPU batches. */
public final class RailLod {
    private static double nearDistance=-1,farDistance=-1;
    private static RailDetailDistances distances;
    private record Levels(ProfileModel[] models,RailResource[] resources) {}
    private record ChunkKey(String id,int x,int y,int z) {}
    private static final Map<String,Levels> LEVELS=new HashMap<>();
    private static final Map<ChunkKey,Integer> CHUNK_LEVELS=new HashMap<>();
    private static final int MAX_CHUNK_LEVELS=8192;
    public static final long[] draws=new long[3];
    public static void clear(){LEVELS.clear();CHUNK_LEVELS.clear();Arrays.fill(draws,0);}
    public static ProfileModel register(String id,RailResource source,ProfileModel high,JsonObject descriptor,ObjTemplate.Reader reader)throws Exception{
        if(!descriptor.has("lod")||!descriptor.getAsJsonObject("lod").has("mid")||!descriptor.getAsJsonObject("lod").has("far"))return high;
        var access=(RailResourceAccess)(Object)source;ProfileModel[] models={high,null,null};RailResource[] resources=new RailResource[3];
        Set<String> supportNames=new HashSet<>();descriptor.getAsJsonObject("modelGroups").getAsJsonArray("supports").forEach(v->supportNames.add(v.getAsString()));
        for(int level=0;level<3;level++){
            String model=level==0?access.point$model():descriptor.getAsJsonObject("lod").getAsJsonObject(level==1?"mid":"far").get("model").getAsString();
            JsonObject variant=descriptor.deepCopy();variant.addProperty("model",model);
            if(level>0&&variant.has("continuousGuard")){
                JsonObject turnout=variant.getAsJsonObject("continuousGuard").getAsJsonObject("turnout");
                if(turnout!=null&&turnout.has("lod")){
                    JsonObject turnoutLevel=turnout.getAsJsonObject("lod").getAsJsonObject(level==1?"mid":"far");
                    if(turnoutLevel!=null){turnout.addProperty("model",turnoutLevel.get("model").getAsString());if(turnoutLevel.has("modelGroups"))turnout.add("modelGroups",turnoutLevel.getAsJsonObject("modelGroups").deepCopy());}
                }
            }
            if(level>0)models[level]=ProfileModel.read(variant,model,access.point$texture(),access.point$flipV(),reader);
            var d=models[level].detail();var base=high.detail();
            if(Math.abs(d.railCenter()-base.railCenter())>1e-5||Math.abs(d.railTop()-base.railTop())>1e-5||Math.abs(d.zMax()-base.zMax())>1e-5||Math.abs(d.zMin()-base.zMin())>1e-5)throw new IllegalArgumentException("LOD dimensions differ from ordinary rail");
            JsonObject data=new JsonObject();data.addProperty("id",id+"_point_lod"+level);data.addProperty("name",source.getName());data.addProperty("modelResource",model);
            data.addProperty("textureResource",access.point$texture());data.addProperty("flipTextureV",access.point$flipV());data.addProperty("repeatInterval",source.getRepeatInterval());data.addProperty("modelYOffset",source.getModelYOffset());
            String supportObj=supportObj(reader.read(model),supportNames);
            resources[level]=new RailResource(JsonReader.parse(data.toString()),key->{try{return key.data.toString().equals(model)?supportObj:reader.read(key.data.toString());}catch(Exception ex){throw new IllegalArgumentException(ex);}});
        }
        high=new ProfileModel(high.detail().withFittingLods(List.of(models[1].detail().fittings(),models[2].detail().fittings())),high.attachments(),high.fixed(),high.supports(),high.alignSleepers(),high.continuousGuard());models[0]=high;
        LEVELS.put(id,new Levels(models,resources));return high;
    }
    public static RailDetailDistances distances(){
        double near=org.mtrpoint.PointConfig.lodNear(),far=org.mtrpoint.PointConfig.lodFar();
        if(near!=nearDistance||far!=farDistance){nearDistance=near;farDistance=far;distances=RailDetailDistances.metres(near,far);CHUNK_LEVELS.clear();}
        return distances;
    }
    public static int level(double distanceSquared){return distances().level(distanceSquared);}
    public static int level(V3 position){var p=Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();double x=p.x-position.x(),y=p.y-position.y(),z=p.z-position.z();return level(x*x+y*y+z*z);}
    private static int chunkLevel(String id,V3 position){
        var p=Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        int x=(int)Math.floor(position.x()/8),y=(int)Math.floor(position.y()/8),z=(int)Math.floor(position.z()/8);
        var key=new ChunkKey(id,x,y,z);double cx=x*8+4,cy=y*8+4,cz=z*8+4;
        double dx=p.x-cx,dy=p.y-cy,dz=p.z-cz,d=dx*dx+dy*dy+dz*dz;
        int base=distances().level(d),previous=CHUNK_LEVELS.getOrDefault(key,base),next=base;
        double near=nearDistance*nearDistance,far=Math.max(nearDistance,farDistance);far*=far;
        double nearIn=near*.81,nearOut=near*1.21,farIn=far*.81,farOut=far*1.21;
        if(previous==0&&d<nearOut)next=0;
        else if(previous==2&&d>farIn)next=2;
        else if(previous==1&&d>=nearIn&&d<=farOut)next=1;
        if(CHUNK_LEVELS.size()>=MAX_CHUNK_LEVELS&&!CHUNK_LEVELS.containsKey(key))CHUNK_LEVELS.clear();
        CHUNK_LEVELS.put(key,next);return next;
    }
    public static ProfileModel model(String id,V3 position){Profiles.get(id);var levels=LEVELS.get(id);return levels==null?Profiles.model(id):levels.models[level(position)];}
    public static boolean render(org.mtr.core.data.Rail rail,RailResource source,boolean flip,V3 a,V3 b){
        // MTR can expose the same resource with a direction suffix (for example _1/_2).
        // LOD registration is keyed by the canonical style, so looking up the raw resource
        // id would silently fall back to MTR's renderer for only those cells.
        var levels=LEVELS.get(Profiles.canonical(source.getId()));if(levels==null)return false;int level=chunkLevel(Profiles.canonical(source.getId()),a.lerp(b,.5));
        var model=levels.models[level];
        if(model.continuousGuard()!=null)return false;
        draws[level]++;var sweep=RailSampler.sweep(rail,source,a,b);
        RailCellCache.submit(model.fixed(),sweep,flip,source.getModelYOffset(),model.detail().zMin(),model.detail().zMax(),V3.ZERO,true);
        V3 direction=sweep.forward(.5),center=sweep.rigid(V3.ZERO,flip,source.getModelYOffset());
        double yaw=Math.atan2(direction.z(),direction.x()),pitch=Math.atan2(direction.y(),Math.hypot(direction.x(),direction.z())),cant=(sweep.cantA()+sweep.cantB())/2;
        var transform=new StoredMatrixTransformations(center.x(),center.y(),center.z());
        transform.add(g->{g.rotateYRadians((float)(Math.PI/2-yaw+(flip?Math.PI:0)));g.rotateXRadians((float)(Math.PI-pitch*(flip?-1:1)));g.rotateZDegrees((float)(-Math.toDegrees(cant)*(flip?-1:1)));});
        var mc=Minecraft.getInstance();int light=net.minecraft.client.renderer.LevelRenderer.getLightColor(mc.level,net.minecraft.core.BlockPos.containing(center.x(),center.y()+.3,center.z()));
        RailSampler.withoutBank(()->levels.resources[level].render(transform,light));return true;
    }
    private static String supportObj(String obj,Set<String> supports){
        StringBuilder out=new StringBuilder();boolean include=false;
        for(String line:obj.split("\\R")){String trimmed=line.trim();if(trimmed.startsWith("g "))include=Arrays.stream(trimmed.substring(2).trim().split("\\s+")).anyMatch(supports::contains);
            if(!trimmed.startsWith("f ")||include)out.append(line).append('\n');}
        return out.toString();
    }
}
