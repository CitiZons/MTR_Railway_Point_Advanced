package org.mtrpoint.client;

import com.mojang.blaze3d.vertex.*;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.mtrpoint.geometry.*;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.*;

/** Persistent GPU geometry: stationary turnouts submit draw calls, not every vertex. */
final class PointGpu implements AutoCloseable {
    private record Batch(VertexBuffer buffer,RenderType type,int lod,V3 center,int faces){}
    private record Group(String texture,int lod,int x,int y,int z,boolean moving,int slot){}
    private record Cached(Batch batch,long fingerprint,V3 origin){}
    private record DrawTicket(Batch batch,Matrix4f pose,Matrix4f projection){}
    private static final class TypeDraws {final RenderType type;final List<DrawTicket> tickets=new ArrayList<>();TypeDraws(RenderType type){this.type=type;}}
    private static final class Pending {
        final Group group;final List<Integer> ids;final V3 origin;final boolean moving;int cursor;long fingerprint=0xcbf29ce484222325L;boolean fingerprintReady;
        Pending(Group group,List<Integer> ids,V3 origin,boolean moving){this.group=group;this.ids=ids;this.origin=origin;this.moving=moving;}
    }
    private record IdTask(List<Integer> ids,boolean moving){}
    private static final class PlanWork {
        final List<IdTask> tasks;final LinkedHashMap<Group,List<Integer>> groups=new LinkedHashMap<>();int taskIndex,cursor;
        PlanWork(List<IdTask> tasks){this.tasks=tasks;}
    }
    private record PreparedGeometry(AABB bounds,V3 origin,List<Integer> fixedIds,List<Integer> movingIds,List<Integer> frogIds,Set<Long> lightCells){}
    private static final class GeometryEntry {
        final Mesh mesh;final boolean removeGuards;final List<Mesh.Quad> snapshot;final GeometryJobs.Slot<PreparedGeometry> slot=new GeometryJobs.Slot<>();private PreparedGeometry prepared;
        GeometryEntry(Mesh mesh,boolean removeGuards){this.mesh=mesh;this.removeGuards=removeGuards;this.snapshot=List.copyOf(mesh.quads);}
        PreparedGeometry poll(boolean priority,boolean background){if(prepared!=null)return prepared;prepared=priority?slot.getPriority(()->prepareGeometry(snapshot,removeGuards)):background?slot.getBackground(()->prepareGeometry(snapshot,removeGuards)):slot.get(()->prepareGeometry(snapshot,removeGuards));return prepared;}
    }
    private static final int MAX_FACES_PER_BATCH=512;
    private static final long UPLOAD_BYTES_PER_FRAME=2L*1024*1024;
    private static final int UPLOAD_BATCHES_PER_FRAME=8;
    private static final int PLAN_FACES_PER_FRAME=8192,PLAN_FACES_PER_GPU_TURN=512;
    private static final long UPLOAD_TIME_NANOS_PER_FRAME=3_000_000L;
    static final class UploadBudget {
        private long bytes,spentNanos;private int batches,plannedFaces;
        void beginFrame(){bytes=0;batches=0;spentNanos=0;plannedFaces=0;}
        boolean reserve(long amount){if(batches>=UPLOAD_BATCHES_PER_FRAME||bytes+amount>UPLOAD_BYTES_PER_FRAME||spentNanos>=UPLOAD_TIME_NANOS_PER_FRAME)return false;bytes+=amount;batches++;return true;}
        void recordTime(long elapsedNanos){spentNanos+=Math.max(0,elapsedNanos);}
        int planAllowance(int requested){int count=Math.min(requested,PLAN_FACES_PER_FRAME-plannedFaces);plannedFaces+=count;return count;}
        long bytes(){return bytes;}int batches(){return batches;}
    }
    private static final UploadBudget UPLOAD_BUDGET=new UploadBudget();
    private static boolean priorityQueueTurn=true;
    static void beginFrame(){DRAW_TICKETS.clear();drainRetiredBuffers();UPLOAD_BUDGET.beginFrame();}
    static void flushUploads(){
        int stalled=0;
        while(hasQueuedUploads()&&stalled<queuedUploadCount()){
            PointGpu gpu=nextQueuedUpload();gpu.queuedForUpload=false;
            boolean progressed=false;
            if(gpu.planWork!=null){int allowance=UPLOAD_BUDGET.planAllowance(PLAN_FACES_PER_GPU_TURN);if(allowance>0){gpu.advancePlan(allowance);progressed=true;}}
            if(gpu.planWork==null&&!gpu.pending.isEmpty()){
                Pending task=gpu.pending.peekFirst();
                if(!task.fingerprintReady){int allowance=UPLOAD_BUDGET.planAllowance(Math.min(PLAN_FACES_PER_GPU_TURN,task.ids.size()-task.cursor));if(allowance>0){gpu.fingerprint(task,allowance);progressed=true;}}
                if(task.fingerprintReady){
                    Map<Group,Cached> cache=task.moving?gpu.movingCache:gpu.fixedCache;Cached cached=cache.get(task.group);
                    if(cached!=null&&cached.fingerprint==task.fingerprint){gpu.pending.removeFirst();(task.moving?gpu.nextMoving:gpu.nextFixed).add(cached.batch);progressed=true;}
                    else{
                        long bytes=(long)task.ids.size()*4*36;
                        if(UPLOAD_BUDGET.reserve(bytes)){
                            gpu.pending.removeFirst();long started=System.nanoTime();Batch batch;
                            try{batch=gpu.uploadOne(task);}finally{UPLOAD_BUDGET.recordTime(System.nanoTime()-started);}
                            Cached previous=cache.put(task.group,new Cached(batch,task.fingerprint,task.origin));if(previous!=null)gpu.retireAfterCommit.add(previous.batch);gpu.batchOrigins.put(batch,task.origin);
                            (task.moving?gpu.nextMoving:gpu.nextFixed).add(batch);progressed=true;
                        }
                    }
                }
            }
            if(gpu.planWork==null&&gpu.pending.isEmpty()&&!gpu.ready)gpu.finishPlan();
            if(gpu.hasWork())gpu.queueUploads(false);
            if(progressed)stalled=0;else stalled++;
        }
    }
    // BufferBuilder owns native memory; reuse one staging buffer instead of allocating per throw.
    private static final BufferBuilder STAGING=new BufferBuilder(262144);
    private static final ArrayDeque<PointGpu> UPLOAD_QUEUE=new ArrayDeque<>(),PRIORITY_UPLOAD_QUEUE=new ArrayDeque<>(),BACKGROUND_UPLOAD_QUEUE=new ArrayDeque<>();
    private static final List<DrawTicket> DRAW_TICKETS=new ArrayList<>();
    private static final ArrayDeque<VertexBuffer> RETIRED_BUFFERS=new ArrayDeque<>();
    private static final Set<VertexBuffer> RETIRED_BUFFER_SET=Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<Batch> fixed=new ArrayList<>(),moving=new ArrayList<>();
    private final List<Batch> orderedFixed=new ArrayList<>(),orderedMoving=new ArrayList<>();
    private final List<Integer> staticIds=new ArrayList<>(),movingIds=new ArrayList<>();
    private Mesh source,preparedSource;private boolean preparedRemoveGuards,preparedMovable,requestedRemoveGuards,requestedMovable;private GeometryEntry geometryEntry;private double frame;private long lightTick=-100;private V3 origin;private AABB bounds;
    private final Map<Group,Cached> fixedCache=new LinkedHashMap<>(16,.75F,true),movingCache=new LinkedHashMap<>();
    private final Map<Batch,V3> batchOrigins=new IdentityHashMap<>();
    private final ArrayDeque<Pending> pending=new ArrayDeque<>();
    private PlanWork planWork;
    private final List<Batch> nextFixed=new ArrayList<>(),nextMoving=new ArrayList<>();
    private boolean ready=true;
    private boolean queuedForUpload;
    private boolean priority,background;
    private boolean committed;
    private Mesh committedSource;
    private boolean committedRemoveGuards,committedMovable;
    private final List<Batch> retireAfterCommit=new ArrayList<>();
    /** Moving quads are replaced in-place by animated views; each plan must see one pose. */
    private Map<Integer,Mesh.Quad> plannedMoving=Map.of();
    private boolean lightRefreshDeferred,movingRefreshDeferred;
    private final Long2IntOpenHashMap bakedLight=new Long2IntOpenHashMap();
    static long uploads,draws,vertices;
    static long submittedFaces;
    static final long[] lodDraws=new long[3];
    private static final org.joml.Vector3f WORLD_LIGHT0=new org.joml.Vector3f(.2F,1,-.7F).normalize();
    private static final org.joml.Vector3f WORLD_LIGHT1=new org.joml.Vector3f(-.2F,1,.7F).normalize();
    private static final org.joml.Vector3f PREVIOUS_LIGHT0=new org.joml.Vector3f();
    private static final org.joml.Vector3f PREVIOUS_LIGHT1=new org.joml.Vector3f();
    /** Parts the per-view draw hides because the shared component assembly owns them instead. */
    static boolean hiddenInView(Mesh.Quad q){
        String part=q.part();return part.equals("guard")||part.equals("sleeper")||part.equals("track_bed")||part.startsWith("fastener")||part.equals("wing");
    }
    void update(Mesh mesh,double position,boolean movable,boolean removeGuards){
        boolean changed=source!=mesh||requestedRemoveGuards!=removeGuards||requestedMovable!=movable;
        if(changed){
            // Pending indices and pose snapshots belong to the old source. They must never
            // reach the upload queue while metadata for a replacement mesh is still pending.
            UPLOAD_QUEUE.remove(this);PRIORITY_UPLOAD_QUEUE.remove(this);BACKGROUND_UPLOAD_QUEUE.remove(this);queuedForUpload=false;pending.clear();planWork=null;
            plannedMoving=Map.of();nextFixed.clear();nextMoving.clear();
            lightRefreshDeferred=false;movingRefreshDeferred=false;
            source=mesh;requestedRemoveGuards=removeGuards;requestedMovable=movable;ready=false;
        }
        GeometryEntry geometry=geometry(mesh,removeGuards);PreparedGeometry prepared=geometry.poll(priority,background);
        if(prepared==null)return;
        if(preparedSource!=mesh||preparedRemoveGuards!=removeGuards||preparedMovable!=movable){
            preparedSource=mesh;preparedRemoveGuards=removeGuards;preparedMovable=movable;origin=prepared.origin;bounds=prepared.bounds;
            staticIds.clear();staticIds.addAll(prepared.fixedIds);movingIds.clear();movingIds.addAll(prepared.movingIds);if(movable){movingIds.addAll(prepared.frogIds);}else{staticIds.addAll(prepared.frogIds);}
            bakedLight.keySet().removeIf(key->!prepared.lightCells.contains(key));frame=position;plan(staticIds,movingIds);
        }
        var mc=Minecraft.getInstance();long tick=mc.level.getGameTime();
        // Block light updates are infrequent; sky brightness itself is applied by the lightmap.
        boolean light=false;
        if(tick-lightTick>=20){for(var entry:bakedLight.long2IntEntrySet()){int value=LevelRenderer.getLightColor(mc.level,BlockPos.of(entry.getLongKey()));if(value!=entry.getIntValue()){entry.setValue(value);light=true;}}lightTick=tick;}
        if(light&&!changed){if(ready)plan(staticIds,movingIds);else lightRefreshDeferred=true;}
        if(ready){resort(fixed,orderedFixed);resort(moving,orderedMoving);}
        if(frame!=position){if(ready){planMoving(position);if(ready)resort(moving,orderedMoving);}else movingRefreshDeferred=true;}
        if(changed)lightTick=tick-(Math.abs(System.identityHashCode(this))%20);frame=position;
    }
    private void plan(List<Integer> fixedIds,List<Integer> movingIds){
        // Keep old buffers alive while a replacement set is prepared. Rendering is gated by
        // ready, so an edited source can never accidentally display a stale buffer.
        pending.clear();planWork=null;nextFixed.clear();nextMoving.clear();ready=false;
        plannedMoving=snapshotMoving(movingIds);
        planWork=new PlanWork(List.of(new IdTask(fixedIds,false),new IdTask(movingIds,true)));
        queueUploads(false);
    }
    private GeometryEntry geometry(Mesh mesh,boolean removeGuards){
        if(geometryEntry==null||geometryEntry.mesh!=mesh||geometryEntry.removeGuards!=removeGuards){
            if(geometryEntry!=null)geometryEntry.slot.clear();geometryEntry=new GeometryEntry(mesh,removeGuards);
        }
        return geometryEntry;
    }
    void setPriority(boolean priority){
        if(this.priority==priority)return;this.priority=priority;
        if(queuedForUpload){UPLOAD_QUEUE.remove(this);PRIORITY_UPLOAD_QUEUE.remove(this);BACKGROUND_UPLOAD_QUEUE.remove(this);queuedForUpload=false;queueUploads(false);}
        if(geometryEntry!=null&&geometryEntry.prepared==null)geometryEntry.poll(priority,background);
    }
    boolean priority(){return priority;}
    void setBackground(boolean background){
        if(this.background==background)return;this.background=background;
        if(queuedForUpload){UPLOAD_QUEUE.remove(this);PRIORITY_UPLOAD_QUEUE.remove(this);BACKGROUND_UPLOAD_QUEUE.remove(this);queuedForUpload=false;queueUploads(false);}
        if(geometryEntry!=null&&geometryEntry.prepared==null)geometryEntry.poll(priority,background);
    }
    boolean background(){return background;}
    private static boolean hasQueuedUploads(){return !PRIORITY_UPLOAD_QUEUE.isEmpty()||!UPLOAD_QUEUE.isEmpty()||!BACKGROUND_UPLOAD_QUEUE.isEmpty();}
    private static int queuedUploadCount(){return PRIORITY_UPLOAD_QUEUE.size()+UPLOAD_QUEUE.size()+BACKGROUND_UPLOAD_QUEUE.size();}
    private static PointGpu nextQueuedUpload(){
        if(PRIORITY_UPLOAD_QUEUE.isEmpty()&&UPLOAD_QUEUE.isEmpty())return BACKGROUND_UPLOAD_QUEUE.removeFirst();
        if(PRIORITY_UPLOAD_QUEUE.isEmpty())return UPLOAD_QUEUE.removeFirst();
        if(UPLOAD_QUEUE.isEmpty())return PRIORITY_UPLOAD_QUEUE.removeFirst();
        if(priorityQueueTurn){priorityQueueTurn=false;return PRIORITY_UPLOAD_QUEUE.removeFirst();}
        priorityQueueTurn=true;return UPLOAD_QUEUE.removeFirst();
    }
    private static PreparedGeometry prepareGeometry(List<Mesh.Quad> quads,boolean removeGuards){
        List<Integer> fixed=new ArrayList<>(),moving=new ArrayList<>(),frogs=new ArrayList<>();Set<Long> lightCells=new HashSet<>();
        V3 first=null;double x0=0,x1=0,y0=0,y1=0,z0=0,z1=0;
        for(int i=0;i<quads.size();i++){
            Mesh.Quad q=quads.get(i);if(removeGuards&&hiddenInView(q))continue;
            V3 center=q.center();lightCells.add(BlockPos.containing(center.x(),center.y()+.3,center.z()).asLong());
            switch(q.part()){case "blade","stretcher"->moving.add(i);case "frog"->frogs.add(i);default->fixed.add(i);}
            for(V3 v:List.of(q.a(),q.b(),q.c(),q.d())){if(first==null){first=v;x0=x1=v.x();y0=y1=v.y();z0=z1=v.z();}else{x0=Math.min(x0,v.x());x1=Math.max(x1,v.x());y0=Math.min(y0,v.y());y1=Math.max(y1,v.y());z0=Math.min(z0,v.z());z1=Math.max(z1,v.z());}}
        }
        AABB box=first==null?null:new AABB(x0-.5,y0-.5,z0-.5,x1+.5,y1+.5,z1+.5);
        return new PreparedGeometry(box,first==null?new V3(0,0,0):first,List.copyOf(fixed),List.copyOf(moving),List.copyOf(frogs),Set.copyOf(lightCells));
    }
    private void planMoving(double position){
        // Moving faces are intentionally rebuilt as their vertices change; fixed subbatches stay cached.
        frame=position;
        if(movingIds.isEmpty())return;
        pending.clear();planWork=null;nextFixed.clear();nextFixed.addAll(fixed);nextMoving.clear();ready=false;
        plannedMoving=snapshotMoving(movingIds);
        planWork=new PlanWork(List.of(new IdTask(movingIds,true)));
        queueUploads(false);
    }
    private Map<Integer,Mesh.Quad> snapshotMoving(List<Integer> ids){
        Map<Integer,Mesh.Quad> snapshot=new HashMap<>(ids.size()*2);
        for(int id:ids)snapshot.put(id,source.quads.get(id));
        return snapshot;
    }
    private Mesh.Quad plannedQuad(int id,boolean movingFace){return movingFace?plannedMoving.get(id):source.quads.get(id);}
    private void advancePlan(int maximum){
        int remaining=maximum;
        while(remaining>0&&planWork!=null){
            if(planWork.taskIndex>=planWork.tasks.size()){materializePlan();planWork=null;if(pending.isEmpty())finishPlan();return;}
            IdTask task=planWork.tasks.get(planWork.taskIndex);
            if(planWork.cursor>=task.ids.size()){planWork.taskIndex++;planWork.cursor=0;continue;}
            int id=task.ids.get(planWork.cursor++);var q=plannedQuad(id,task.moving);int lod=switch(q.part()){case "fastener_near"->0;case "fastener_mid"->1;case "fastener_far"->2;default->-1;};V3 c=q.center();
            int x=(int)Math.floor(c.x()/4),y=(int)Math.floor(c.y()/4),z=(int)Math.floor(c.z()/4);
            Group base=new Group(q.surface().texture(),lod,x,y,z,task.moving,0);planWork.groups.computeIfAbsent(base,k->new ArrayList<>()).add(id);remaining--;
        }
        if(planWork!=null&&planWork.taskIndex>=planWork.tasks.size()){materializePlan();planWork=null;if(pending.isEmpty())finishPlan();}
    }
    private void materializePlan(){
        for(var entry:planWork.groups.entrySet())for(int start=0,slot=0;start<entry.getValue().size();start+=MAX_FACES_PER_BATCH,slot++){
            Group base=entry.getKey();Group group=new Group(base.texture,base.lod,base.x,base.y,base.z,base.moving,slot);
            List<Integer> ids=entry.getValue().subList(start,Math.min(start+MAX_FACES_PER_BATCH,entry.getValue().size()));
            pending.addLast(new Pending(group,ids,new V3(group.x*4,group.y*4,group.z*4),group.moving));
        }
    }
    private int fingerprint(Pending task,int maximum){
        int count=0;while(count<maximum&&task.cursor<task.ids.size()){
            var q=plannedQuad(task.ids.get(task.cursor++),task.moving);long h=task.fingerprint;h=mix(h,q.part().hashCode());h=mix(h,q.surface().hashCode());
            for(V3 v:List.of(q.a(),q.b(),q.c(),q.d())){h=mix(h,Double.doubleToLongBits(v.x()));h=mix(h,Double.doubleToLongBits(v.y()));h=mix(h,Double.doubleToLongBits(v.z()));}
            if(q.uv()!=null)for(float f:q.uv())h=mix(h,Float.floatToIntBits(f));if(q.normals()!=null)for(V3 v:q.normals()){h=mix(h,Double.doubleToLongBits(v.x()));h=mix(h,Double.doubleToLongBits(v.y()));h=mix(h,Double.doubleToLongBits(v.z()));}
            long key=BlockPos.containing(q.center().x(),q.center().y()+.3,q.center().z()).asLong();h=mix(h,getLight(key));task.fingerprint=h;count++;
        }
        if(task.cursor>=task.ids.size())task.fingerprintReady=true;return count;
    }
    private static long mix(long h,long x){return (h^x)*0x100000001b3L;}
    private int getLight(long key){if(bakedLight.containsKey(key))return bakedLight.get(key);var mc=Minecraft.getInstance();int value=LevelRenderer.getLightColor(mc.level,BlockPos.of(key));bakedLight.put(key,value);return value;}
    private boolean hasWork(){return planWork!=null||!pending.isEmpty();}
    private void queueUploads(boolean front){if(!hasWork()||queuedForUpload)return;ArrayDeque<PointGpu> queue=priority?PRIORITY_UPLOAD_QUEUE:background?BACKGROUND_UPLOAD_QUEUE:UPLOAD_QUEUE;if(front)queue.addFirst(this);else queue.addLast(this);queuedForUpload=true;}
    private void finishPlan(){
        Set<Batch> keep=Collections.newSetFromMap(new IdentityHashMap<>());keep.addAll(nextFixed);keep.addAll(nextMoving);
        movingCache.entrySet().removeIf(e->{if(keep.contains(e.getValue().batch))return false;retireAfterCommit.add(e.getValue().batch);batchOrigins.remove(e.getValue().batch);return true;});
        // Keep absent fixed groups as reusable LOD/source variants. Evict only inactive entries
        // when the cache grows past its practical bound; current draw entries remain pinned.
        if(fixedCache.size()>256){var iterator=fixedCache.entrySet().iterator();while(fixedCache.size()>256&&iterator.hasNext()){var entry=iterator.next();if(keep.contains(entry.getValue().batch))continue;retireAfterCommit.add(entry.getValue().batch);batchOrigins.remove(entry.getValue().batch);iterator.remove();}}
        fixed.clear();fixed.addAll(nextFixed);moving.clear();moving.addAll(nextMoving);ready=true;committed=true;committedSource=source;committedRemoveGuards=preparedRemoveGuards;committedMovable=preparedMovable;
        resort(fixed,orderedFixed);resort(moving,orderedMoving);
        for(Batch batch:retireAfterCommit)if(!keep.contains(batch))retireBuffer(batch.buffer);retireAfterCommit.clear();
        plannedMoving=Map.of();
        if(lightRefreshDeferred){lightRefreshDeferred=false;movingRefreshDeferred=false;plan(staticIds,movingIds);}
        else if(movingRefreshDeferred){movingRefreshDeferred=false;planMoving(frame);}
    }
    private Batch uploadOne(Pending task){
        var group=task.group;
        BufferBuilder b=STAGING;b.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.NEW_ENTITY);
            for(int id:task.ids){
                var q=plannedQuad(id,task.moving);var s=q.surface();
                V3 ab=q.b().sub(q.a()),ac=q.c().sub(q.a());
                V3 n=new V3(ab.y()*ac.z()-ab.z()*ac.y(),ab.z()*ac.x()-ab.x()*ac.z(),ab.x()*ac.y()-ab.y()*ac.x()).unit();
                V3 center=q.center();long key=BlockPos.containing(center.x(),center.y()+.3,center.z()).asLong();
                int light=getLight(key);
                for(int i=0;i<4;i++){
                    V3 v=switch(i){case 0->q.a();case 1->q.b();case 2->q.c();default->q.d();};
                    float u=q.uv()==null?(i==0||i==3?s.u0():s.u1()):q.uv().get(i*2),vv=q.uv()==null?(i<2?s.v0():s.v1()):q.uv().get(i*2+1);
                    // The resource pack intentionally places the bed top on the block
                    // plane. Lift only support faces by a tiny epsilon to win the depth
                    // test against the coplanar world surface and prevent z-fighting.
                    double depthBias=q.part().equals("track_bed")||q.part().equals("track_bed_joint")||q.part().equals("sleeper")||q.part().equals("direct_bearer")?0.00015:0;
                    V3 vertexNormal=q.normals()==null?n:q.normals().get(i);
                    b.vertex(v.x()-task.origin.x(),v.y()-task.origin.y()+depthBias,v.z()-task.origin.z()).color(s.color()).uv(u,vv).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal((float)vertexNormal.x(),(float)vertexNormal.y(),(float)vertexNormal.z()).endVertex();vertices++;
                }
            }
            VertexBuffer gpu=new VertexBuffer(VertexBuffer.Usage.STATIC);gpu.bind();gpu.upload(b.end());VertexBuffer.unbind();
            uploads++;
        return new Batch(gpu,PointRenderType.texture(new ResourceLocation(group.texture)),group.lod,new V3(group.x*4+2,group.y*4+2,group.z*4+2),task.ids.size());
    }
    private static void resort(List<Batch> source,List<Batch> target){
        target.clear();target.addAll(source);target.sort(Comparator.comparingInt(b->System.identityHashCode(b.type)));
    }
    boolean visible(RenderLevelStageEvent e){return bounds==null||e.getFrustum().isVisible(bounds);}
    boolean visible(RenderLevelStageEvent e,Mesh mesh,boolean removeGuards){PreparedGeometry candidate=geometry(mesh,removeGuards).poll(priority,background);return candidate!=null&&(candidate.bounds==null||e.getFrustum().isVisible(candidate.bounds));}
    static AABB computeBounds(Mesh mesh,boolean removeGuards){
        V3 first=null;double x0=0,x1=0,y0=0,y1=0,z0=0,z1=0;
        for(var q:mesh.quads){if(removeGuards&&hiddenInView(q))continue;for(V3 v:List.of(q.a(),q.b(),q.c(),q.d())){if(first==null){first=v;x0=x1=v.x();y0=y1=v.y();z0=z1=v.z();}else{x0=Math.min(x0,v.x());x1=Math.max(x1,v.x());y0=Math.min(y0,v.y());y1=Math.max(y1,v.y());z0=Math.min(z0,v.z());z1=Math.max(z1,v.z());}}}
        return first==null?null:new AABB(x0-.5,y0-.5,z0-.5,x1+.5,y1+.5,z1+.5);
    }
    boolean hasSource(Mesh mesh){return committed&&committedSource==mesh&&source==mesh&&committedRemoveGuards==requestedRemoveGuards&&committedMovable==requestedMovable;}
    boolean ready(){return committed&&committedSource==source&&committedRemoveGuards==requestedRemoveGuards&&committedMovable==requestedMovable;}
    boolean updateInProgress(){return !ready;}
    boolean readyForDraw(){return ready();}
    void draw(RenderLevelStageEvent e){
        if(!readyForDraw()||bounds==null||!e.getFrustum().isVisible(bounds))return;
        var camera=e.getCamera().getPosition();Matrix4f pose=new Matrix4f(e.getPoseStack().last().pose()).translate((float)(origin.x()-camera.x),(float)(origin.y()-camera.y),(float)(origin.z()-camera.z));
        var distances=RailLod.distances();
        queueDraws(orderedFixed,pose,e.getProjectionMatrix(),camera,distances,batchOrigins,origin);queueDraws(orderedMoving,pose,e.getProjectionMatrix(),camera,distances,batchOrigins,origin);
    }
    private static void queueDraws(List<Batch> batches,Matrix4f pose,Matrix4f projection,net.minecraft.world.phys.Vec3 camera,RailDetailDistances distances,Map<Batch,V3> origins,V3 root){
        for(Batch batch:batches){
            if(batch.lod>=0&&RailLod.fittingLevel(batch.center)!=batch.lod)continue;
            V3 local=origins.get(batch);
            Matrix4f batchPose=local==null?pose:new Matrix4f(pose).translate((float)(local.x()-root.x()),(float)(local.y()-root.y()),(float)(local.z()-root.z()));
            DRAW_TICKETS.add(new DrawTicket(batch,batchPose,projection));
        }
    }
    /** Called once after every world-geometry renderer has submitted its batches. */
    static void flushDraws(RenderLevelStageEvent event){
        try{
            List<TypeDraws> grouped=new ArrayList<>();Map<RenderType,TypeDraws> byType=new IdentityHashMap<>();
            for(DrawTicket ticket:DRAW_TICKETS){
                TypeDraws group=byType.get(ticket.batch.type);
                if(group==null){group=new TypeDraws(ticket.batch.type);byType.put(ticket.batch.type,group);grouped.add(group);}
                group.tickets.add(ticket);
            }
            for(TypeDraws group:grouped){
                RenderType type=group.type;boolean restoreLights=false;
                try{
                    type.setupRenderState();ShaderInstance shader=RenderSystem.getShader();RenderSystem.setupShaderLights(shader);
                    var light0=shader.getUniform("Light0_Direction");var light1=shader.getUniform("Light1_Direction");restoreLights=light0!=null&&light1!=null;
                    if(restoreLights){PREVIOUS_LIGHT0.set(light0.getFloatBuffer());PREVIOUS_LIGHT1.set(light1.getFloatBuffer());RenderSystem.setShaderLights(WORLD_LIGHT0,WORLD_LIGHT1);}
                    for(DrawTicket ticket:group.tickets){ticket.batch.buffer.bind();ticket.batch.buffer.drawWithShader(ticket.pose,ticket.projection,shader);VertexBuffer.unbind();draws++;submittedFaces+=ticket.batch.faces;if(ticket.batch.lod>=0)lodDraws[ticket.batch.lod]++;}
                }finally{VertexBuffer.unbind();if(restoreLights)RenderSystem.setShaderLights(PREVIOUS_LIGHT0,PREVIOUS_LIGHT1);type.clearRenderState();}
            }
        }finally{DRAW_TICKETS.clear();drainRetiredBuffers();}
    }
    private static void retireBuffer(VertexBuffer buffer){if(RETIRED_BUFFER_SET.add(buffer))RETIRED_BUFFERS.addLast(buffer);}
    private static void drainRetiredBuffers(){while(!RETIRED_BUFFERS.isEmpty()){VertexBuffer buffer=RETIRED_BUFFERS.removeFirst();RETIRED_BUFFER_SET.remove(buffer);buffer.close();}}
    private static void draw(List<Batch> batches,Matrix4f pose,Matrix4f projection,net.minecraft.world.phys.Vec3 camera,RailDetailDistances distances){draw(batches,pose,projection,camera,distances,Map.of(),new V3(0,0,0));}
    private static void draw(List<Batch> batches,Matrix4f pose,Matrix4f projection,net.minecraft.world.phys.Vec3 camera,RailDetailDistances distances,Map<Batch,V3> origins,V3 root){
        if(batches.isEmpty())return;
        RenderType activeType=null;org.joml.Vector3f previous0=null,previous1=null;ShaderInstance shader=null;
        try{
            for(var b:batches){
                if(b.lod>=0&&RailLod.fittingLevel(b.center)!=b.lod)continue;
                if(activeType!=b.type){
                    if(activeType!=null){if(previous0!=null)RenderSystem.setShaderLights(previous0,previous1);activeType.clearRenderState();}
                    activeType=b.type;activeType.setupRenderState();shader=RenderSystem.getShader();RenderSystem.setupShaderLights(shader);
                    var light0=shader.getUniform("Light0_Direction");var light1=shader.getUniform("Light1_Direction");previous0=null;previous1=null;
                    if(light0!=null&&light1!=null){previous0=PREVIOUS_LIGHT0;previous1=PREVIOUS_LIGHT1;previous0.set(light0.getFloatBuffer());previous1.set(light1.getFloatBuffer());RenderSystem.setShaderLights(WORLD_LIGHT0,WORLD_LIGHT1);}
                }
                V3 local=origins.get(b);Matrix4f batchPose=local==null?pose:new Matrix4f(pose).translate((float)(local.x()-root.x()),(float)(local.y()-root.y()),(float)(local.z()-root.z()));
                b.buffer.bind();b.buffer.drawWithShader(batchPose,projection,shader);VertexBuffer.unbind();
                draws++;submittedFaces+=b.faces;if(b.lod>=0)lodDraws[b.lod]++;
            }
        }finally{
            VertexBuffer.unbind();if(activeType!=null){if(previous0!=null)RenderSystem.setShaderLights(previous0,previous1);activeType.clearRenderState();}
        }
    }
    /** Compatibility entry point retained for the isolated runtime probe. */
    static void draw(List<Batch> batches,Matrix4f pose,Matrix4f projection){
        var camera=Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        draw(batches,pose,projection,camera,RailLod.distances());
    }
    public void close(){UPLOAD_QUEUE.remove(this);PRIORITY_UPLOAD_QUEUE.remove(this);BACKGROUND_UPLOAD_QUEUE.remove(this);queuedForUpload=false;Set<VertexBuffer> buffers=Collections.newSetFromMap(new IdentityHashMap<>());fixedCache.values().forEach(c->buffers.add(c.batch.buffer));movingCache.values().forEach(c->buffers.add(c.batch.buffer));retireAfterCommit.forEach(b->buffers.add(b.buffer));buffers.forEach(PointGpu::retireBuffer);fixedCache.clear();movingCache.clear();fixed.clear();moving.clear();orderedFixed.clear();orderedMoving.clear();pending.clear();planWork=null;plannedMoving=Map.of();lightRefreshDeferred=false;movingRefreshDeferred=false;retireAfterCommit.clear();batchOrigins.clear();if(geometryEntry!=null)geometryEntry.slot.clear();geometryEntry=null;source=null;preparedSource=null;committedSource=null;committed=false;bounds=null;ready=true;}
}
