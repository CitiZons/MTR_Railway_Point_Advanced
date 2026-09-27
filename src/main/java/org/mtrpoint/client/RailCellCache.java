package org.mtrpoint.client;

import org.mtrpoint.geometry.*;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import java.util.*;

/** Small persistent world batches; unchanged frames allocate no transformed vertices or GPU uploads. */
final class RailCellCache {
    private record Chunk(int x,int y,int z){}
    private record Cell(List<Mesh.Quad> faces,RailSweep frame,boolean flip,double offset,double zMin,double zMax,V3 shift,boolean swept){}
    private static final Map<Chunk,List<Cell>> QUEUED=new LinkedHashMap<>();
    private static final Map<Chunk,Batch> CACHED=new HashMap<>();
    private static final class Batch {List<Cell> cells=List.of();Mesh mesh;PointGpu gpu=new PointGpu();long seen;}
    private static final List<Batch> ACTIVE=new ArrayList<>();
    private static long frame;
    static long builds,transformedVertices;
    static void submit(List<Mesh.Quad> faces,RailSweep sweep,boolean flip,double offset,double zMin,double zMax,V3 shift,boolean swept){
        if(faces.isEmpty())return;V3 p=sweep.a().lerp(sweep.b(),.5);
        Chunk chunk=new Chunk((int)Math.floor(p.x()/8),(int)Math.floor(p.y()/8),(int)Math.floor(p.z()/8));
        QUEUED.computeIfAbsent(chunk,k->new ArrayList<>()).add(new Cell(faces,sweep,flip,offset,zMin,zMax,shift,swept));
    }
    static void discard(){QUEUED.clear();ACTIVE.clear();}
    static void clear(){CACHED.values().forEach(b->b.gpu.close());CACHED.clear();discard();builds=transformedVertices=0;}
    static void finish(){
        frame++;ACTIVE.clear();
        for(var entry:QUEUED.entrySet()){
            Batch batch=CACHED.computeIfAbsent(entry.getKey(),k->new Batch());batch.seen=frame;
            if(!batch.cells.equals(entry.getValue())){
                batch.cells=List.copyOf(entry.getValue());Mesh mesh=new Mesh();
                for(Cell cell:batch.cells){
                    Map<V3,V3> mapped=new HashMap<>();java.util.function.Function<V3,V3> transform=p->mapped.computeIfAbsent(p,v->{transformedVertices++;return (cell.swept?cell.frame.model(v,cell.flip,cell.offset,cell.zMin,cell.zMax):cell.frame.rigid(v,cell.flip,cell.offset)).add(cell.shift);});
                    for(var q:cell.faces)mesh.quad(new Mesh.Quad(transform.apply(q.a()),transform.apply(q.b()),transform.apply(q.c()),transform.apply(q.d()),q.surface(),q.part(),-1,q.uv()));
                }
                batch.mesh=mesh;builds++;
            }
            ACTIVE.add(batch);
        }
        QUEUED.clear();
        for(var it=CACHED.values().iterator();it.hasNext();){var b=it.next();if(frame-b.seen>120){b.gpu.close();it.remove();}}
    }
    static void draw(RenderLevelStageEvent e){for(var b:ACTIVE)if(b.gpu.visible(e)){b.gpu.update(b.mesh,0,false,false);b.gpu.draw(e);}}
}
