package org.mtrpoint;

import com.google.gson.Gson;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.mtrpoint.geometry.PointSettings;
import java.util.*;

public final class AppearanceData extends SavedData {
    public static final Gson JSON=new Gson();
    public record Entry(PointSettings value,long revision) {}
    public final Map<String,Entry> entries=new HashMap<>();
    public static AppearanceData get(ServerLevel level){return level.getDataStorage().computeIfAbsent(AppearanceData::load,AppearanceData::new,"mtrpoint_appearance");}
    public static PointSettings decode(String json){PointSettings s=JSON.fromJson(json,PointSettings.class);if(s==null)throw new IllegalArgumentException("Missing settings");return s;}
    public static AppearanceData load(CompoundTag root){
        var data=new AppearanceData();var list=root.getList("points",10);
        for(int i=0;i<list.size();i++){var v=list.getCompound(i);try{data.entries.put(v.getString("id"),new Entry(decode(v.getString("settings")),v.getLong("revision")));}catch(RuntimeException ex){PointMod.LOG.warn("Skipped invalid saved point appearance {}",v.getString("id"));}}
        return data;
    }
    @Override public CompoundTag save(CompoundTag root){var list=new ListTag();entries.forEach((id,e)->{var tag=new CompoundTag();tag.putString("id",id);tag.putString("settings",JSON.toJson(e.value));tag.putLong("revision",e.revision);list.add(tag);});root.put("points",list);return root;}
}
