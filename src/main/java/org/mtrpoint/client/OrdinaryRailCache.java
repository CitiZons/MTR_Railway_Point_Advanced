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
        final boolean endpoint;
        final String owner;
        private ProfileModel model;
        private Object owners;
        private Object ownershipInput,localOwners;
        private ContinuousGuards.Ends ends;
        private RailCellCache.Contribution contribution;
        private record Variant(ProfileModel model,Object owners,ContinuousGuards.Ends ends){
            @Override public boolean equals(Object other){return other instanceof Variant v&&model==v.model&&Objects.equals(owners,v.owners)&&Objects.equals(ends,v.ends);}
            @Override public int hashCode(){return 31*(31*System.identityHashCode(model)+Objects.hashCode(owners))+Objects.hashCode(ends);}
        }
        private final Map<Variant,RailCellCache.Contribution> variants=new LinkedHashMap<>();
        Segment(List<IndexedCell> cells){this(cells,null);}
        Segment(List<IndexedCell> cells,String owner){this.owner=owner;this.cells=cells;endpoint=cells.stream().anyMatch(c->c.first||c.last);}
        Object owners(Object input,Rail rail,String style){
            if(!Objects.equals(input,ownershipInput)){
                localOwners=PointClient.localCellOwners(input,rail,style,cells);ownershipInput=input;
            }
            return localOwners;
        }
        void submit(ProfileModel selected,Object ownership,ContinuousGuards.Ends exposed,Runnable build){
            if(contribution==null||model!=selected||!Objects.equals(owners,ownership)||!Objects.equals(ends,exposed)){
                var key=new Variant(selected,ownership,exposed);contribution=variants.get(key);
                if(contribution==null){contribution=RailCellCache.capture(owner,build);variants.put(key,contribution);builds++;}
                model=selected;owners=ownership;ends=exposed;
            }
            contribution.submit();
        }
        void release(){contribution=null;model=null;owners=null;ends=null;variants.clear();}
        void invalidate(){for(var cell:cells)RailCellCache.invalidate(cell.cell.a().lerp(cell.cell.b(),.5),owner,true);release();ownershipInput=null;localOwners=null;}
    }
    private static final class Entry {
        Rail rail;
        RailResource resource;
        final boolean flip;
        final String owner;
        Track sampled;
        ProfileModel high;
        Map<Chunk,Segment> chunks=Map.of();
        Entry(Rail rail,RailResource resource,boolean flip){this.rail=rail;this.resource=resource;this.flip=flip;owner=rail.getHexId()+"/"+resource.getId()+"/"+flip;}
        boolean refresh(){
            Track track=RailSampler.sample(rail);ProfileModel current=Profiles.model(resource.getId());
            if(track==null||current==null)return false;
            if(sampled!=track||high!=current){
                GuardTerminalCache.remove(owner);
                if(sampled!=null)chunks.values().forEach(Segment::invalidate);
                var cells=RailSampler.ordinaryCells(rail,resource);if(cells.isEmpty())return false;
                var next=new LinkedHashMap<Chunk,Segment>();partition(cells).forEach((key,list)->next.put(key,new Segment(list,owner)));
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
    public static void begin(){
        SEEN.clear();SEEN_RAILS.clear();
        var mc=Minecraft.getInstance();if(mc==null||mc.level==null)return;
        var rails=MinecraftClientData.getInstance().railIdMap;
        // Invalidate edits/deletions before MTR can cancel any native callbacks this frame.
        for(var it=ENTRIES.entrySet().iterator();it.hasNext();){var item=it.next();Entry e=item.getValue();Rail rail=rails.get(item.getKey().rail);
            if(rail==null||!containsStyle(rail.getStyles(),item.getKey().style,item.getKey().flip)){GuardTerminalCache.remove(e.owner);e.chunks.values().forEach(Segment::invalidate);it.remove();continue;}
            e.rail=rail;if(!e.refresh()){e.chunks.values().forEach(Segment::invalidate);it.remove();continue;}
            Object input=PointClient.cellOwners(rail,e.resource.getId());
            for(Segment segment:e.chunks.values()){Object previous=segment.localOwners;Object local=segment.owners(input,rail,e.resource.getId());
                if(previous!=null&&!Objects.equals(previous,local)){GuardTerminalCache.remove(e.owner);for(var cell:segment.cells)RailCellCache.invalidate(cell.cell.a().lerp(cell.cell.b(),.5),segment.owner,false);}}
        }
    }
    public static void clear(){GuardTerminalCache.clear();ENTRIES.clear();SEEN.clear();SEEN_RAILS.clear();builds=0;}
    /** Called once per native rail resource, before its per-cell visibility/lighting loop. */
    public static boolean register(Rail rail,RailResource resource,boolean flip){
        if(rail==null||!RailLod.available(resource.getId()))return false;
        Key key=new Key(rail.getHexId(),resource.getId(),flip);
        Entry entry=ENTRIES.get(key);
        if(entry==null){entry=new Entry(rail,resource,flip);if(!entry.refresh())return false;ENTRIES.put(key,entry);}
        else if(entry.rail!=rail||entry.resource!=resource){entry.rail=rail;if(entry.resource!=resource)entry.high=null;entry.resource=resource;if(!entry.refresh())return false;}
        SEEN.add(key);SEEN_RAILS.add(key.rail);
        var loaded=entry.chunks.keySet().stream().filter(OrdinaryRailCache::loaded).toList();
        String owner=entry.owner;return !loaded.isEmpty()&&loaded.stream().allMatch(chunk->RailCellCache.ready(chunk.center(),owner));
    }
    private static boolean loaded(Chunk chunk){var mc=Minecraft.getInstance();return mc!=null&&mc.level!=null&&mc.level.hasChunk(Math.floorDiv(chunk.x,2),Math.floorDiv(chunk.z,2));}
    /** During preparation, native MTR keeps drawing only the cells without a committed replacement. */
    public static boolean registered(Rail rail,RailResource resource,boolean flip){return rail!=null&&ENTRIES.containsKey(new Key(rail.getHexId(),resource.getId(),flip));}
    public static boolean ready(Rail rail,RailResource resource,boolean flip,V3 position){var entry=ENTRIES.get(new Key(rail.getHexId(),resource.getId(),flip));return entry!=null&&RailCellCache.ready(position,entry.owner);}
    static void submit(){
        var rails=MinecraftClientData.getInstance().railIdMap;
        for(var it=ENTRIES.entrySet().iterator();it.hasNext();){
            var item=it.next();Key key=item.getKey();Entry e=item.getValue();Rail current=rails.get(key.rail);
            if(current==null||!containsStyle(current.getStyles(),key.style,key.flip)){e.chunks.values().forEach(Segment::invalidate);it.remove();continue;}
            if(current!=e.rail)e.rail=current;
            if(!e.refresh()){it.remove();continue;}
            if(e.chunks.keySet().stream().noneMatch(OrdinaryRailCache::loaded))it.remove();
        }
        // All entries have now refreshed their samples, including adjacent guard-track endpoints.
        for(var e:ENTRIES.values()){
            SleeperSeams.begin();
            Object ownership=PointClient.cellOwners(e.rail,e.resource.getId());
            var ends=e.high.continuousGuard()==null?null:ContinuousGuards.exposed(e.rail,e.resource.getId());
            var terminalContributions=new ArrayList<RailCellCache.Contribution>();var terminalOwners=new ArrayList<Object>();var terminalChunks=new LinkedHashSet<Chunk>();
            for(var item:e.chunks.entrySet()){
                var chunk=item.getKey();var segment=item.getValue();
                if(!loaded(chunk)){segment.release();continue;}
                Object owners=segment.owners(ownership,e.rail,e.resource.getId());
                ProfileModel model=RailLod.model(e.resource.getId(),chunk.center());
                var localEnds=segment.endpoint?ends:null;
                segment.submit(model,owners,localEnds,()->build(e,segment,model,localEnds));
                if(ends!=null&&GuardTerminalCache.terminalSegment(e.sampled,segment.cells,e.high.continuousGuard().length(),ends)){
                    terminalContributions.add(segment.contribution);terminalOwners.add(owners);terminalChunks.add(chunk);
                }
            }
            GuardTerminalCache.update(e.owner,e.sampled,e.high,ends,terminalOwners,terminalChunks,terminalContributions,()->{
                var nativeCells=new ArrayList<GuardTerminalCache.NativeCell>();
                for(Chunk chunk:terminalChunks)for(var indexed:e.chunks.get(chunk).cells){var cell=indexed.cell();nativeCells.add(new GuardTerminalCache.NativeCell(RailSampler.sweep(e.rail,e.resource,cell.a(),cell.b()),e.sampled.nearest(cell.a()),e.sampled.nearest(cell.b()),chunk));}
                return nativeCells;
            });
        }
        GuardTerminalCache.retain(ENTRIES.values().stream().map(e->e.owner).collect(java.util.stream.Collectors.toSet()));
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
