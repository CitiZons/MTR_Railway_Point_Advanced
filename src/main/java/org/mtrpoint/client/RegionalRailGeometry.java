package org.mtrpoint.client;

import org.mtrpoint.geometry.*;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import java.util.*;
import java.util.function.LongPredicate;

/** Spatial invalidation for beds and seam bridges. Inputs remain whole faces at region boundaries. */
final class RegionalRailGeometry {
    private static final int SIZE=16,MAX_INACTIVE_VARIANTS=8;
    private static final int MAX_INACTIVE_SOURCE_ENTRIES=512;
    private static final long MAX_INACTIVE_VARIANT_FACE_REFS=250_000,MAX_INACTIVE_SOURCE_FACE_REFS=131_072;
    private record Region(int x,int z) {
        static Region of(V3 p){return new Region((int)Math.floor(p.x()/SIZE),(int)Math.floor(p.z()/SIZE));}
        long key(){return ((long)x<<32)^(z&0xffffffffL);}
    }
    /** Lists are immutable snapshots; their hash is computed once on the geometry worker. */
    private record FaceList(List<Mesh.Quad> faces,int hash) {
        FaceList(List<Mesh.Quad> faces){this(List.copyOf(faces),faces.hashCode());}
        @Override public boolean equals(Object other){return this==other||other instanceof FaceList that&&hash==that.hash&&faces.equals(that.faces);}
        @Override public int hashCode(){return hash;}
    }
    private static final class InputKey {
        final List<FaceList> sources;final int hash;final long faceReferences;
        InputKey(List<FaceList> sources){this.sources=List.copyOf(sources);int h=1;long refs=0;for(FaceList source:this.sources){h=31*h+source.hashCode();refs+=source.faces.size();}hash=h;faceReferences=refs;}
        @Override public int hashCode(){return hash;}
        @Override public boolean equals(Object other){return this==other||other instanceof InputKey that&&hash==that.hash&&sources.equals(that.sources);}
    }
    private record IndexedSource(Map<Region,FaceList> faces) {}
    private static final class SourceEntry {
        final List<Mesh.Quad> snapshot;final GeometryJobs.Slot<IndexedSource> slot=new GeometryJobs.Slot<>();IndexedSource value;long lastUse;
        SourceEntry(Mesh mesh){snapshot=List.copyOf(mesh.quads);}
        long faceReferences(){long refs=snapshot.size();if(value!=null)for(FaceList faces:value.faces.values())refs+=faces.faces.size();return refs;}
    }
    private static final class Variant {
        final InputKey key;final GeometryJobs.Slot<Mesh> slot=new GeometryJobs.Slot<>();Mesh mesh;PointGpu gpu;long lastUse,faceReferences;
        Variant(InputKey key,long lastUse){this.key=key;this.lastUse=lastUse;this.faceReferences=key.faceReferences;}
    }
    private static final class RegionState {
        final LinkedHashMap<InputKey,Variant> variants=new LinkedHashMap<>(16,.75F,true);
    }

    private final boolean beds;
    private final IdentityHashMap<Mesh,SourceEntry> indexed=new IdentityHashMap<>();
    private final Map<Region,RegionState> regions=new LinkedHashMap<>();
    private final IdentityHashMap<Mesh,Variant> variantsByMesh=new IdentityHashMap<>();
    private final Set<Variant> pinned=Collections.newSetFromMap(new IdentityHashMap<>());
    private Set<Mesh> lastInputs=Collections.newSetFromMap(new IdentityHashMap<>());
    private long useClock;
    private Map<Long,Mesh> currentTargets=Map.of();
    private boolean complete=true;
    long builds;

    RegionalRailGeometry(boolean beds){this.beds=beds;}

    /** Return current targets. While any index or union job is pending, keep the previous set intact. */
    List<Mesh> update(List<Mesh> meshes){
        var sources=new ArrayList<SourceEntry>(meshes.size());Set<Mesh> currentInputs=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Mesh mesh:meshes){currentInputs.add(mesh);SourceEntry source=indexed.computeIfAbsent(mesh,SourceEntry::new);source.lastUse=++useClock;sources.add(source);}
        boolean inputsChanged=!sameIdentitySet(lastInputs,currentInputs);if(inputsChanged){lastInputs=currentInputs;pruneSources(currentInputs);}

        var byRegion=new LinkedHashMap<Region,List<FaceList>>();boolean pendingIndex=false;
        for(SourceEntry source:sources){
            IndexedSource result=source.value!=null?source.value:source.slot.get(()->index(source.snapshot));
            if(result==null){pendingIndex=true;continue;}
            source.value=result;
            result.faces.forEach((region,faces)->byRegion.computeIfAbsent(region,key->new ArrayList<>()).add(faces));
        }
        if(pendingIndex){complete=false;return currentTargets.values().stream().toList();}

        var desired=new LinkedHashMap<Long,Mesh>();var activeVariants=Collections.newSetFromMap(new IdentityHashMap<Variant,Boolean>());boolean pendingBuild=false,variantCreated=false;
        for(var entry:byRegion.entrySet()){
            Region region=entry.getKey();InputKey key=new InputKey(entry.getValue());RegionState state=regions.computeIfAbsent(region,k->new RegionState());
            Variant variant=state.variants.get(key);
            if(variant==null){variant=new Variant(key,++useClock);state.variants.put(key,variant);builds++;variantCreated=true;}
            Mesh result=variant.mesh!=null?variant.mesh:variant.slot.get(()->build(region,key));
            if(result==null){pendingBuild=true;continue;}
            variant.mesh=result;variant.faceReferences=key.faceReferences+result.quads.size();variant.lastUse=++useClock;variantsByMesh.put(result,variant);
            if(!result.quads.isEmpty()){desired.put(region.key(),result);activeVariants.add(variant);}
        }
        if(pendingBuild){complete=false;return currentTargets.values().stream().toList();}

        boolean targetsChanged=!sameTargets(currentTargets,desired);currentTargets=Collections.unmodifiableMap(desired);complete=true;
        if(variantCreated||targetsChanged)pruneInactive(activeVariants);
        return currentTargets.values().stream().toList();
    }

    private IndexedSource index(List<Mesh.Quad> snapshot){
        var faces=new LinkedHashMap<Region,List<Mesh.Quad>>();
        // A halo includes both owners of edges within the existing 2 cm seam limit.
        double halo=beds?0:.04;
        for(var q:snapshot){
            if(!beds&&!RailJoints.seamSurface(q.part()))continue;
            double x0=Math.min(Math.min(q.a().x(),q.b().x()),Math.min(q.c().x(),q.d().x()));
            double x1=Math.max(Math.max(q.a().x(),q.b().x()),Math.max(q.c().x(),q.d().x()));
            double z0=Math.min(Math.min(q.a().z(),q.b().z()),Math.min(q.c().z(),q.d().z()));
            double z1=Math.max(Math.max(q.a().z(),q.b().z()),Math.max(q.c().z(),q.d().z()));
            for(int x=(int)Math.floor((x0-halo)/SIZE);x<=(int)Math.floor((x1+halo)/SIZE);x++)
                for(int z=(int)Math.floor((z0-halo)/SIZE);z<=(int)Math.floor((z1+halo)/SIZE);z++)
                    faces.computeIfAbsent(new Region(x,z),k->new ArrayList<>()).add(q);
        }
        var immutable=new LinkedHashMap<Region,FaceList>();faces.forEach((region,list)->immutable.put(region,new FaceList(list)));
        return new IndexedSource(Collections.unmodifiableMap(immutable));
    }

    private Mesh build(Region region,InputKey key){
        Mesh input=new Mesh();for(FaceList faces:key.sources)input.quads.addAll(faces.faces);
        Mesh built=beds?clip(SurfaceUnion.build(input),region):RailJoints.bridges(input,true);
        if(!beds)built.quads.removeIf(q->!Region.of(q.center()).equals(region));
        return built;
    }

    private record VariantEviction(RegionState state,InputKey key,Variant variant){}
    private void pruneInactive(Set<Variant> active){
        List<VariantEviction> candidates=new ArrayList<>();Map<RegionState,Integer> inactiveCounts=new IdentityHashMap<>();long inactiveRefs=0;
        for(RegionState state:regions.values())for(var entry:state.variants.entrySet()){
            Variant variant=entry.getValue();if(active.contains(variant)||pinned.contains(variant))continue;
            candidates.add(new VariantEviction(state,entry.getKey(),variant));inactiveCounts.merge(state,1,Integer::sum);inactiveRefs+=variant.faceReferences;
        }
        candidates.sort(Comparator.comparingLong(candidate->candidate.variant.lastUse));
        for(VariantEviction candidate:candidates){
            int local=inactiveCounts.getOrDefault(candidate.state,0);
            if(inactiveRefs<=MAX_INACTIVE_VARIANT_FACE_REFS&&local<=MAX_INACTIVE_VARIANTS)break;
            Variant variant=candidate.variant;
            if(candidate.state.variants.remove(candidate.key)!=null){inactiveRefs-=variant.faceReferences;inactiveCounts.put(candidate.state,local-1);release(variant);}
        }
    }
    private void pruneSources(Set<Mesh> currentInputs){
        List<Map.Entry<Mesh,SourceEntry>> candidates=new ArrayList<>();long inactiveRefs=0;
        for(var entry:indexed.entrySet()){
            SourceEntry source=entry.getValue();if(currentInputs.contains(entry.getKey()))continue;
            inactiveRefs+=source.faceReferences();candidates.add(entry);
        }
        candidates.sort(Comparator.comparingLong(entry->entry.getValue().lastUse));
        int inactiveCount=candidates.size();
        for(var candidate:candidates){if(inactiveRefs<=MAX_INACTIVE_SOURCE_FACE_REFS&&inactiveCount<=MAX_INACTIVE_SOURCE_ENTRIES)break;SourceEntry source=indexed.remove(candidate.getKey());if(source!=null){inactiveRefs-=source.faceReferences();inactiveCount--;source.slot.clear();}}
    }
    private static boolean sameIdentitySet(Set<Mesh> left,Set<Mesh> right){if(left.size()!=right.size())return false;for(Mesh mesh:left)if(!right.contains(mesh))return false;return true;}
    private static boolean sameTargets(Map<Long,Mesh> left,Map<Long,Mesh> right){if(left.size()!=right.size())return false;for(var entry:left.entrySet())if(right.get(entry.getKey())!=entry.getValue())return false;return true;}

    Map<Long,Mesh> targets(){return currentTargets;}
    boolean complete(){return complete;}
    void pin(Collection<Mesh> meshes){
        Set<Variant> next=Collections.newSetFromMap(new IdentityHashMap<>());for(Mesh mesh:meshes){Variant variant=variantsByMesh.get(mesh);if(variant!=null)next.add(variant);}
        if(next.equals(pinned))return;pinned.clear();pinned.addAll(next);for(Variant variant:pinned)variant.lastUse=++useClock;
        Set<Variant> active=Collections.newSetFromMap(new IdentityHashMap<>());for(Mesh mesh:currentTargets.values()){Variant variant=variantsByMesh.get(mesh);if(variant!=null)active.add(variant);}pruneInactive(active);
    }
    PointGpu gpu(Mesh mesh){
        Variant variant=variantsByMesh.get(mesh);if(variant==null)throw new IllegalArgumentException("Mesh is not a retained regional target");
        if(variant.gpu==null)variant.gpu=new PointGpu();return variant.gpu;
    }
    void prepare(RenderLevelStageEvent event){
        for(Mesh mesh:currentTargets.values()){
            PointGpu gpu=gpu(mesh);if(!gpu.visible(event,mesh,false))continue;
            gpu.update(mesh,0,false,false);
        }
    }
    void drawMesh(RenderLevelStageEvent event,Mesh mesh){
        Variant variant=variantsByMesh.get(mesh);if(variant==null||variant.mesh!=mesh||variant.gpu==null)return;
        PointGpu gpu=variant.gpu;if(gpu.readyForDraw()&&gpu.hasSource(mesh))gpu.draw(event);
    }
    void draw(RenderLevelStageEvent event){prepare(event);for(Mesh mesh:currentTargets.values())drawMesh(event,mesh);}

    void removeRegion(int x,int z){
        Region region=new Region(x,z);RegionState state=regions.remove(region);if(state!=null)for(Variant variant:state.variants.values())release(variant);
        var targets=new LinkedHashMap<>(currentTargets);targets.remove(region.key());currentTargets=Collections.unmodifiableMap(targets);
    }
    void retainRegions(LongPredicate resident){
        for(Region region:new ArrayList<>(regions.keySet()))if(!resident.test(region.key()))removeRegion(region.x,region.z);
        var sourceIterator=indexed.values().iterator();while(sourceIterator.hasNext()){SourceEntry source=sourceIterator.next();
            if(source.slot.pending())continue;
            if(source.value==null||source.value.faces.keySet().stream().anyMatch(region->resident.test(region.key())))continue;
            source.slot.clear();sourceIterator.remove();
        }
    }
    private void release(Variant variant){pinned.remove(variant);if(variant.mesh!=null&&variantsByMesh.get(variant.mesh)==variant)variantsByMesh.remove(variant.mesh);if(variant.gpu!=null)variant.gpu.close();variant.slot.clear();}

    private static Mesh clip(Mesh input,Region r){
        V3[] origins={new V3(r.x*SIZE,0,0),new V3((r.x+1)*SIZE,0,0),new V3(0,0,r.z*SIZE),new V3(0,0,(r.z+1)*SIZE)};
        V3[] normals={new V3(-1,0,0),new V3(1,0,0),new V3(0,0,-1),new V3(0,0,1)};
        for(int i=0;i<4;i++){
            Mesh next=new Mesh();
            for(var q:input.quads){
                double a=q.a().sub(origins[i]).dot(normals[i]),b=q.b().sub(origins[i]).dot(normals[i]),c=q.c().sub(origins[i]).dot(normals[i]),d=q.d().sub(origins[i]).dot(normals[i]);
                if(Math.max(Math.max(a,b),Math.max(c,d))<=0)next.quad(q);
                else if(Math.min(Math.min(a,b),Math.min(c,d))<=0)Mesh.clip(next,q,origins[i],normals[i]);
            }
            input=next;
        }
        // A vertical face lying exactly on a partition plane belongs to just one region.
        input.quads.removeIf(q->!Region.of(q.center()).equals(r));
        return input;
    }

    void clear(){
        for(RegionState state:regions.values())for(Variant variant:state.variants.values())release(variant);
        for(SourceEntry source:indexed.values())source.slot.clear();
        regions.clear();indexed.clear();variantsByMesh.clear();pinned.clear();lastInputs.clear();currentTargets=Map.of();complete=true;builds=0;useClock=0;
    }
}
