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
    public static final double NEAR=4,MID=12;
    private record Levels(ProfileModel[] models,RailResource[] resources) {}
    private static final Map<String,Levels> LEVELS=new HashMap<>();
    public static final long[] draws=new long[3];
    public static void clear(){LEVELS.clear();Arrays.fill(draws,0);}
    public static ProfileModel register(String id,RailResource source,ProfileModel high,JsonObject descriptor,ObjTemplate.Reader reader)throws Exception{
        if(!descriptor.has("lod")||!descriptor.getAsJsonObject("lod").has("mid")||!descriptor.getAsJsonObject("lod").has("far"))return high;
        var access=(RailResourceAccess)(Object)source;ProfileModel[] models={high,null,null};RailResource[] resources=new RailResource[3];
        Set<String> supportNames=new HashSet<>();descriptor.getAsJsonObject("modelGroups").getAsJsonArray("supports").forEach(v->supportNames.add(v.getAsString()));
        for(int level=0;level<3;level++){
            String model=level==0?access.point$model():descriptor.getAsJsonObject("lod").getAsJsonObject(level==1?"mid":"far").get("model").getAsString();
            JsonObject variant=descriptor.deepCopy();variant.addProperty("model",model);
            if(level>0)models[level]=ProfileModel.read(variant,model,access.point$texture(),access.point$flipV(),reader);
            var d=models[level].detail();var base=high.detail();
            if(Math.abs(d.railCenter()-base.railCenter())>1e-5||Math.abs(d.railTop()-base.railTop())>1e-5||Math.abs(d.zMax()-base.zMax())>1e-5||Math.abs(d.zMin()-base.zMin())>1e-5)throw new IllegalArgumentException("LOD dimensions differ from ordinary rail");
            JsonObject data=new JsonObject();data.addProperty("id",id+"_point_lod"+level);data.addProperty("name",source.getName());data.addProperty("modelResource",model);
            data.addProperty("textureResource",access.point$texture());data.addProperty("flipTextureV",access.point$flipV());data.addProperty("repeatInterval",source.getRepeatInterval());data.addProperty("modelYOffset",source.getModelYOffset());
            String supportObj=supportObj(reader.read(model),supportNames);
            resources[level]=new RailResource(JsonReader.parse(data.toString()),key->{try{return key.data.toString().equals(model)?supportObj:reader.read(key.data.toString());}catch(Exception ex){throw new IllegalArgumentException(ex);}});
        }
        high=new ProfileModel(high.detail().withFittingLods(List.of(models[1].detail().fittings(),models[2].detail().fittings())),high.attachments(),high.fixed(),high.supports(),high.alignSleepers());models[0]=high;
        LEVELS.put(id,new Levels(models,resources));return high;
    }
    public static int level(double distanceSquared){return distanceSquared<NEAR*NEAR?0:distanceSquared<MID*MID?1:2;}
    public static int level(V3 position){var p=Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();double x=p.x-position.x(),y=p.y-position.y(),z=p.z-position.z();return level(x*x+y*y+z*z);}
    public static ProfileModel model(String id,V3 position){Profiles.get(id);var levels=LEVELS.get(id);return levels==null?Profiles.model(id):levels.models[level(position)];}
    public static boolean render(org.mtr.core.data.Rail rail,RailResource source,boolean flip,V3 a,V3 b){
        var levels=LEVELS.get(source.getId());if(levels==null)return false;int level=level(a.lerp(b,.5));draws[level]++;
        var model=levels.models[level];var sweep=RailSampler.sweep(rail,source,a,b);
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
