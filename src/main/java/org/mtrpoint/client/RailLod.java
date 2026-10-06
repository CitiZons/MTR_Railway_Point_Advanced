package org.mtrpoint.client;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import org.mtr.mod.resource.RailResource;
import org.mtrpoint.geometry.*;
import org.mtrpoint.mixin.RailResourceAccess;
import java.util.*;

/** Distance-selected OBJ models; ordinary rails and supports share persistent world batches. */
public final class RailLod {
    private static double nearDistance=-1,farDistance=-1;
    private static RailDetailDistances distances;
    private record Levels(ProfileModel[] models) {}
    private record ChunkKey(String id,int x,int y,int z) {}
    private static final Map<String,Levels> LEVELS=new HashMap<>();
    private static final Map<ChunkKey,Integer> CHUNK_LEVELS=new HashMap<>();
    private static final int MAX_CHUNK_LEVELS=8192;
    public static final long[] draws=new long[3];
    public static void clear(){LEVELS.clear();CHUNK_LEVELS.clear();OrdinaryRailCache.clear();Arrays.fill(draws,0);}
    public static ProfileModel register(String id,RailResource source,ProfileModel high,JsonObject descriptor,ObjTemplate.Reader reader)throws Exception{
        if(!descriptor.has("lod")||!descriptor.getAsJsonObject("lod").has("mid")||!descriptor.getAsJsonObject("lod").has("far"))return high;
        var access=(RailResourceAccess)(Object)source;ProfileModel[] models={high,null,null};
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

        }
        high=new ProfileModel(high.detail().withFittingLods(List.of(models[1].detail().fittings(),models[2].detail().fittings())),high.attachments(),high.fixed(),high.supports(),high.alignSleepers(),high.continuousGuard());models[0]=high;
        LEVELS.put(id,new Levels(models));return high;
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
    static ProfileModel[] registeredModels(String id){var levels=LEVELS.get(Profiles.canonical(id));return levels==null?null:levels.models;}
    public static ProfileModel model(String id,V3 position){id=Profiles.canonical(id);Profiles.get(id);var models=registeredModels(id);return models==null?Profiles.model(id):models[chunkLevel(id,position)];}
    public static boolean available(String id){id=Profiles.canonical(id);Profiles.get(id);return LEVELS.containsKey(id);}
    /** Shared chunk-level selection for turnout fitting batches. */
    public static int fittingLevel(V3 position){return chunkLevel("__fittings__",position);}
    public static boolean render(org.mtr.core.data.Rail rail,RailResource source,boolean flip,V3 a,V3 b){
        // MTR can expose the same resource with a direction suffix (for example _1/_2).
        // LOD registration is keyed by the canonical style, so looking up the raw resource
        // id would silently fall back to MTR's renderer for only those cells.
        var models=registeredModels(source.getId());if(models==null)return false;int level=chunkLevel(Profiles.canonical(source.getId()),a.lerp(b,.5));
        var model=models[level];
        if(model.continuousGuard()!=null)return false;
        draws[level]++;var sweep=RailSampler.sweep(rail,source,a,b);
        RailCellCache.submit(model.fixed(),sweep,flip,source.getModelYOffset(),model.detail().zMin(),model.detail().zMax(),V3.ZERO,true);
        RailCellCache.submit(model.supports(),sweep,flip,source.getModelYOffset(),model.detail().zMin(),model.detail().zMax(),V3.ZERO,false);
        return true;
    }
}
