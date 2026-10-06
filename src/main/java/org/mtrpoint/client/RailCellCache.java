package org.mtrpoint.client;

import org.mtrpoint.geometry.*;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import java.util.*;

/** Small persistent world batches; unchanged frames allocate no transformed vertices or GPU uploads. */
final class RailCellCache {
    private static final boolean PROFILE=Boolean.getBoolean("mtrpoint.profile");
    private static long profileSince,profileFinishNanos,profileBedNanos,profileJointNanos,profileRebuilds,profileBedRebuilds,profileJointRebuilds;
    private record Chunk(int x,int y,int z){}
    // A shared batch lets diverging beds union even across the ordinary 8 m
    // cache boundary. It rebuilds only when the submitted visible cells change.
    private static final Chunk BEDS=new Chunk(Integer.MIN_VALUE,0,0);
    private record Cell(List<Mesh.Quad> faces,RailSweep frame,boolean flip,double offset,double zMin,double zMax,V3 shift,boolean swept,double inset,double running,double guard,boolean shared){}
    private static final Map<Chunk,List<Cell>> QUEUED=new LinkedHashMap<>();
    record FaceSplit(List<Mesh.Quad> beds,List<Mesh.Quad> other) {}
    private static final IdentityHashMap<List<Mesh.Quad>,FaceSplit> SPLITS=new IdentityHashMap<>();
    private static int splitFaces;
    private static final int MAX_SPLITS=256,MAX_SPLIT_FACES=65536;
    private static final Map<Chunk,Batch> CACHED=new HashMap<>();
    private static final class Batch {List<Cell> cells=List.of();Mesh mesh;PointGpu gpu=new PointGpu();long seenAt;}
    private static final List<Batch> ACTIVE=new ArrayList<>();
    private static final Batch JOINTS=new Batch();
    private static List<Mesh> jointSources=List.of();
    private static long frame;
    static void begin(){for(var cells:QUEUED.values())cells.clear();}
    static int splitCount(){return SPLITS.size();}
    static int splitFaceCount(){return splitFaces;}
    static int activeCount(){return ACTIVE.size();}
    static long builds,transformedVertices;
    static String worldPart(Mesh.Quad q){
        if(q.part().equals("ballast")&&Math.max(Math.max(q.a().z(),q.b().z()),Math.max(q.c().z(),q.d().z()))-Math.min(Math.min(q.a().z(),q.b().z()),Math.min(q.c().z(),q.d().z()))<1e-7)return "ballast_end";
        return q.part();
    }
    static void submit(List<Mesh.Quad> faces,RailSweep sweep,boolean flip,double offset,double zMin,double zMax,V3 shift,boolean swept){
        if(faces.isEmpty())return;double px=(sweep.a().x()+sweep.b().x())*.5,py=(sweep.a().y()+sweep.b().y())*.5,pz=(sweep.a().z()+sweep.b().z())*.5;
        Chunk chunk=new Chunk((int)Math.floor(px/8),(int)Math.floor(py/8),(int)Math.floor(pz/8));
        var split=split(faces);
        if(!split.beds.isEmpty())QUEUED.computeIfAbsent(BEDS,k->new ArrayList<>()).add(new Cell(split.beds,sweep,flip,offset,zMin,zMax,shift,swept,0,0,0,false));
        if(!split.other.isEmpty())QUEUED.computeIfAbsent(chunk,k->new ArrayList<>()).add(new Cell(split.other,sweep,flip,offset,zMin,zMax,shift,swept,0,0,0,false));
    }
    static void submitGuard(List<Mesh.Quad> faces,RailSweep sweep,boolean flip,double offset,double zMin,double zMax,V3 shift,double inset,double running,double guard,boolean shared){
        if(faces.isEmpty())return;double px=(sweep.a().x()+sweep.b().x())*.5,py=(sweep.a().y()+sweep.b().y())*.5,pz=(sweep.a().z()+sweep.b().z())*.5;Chunk chunk=new Chunk((int)Math.floor(px/8),(int)Math.floor(py/8),(int)Math.floor(pz/8));
        QUEUED.computeIfAbsent(chunk,k->new ArrayList<>()).add(new Cell(faces,sweep,flip,offset,zMin,zMax,shift,false,inset,running,guard,shared));
    }
    static void discard(){QUEUED.clear();ACTIVE.clear();}
    static void submitWorldBeds(List<Mesh.Quad> faces){
        if(!faces.isEmpty())QUEUED.computeIfAbsent(BEDS,k->new ArrayList<>()).add(new Cell(faces,null,false,0,0,0,V3.ZERO,false,0,0,0,false));
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
    static void clear(){CACHED.values().forEach(b->b.gpu.close());CACHED.clear();JOINTS.gpu.close();JOINTS.mesh=null;jointSources=List.of();SPLITS.clear();splitFaces=0;discard();builds=transformedVertices=0;}
    static void finish(Mesh turnoutSteel){
        frame++;ACTIVE.clear();long now=System.nanoTime(),started=now;long before=builds;
        for(var entry:QUEUED.entrySet()){
            if(entry.getValue().isEmpty())continue;
            Batch batch=CACHED.computeIfAbsent(entry.getKey(),k->new Batch());batch.seenAt=now;
            if(!batch.cells.equals(entry.getValue())){
                batch.cells=List.copyOf(entry.getValue());Mesh mesh=new Mesh();
                for(Cell cell:batch.cells){
                    Map<V3,V3> mapped=new HashMap<>();java.util.function.Function<V3,V3> transform=p->mapped.computeIfAbsent(p,v->{transformedVertices++;double x=v.x();
                        if(cell.inset>0){double sign=Math.signum(x),weight=cell.shared?Math.max(0,Math.min(1,(cell.running-Math.abs(x))/Math.max(1e-6,cell.running-cell.guard))):1;x-=sign*cell.inset*weight;}
                        V3 local=new V3(x,v.y(),v.z());return cell.frame==null?local:(cell.swept?cell.frame.model(local,cell.flip,cell.offset,cell.zMin,cell.zMax):cell.frame.rigid(local,cell.flip,cell.offset)).add(cell.shift);});
                    for(var q:cell.faces){
                        // Ballast has rendered transverse end caps. Keep drawing them, but
                        // do not let them hide the longitudinal surface boundary from joining.
                        mesh.quad(new Mesh.Quad(transform.apply(q.a()),transform.apply(q.b()),transform.apply(q.c()),transform.apply(q.d()),q.surface(),worldPart(q),-1,q.uv()));
                    }
                }
                if(entry.getKey().equals(BEDS)){
                    long t=System.nanoTime();batch.mesh=SurfaceUnion.build(mesh);if(PROFILE){profileBedNanos+=System.nanoTime()-t;profileBedRebuilds++;}
                }else batch.mesh=mesh;
                builds++;
            }
            ACTIVE.add(batch);
        }
        // Keep the per-chunk lists for the next render pass. The rail renderer submits the
        // same cells every frame; retaining these containers avoids a large transient allocation
        // spike while the cell contents are replaced in-place on the next submit.
        QUEUED.entrySet().removeIf(entry->entry.getValue().isEmpty());
        // Use the short-lived cache for seam generation. The native renderer may
        // omit cells from this frame's queue while the camera rotates; using ACTIVE
        // here would rebuild every bridge set on each visibility change.
        var sources=new ArrayList<Mesh>();sources.add(turnoutSteel);for(var batch:CACHED.values())if(batch.mesh!=null)sources.add(batch.mesh);
        if(!sources.equals(jointSources)){
            jointSources=List.copyOf(sources);Mesh steel=new Mesh();long t=System.nanoTime();
            for(var source:sources)for(var q:source.quads)if(RailJoints.seamSurface(q.part()))steel.quad(q);
            JOINTS.mesh=RailJoints.bridges(steel,true);
            if(PROFILE){profileJointNanos+=System.nanoTime()-t;profileJointRebuilds++;}
        }
        if(JOINTS.mesh!=null&&!JOINTS.mesh.quads.isEmpty())ACTIVE.add(JOINTS);
        for(var it=CACHED.values().iterator();it.hasNext();){var b=it.next();if(now-b.seenAt>3_000_000_000L){b.gpu.close();it.remove();}}
        if(PROFILE){
            if(profileSince==0)profileSince=now;profileFinishNanos+=System.nanoTime()-started;profileRebuilds+=builds-before;
            if(now-profileSince>=1_000_000_000L){
                org.mtrpoint.PointMod.LOG.info("Point profile: finish={}ms rebuilds={} beds={}ms/{} joints={}ms/{}",profileFinishNanos/1_000_000D,profileRebuilds,profileBedNanos/1_000_000D,profileBedRebuilds,profileJointNanos/1_000_000D,profileJointRebuilds);
                profileSince=now;profileFinishNanos=profileBedNanos=profileJointNanos=profileRebuilds=profileBedRebuilds=profileJointRebuilds=0;
            }
        }
    }
    static void draw(RenderLevelStageEvent e){
        for(var b:CACHED.values())if(b.mesh!=null&&b.gpu.visible(e)){b.gpu.update(b.mesh,0,false,false);b.gpu.draw(e);}
        if(JOINTS.mesh!=null&&!JOINTS.mesh.quads.isEmpty()&&JOINTS.gpu.visible(e)){JOINTS.gpu.update(JOINTS.mesh,0,false,false);JOINTS.gpu.draw(e);}
    }
}
