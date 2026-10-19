package org.mtrpoint.client;

import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.mtrpoint.geometry.*;
import java.util.*;

/** Small complete endpoint cells can take over before the distant bed/seam scene is ready. */
final class GuardTerminalCache {
    record NativeCell(RailSweep sweep,double from,double to,OrdinaryRailCache.Chunk chunk) {}
    static OrdinaryRailCache.Chunk nativeChunk(Track track,List<NativeCell> cells,RailSweep sweep){
        for(NativeCell cell:cells)if(cell.sweep.equals(sweep))return cell.chunk;
        double station=track.nearest(sweep.a().lerp(sweep.b(),.5)),best=Double.POSITIVE_INFINITY;NativeCell closest=null;
        for(NativeCell cell:cells){double distance=Math.max(Math.min(cell.from,cell.to)-station,Math.max(0,station-Math.max(cell.from,cell.to)));
            if(distance<best){best=distance;closest=cell;}}
        return closest==null?OrdinaryRailCache.Chunk.of(sweep.a().lerp(sweep.b(),.5)):closest.chunk;
    }
    private record Signature(Track track,ProfileModel profile,ContinuousGuards.Ends ends,List<Object> owners,Set<OrdinaryRailCache.Chunk> chunks) {}
    private static final class Part {
        final Mesh mesh;final PointGpu gpu=new PointGpu();boolean requested;
        Part(Mesh mesh){this.mesh=mesh;gpu.setPriority(true);}
    }
    private static final class Entry {
        final Signature signature;final List<RailCellCache.Contribution> snapshot;
        final List<NativeCell> nativeCells;
        Map<OrdinaryRailCache.Chunk,Part> parts;
        Entry(String owner,Signature signature,List<RailCellCache.Contribution> contributions,List<NativeCell> nativeCells){this.owner=owner;this.signature=signature;this.snapshot=List.copyOf(contributions);this.nativeCells=List.copyOf(nativeCells);}
        void prepare(){
            if(parts==null){var built=RailCellCache.Contribution.priorityWorldChunks(snapshot,frame->nativeChunk(signature.track,nativeCells,frame));if(built==null)return;
                parts=new LinkedHashMap<>();built.forEach((chunk,mesh)->{if(signature.chunks.contains(chunk))parts.put(chunk,new Part(mesh));});}
            var mc=net.minecraft.client.Minecraft.getInstance();if(mc!=null&&mc.level!=null)for(var item:parts.entrySet()){
                Part part=item.getValue();
                if(RailCellCache.committedReady(item.getKey().center(),owner)){if(part.gpu.hasSource(part.mesh))part.gpu.close();}
                else part.gpu.update(part.mesh,0,false,false);
            }
        }
        final String owner;
        void close(){if(parts!=null)for(Part part:parts.values())part.gpu.close();}
    }
    private static final Map<String,Entry> ENTRIES=new LinkedHashMap<>();
    static void begin(){for(Entry entry:ENTRIES.values())if(entry.parts!=null)for(Part part:entry.parts.values())part.requested=false;}
    static boolean terminalSegment(Track track,List<OrdinaryRailCache.IndexedCell> cells,double reach,ContinuousGuards.Ends ends){
        for(var cell:cells){double a=track.nearest(cell.cell().a()),b=track.nearest(cell.cell().b());
            if(ends.start()&&Math.min(a,b)<=reach+.001||ends.end()&&Math.max(a,b)>=track.length-reach-.001)return true;}
        return false;
    }
    static void update(String owner,Track track,ProfileModel high,ContinuousGuards.Ends ends,List<Object> owners,Set<OrdinaryRailCache.Chunk> chunks,List<RailCellCache.Contribution> contributions){
        update(owner,track,high,ends,owners,chunks,contributions,List::of);
    }
    static void update(String owner,Track track,ProfileModel high,ContinuousGuards.Ends ends,List<Object> owners,Set<OrdinaryRailCache.Chunk> chunks,List<RailCellCache.Contribution> contributions,java.util.function.Supplier<List<NativeCell>> nativeCells){
        if(chunks.isEmpty()){remove(owner);return;}
        Signature signature=new Signature(track,high,ends,List.copyOf(owners),Set.copyOf(chunks));Entry entry=ENTRIES.get(owner);
        // LOD is deliberately absent: keep a valid endpoint replacement while the main scene changes detail.
        if(entry==null||!entry.signature.equals(signature)){remove(owner);entry=new Entry(owner,signature,contributions,nativeCells.get());ENTRIES.put(owner,entry);}
        entry.prepare();
    }
    static boolean ready(V3 position,String owner){
        Entry entry=ENTRIES.get(owner);if(entry==null||entry.parts==null)return false;Part part=entry.parts.get(OrdinaryRailCache.Chunk.of(position));
        if(part==null||!part.gpu.hasSource(part.mesh)||!part.gpu.readyForDraw())return false;
        part.requested=true;return true;
    }
    static Mesh preparedMesh(String owner,OrdinaryRailCache.Chunk chunk){Entry entry=ENTRIES.get(owner);Part part=entry==null||entry.parts==null?null:entry.parts.get(chunk);return part==null?null:part.mesh;}
    static void draw(RenderLevelStageEvent event){
        for(Entry entry:ENTRIES.values())if(entry.parts!=null)for(Part part:entry.parts.values())if(part.requested)part.gpu.draw(event);
    }
    static void retain(Set<String> owners){for(var it=ENTRIES.entrySet().iterator();it.hasNext();){var entry=it.next();if(!owners.contains(entry.getKey())){entry.getValue().close();it.remove();}}}
    static void remove(String owner){Entry old=ENTRIES.remove(owner);if(old!=null)old.close();}
    static void clear(){ENTRIES.values().forEach(Entry::close);ENTRIES.clear();}
}
