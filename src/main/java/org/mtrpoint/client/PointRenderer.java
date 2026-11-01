package org.mtrpoint.client;

import org.mtrpoint.PointConfig;
import org.mtrpoint.geometry.*;
import java.util.*;

/** Persistent world geometry, merged check rails and preserved third-party attachments. */
public final class PointRenderer {
    private record GuardSource(PointClient.View view,PointSettings settings,Profile profile,ScissorsLayout group,
        PointMesh.YBoundary boundary,PointMesh.DiamondBoundary diamond,List<Track> centreRoads,List<Track> channels,ScissorsLayout shared,boolean centreHidden) {}
    private static GuardSource source(PointClient.View v){return new GuardSource(v,v.settings,v.profile,v.scissors,v.boundary,v.diamond,v.centreRoads,v.channels,v.shared,v.centreHidden);}
    private static List<GuardSource> guardSources=List.of();
    private static final Compiled GUARDS=new Compiled();
    private static long guardBuilds;
    private static final Map<PointClient.View,PointGpu> GPU=new IdentityHashMap<>();
    private static List<PointClient.View> knownViews=List.of();
    private static List<PointClient.View> activeViews=List.of();
    private static final Map<List<GuardSource>,Mesh> ASSEMBLIES=new LinkedHashMap<>();
    private static final Map<List<GuardSource>,GeometryJobs.Slot<AssemblyResult>> ASSEMBLY_JOBS=new LinkedHashMap<>();
    private static final Map<List<GuardSource>,AssemblySnapshot> ASSEMBLY_SNAPSHOTS=new LinkedHashMap<>();
    private static final IdentityHashMap<PointClient.View,GeometryJobs.Slot<FallbackResult>> FALLBACK_JOBS=new IdentityHashMap<>();
    private static final IdentityHashMap<PointClient.View,AssemblySnapshot> FALLBACK_SNAPSHOTS=new IdentityHashMap<>();
    private static final IdentityHashMap<PointClient.View,Fallback> FALLBACKS=new IdentityHashMap<>();
    private static final IdentityHashMap<PointClient.View,PointGpu> FALLBACK_GPU=new IdentityHashMap<>();
    private static final Map<Mesh,PointGpu> ASSEMBLY_GPU=new IdentityHashMap<>();
    private static final Map<Mesh,List<Mesh.Quad>> ASSEMBLY_BEDS=new IdentityHashMap<>();
    private static List<List<GuardSource>> COMPONENTS=List.of();
    private static List<GuardSource> componentSources=List.of();
    private static final IdentityHashMap<PointClient.View,List<GuardSource>> COMPONENT_BY_VIEW=new IdentityHashMap<>();
    private static final IdentityHashMap<PointClient.View,Integer> COMPONENT_READY=new IdentityHashMap<>();
    private record JointInput(Mesh source,Mesh filtered){}
    private static final IdentityHashMap<PointClient.View,JointInput> JOINT_FILTERS=new IdentityHashMap<>();
    private static boolean originalWings(GuardSource s,List<GuardSource> component){
        // Crossing components still need the common interval pool to resolve terminal ownership.
        if(component.stream().anyMatch(other->other.view.junction.kind()==Junction.Kind.DIAMOND))return false;
        // ThreeWayMesh already bakes its connected wings together. Re-sampling
        // them here loses the common knee section and produces hairline openings.
        if(s.view.junction.kind()==Junction.Kind.THREE)return s.settings.guardEdits().isEmpty();
        return s.view.junction.kind()==Junction.Kind.Y&&!s.settings.guardEdits().containsKey(2)&&!s.settings.guardEdits().containsKey(3);
    }
    private record Bounds(double x0,double x1,double z0,double z1){
        boolean overlaps(Bounds b){return x0<=b.x1&&b.x0<=x1&&z0<=b.z1&&b.z0<=z1;}
    }
    private record AssemblyResult(Mesh mesh,List<Mesh.Quad> beds){}
    private record FallbackResult(AssemblyResult assembly,Map<AttachmentRegion,Mesh> attachments){}
    private record AttachmentRegion(int x,int z){
        static AttachmentRegion of(V3 p){return new AttachmentRegion((int)Math.floor(p.x()/16),(int)Math.floor(p.z()/16));}
        V3 center(){return new V3(x*16+8,0,z*16+8);}
    }
    private static final class AttachmentGpu{
        final AttachmentRegion region;final Mesh mesh;final PointGpu gpu=new PointGpu();boolean drawThisFrame;
        AttachmentGpu(AttachmentRegion region,Mesh mesh){this.region=region;this.mesh=mesh;gpu.setBackground(true);}
    }
    private record Fallback(Mesh mesh,List<Mesh.Quad> beds,int[] sourceIndices,int[] targetIndices,
        Map<AttachmentRegion,AttachmentGpu> attachments){}
    private record AssemblySource(Junction junction,PointSettings settings,Profile profile,ScissorsLayout group,
        PointMesh.YBoundary threeBoundary,List<Mesh.Quad> viewMesh,List<Mesh.Quad> jointSteel,
        List<Track> crossingRoads,List<Track> drawnRoads){}
    private record AssemblySnapshot(List<AssemblySource> sources,java.util.function.UnaryOperator<Mesh> bank,
        List<RailCellCache.Contribution> attachments){}
    private static List<Mesh.Quad> immutableQuads(Mesh mesh){
        // Quad/V3 records and their UV/normal lists are never mutated; animation replaces list entries.
        return List.copyOf(mesh.quads);
    }
    private static Bounds bounds(GuardSource source){
        var j=source.view.junction;double extent=PointMesh.extent(j,source.settings),x0=Double.MAX_VALUE,x1=-x0,z0=x0,z1=-x0;
        for(var road:source.group!=null?source.group.tracks():j.tracks()){
            double c=road.nearest(j.center()),start=j.kind()==Junction.Kind.DIAMOND?Math.max(0,c-extent):0,end=j.kind()==Junction.Kind.DIAMOND?Math.min(road.length,c+extent):extent;
            if(source.group!=null){start=0;end=road.length;}
            // Track points already are the sampled polyline used by intersection tests; reuse
            // them instead of resampling every road at quarter-metre intervals on the main thread.
            for(V3 v:road.points){x0=Math.min(x0,v.x());x1=Math.max(x1,v.x());z0=Math.min(z0,v.z());z1=Math.max(z1,v.z());}
        }
        double pad=source.profile.gauge()/2+source.settings.sleeperOverhang()+.5;return new Bounds(x0-pad,x1+pad,z0-pad,z1+pad);
    }
    private static final class Compiled {
        Mesh mesh;
        void update(Mesh next,double frame){mesh=next;}
    }
    private static void closeFallback(Fallback fallback){if(fallback!=null)fallback.attachments.values().forEach(attachment->attachment.gpu.close());}
    public static void clear(){GPU.values().forEach(PointGpu::close);GPU.clear();FALLBACK_GPU.values().forEach(PointGpu::close);FALLBACK_GPU.clear();FALLBACKS.values().forEach(PointRenderer::closeFallback);FALLBACK_JOBS.values().forEach(GeometryJobs.Slot::clear);FALLBACK_JOBS.clear();FALLBACK_SNAPSHOTS.clear();FALLBACKS.clear();knownViews=List.of();activeViews=List.of();ASSEMBLY_GPU.values().forEach(PointGpu::close);ASSEMBLY_GPU.clear();ASSEMBLIES.clear();ASSEMBLY_JOBS.values().forEach(GeometryJobs.Slot::clear);ASSEMBLY_JOBS.clear();ASSEMBLY_SNAPSHOTS.clear();ASSEMBLY_BEDS.clear();COMPONENTS=List.of();componentSources=List.of();COMPONENT_BY_VIEW.clear();COMPONENT_READY.clear();JOINT_FILTERS.clear();RailCellCache.clear();guardSources=List.of();GUARDS.mesh=null;}
    private static void ensureComponents(){
        ensureComponents(PointClient.views);
    }
    private static void ensureComponents(List<PointClient.View> views){
        var next=views.stream().filter(v->!v.styles.isEmpty()&&v.settings.enabled()).map(PointRenderer::source).toList();
        if(next.equals(componentSources))return;
        var previous=new IdentityHashMap<PointClient.View,GuardSource>();for(var source:componentSources)previous.put(source.view,source);
        componentSources=next;
        for(var source:next){GuardSource old=previous.get(source.view);if(old!=null&&!old.equals(source)){
            var job=FALLBACK_JOBS.remove(source.view);if(job!=null)job.clear();FALLBACK_SNAPSHOTS.remove(source.view);closeFallback(FALLBACKS.remove(source.view));PointGpu stale=FALLBACK_GPU.remove(source.view);if(stale!=null)stale.close();
        }}
        var boxes=next.stream().map(PointRenderer::bounds).toList();var components=new ArrayList<List<GuardSource>>();boolean[] used=new boolean[next.size()];
        for(int i=0;i<next.size();i++)if(!used[i]){
            var indices=new ArrayList<Integer>();indices.add(i);used[i]=true;
            for(int k=0;k<indices.size();k++)for(int n=0;n<next.size();n++)if(!used[n]&&boxes.get(indices.get(k)).overlaps(boxes.get(n))){used[n]=true;indices.add(n);}
            indices.sort(Integer::compareTo);components.add(indices.stream().map(next::get).toList());
        }
        COMPONENTS=List.copyOf(components);COMPONENT_BY_VIEW.clear();COMPONENT_READY.clear();for(var c:COMPONENTS)for(var s:c)COMPONENT_BY_VIEW.put(s.view,c);
        Set<PointClient.View> validViews=Collections.newSetFromMap(new IdentityHashMap<>());for(var source:next)validViews.add(source.view);
        for(var it=FALLBACK_JOBS.entrySet().iterator();it.hasNext();){var entry=it.next();if(!validViews.contains(entry.getKey())){entry.getValue().clear();FALLBACK_SNAPSHOTS.remove(entry.getKey());it.remove();}}
        for(var it=FALLBACKS.entrySet().iterator();it.hasNext();){var entry=it.next();if(!validViews.contains(entry.getKey())){closeFallback(entry.getValue());it.remove();}}
        for(var it=FALLBACK_GPU.entrySet().iterator();it.hasNext();){var entry=it.next();if(!validViews.contains(entry.getKey())){entry.getValue().close();it.remove();}}
        var valid=new HashSet<>(COMPONENTS);ASSEMBLIES.keySet().retainAll(valid);
        for(var iterator=ASSEMBLY_JOBS.entrySet().iterator();iterator.hasNext();){var entry=iterator.next();if(!valid.contains(entry.getKey())){entry.getValue().clear();ASSEMBLY_SNAPSHOTS.remove(entry.getKey());iterator.remove();}}
        var meshes=new HashSet<>(ASSEMBLIES.values());
        ASSEMBLY_BEDS.keySet().retainAll(meshes);
        for(var it=ASSEMBLY_GPU.entrySet().iterator();it.hasNext();){var entry=it.next();if(!meshes.contains(entry.getKey())){entry.getValue().close();it.remove();}}
    }
    static void refreshComponents(){ensureComponents();}
    /** Native steel hands off per view as soon as that view's complete steel GPU is ready. */
    public static boolean componentVisible(PointClient.View view){
        return takeoverStage(view)>0;
    }
    /** 0 keeps the whole native path, 1 hands off this exact view to its local complete fallback,
     *  2 hands off the legacy pooled component atomically. */
    public static int takeoverStage(PointClient.View view){
        if(view==null)return 0;Integer cached=COMPONENT_READY.get(view);if(cached!=null)return cached;
        var mc=net.minecraft.client.Minecraft.getInstance();
        // Runtime stage decisions are captured once in beginFrame, before native-cell ownership
        // is sampled. A buffer becoming ready during rendering only takes effect next frame.
        if(mc!=null&&mc.level!=null)return 0;
        int stage=PointClient.centerTakeoverVisible(view)?2:0;COMPONENT_READY.put(view,stage);return stage;
    }
    static int resolveStageForTest(boolean pooledReady,boolean pooledNear,boolean fallbackReady,boolean fallbackNear){
        return pooledReady&&pooledNear?2:fallbackReady&&fallbackNear?1:0;
    }
    private static boolean fullComponentReady(List<GuardSource> component){
        var mc=net.minecraft.client.Minecraft.getInstance();if(mc==null||mc.level==null)return true;
        Mesh assembly=ASSEMBLIES.get(component);PointGpu assemblyGpu=assembly==null?null:ASSEMBLY_GPU.get(assembly);
        if(assemblyGpu==null||!assemblyGpu.hasSource(assembly)||!assemblyGpu.readyForDraw())return false;
        for(var source:component){
            Mesh mesh=source.view.mesh;PointGpu gpu=GPU.get(source.view);
            if(mesh==null||gpu==null||!gpu.hasSource(mesh)||!gpu.readyForDraw())return false;
        }
        return true;
    }
    /** The pooled assembly and its thin per-view meshes replace local fallbacks atomically. */
    public static boolean supportsVisible(PointClient.View view){return takeoverStage(view)==2;}
    private static boolean pooledStage(List<GuardSource> component){return !component.isEmpty()&&component.stream().allMatch(source->COMPONENT_READY.getOrDefault(source.view,0)==2);}
    private static void prepareVisibleComponents(){
        ensureComponents();PointPreparation.beginFrame();
        var ordered=componentSources.stream().sorted(Comparator.comparingDouble(s->PointPreparation.distanceSquared(s.view))).toList();
        int fallbackSnapshots=0;
        for(var source:ordered){
            if(!PointPreparation.eligible(source.view,PointPreparation.cpuDistance())||!source.view.prepareAsync())continue;
            Mesh viewMesh=source.view.mesh();boolean gpuEligible=PointPreparation.eligible(source.view,PointPreparation.gpuDistance());
            boolean needsSnapshot=!FALLBACKS.containsKey(source.view)&&!FALLBACK_SNAPSHOTS.containsKey(source.view);
            if(needsSnapshot&&fallbackSnapshots>=1)continue;
            if(needsSnapshot)fallbackSnapshots++;
            prepareFallback(source,viewMesh);
            Fallback fallback=FALLBACKS.get(source.view);
            if(fallback!=null&&gpuEligible){
                syncFallbackPose(fallback,viewMesh);
                PointGpu gpu=FALLBACK_GPU.computeIfAbsent(source.view,key->new PointGpu());gpu.setBackground(true);
                gpu.update(fallback.mesh,source.view.renderedPosition(),source.settings.movableFrog(),false);
                for(var attachment:fallback.attachments.values())attachment.gpu.update(attachment.mesh,0,false,false);
                if(gpu.hasSource(fallback.mesh)&&gpu.readyForDraw()&&fallback.attachments.values().stream().allMatch(a->a.gpu.hasSource(a.mesh)&&a.gpu.readyForDraw()))PointPreparation.gpuReady(source.view);
            }
        }
        // Build the final pooled component only after every local replacement is already GPU-ready.
        // A singleton fallback is itself the complete final model and never needs a duplicate VBO.
        int pooledSnapshots=0;
        var components=COMPONENTS.stream().sorted(Comparator.comparingDouble(c->c.stream().mapToDouble(s->PointPreparation.distanceSquared(s.view)).min().orElse(Double.MAX_VALUE))).toList();
        for(var component:components){
            if(component.size()<2||component.stream().noneMatch(s->PointPreparation.eligible(s.view,PointPreparation.cpuDistance())))continue;
            boolean fallbacksReady=component.stream().allMatch(s->{
                Fallback f=FALLBACKS.get(s.view);PointGpu g=FALLBACK_GPU.get(s.view);
                return f!=null&&g!=null&&g.hasSource(f.mesh)&&g.readyForDraw()&&f.attachments.values().stream().allMatch(a->a.gpu.hasSource(a.mesh)&&a.gpu.readyForDraw());
            });
            if(!fallbacksReady)continue;
            boolean viewsReady=component.stream().allMatch(s->PointPreparation.eligible(s.view,PointPreparation.cpuDistance())&&s.view.prepareAsync());
            if(!viewsReady)continue;
            Mesh assembly=ASSEMBLIES.get(component);
            if(assembly==null){
                boolean newSnapshot=!ASSEMBLY_SNAPSHOTS.containsKey(component);
                if(newSnapshot&&pooledSnapshots>=1)continue;
                if(newSnapshot)pooledSnapshots++;
                var slot=ASSEMBLY_JOBS.computeIfAbsent(component,k->new GeometryJobs.Slot<>());
                var snapshot=ASSEMBLY_SNAPSHOTS.computeIfAbsent(component,k->snapshot(component,false));
                AssemblyResult result=slot.getBackground(()->assembleSnapshot(snapshot));
                if(result!=null){assembly=result.mesh;ASSEMBLIES.put(component,assembly);if(!result.beds.isEmpty())ASSEMBLY_BEDS.put(assembly,result.beds);slot.clear();ASSEMBLY_JOBS.remove(component);ASSEMBLY_SNAPSHOTS.remove(component);guardBuilds++;for(var source:component)PointPreparation.assemblyReady(source.view);}
            }
            if(assembly!=null&&component.stream().allMatch(s->PointPreparation.eligible(s.view,PointPreparation.gpuDistance()))){
                for(var source:component){Mesh mesh=source.view.mesh();PointGpu gpu=GPU.computeIfAbsent(source.view,k->new PointGpu());gpu.setBackground(true);gpu.update(mesh,source.view.renderedPosition(),source.settings.movableFrog(),true);}
                PointGpu gpu=ASSEMBLY_GPU.computeIfAbsent(assembly,k->new PointGpu());gpu.setBackground(true);gpu.update(assembly,0,false,false);
            }
        }
    }
    private static void prepareFallback(GuardSource source,Mesh viewMesh){
        PointClient.View view=source.view;Fallback fallback=FALLBACKS.get(view);
        if(fallback==null){
            var slot=FALLBACK_JOBS.computeIfAbsent(view,key->new GeometryJobs.Slot<>());
            var snapshot=FALLBACK_SNAPSHOTS.computeIfAbsent(view,key->snapshot(List.of(source),true));
            FallbackResult result=slot.getBackground(()->assembleFallbackSnapshot(snapshot));
            if(result!=null){
                Mesh complete=result.assembly.mesh;var attachments=new LinkedHashMap<AttachmentRegion,AttachmentGpu>();result.attachments.forEach((region,mesh)->attachments.put(region,new AttachmentGpu(region,mesh)));
                var sourceIndices=new ArrayList<Integer>();var targetIndices=new ArrayList<Integer>();
                for(int i=0;i<viewMesh.quads.size();i++)if(!PointGpu.hiddenInView(viewMesh.quads.get(i))){
                    Mesh.Quad q=viewMesh.quads.get(i);if(q.part().equals("blade")||q.part().equals("stretcher")||source.settings.movableFrog()&&q.part().equals("frog")){sourceIndices.add(i);targetIndices.add(complete.quads.size());}
                    complete.quad(q);
                }
                fallback=new Fallback(complete,result.assembly.beds,sourceIndices.stream().mapToInt(Integer::intValue).toArray(),targetIndices.stream().mapToInt(Integer::intValue).toArray(),Collections.unmodifiableMap(attachments));
                FALLBACKS.put(view,fallback);slot.clear();FALLBACK_JOBS.remove(view);FALLBACK_SNAPSHOTS.remove(view);PointPreparation.assemblyReady(view);
            }
        }
    }
    private static void syncFallbackPose(Fallback fallback,Mesh viewMesh){
        for(int i=0;i<fallback.sourceIndices.length;i++){
            int source=fallback.sourceIndices[i],target=fallback.targetIndices[i];
            if(source<viewMesh.quads.size()&&target<fallback.mesh.quads.size())fallback.mesh.quads.set(target,viewMesh.quads.get(source));
        }
    }
    private static FallbackResult assembleFallbackSnapshot(AssemblySnapshot snapshot){AssemblyResult assembly=assembleSnapshot(snapshot);return new FallbackResult(assembly,attachmentMeshData(snapshot.attachments,assembly.beds));}
    private static void guards(List<PointClient.View> active){
        var client=net.minecraft.client.Minecraft.getInstance();
        // GUARDS only serves synchronous headless geometry probes. Runtime geometry is
        // assembled by GeometryJobs; never fall back to render-thread assembly here.
        if(client!=null&&client.level!=null)return;
        if(PointClient.views.isEmpty()&&(client==null||client.level==null)){if(!active.isEmpty())ensureComponents(active);}else ensureComponents();
        var activeSet=Collections.newSetFromMap(new IdentityHashMap<PointClient.View,Boolean>());activeSet.addAll(active);
        var visibleComponents=new ArrayList<List<GuardSource>>();for(var component:COMPONENTS)if(component.stream().anyMatch(s->activeSet.contains(s.view)))visibleComponents.add(component);
        var nextSources=visibleComponents.stream().flatMap(List::stream).toList();
        if(nextSources.equals(guardSources)&&GUARDS.mesh!=null)return;
        Mesh combined=new Mesh();
        for(var component:visibleComponents){
            Mesh mesh=ASSEMBLIES.get(component);
            if(mesh==null){mesh=assemble(component);ASSEMBLIES.put(component,mesh);guardBuilds++;}
            combined.quads.addAll(mesh.quads);
        }
        guardSources=nextSources;GUARDS.update(combined,0);
    }
    private static AssemblySnapshot snapshot(List<GuardSource> next){return snapshot(next,false);}
    private static AssemblySnapshot snapshot(List<GuardSource> next,boolean captureAttachments){
        var sources=new ArrayList<AssemblySource>();var bankRoads=new LinkedHashMap<String,Track>();var copies=new PointClient.GeometryCopies();
        for(var source:next){
            var view=source.view;var mesh=view.mesh();var joint=view.jointSteel();
            var drawn=copies.tracks(view.drawnRoads());var crossing=copies.tracks(view.crossingRoads());
            List<Track> guardRoads=source.group!=null?source.group.tracks():view.junction.tracks();for(Track road:guardRoads)bankRoads.putIfAbsent(road.id,road);
            var junction=copies.junction(view.junction);var group=copies.scissors(source.group);
            sources.add(new AssemblySource(junction,source.settings,source.profile,group,
                junction.kind()==Junction.Kind.THREE?RailSampler.yBoundary(junction,source.settings):null,
                immutableQuads(mesh),immutableQuads(joint),crossing,drawn));
        }
        var bank=next.isEmpty()?java.util.function.UnaryOperator.<Mesh>identity():RailSampler.bankSnapshot(copies.tracks(List.copyOf(bankRoads.values())),next.get(0).profile.source());
        List<RailCellCache.Contribution> attachments=captureAttachments?captureAttachments(next.get(0)):List.of();
        return new AssemblySnapshot(List.copyOf(sources),bank,attachments);
    }
    private static List<RailCellCache.Contribution> captureAttachments(GuardSource source){
        var mc=net.minecraft.client.Minecraft.getInstance();if(mc==null||mc.level==null)return List.of();
        var rails=org.mtr.mod.client.MinecraftClientData.getInstance().railIdMap;
        var contributions=new ArrayList<RailCellCache.Contribution>();var seen=new HashSet<String>();
        for(Track road:source.view.drawnRoads()){
            var rail=rails.get(road.id);if(rail==null)continue;
            for(String raw:rail.getStyles()){
                String rawId=org.mtr.mod.resource.RailResource.getIdWithoutDirection(raw);
                String resourceId=Profiles.canonical(rawId);
                if(resourceId.equals("default"))for(String candidate:source.view.styles)if(Set.of("default_3d","default_3d_siding").contains(candidate)){resourceId=candidate;break;}
                if(!PointClient.matchesNativeStyle(source.view.styles,resourceId))continue;
                String key=road.id+"/"+resourceId+"/"+raw.endsWith("_2");if(!seen.add(key))continue;
                String selectedResource=resourceId;org.mtr.mod.resource.RailResource[] resource={null};org.mtr.mod.client.CustomResourceLoader.getRailById(selectedResource,r->resource[0]=r);
                if(resource[0]==null)continue;boolean flip=raw.endsWith("_2");
                for(var cell:RailSampler.ordinaryCells(rail,resource[0])){
                    V3 midpoint=cell.a().lerp(cell.b(),.5);
                    if(!PointClient.ownsCell(source.view,road.id,midpoint))continue;
                    if(!mc.level.hasChunk(Math.floorDiv((int)Math.floor(midpoint.x()),16),Math.floorDiv((int)Math.floor(midpoint.z()),16)))continue;
                    contributions.add(RailCellCache.capture(key,()->preserveAttachments(rail,resource[0],flip,cell.a(),cell.b())));
                }
            }
        }
        return List.copyOf(contributions);
    }
    private static void preserveAttachments(org.mtr.core.data.Rail rail,org.mtr.mod.resource.RailResource resource,boolean flip,V3 a,V3 b){
        ProfileModel model=RailLod.model(resource.getId(),a.lerp(b,.5));
        cell(rail,resource,flip,a,b,model==null?Profiles.attachments(resource.getId()):model.attachments(),V3.ZERO,true);
    }
    private static Map<AttachmentRegion,Mesh> attachmentMeshData(List<RailCellCache.Contribution> contributions,List<Mesh.Quad> beds){
        var source=new Mesh();for(var contribution:contributions)source.quads.addAll(contribution.detachedMesh().quads);source.quads.addAll(beds);
        var indexed=new LinkedHashMap<AttachmentRegion,Mesh>();
        for(var q:source.quads){
            double x0=Math.min(Math.min(q.a().x(),q.b().x()),Math.min(q.c().x(),q.d().x()));
            double x1=Math.max(Math.max(q.a().x(),q.b().x()),Math.max(q.c().x(),q.d().x()));
            double z0=Math.min(Math.min(q.a().z(),q.b().z()),Math.min(q.c().z(),q.d().z()));
            double z1=Math.max(Math.max(q.a().z(),q.b().z()),Math.max(q.c().z(),q.d().z()));
            for(int x=(int)Math.floor(x0/16);x<=(int)Math.floor(x1/16);x++)for(int z=(int)Math.floor(z0/16);z<=(int)Math.floor(z1/16);z++){
                AttachmentRegion region=new AttachmentRegion(x,z);var clipped=clipAttachment(q,region);
                for(var face:clipped)indexed.computeIfAbsent(region,key->new Mesh()).quad(face);
            }
        }
        for(var entry:indexed.entrySet()){
            Mesh region=entry.getValue();var bedFaces=region.quads.stream().filter(q->q.part().equals("track_bed")).toList();
            if(!bedFaces.isEmpty()){region.quads.removeIf(q->q.part().equals("track_bed"));Mesh union=new Mesh();union.quads.addAll(bedFaces);region.quads.addAll(SurfaceUnion.build(union).quads);}
        }
        indexed.entrySet().removeIf(entry->entry.getValue().quads.isEmpty());return Collections.unmodifiableMap(indexed);
    }
    private static List<Mesh.Quad> clipAttachment(Mesh.Quad quad,AttachmentRegion region){
        Mesh mesh=new Mesh();mesh.quad(quad);V3[] origins={new V3(region.x*16,0,0),new V3((region.x+1)*16,0,0),new V3(0,0,region.z*16),new V3(0,0,(region.z+1)*16)};
        V3[] normals={new V3(-1,0,0),new V3(1,0,0),new V3(0,0,-1),new V3(0,0,1)};
        for(int i=0;i<4;i++){Mesh next=new Mesh();for(var q:mesh.quads){double a=q.a().sub(origins[i]).dot(normals[i]),b=q.b().sub(origins[i]).dot(normals[i]),c=q.c().sub(origins[i]).dot(normals[i]),d=q.d().sub(origins[i]).dot(normals[i]);if(Math.max(Math.max(a,b),Math.max(c,d))<=0)next.quad(q);else if(Math.min(Math.min(a,b),Math.min(c,d))<=0)Mesh.clip(next,q,origins[i],normals[i]);}mesh=next;}
        mesh.quads.removeIf(q->!AttachmentRegion.of(q.center()).equals(region));return mesh.quads;
    }
    private static Mesh mesh(List<Mesh.Quad> faces){Mesh result=new Mesh();result.quads.addAll(faces);return result;}
    private static AssemblyResult assembleSnapshot(AssemblySnapshot snapshot){
        List<AssemblySource> next=snapshot.sources;
        var runs=new ArrayList<GuardRails.Run>();var steel=new ArrayList<DiamondGeometry.Steel>();
        for(var source:next){
            Profile tuned=source.profile.tune(source.settings);
            // One cut source per view: its swept running steel plus the roads whose flange
            // channels have to stay clear of any check steel that crosses them.
            steel.add(new DiamondGeometry.Steel(mesh(source.viewMesh),tuned.top()+source.settings.verticalOffset(),tuned,source.settings,
                source.crossingRoads));
            for(var original:GuardRails.assembled(source.junction,source.settings,source.profile,source.group,source.threeBoundary)){
                GuardRails.Run run=next.size()==1?clipFallbackRun(source.junction,source.group,original):original;
                if(run==null)continue;
                // The frog already sweeps both sides of this knee with one common section.
                // A separately sampled pooled wing discards that section and opens its edges.
                if(run!=null&&!(originalWings(source,next)&&(run.attachedStart()||run.attachedEnd())))runs.add(run);
            }
        }
        // The pool holds check steel from every source at once, so it is cut once against the whole
        // component: a guard that runs into a neighbour's crossing, stock rail or blade is severed
        // exactly where the two swept sections meet instead of passing through it.
        Mesh merged=DiamondGeometry.guards(runs,steel);
        if(!runs.isEmpty())merged=snapshot.bank.apply(merged);
        Mesh supports=new Mesh();var fasteners=new LinkedHashSet<Mesh.Quad>();
        for(int i=0;i<next.size();i++){
            var source=next.get(i);Profile tuned=source.profile.tune(source.settings);
            var cutters=new ArrayList<DiamondGeometry.Steel>(steel);
            Mesh check=new Mesh();
            for(var q:source.viewMesh){
                if(q.part().startsWith("fastener_"))fasteners.add(new Mesh.Quad(q.a(),q.b(),q.c(),q.d(),q.surface(),q.part(),-1,q.uv()));
                else if(q.part().equals("sleeper")||q.part().equals("fastener")||q.part().equals("track_bed"))supports.quad(q);
                else if(q.part().equals("wing")&&(source.junction.kind()==Junction.Kind.Y&&(q.index()!=-2||originalWings(source,next))||source.junction.kind()==Junction.Kind.THREE&&originalWings(source,next)))check.quad(q);
            }
            // These are incoming closure wings, joined to their route rails. Cutting them by
            // running-head overlap amputates their knees. Every road's flange channels still cut.
            supports.quads.addAll(DiamondGeometry.cutSteel(check,cutters,tuned,source.settings,tuned.top()+source.settings.verticalOffset(),source.drawnRoads,false).quads);
        }
        supports.quads.addAll(merged.quads);merged=SurfaceUnion.build(supports);merged.quads.addAll(fasteners);
        Mesh finalSteel=new Mesh();finalSteel.quads.addAll(merged.quads);
        for(var source:next)for(var q:source.jointSteel)
            if(!PointGpu.hiddenInView(q)&&!(source.settings.movableFrog()&&q.part().equals("frog")))finalSteel.quad(q);
        merged.quads.addAll(RailJoints.bridges(finalSteel).quads);
        var beds=merged.quads.stream().filter(q->q.part().equals("track_bed")).toList();
        merged.quads.removeIf(q->q.part().equals("track_bed"));
        return new AssemblyResult(merged,List.copyOf(beds));
    }
    static GuardRails.Run clipFallbackRun(Junction junction,ScissorsLayout group,GuardRails.Run run){
        if(group==null||junction.kind()!=Junction.Kind.Y)return run;
        boolean before=junction.center().sub(group.crossing().center()).dot(group.axis())<0;
        V3 plane=group.crossing().center().add(group.axis().mul(before?group.lo():group.hi()));
        V3 normal=before?group.axis():group.axis().mul(-1);
        return run.clip(plane,normal);
    }
    private static Mesh assemble(List<GuardSource> next){
        AssemblyResult result=assembleSnapshot(snapshot(next));if(!result.beds.isEmpty())ASSEMBLY_BEDS.put(result.mesh,result.beds);return result.mesh;
    }
    private static boolean originalWings(AssemblySource s,List<AssemblySource> component){
        if(component.stream().anyMatch(other->other.junction.kind()==Junction.Kind.DIAMOND))return false;
        if(s.junction.kind()==Junction.Kind.THREE)return s.settings.guardEdits().isEmpty();
        return s.junction.kind()==Junction.Kind.Y&&!s.settings.guardEdits().containsKey(2)&&!s.settings.guardEdits().containsKey(3);
    }
    /** Test access to the exact final assembly the frame renderer submits. */
    static Mesh assembleForTest(List<PointClient.View> active){
        return assemble(active.stream().map(PointRenderer::source).toList());
    }
    /** Test access to everything the world draws for these views: the shared component assembly plus
     *  each view's own steel, filtered exactly like the per-view draw filters it. */
    static Mesh worldForTest(List<PointClient.View> active){
        Mesh world=assemble(active.stream().map(PointRenderer::source).toList());
        for(var view:active)for(var q:view.mesh().quads)if(!PointGpu.hiddenInView(q))world.quad(q);
        return world;
    }
    /** Test access to the view record the assembly pools from. */
    static PointClient.View view(Junction junction,PointSettings settings,Profile profile,ScissorsLayout group){
        var v=new PointClient.View(junction,settings,profile,Set.of("default"));v.scissors=group;return v;
    }
    /** Test access to the view set a world build produces: a shared centre replaces the crossings it
     *  already draws in full, and a neighbour that only reaches into a shared region keeps its own
     *  steel but yields to the shared flangeways. */
    static List<PointClient.View> viewsForTest(List<Junction> junctions,PointSettings settings,Profile profile){
        var groups=ScissorsLayout.find(junctions);var members=new HashMap<String,ScissorsLayout>();
        for(var group:groups){members.put(group.crossing().id(),group);for(var y:group.turnouts())members.put(y.id(),group);}
        var out=new ArrayList<PointClient.View>();
        for(var j:junctions){
            if(ScissorsLayout.absorbedByCentre(groups,j))continue;
            var v=view(j,settings,profile,members.get(j.id()));
            if(v.scissors==null)v.shared=ScissorsLayout.reachedBy(groups,j);
            out.add(v);
        }
        var roads=new LinkedHashMap<String,Track>();for(var j:junctions)for(var road:j.tracks())roads.putIfAbsent(road.id,road);
        PointClient.refreshCrossings(out,List.copyOf(roads.values()));
        return out;
    }
    public static void preserve(org.mtr.core.data.Rail rail,org.mtr.mod.resource.RailResource resource,boolean flip,V3 a,V3 b){
        preserveAttachments(rail,resource,flip,a,b);
    }
    public static void cell(org.mtr.core.data.Rail rail,org.mtr.mod.resource.RailResource resource,boolean flip,V3 a,V3 b,List<Mesh.Quad> faces,V3 shift,boolean swept){
        if(faces.isEmpty())return;ProfileModel model=RailLod.model(resource.getId(),a.lerp(b,.5));
        double zMin=model==null?-resource.getRepeatInterval()/2:model.detail().zMin(),zMax=model==null?resource.getRepeatInterval()/2:model.detail().zMax();
        RailCellCache.submit(faces,RailSampler.sweep(rail,resource,a,b),flip,resource.getModelYOffset(),zMin,zMax,shift,swept);
    }
    private static void freezeTakeoverStages(){
        for(var component:COMPONENTS){
            boolean pooled=fullComponentReady(component)&&component.stream().anyMatch(source->PointPreparation.eligible(source.view,PointConfig.takeoverDistance()));
            if(pooled){for(var source:component)COMPONENT_READY.put(source.view,2);continue;}
            for(var source:component){
                boolean near=PointPreparation.eligible(source.view,PointConfig.takeoverDistance());Fallback fallback=FALLBACKS.get(source.view);PointGpu gpu=FALLBACK_GPU.get(source.view);
                boolean fallbackReady=near&&fallback!=null&&gpu!=null&&gpu.hasSource(fallback.mesh)&&gpu.readyForDraw()&&fallback.attachments.values().stream().allMatch(a->a.gpu.hasSource(a.mesh)&&a.gpu.readyForDraw());
                int stage=resolveStageForTest(false,false,fallbackReady,near);COMPONENT_READY.put(source.view,stage);
                if(stage==1)PointPreparation.gpuReady(source.view);
            }
        }
    }
    public static void beginFrame(){
        COMPONENT_READY.clear();PointGpu.beginFrame();GuardTerminalCache.begin();RailCellCache.begin();
        var mc=net.minecraft.client.Minecraft.getInstance();
        if(mc.level!=null&&mc.player!=null&&!(mc.screen instanceof BlueprintScreen)&&!(mc.screen instanceof PointSelectionScreen))prepareVisibleComponents();
        if(mc.level!=null)freezeTakeoverStages();
        OrdinaryRailCache.begin();
    }
    public static void render(){
        var mc=net.minecraft.client.Minecraft.getInstance();if(mc.level==null||mc.player==null){clear();return;}
        if(mc.screen instanceof BlueprintScreen||mc.screen instanceof PointSelectionScreen){RailCellCache.discard();return;}
        var active=PointClient.views.stream().filter(v->!v.styles.isEmpty()&&v.settings.enabled()&&PointClient.takeoverVisible(v)).toList();
        activeViews=active;
        guards(active);
        var activeKeys=new LinkedHashSet<List<GuardSource>>();for(var component:COMPONENTS)if(component.stream().anyMatch(s->active.contains(s.view)))activeKeys.add(component);
        for(var key:activeKeys)if(pooledStage(key)){Mesh mesh=ASSEMBLIES.get(key);if(mesh!=null)RailCellCache.submitWorldBeds(ASSEMBLY_BEDS.getOrDefault(mesh,List.of()));}
        for(var key:activeKeys)if(!pooledStage(key))for(var source:key)if(active.contains(source.view)){Fallback fallback=FALLBACKS.get(source.view);if(fallback!=null)RailCellCache.submitWorldBeds(fallback.beds);}
        var sources=new ArrayList<Mesh>();for(var key:activeKeys){
            if(pooledStage(key)){Mesh mesh=ASSEMBLIES.get(key);if(mesh!=null)sources.add(mesh);}
            else for(var source:key)if(active.contains(source.view)){Fallback fallback=FALLBACKS.get(source.view);if(fallback!=null)sources.add(fallback.mesh);}
        }
        for(var view:active){Mesh steel=view.jointSteel();JointInput cached=JOINT_FILTERS.get(view);
            if(cached==null||cached.source!=steel){Mesh filtered=new Mesh();for(var q:steel.quads)if(!PointGpu.hiddenInView(q))filtered.quad(q);cached=new JointInput(steel,filtered);JOINT_FILTERS.put(view,cached);}
            sources.add(cached.filtered);
        }
        // Retain filters for valid resident views, including inactive components.
        if(knownViews!=PointClient.views)JOINT_FILTERS.keySet().retainAll(PointClient.views);
        OrdinaryRailCache.submit();
        RailCellCache.finish(sources);
        // Freeze the fallback attachment decision before draw() can commit a newly prepared
        // ordinary region. The ordinary replacement becomes visible next frame as one unit.
        for(var view:active){Fallback fallback=FALLBACKS.get(view);if(fallback!=null)for(var attachment:fallback.attachments.values())attachment.drawThisFrame=!RailCellCache.ready(attachment.region.center());}
    }
    public static void drawGpu(net.minecraftforge.client.event.RenderLevelStageEvent e){
        if(e.getStage()!=net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_ENTITIES)return;
        var mc=net.minecraft.client.Minecraft.getInstance();if(mc.level==null||mc.player==null||mc.screen instanceof BlueprintScreen||mc.screen instanceof PointSelectionScreen)return;
        RailCellCache.draw(e);
        GuardTerminalCache.draw(e);
        if(knownViews!=PointClient.views){
            knownViews=PointClient.views;var retained=new HashSet<>(knownViews);
            for(var it=GPU.entrySet().iterator();it.hasNext();){var item=it.next();if(!retained.contains(item.getKey())){item.getValue().close();it.remove();}}
        }
        var active=activeViews.stream().filter(PointClient::takeoverVisible).toList();
        for(var component:COMPONENTS){
            if(pooledStage(component)){
                Mesh mesh=ASSEMBLIES.get(component);if(mesh==null)continue;var gpu=ASSEMBLY_GPU.get(mesh);if(gpu==null||!gpu.visible(e,mesh,false))continue;gpu.update(mesh,0,false,false);gpu.draw(e);
                for(var source:component){Mesh viewMesh=source.view.mesh;var viewGpu=GPU.get(source.view);if(viewMesh!=null&&viewGpu!=null&&viewGpu.visible(e,viewMesh,true)){viewGpu.update(viewMesh,source.view.renderedPosition(),source.settings.movableFrog(),true);viewGpu.draw(e);}}
            }else for(var source:component){
                if(!active.contains(source.view))continue;Fallback fallback=FALLBACKS.get(source.view);PointGpu gpu=FALLBACK_GPU.get(source.view);
                if(COMPONENT_READY.getOrDefault(source.view,0)!=1)continue;
                if(fallback!=null&&gpu!=null&&gpu.visible(e,fallback.mesh,false)){syncFallbackPose(fallback,source.view.mesh);gpu.update(fallback.mesh,source.view.renderedPosition(),source.settings.movableFrog(),false);gpu.draw(e);}
            }
        }
        for(var view:active){if(takeoverStage(view)==0)continue;Fallback fallback=FALLBACKS.get(view);if(fallback==null)continue;
            for(var attachment:fallback.attachments.values())if(attachment.drawThisFrame&&attachment.gpu.visible(e,attachment.mesh,false)){attachment.gpu.update(attachment.mesh,0,false,false);attachment.gpu.draw(e);}
        }
        PointGpu.flushDraws(e);
    }
}
