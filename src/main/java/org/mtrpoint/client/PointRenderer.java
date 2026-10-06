package org.mtrpoint.client;

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
    private static final Map<Mesh,PointGpu> ASSEMBLY_GPU=new IdentityHashMap<>();
    private static final Map<Mesh,List<Mesh.Quad>> ASSEMBLY_BEDS=new IdentityHashMap<>();
    private record SavedAssembly(Mesh mesh,List<Mesh.Quad> beds) {}
    // CPU-only return cache: GPU buffers are still closed when an assembly leaves the frame.
    private static final Map<List<GuardSource>,SavedAssembly> RECENT_ASSEMBLIES=new LinkedHashMap<>();
    private static final int MAX_RECENT_ASSEMBLIES=1,MAX_RECENT_FACES=16000;
    private static int recentFaces;
    private static List<Mesh> jointSources=List.of();
    private static Mesh jointSteel=new Mesh();
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
    private static Bounds bounds(GuardSource source){
        var j=source.view.junction;double extent=PointMesh.extent(j,source.settings),x0=Double.MAX_VALUE,x1=-x0,z0=x0,z1=-x0;
        for(var road:source.group!=null?source.group.tracks():j.tracks()){
            double c=road.nearest(j.center()),start=j.kind()==Junction.Kind.DIAMOND?Math.max(0,c-extent):0,end=j.kind()==Junction.Kind.DIAMOND?Math.min(road.length,c+extent):extent;
            if(source.group!=null){start=0;end=road.length;}
            for(double d=start;d<=end+.25;d+=.25){V3 v=road.at(Math.min(end,d));x0=Math.min(x0,v.x());x1=Math.max(x1,v.x());z0=Math.min(z0,v.z());z1=Math.max(z1,v.z());}
        }
        double pad=source.profile.gauge()/2+source.settings.sleeperOverhang()+.5;return new Bounds(x0-pad,x1+pad,z0-pad,z1+pad);
    }
    private static final class Compiled {
        Mesh mesh;
        void update(Mesh next,double frame){mesh=next;}
    }
    public static void clear(){GPU.values().forEach(PointGpu::close);GPU.clear();knownViews=List.of();activeViews=List.of();ASSEMBLY_GPU.values().forEach(PointGpu::close);ASSEMBLY_GPU.clear();ASSEMBLIES.clear();ASSEMBLY_BEDS.clear();RECENT_ASSEMBLIES.clear();recentFaces=0;jointSources=List.of();jointSteel=new Mesh();RailCellCache.clear();guardSources=List.of();GUARDS.mesh=null;}
    private static void remember(List<GuardSource> key,Mesh mesh){
        var beds=ASSEMBLY_BEDS.getOrDefault(mesh,List.of());int faces=mesh.quads.size()+beds.size();
        if(faces>MAX_RECENT_FACES)return;
        while(!RECENT_ASSEMBLIES.isEmpty()&&(RECENT_ASSEMBLIES.size()>=MAX_RECENT_ASSEMBLIES||recentFaces+faces>MAX_RECENT_FACES)){
            var it=RECENT_ASSEMBLIES.entrySet().iterator();var old=it.next().getValue();recentFaces-=old.mesh.quads.size()+old.beds.size();it.remove();
        }
        RECENT_ASSEMBLIES.put(key,new SavedAssembly(mesh,beds));recentFaces+=faces;
    }
    private static void guards(List<PointClient.View> active){
        var next=active.stream().map(PointRenderer::source).toList();
        if(next.equals(guardSources)&&GUARDS.mesh!=null)return;
        guardSources=next;var boxes=next.stream().map(PointRenderer::bounds).toList();var components=new ArrayList<List<GuardSource>>();boolean[] used=new boolean[next.size()];
        for(int i=0;i<next.size();i++)if(!used[i]){
            var indices=new ArrayList<Integer>();indices.add(i);used[i]=true;
            for(int k=0;k<indices.size();k++)for(int n=0;n<next.size();n++)if(!used[n]&&boxes.get(indices.get(k)).overlaps(boxes.get(n))){used[n]=true;indices.add(n);}
            indices.sort(Integer::compareTo);components.add(indices.stream().map(next::get).toList());
        }
        var retained=new LinkedHashMap<List<GuardSource>,Mesh>();Mesh combined=new Mesh();
        for(var component:components){
            Mesh mesh=ASSEMBLIES.get(component);
            if(mesh==null){var saved=RECENT_ASSEMBLIES.remove(component);if(saved!=null){mesh=saved.mesh;recentFaces-=mesh.quads.size()+saved.beds.size();if(!saved.beds.isEmpty())ASSEMBLY_BEDS.put(mesh,saved.beds);}}
            if(mesh==null){mesh=assemble(component);guardBuilds++;}retained.put(component,mesh);combined.quads.addAll(mesh.quads);
        }
        for(var old:ASSEMBLIES.entrySet())if(!retained.containsKey(old.getKey()))remember(old.getKey(),old.getValue());
        ASSEMBLIES.clear();ASSEMBLIES.putAll(retained);var meshes=new HashSet<>(retained.values());
        ASSEMBLY_BEDS.keySet().retainAll(meshes);
        for(var it=ASSEMBLY_GPU.entrySet().iterator();it.hasNext();){var entry=it.next();if(!meshes.contains(entry.getKey())){entry.getValue().close();it.remove();}}
        GUARDS.update(combined,0);
    }
    private static Mesh assemble(List<GuardSource> next){
        var runs=new ArrayList<GuardRails.Run>();var steel=new ArrayList<DiamondGeometry.Steel>();
        for(var source:next){
            Profile tuned=source.profile.tune(source.settings);
            // One cut source per view: its swept running steel plus the roads whose flange
            // channels have to stay clear of any check steel that crosses them.
            steel.add(new DiamondGeometry.Steel(source.view.mesh(),tuned.top()+source.settings.verticalOffset(),tuned,source.settings,
                source.view.crossingRoads()));
            for(var original:GuardRails.assembled(source.view.junction,source.settings,source.profile,source.group,
                source.view.junction.kind()==Junction.Kind.THREE?RailSampler.yBoundary(source.view.junction,source.settings):null)){
                GuardRails.Run run=original;
                // The frog already sweeps both sides of this knee with one common section.
                // A separately sampled pooled wing discards that section and opens its edges.
                if(run!=null&&!(originalWings(source,next)&&(run.attachedStart()||run.attachedEnd())))runs.add(run);
            }
        }
        // The pool holds check steel from every source at once, so it is cut once against the whole
        // component: a guard that runs into a neighbour's crossing, stock rail or blade is severed
        // exactly where the two swept sections meet instead of passing through it.
        Mesh merged=DiamondGeometry.guards(runs,steel);
        if(!runs.isEmpty())merged=RailSampler.bank(merged,next.get(0).view.junction,runs.stream().map(GuardRails.Run::road).distinct().toList(),next.get(0).profile.source());
        Mesh supports=new Mesh();var fasteners=new LinkedHashSet<Mesh.Quad>();
        for(int i=0;i<next.size();i++){
            var source=next.get(i);Profile tuned=source.profile.tune(source.settings);
            var cutters=new ArrayList<DiamondGeometry.Steel>(steel);
            Mesh check=new Mesh();
            for(var q:source.view.mesh().quads){
                if(q.part().startsWith("fastener_"))fasteners.add(new Mesh.Quad(q.a(),q.b(),q.c(),q.d(),q.surface(),q.part(),-1,q.uv()));
                else if(q.part().equals("sleeper")||q.part().equals("fastener")||q.part().equals("track_bed"))supports.quad(q);
                else if(q.part().equals("wing")&&(source.view.junction.kind()==Junction.Kind.Y&&(q.index()!=-2||originalWings(source,next))||source.view.junction.kind()==Junction.Kind.THREE&&originalWings(source,next)))check.quad(q);
            }
            // These are incoming closure wings, joined to their route rails. Cutting them by
            // running-head overlap amputates their knees. Every road's flange channels still cut.
            supports.quads.addAll(DiamondGeometry.cutSteel(check,cutters,tuned,source.settings,tuned.top()+source.settings.verticalOffset(),source.view.drawnRoads(),false).quads);
        }
        supports.quads.addAll(merged.quads);merged=SurfaceUnion.build(supports);merged.quads.addAll(fasteners);
        Mesh finalSteel=new Mesh();finalSteel.quads.addAll(merged.quads);
        for(var source:next)for(var q:source.view.jointSteel().quads)
            if(!PointGpu.hiddenInView(q)&&!(source.settings.movableFrog()&&q.part().equals("frog")))finalSteel.quad(q);
        merged.quads.addAll(RailJoints.bridges(finalSteel).quads);
        var beds=merged.quads.stream().filter(q->q.part().equals("track_bed")).toList();
        merged.quads.removeIf(q->q.part().equals("track_bed"));
        if(!beds.isEmpty())ASSEMBLY_BEDS.put(merged,beds);
        return merged;
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
        ProfileModel model=RailLod.model(resource.getId(),a.lerp(b,.5));
        cell(rail,resource,flip,a,b,model==null?Profiles.attachments(resource.getId()):model.attachments(),V3.ZERO,true);
    }
    public static void cell(org.mtr.core.data.Rail rail,org.mtr.mod.resource.RailResource resource,boolean flip,V3 a,V3 b,List<Mesh.Quad> faces,V3 shift,boolean swept){
        if(faces.isEmpty())return;ProfileModel model=Profiles.model(resource.getId());
        double zMin=model==null?-resource.getRepeatInterval()/2:model.detail().zMin(),zMax=model==null?resource.getRepeatInterval()/2:model.detail().zMax();
        RailCellCache.submit(faces,RailSampler.sweep(rail,resource,a,b),flip,resource.getModelYOffset(),zMin,zMax,shift,swept);
    }
    public static void beginFrame(){RailCellCache.begin();OrdinaryRailCache.begin();}
    public static void render(){
        var mc=net.minecraft.client.Minecraft.getInstance();if(mc.level==null||mc.player==null){clear();return;}
        if(mc.screen instanceof BlueprintScreen||mc.screen instanceof PointSelectionScreen){RailCellCache.discard();return;}
        var active=PointClient.views.stream().filter(v->!v.styles.isEmpty()&&v.settings.enabled()&&PointClient.takeoverVisible(v)).toList();
        activeViews=active;
        guards(active);
        for(Mesh mesh:ASSEMBLIES.values())RailCellCache.submitWorldBeds(ASSEMBLY_BEDS.getOrDefault(mesh,List.of()));
        var sources=new ArrayList<Mesh>(ASSEMBLIES.values());for(var view:active)sources.add(view.jointSteel());
        if(!sources.equals(jointSources)){
            jointSources=List.copyOf(sources);jointSteel=new Mesh();
            for(int i=0;i<sources.size();i++)for(var q:sources.get(i).quads)
                if(RailJoints.steel(q.part())&&(i<ASSEMBLIES.size()||!PointGpu.hiddenInView(q)))jointSteel.quad(q);
        }
        OrdinaryRailCache.submit();
        RailCellCache.finish(jointSteel);
    }
    public static void drawGpu(net.minecraftforge.client.event.RenderLevelStageEvent e){
        if(e.getStage()!=net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_ENTITIES)return;
        var mc=net.minecraft.client.Minecraft.getInstance();if(mc.level==null||mc.player==null||mc.screen instanceof BlueprintScreen||mc.screen instanceof PointSelectionScreen)return;
        RailCellCache.draw(e);
        if(knownViews!=PointClient.views){
            knownViews=PointClient.views;var retained=new HashSet<>(knownViews);
            for(var it=GPU.entrySet().iterator();it.hasNext();){var item=it.next();if(!retained.contains(item.getKey())){item.getValue().close();it.remove();}}
        }
        var active=activeViews.stream().filter(PointClient::takeoverVisible).toList();
        for(Mesh mesh:ASSEMBLIES.values()){var gpu=ASSEMBLY_GPU.computeIfAbsent(mesh,k->new PointGpu());if(!gpu.visible(e))continue;gpu.update(mesh,0,false,false);gpu.draw(e);}
        for(var view:active){var gpu=GPU.computeIfAbsent(view,k->new PointGpu());if(!gpu.visible(e))continue;Mesh mesh=view.mesh();gpu.update(mesh,view.renderedPosition(),view.settings.movableFrog(),true);gpu.draw(e);}
    }
}
