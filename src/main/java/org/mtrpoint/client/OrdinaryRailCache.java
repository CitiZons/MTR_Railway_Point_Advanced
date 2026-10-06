package org.mtrpoint.client;

import net.minecraft.client.Minecraft;
import org.mtr.core.data.Rail;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.resource.RailResource;
import org.mtrpoint.geometry.*;
import java.util.*;

/** Complete sampled chunks: camera direction controls drawing, never their contents. */
public final class OrdinaryRailCache {
    private record Key(String rail,String style,boolean flip) {}
    record Chunk(int x,int y,int z) {
        static Chunk of(V3 p){return new Chunk((int)Math.floor(p.x()/8),(int)Math.floor(p.y()/8),(int)Math.floor(p.z()/8));}
        V3 center(){return new V3(x*8+4,y*8+4,z*8+4);}
        boolean nearby(V3 camera,double radius){return Math.abs(x*8+4-camera.x())+Math.abs(y*8+4-camera.y())+Math.abs(z*8+4-camera.z())<=radius+24;}
    }
    record IndexedCell(RailSampler.Cell cell,boolean first,boolean last) {}
    static Map<Chunk,List<IndexedCell>> partition(List<RailSampler.Cell> cells){
        var chunks=new LinkedHashMap<Chunk,List<IndexedCell>>();
        for(int i=0;i<cells.size();i++){
            var cell=cells.get(i);Chunk chunk=Chunk.of(cell.a().lerp(cell.b(),.5));
            chunks.computeIfAbsent(chunk,k->new ArrayList<>()).add(new IndexedCell(cell,i==0,i==cells.size()-1));
        }
        chunks.replaceAll((key,value)->List.copyOf(value));return chunks;
    }
    static final class Segment {
        final List<IndexedCell> cells;
        private ProfileModel model;
        private Object owners;
        private ContinuousGuards.Ends ends;
        private RailCellCache.Contribution contribution;
        Segment(List<IndexedCell> cells){this.cells=cells;}
        void submit(ProfileModel selected,Object ownership,ContinuousGuards.Ends exposed,Runnable build){
            if(contribution==null||model!=selected||!Objects.equals(owners,ownership)||!Objects.equals(ends,exposed)){
                contribution=RailCellCache.capture(build);model=selected;owners=ownership;ends=exposed;builds++;
            }
            contribution.submit();
        }
        void release(){contribution=null;model=null;owners=null;ends=null;}
    }
    private static final class Entry {
        Rail rail;
        RailResource resource;
        final boolean flip;
        Track sampled;
        ProfileModel high;
        Map<Chunk,Segment> chunks=Map.of();
        Entry(Rail rail,RailResource resource,boolean flip){this.rail=rail;this.resource=resource;this.flip=flip;}
        boolean refresh(){
            Track track=RailSampler.sample(rail);ProfileModel current=Profiles.model(resource.getId());
            if(track==null||current==null)return false;
            if(sampled!=track||high!=current){
                var cells=RailSampler.ordinaryCells(rail,resource);if(cells.isEmpty())return false;
                var next=new LinkedHashMap<Chunk,Segment>();partition(cells).forEach((key,list)->next.put(key,new Segment(list)));
                sampled=track;high=current;chunks=next;
            }
            return true;
        }
    }
    private static final Map<Key,Entry> ENTRIES=new LinkedHashMap<>();
    private static final Set<Key> SEEN=new HashSet<>();
    private static final Set<String> SEEN_RAILS=new HashSet<>();
    static long builds;
    static boolean containsStyle(Collection<String> styles,String resourceId,boolean flip){
        String canonical=Profiles.canonical(resourceId);
        return styles.stream().anyMatch(style->style.endsWith("_2")==flip&&Profiles.canonical(style).equals(canonical));
    }
    static Set<String> sampleIds(Set<String> detectorIds){
        var ids=new HashSet<>(detectorIds);for(var key:ENTRIES.keySet())ids.add(key.rail);return ids;
    }
    public static void begin(){SEEN.clear();SEEN_RAILS.clear();}
    public static void clear(){ENTRIES.clear();begin();builds=0;}
    /** Called once per native rail resource, before its per-cell visibility/lighting loop. */
    public static boolean register(Rail rail,RailResource resource,boolean flip){
        if(rail==null||!RailLod.available(resource.getId()))return false;
        Key key=new Key(rail.getHexId(),resource.getId(),flip);
        Entry entry=ENTRIES.get(key);
        if(entry==null){entry=new Entry(rail,resource,flip);if(!entry.refresh())return false;ENTRIES.put(key,entry);}
        else if(entry.rail!=rail||entry.resource!=resource){entry.rail=rail;entry.resource=resource;entry.sampled=null;if(!entry.refresh())return false;}
        SEEN.add(key);SEEN_RAILS.add(key.rail);return true;
    }
    static void submit(){
        var mc=Minecraft.getInstance();var p=mc.gameRenderer.getMainCamera().getPosition();
        V3 camera=new V3(Math.floor(p.x),Math.floor(p.y),Math.floor(p.z));
        double radius=org.mtr.mapping.mapper.MinecraftClientHelper.getRenderDistance()*16;
        var rails=MinecraftClientData.getInstance().railIdMap;
        for(var it=ENTRIES.entrySet().iterator();it.hasNext();){
            var item=it.next();Key key=item.getKey();Entry e=item.getValue();Rail current=rails.get(key.rail);
            if(current==null||SEEN_RAILS.contains(key.rail)&&!SEEN.contains(key)
                ||!containsStyle(current.getStyles(),key.style,key.flip)){it.remove();continue;}
            if(current!=e.rail){e.rail=current;e.sampled=null;}
            if(!e.refresh()){it.remove();continue;}
            if(e.chunks.keySet().stream().noneMatch(chunk->chunk.nearby(camera,radius+16)))it.remove();
        }
        // All entries have now refreshed their samples, including adjacent guard-track endpoints.
        for(var e:ENTRIES.values()){
            SleeperSeams.begin();
            Object owners=PointClient.cellOwners(e.rail,e.resource.getId());
            var ends=e.high.continuousGuard()==null?null:ContinuousGuards.exposed(e.rail,e.resource.getId());
            for(var item:e.chunks.entrySet()){
                var chunk=item.getKey();var segment=item.getValue();
                if(!chunk.nearby(camera,radius)){segment.release();continue;}
                ProfileModel model=RailLod.model(e.resource.getId(),chunk.center());
                segment.submit(model,owners,ends,()->build(e,segment,model,ends));
            }
        }
    }
    private static void build(Entry e,Segment segment,ProfileModel model,ContinuousGuards.Ends ends){
        for(var indexed:segment.cells){
            var cell=indexed.cell;V3 a=cell.a(),b=cell.b();
            if(PointClient.suppress(e.rail,e.resource.getId(),a.lerp(b,.5),0))PointRenderer.preserve(e.rail,e.resource,e.flip,a,b);
            else {
                if(!SleeperSeams.render(e.rail,e.resource,e.flip,a,b,false))RailLod.render(e.rail,e.resource,e.flip,a,b);
                if(model.continuousGuard()!=null&&(indexed.first||indexed.last))
                    ContinuousGuards.endpoint(e.rail,e.resource,e.flip,a,b,model,e.sampled,ends,indexed.first,indexed.last);
            }
        }
    }
}
