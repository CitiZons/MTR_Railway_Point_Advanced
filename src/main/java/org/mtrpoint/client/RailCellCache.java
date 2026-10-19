package org.mtrpoint.client;

import org.mtrpoint.geometry.*;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import java.util.*;

/** Small persistent world batches; unchanged frames allocate no transformed vertices or GPU uploads. */
final class RailCellCache {
    private record Chunk(int x,int y,int z){}
    // Gathering key only: beds are transformed per cell and unioned in spatial regions.
    private static final Chunk BEDS=new Chunk(Integer.MIN_VALUE,0,0);
    private record Cell(List<Mesh.Quad> faces,RailSweep frame,boolean flip,double offset,double zMin,double zMax,V3 shift,boolean swept,double inset,double running,double guard,boolean shared,String owner){
        // Resource face lists can contain thousands of faces; never hash all of them per frame.
        @Override public int hashCode(){
            int h=31*faces.size()+Objects.hashCode(frame);
            h=31*h+Boolean.hashCode(flip);h=31*h+Double.hashCode(offset);
            h=31*h+Double.hashCode(zMin);h=31*h+Double.hashCode(zMax);h=31*h+Objects.hashCode(shift);
            h=31*h+Boolean.hashCode(swept);h=31*h+Double.hashCode(inset);
            h=31*h+Double.hashCode(running);h=31*h+Double.hashCode(guard);h=31*h+Boolean.hashCode(shared);return 31*h+Objects.hashCode(owner);
        }
    }
    private static final Map<Chunk,List<Cell>> QUEUED=new LinkedHashMap<>();
    private static Map<Chunk,List<Cell>> target=QUEUED;
    private static String targetOwner;
    static final class Contribution {
        private final Map<Chunk,List<Cell>> cells;
        private Contribution(Map<Chunk,List<Cell>> cells){this.cells=cells;}
        void submit(){cells.forEach((chunk,list)->QUEUED.computeIfAbsent(chunk,k->new ArrayList<>()).addAll(list));}
        /** Pure detached preparation for a complete turnout fallback, never reads cache/world state. */
        Mesh detachedMesh(){Mesh result=new Mesh();for(var list:cells.values())for(Cell cell:list)result.quads.addAll(transform(cell).mesh.quads);return result;}
        /** Detached, bounded endpoint replacement; no Minecraft state is read on the worker. */
        static Map<OrdinaryRailCache.Chunk,Mesh> worldChunks(List<Contribution> contributions){
            return worldChunks(contributions,false);
        }
        static Map<OrdinaryRailCache.Chunk,Mesh> priorityWorldChunks(List<Contribution> contributions,java.util.function.Function<RailSweep,OrdinaryRailCache.Chunk> ownership){return worldChunks(contributions,true,ownership);}
        private static Map<OrdinaryRailCache.Chunk,Mesh> worldChunks(List<Contribution> contributions,boolean priority){
            return worldChunks(contributions,priority,frame->OrdinaryRailCache.Chunk.of(frame.a().lerp(frame.b(),.5)));
        }
        private static Map<OrdinaryRailCache.Chunk,Mesh> worldChunks(List<Contribution> contributions,boolean priority,java.util.function.Function<RailSweep,OrdinaryRailCache.Chunk> ownership){
            var sources=new ArrayList<Cell>();var meshes=new ArrayList<Mesh>();boolean pending=false;
            for(Contribution contribution:contributions)for(var cells:contribution.cells.values())for(Cell cell:cells){sources.add(cell);Mesh mesh=world(cell,priority);meshes.add(mesh);pending|=mesh==null;}
            if(pending)return null;
            var result=new LinkedHashMap<OrdinaryRailCache.Chunk,Mesh>();
            for(int i=0;i<sources.size();i++){Cell cell=sources.get(i);Mesh mesh=meshes.get(i);
                if(cell.frame!=null){var key=ownership.apply(cell.frame);result.computeIfAbsent(key,k->new Mesh()).quads.addAll(mesh.quads);}
                else for(var q:mesh.quads)result.computeIfAbsent(OrdinaryRailCache.Chunk.of(q.center()),k->new Mesh()).quad(q);
            }
            return Collections.unmodifiableMap(result);
        }
    }
    static Contribution capture(Runnable build){return capture(null,build);}
    static Contribution capture(String owner,Runnable build){
        if(target!=QUEUED)throw new IllegalStateException("Nested cell capture");
        var captured=new LinkedHashMap<Chunk,List<Cell>>();target=captured;targetOwner=owner;
        try{build.run();}finally{target=QUEUED;targetOwner=null;}
        captured.replaceAll((key,value)->List.copyOf(value));return new Contribution(captured);
    }
    record FaceSplit(List<Mesh.Quad> beds,List<Mesh.Quad> other) {}
    private static final IdentityHashMap<List<Mesh.Quad>,FaceSplit> SPLITS=new IdentityHashMap<>();
    private static int splitFaces;
    private static final int MAX_SPLITS=256,MAX_SPLIT_FACES=65536;
    private static final Map<Chunk,LinkedHashMap<List<Cell>,Batch>> CACHED=new HashMap<>();
    private static final class Batch {List<Cell> cells;Mesh mesh;PointGpu gpu=new PointGpu();long used;Batch(List<Cell> cells){this.cells=cells;}}
    private record WorldCell(Mesh mesh,long vertices){}
    private static final class PreparedCell {final GeometryJobs.Slot<WorldCell> job=new GeometryJobs.Slot<>();WorldCell result;long used;}
    private record Scene(List<Batch> bodies,Mesh bed,Mesh joints){}
    private static final Map<Long,Scene> COMMITTED=new LinkedHashMap<>(),DESIRED=new LinkedHashMap<>();
    private static final Map<Cell,PreparedCell> WORLD_CELLS=new HashMap<>();
    private static boolean complete,trimNeeded;
    private static final long MAX_INACTIVE_FACES=250_000,MAX_INACTIVE_CELL_FACES=131_072;
    private static final Set<String> INVALIDATED=new HashSet<>();
    private static final List<Batch> ACTIVE=new ArrayList<>();
    private static final RegionalRailGeometry BED_REGIONS=new RegionalRailGeometry(true);
    private static final RegionalRailGeometry JOINT_REGIONS=new RegionalRailGeometry(false);
    private static int regionalActive;
    private static long frame;
    static void begin(){INVALIDATED.clear();for(var cells:QUEUED.values())cells.clear();}
    static int splitCount(){return SPLITS.size();}
    static int splitFaceCount(){return splitFaces;}
    static int activeCount(){return ACTIVE.size()+regionalActive;}
    static List<Mesh> activeMeshes(){return ACTIVE.stream().map(b->b.mesh).toList();}
    static long bedBuilds(){return BED_REGIONS.builds;}
    static long jointBuilds(){return JOINT_REGIONS.builds;}
    static long builds,transformedVertices;
    static String worldPart(Mesh.Quad q){
        if((q.part().equals("ballast")||q.part().equals("track_bed"))&&Math.max(Math.max(q.a().z(),q.b().z()),Math.max(q.c().z(),q.d().z()))-Math.min(Math.min(q.a().z(),q.b().z()),Math.min(q.c().z(),q.d().z()))<1e-7)return q.part()+"_end";
        return q.part();
    }
    static void submit(List<Mesh.Quad> faces,RailSweep sweep,boolean flip,double offset,double zMin,double zMax,V3 shift,boolean swept){
        if(faces.isEmpty())return;double px=(sweep.a().x()+sweep.b().x())*.5,py=(sweep.a().y()+sweep.b().y())*.5,pz=(sweep.a().z()+sweep.b().z())*.5;
        Chunk chunk=new Chunk((int)Math.floor(px/8),(int)Math.floor(py/8),(int)Math.floor(pz/8));
        var split=split(faces);
        if(!split.beds.isEmpty())target.computeIfAbsent(BEDS,k->new ArrayList<>()).add(new Cell(split.beds,sweep,flip,offset,zMin,zMax,shift,swept,0,0,0,false,targetOwner));
        if(!split.other.isEmpty())target.computeIfAbsent(chunk,k->new ArrayList<>()).add(new Cell(split.other,sweep,flip,offset,zMin,zMax,shift,swept,0,0,0,false,targetOwner));
    }
    static void submitGuard(List<Mesh.Quad> faces,RailSweep sweep,boolean flip,double offset,double zMin,double zMax,V3 shift,double inset,double running,double guard,boolean shared){
        if(faces.isEmpty())return;double px=(sweep.a().x()+sweep.b().x())*.5,py=(sweep.a().y()+sweep.b().y())*.5,pz=(sweep.a().z()+sweep.b().z())*.5;Chunk chunk=new Chunk((int)Math.floor(px/8),(int)Math.floor(py/8),(int)Math.floor(pz/8));
        target.computeIfAbsent(chunk,k->new ArrayList<>()).add(new Cell(faces,sweep,flip,offset,zMin,zMax,shift,false,inset,running,guard,shared,targetOwner));
    }
    static void discard(){QUEUED.clear();ACTIVE.clear();regionalActive=0;}
    static void submitWorldBeds(List<Mesh.Quad> faces){
        if(!faces.isEmpty())target.computeIfAbsent(BEDS,k->new ArrayList<>()).add(new Cell(faces,null,false,0,0,0,V3.ZERO,false,0,0,0,false,targetOwner));
    }
    static FaceSplit split(List<Mesh.Quad> faces){
        var cached=SPLITS.get(faces);if(cached!=null)return cached;
        boolean bed=false,other=false;
        for(var q:faces){if(q.part().equals("track_bed"))bed=true;else other=true;if(bed&&other)break;}
        FaceSplit result;
        if(!bed)result=new FaceSplit(List.of(),faces);
        else if(!other)result=new FaceSplit(faces,List.of());
        else {var beds=new ArrayList<Mesh.Quad>();var rest=new ArrayList<Mesh.Quad>();for(var q:faces)(q.part().equals("track_bed")?beds:rest).add(q);result=new FaceSplit(List.copyOf(beds),List.copyOf(rest));}
        // Generated cell-specific guard faces must not make this resource cache unbounded.
        if(faces.size()<=MAX_SPLIT_FACES){
            while(!SPLITS.isEmpty()&&(SPLITS.size()>=MAX_SPLITS||splitFaces+faces.size()>MAX_SPLIT_FACES)){var it=SPLITS.keySet().iterator();var old=it.next();splitFaces-=old.size();it.remove();}
            SPLITS.put(faces,result);splitFaces+=faces.size();
        }
        return result;
    }
    static void clear(){
        OrdinaryRailCache.clear();for(var variants:CACHED.values())for(var b:variants.values())b.gpu.close();CACHED.clear();
        BED_REGIONS.clear();JOINT_REGIONS.clear();WORLD_CELLS.clear();GeometryJobs.clear();COMMITTED.clear();DESIRED.clear();
        regionalActive=0;SPLITS.clear();splitFaces=0;discard();builds=transformedVertices=0;
    }
    private static long region(Chunk c){return region(Math.floorDiv(c.x,2),Math.floorDiv(c.z,2));}
    private static long region(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
    private static Chunk chunk(V3 p){return new Chunk((int)Math.floor(p.x()/8),(int)Math.floor(p.y()/8),(int)Math.floor(p.z()/8));}
    static boolean ready(V3 p){return COMMITTED.containsKey(region(chunk(p)));}
    static boolean ready(V3 p,String owner){
        return committedReady(p,owner)||GuardTerminalCache.ready(p,owner);
    }
    static boolean committedReady(V3 p,String owner){
        Scene scene=COMMITTED.get(region(chunk(p)));if(scene==null)return false;Chunk target=chunk(p);
        for(Batch batch:scene.bodies)for(Cell cell:batch.cells)if(Objects.equals(owner,cell.owner)&&cell.frame!=null&&chunk(cell.frame.a().lerp(cell.frame.b(),.5)).equals(target))return true;
        return false;
    }
    static void invalidate(V3 p){invalidate(p,null,true);}
    static void invalidate(V3 p,String owner,boolean changed){
        long key=region(chunk(p));COMMITTED.remove(key);DESIRED.remove(key);
        if(!changed||!INVALIDATED.add(key+"/"+owner))return;
        for(var entry:CACHED.entrySet())if(region(entry.getKey())==key){
            var variants=entry.getValue();for(var it=variants.values().iterator();it.hasNext();){Batch batch=it.next();
                if(owner==null||batch.cells.stream().anyMatch(c->Objects.equals(c.owner,owner))){batch.gpu.close();it.remove();}}
        }
        int x=(int)(key>>32),z=(int)key;BED_REGIONS.removeRegion(x,z);JOINT_REGIONS.removeRegion(x,z);
        WORLD_CELLS.entrySet().removeIf(e->{Cell c=e.getKey();boolean remove=(owner==null||Objects.equals(c.owner,owner))&&c.frame!=null&&region(chunk(c.frame.a().lerp(c.frame.b(),.5)))==key;if(remove)e.getValue().job.clear();return remove;});
    }
    private static Mesh world(Cell cell){
        return world(cell,false);
    }
    private static Mesh world(Cell cell,boolean priority){
        PreparedCell prepared=WORLD_CELLS.computeIfAbsent(cell,k->new PreparedCell());prepared.used=frame;
        if(prepared.result==null){WorldCell result=priority?prepared.job.getPriority(()->transform(cell)):prepared.job.get(()->transform(cell));if(result==null)return null;prepared.result=result;transformedVertices+=result.vertices;}
        return prepared.result.mesh;
    }
    static void finish(Mesh turnoutSteel){finish(List.of(turnoutSteel));}
    static void finish(List<Mesh> turnoutSources){
        frame++;ACTIVE.clear();complete=true;var bodies=new LinkedHashMap<Long,List<Batch>>();
        for(var entry:QUEUED.entrySet()){
            if(entry.getValue().isEmpty()||entry.getKey().equals(BEDS))continue;
            var variants=CACHED.computeIfAbsent(entry.getKey(),k->new LinkedHashMap<>());
            Batch batch=variants.get(entry.getValue());
            if(batch==null){var cells=List.copyOf(entry.getValue());batch=new Batch(cells);variants.put(cells,batch);}
            batch.used=frame;
            if(batch.mesh==null){
                var parts=new ArrayList<Mesh>();boolean pending=false;
                for(Cell cell:batch.cells){Mesh mesh=world(cell);if(mesh==null)pending=true;else parts.add(mesh);}
                if(!pending){batch.mesh=new Mesh();for(Mesh part:parts)batch.mesh.quads.addAll(part.quads);builds++;trimNeeded=true;}
            }
            if(batch.mesh==null){complete=false;continue;}
            ACTIVE.add(batch);bodies.computeIfAbsent(region(entry.getKey()),k->new ArrayList<>()).add(batch);
            // Current plus a few recently used LOD/ownership variants. Never evict a committed scene.
            if(variants.size()>6){var protectedBatches=Collections.newSetFromMap(new IdentityHashMap<Batch,Boolean>());for(var scene:COMMITTED.values())protectedBatches.addAll(scene.bodies);protectedBatches.add(batch);
                for(var it=variants.values().iterator();it.hasNext()&&variants.size()>6;){Batch old=it.next();if(!protectedBatches.contains(old)){old.gpu.close();it.remove();}}}
        }
        QUEUED.entrySet().removeIf(entry->entry.getValue().isEmpty());
        var bedSources=new ArrayList<Mesh>();
        for(Cell cell:QUEUED.getOrDefault(BEDS,List.of())){Mesh mesh=world(cell);if(mesh==null)complete=false;else bedSources.add(mesh);}
        if(!complete)return;
        BED_REGIONS.pin(COMMITTED.values().stream().map(Scene::bed).filter(Objects::nonNull).toList());
        JOINT_REGIONS.pin(COMMITTED.values().stream().map(Scene::joints).filter(Objects::nonNull).toList());
        long before=BED_REGIONS.builds;var beds=BED_REGIONS.update(bedSources);builds+=BED_REGIONS.builds-before;
        if(!BED_REGIONS.complete()){complete=false;return;}
        var sources=new ArrayList<Mesh>(turnoutSources);for(var batch:ACTIVE)sources.add(batch.mesh);sources.addAll(beds);
        var joints=JOINT_REGIONS.update(sources);
        if(!JOINT_REGIONS.complete()){complete=false;return;}
        DESIRED.clear();var keys=new LinkedHashSet<Long>();keys.addAll(bodies.keySet());keys.addAll(BED_REGIONS.targets().keySet());keys.addAll(JOINT_REGIONS.targets().keySet());
        for(long key:keys)DESIRED.put(key,new Scene(List.copyOf(bodies.getOrDefault(key,List.of())),BED_REGIONS.targets().get(key),JOINT_REGIONS.targets().get(key)));
        regionalActive=beds.size()+joints.size();if(trimNeeded)trimInactive();
        if(net.minecraft.client.Minecraft.getInstance()==null||net.minecraft.client.Minecraft.getInstance().level==null){COMMITTED.clear();COMMITTED.putAll(DESIRED);}
        else retainLoaded();
    }
    private static void trimInactive(){
        trimNeeded=false;var pinned=Collections.newSetFromMap(new IdentityHashMap<Batch,Boolean>());pinned.addAll(ACTIVE);for(Scene scene:COMMITTED.values())pinned.addAll(scene.bodies);
        var inactive=new ArrayList<Batch>();long faces=0;
        for(var variants:CACHED.values())for(Batch batch:variants.values())if(batch.mesh!=null&&!pinned.contains(batch)){inactive.add(batch);faces+=batch.mesh.quads.size();}
        if(faces>MAX_INACTIVE_FACES){
            inactive.sort(Comparator.comparingLong(b->b.used));var evict=Collections.newSetFromMap(new IdentityHashMap<Batch,Boolean>());
            for(Batch batch:inactive){if(faces<=MAX_INACTIVE_FACES)break;faces-=batch.mesh.quads.size();evict.add(batch);batch.gpu.close();}
            for(var variants:CACHED.values())variants.values().removeIf(evict::contains);
        }
        var cells=new HashSet<Cell>();for(var variants:CACHED.values())for(Batch batch:variants.values())cells.addAll(batch.cells);cells.addAll(QUEUED.getOrDefault(BEDS,List.of()));
        WORLD_CELLS.entrySet().removeIf(entry->{if(cells.contains(entry.getKey())||entry.getValue().result!=null)return false;entry.getValue().job.clear();return true;});
        var old=new ArrayList<Map.Entry<Cell,PreparedCell>>();long oldFaces=0;
        for(var entry:WORLD_CELLS.entrySet())if(!cells.contains(entry.getKey())&&entry.getValue().result!=null){old.add(entry);oldFaces+=entry.getValue().result.mesh.quads.size();}
        if(oldFaces>MAX_INACTIVE_CELL_FACES){old.sort(Comparator.comparingLong(e->e.getValue().used));for(var entry:old){if(oldFaces<=MAX_INACTIVE_CELL_FACES)break;oldFaces-=entry.getValue().result.mesh.quads.size();entry.getValue().job.clear();WORLD_CELLS.remove(entry.getKey());}}
    }
    private static void retainLoaded(){
        var level=net.minecraft.client.Minecraft.getInstance().level;
        java.util.function.LongPredicate loaded=key->level.hasChunk((int)(key>>32),(int)key);
        COMMITTED.keySet().removeIf(key->!loaded.test(key));DESIRED.keySet().removeIf(key->!loaded.test(key));
        for(var it=CACHED.entrySet().iterator();it.hasNext();){var e=it.next();if(!loaded.test(region(e.getKey()))){e.getValue().values().forEach(b->b.gpu.close());it.remove();}}
        BED_REGIONS.retainRegions(loaded);JOINT_REGIONS.retainRegions(loaded);
        WORLD_CELLS.entrySet().removeIf(e->{Cell c=e.getKey();boolean remove=c.frame!=null&&!loaded.test(region(chunk(c.frame.a().lerp(c.frame.b(),.5))));if(remove)e.getValue().job.clear();return remove;});
    }
    private static WorldCell transform(Cell cell){
        Mesh mesh=new Mesh();long[] count={0};

        Map<V3,V3> mapped=new HashMap<>();java.util.function.Function<V3,V3> transform=p->mapped.computeIfAbsent(p,v->{count[0]++;double x=v.x();
            if(cell.inset>0){double sign=Math.signum(x),weight=cell.shared?Math.max(0,Math.min(1,(cell.running-Math.abs(x))/Math.max(1e-6,cell.running-cell.guard))):1;x-=sign*cell.inset*weight;}
            V3 local=new V3(x,v.y(),v.z());return cell.frame==null?local:(cell.swept?cell.frame.model(local,cell.flip,cell.offset,cell.zMin,cell.zMax):cell.frame.rigid(local,cell.flip,cell.offset)).add(cell.shift);});
        Map<V3,V3> normalMap=new HashMap<>();
        V3 normalOrigin=cell.frame==null?V3.ZERO:cell.frame.rigid(V3.ZERO,cell.flip,0);
        for(var q:cell.faces){
            // Ballast has rendered transverse end caps. Keep drawing them, but
            // do not let them hide the longitudinal surface boundary from joining.
            mesh.quad(new Mesh.Quad(transform.apply(q.a()),transform.apply(q.b()),transform.apply(q.c()),transform.apply(q.d()),q.surface(),worldPart(q),-1,q.uv(),
                cell.swept||q.normals()==null?null:q.normals().stream().map(n->normalMap.computeIfAbsent(n,v->cell.frame==null?v:cell.frame.rigid(v,cell.flip,0).sub(normalOrigin).unit())).toList()));
        }
        return new WorldCell(mesh,count[0]);
    }
    static void draw(RenderLevelStageEvent e){
        // Draw exactly the scene whose native cells were suppressed earlier in this frame.
        for(var scene:COMMITTED.values()){
            for(var batch:scene.bodies)batch.gpu.draw(e);
            if(scene.bed!=null)BED_REGIONS.drawMesh(e,scene.bed);
            if(scene.joints!=null)JOINT_REGIONS.drawMesh(e,scene.joints);
        }
        for(var batch:ACTIVE)if(batch.gpu.visible(e,batch.mesh,false))batch.gpu.update(batch.mesh,0,false,false);
        BED_REGIONS.prepare(e);JOINT_REGIONS.prepare(e);PointGpu.flushUploads();
        if(!complete)return;
        for(var entry:DESIRED.entrySet()){
            Scene scene=entry.getValue();boolean pending=false;
            for(Batch batch:scene.bodies)if(!batch.gpu.hasSource(batch.mesh)||!batch.gpu.ready()){batch.gpu.update(batch.mesh,0,false,false);pending=true;}
            if(scene.bed!=null){var gpu=BED_REGIONS.gpu(scene.bed);if(!gpu.hasSource(scene.bed)||!gpu.ready()){gpu.update(scene.bed,0,false,false);pending=true;}}
            if(scene.joints!=null){var gpu=JOINT_REGIONS.gpu(scene.joints);if(!gpu.hasSource(scene.joints)||!gpu.ready()){gpu.update(scene.joints,0,false,false);pending=true;}}
            if(pending)break;
        }
        PointGpu.flushUploads();
        for(var entry:DESIRED.entrySet()){
            Scene scene=entry.getValue();boolean ready=scene.bodies.stream().allMatch(b->b.gpu.hasSource(b.mesh)&&b.gpu.ready());
            if(scene.bed!=null){var gpu=BED_REGIONS.gpu(scene.bed);ready&=gpu.hasSource(scene.bed)&&gpu.ready();}
            if(scene.joints!=null){var gpu=JOINT_REGIONS.gpu(scene.joints);ready&=gpu.hasSource(scene.joints)&&gpu.ready();}
            if(ready)COMMITTED.put(entry.getKey(),scene);
        }
        // Empty/deleted regions cannot leave a stale native replacement on screen.
        COMMITTED.keySet().removeIf(key->!DESIRED.containsKey(key));
    }
}
