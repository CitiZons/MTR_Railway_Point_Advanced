package org.mtrpoint.client;

import org.mtr.mapping.holder.Identifier;
import org.mtr.mod.render.*;
import org.mtrpoint.geometry.*;
import org.mtrpoint.mixin.GraphicsAccess;
import java.util.*;

/** Persistent world geometry, merged check rails and preserved third-party attachments. */
public final class PointRenderer {
    private static final List<Mesh.Quad> PRESERVED=new ArrayList<>();
    private static final boolean PROBE_PART_COLORS=Boolean.getBoolean("pointProbePartColors");
    private static final Map<String,Identifier> TEXTURES=new HashMap<>();
    private record GuardSource(PointClient.View view,PointSettings settings,Profile profile,ScissorsLayout group) {
        // View instances are replaced during client rebuilds; geometry ownership is not.
        // Semantic equality also prevents duplicate views from emitting duplicate end caps.
        @Override public boolean equals(Object value){return value instanceof GuardSource other&&view.junction.equals(other.view.junction)&&settings.equals(other.settings)&&profile.equals(other.profile)&&Objects.equals(group,other.group);}
        @Override public int hashCode(){return Objects.hash(view.junction,settings,profile,group);}
    }
    private static List<GuardSource> guardSources=List.of();
    private static final Compiled GUARDS=new Compiled();
    private static long guardBuilds;
    private static final Map<PointClient.View,PointGpu> GPU=new IdentityHashMap<>();
    private static List<PointClient.View> knownViews=List.of();
    private static final Map<List<GuardSource>,Mesh> ASSEMBLIES=new LinkedHashMap<>();
    private static final Map<Mesh,PointGpu> ASSEMBLY_GPU=new IdentityHashMap<>();
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
    private record Face(Mesh.Quad source,float nx,float ny,float nz,long lightPos) {}
    private static final class Compiled {
        Mesh mesh;
        void update(Mesh next,double frame){mesh=next;}
    }
    public static void clear(){GPU.values().forEach(PointGpu::close);GPU.clear();knownViews=List.of();ASSEMBLY_GPU.values().forEach(PointGpu::close);ASSEMBLY_GPU.clear();ASSEMBLIES.clear();PRESERVED.clear();TEXTURES.clear();guardSources=List.of();GUARDS.mesh=null;}
    private static void guards(List<PointClient.View> active){
        var next=active.stream().map(v->new GuardSource(v,v.settings,v.profile,v.scissors)).distinct().toList();
        if(next.equals(guardSources)&&GUARDS.mesh!=null)return;
        guardSources=next;var boxes=next.stream().map(PointRenderer::bounds).toList();var components=new ArrayList<List<GuardSource>>();boolean[] used=new boolean[next.size()];
        for(int i=0;i<next.size();i++)if(!used[i]){
            var indices=new ArrayList<Integer>();indices.add(i);used[i]=true;
            for(int k=0;k<indices.size();k++)for(int n=0;n<next.size();n++)if(!used[n]&&boxes.get(indices.get(k)).overlaps(boxes.get(n))){used[n]=true;indices.add(n);}
            indices.sort(Integer::compareTo);components.add(indices.stream().map(next::get).toList());
        }
        var retained=new LinkedHashMap<List<GuardSource>,Mesh>();Mesh combined=new Mesh();
        for(var component:components){Mesh mesh=ASSEMBLIES.get(component);if(mesh==null){mesh=assemble(component);guardBuilds++;}retained.put(component,mesh);combined.quads.addAll(mesh.quads);}
        ASSEMBLIES.clear();ASSEMBLIES.putAll(retained);var meshes=new HashSet<>(retained.values());
        for(var it=ASSEMBLY_GPU.entrySet().iterator();it.hasNext();){var entry=it.next();if(!meshes.contains(entry.getKey())){entry.getValue().close();it.remove();}}
        GUARDS.update(combined,0);
    }
    private static Mesh assemble(List<GuardSource> next){
        var runs=new ArrayList<GuardRails.Run>();var cuts=new ArrayList<RailCuts.Cut>();var diamonds=new ArrayList<DiamondGeometry.Request>();
        var crossingOwned=new HashSet<String>();
        for(var source:next)for(var original:GuardRails.forJunction(source.view.junction,source.settings,source.profile)){
            if(source.view.junction.kind()==Junction.Kind.THREE)continue;
            GuardRails.Run run=original;
            if(source.group!=null){
                var group=source.group;boolean before=source.view.junction.center().sub(group.crossing().center()).dot(group.axis())<0;
                run=run.clip(group.crossing().center().add(group.axis().mul(before?group.lo():group.hi())),group.axis().mul(before?1:-1));
            }
            if(run!=null)runs.add(run);
        }
        for(var source:next)if(source.group==null){
            cuts.addAll(RailCuts.forJunction(source.view.junction,source.settings,source.profile));
            if(source.view.junction.kind()==Junction.Kind.DIAMOND){diamonds.add(new DiamondGeometry.Request(source.view.junction,source.settings,source.profile.tune(source.settings),PointMesh.extent(source.view.junction,source.settings)));crossingOwned.add(source.view.junction.id());}
            else {var crossing=DiamondGeometry.fixedY(source.view.junction,source.settings,source.profile.tune(source.settings),PointMesh.extent(source.view.junction,source.settings));if(crossing.isPresent()){diamonds.add(crossing.orElseThrow());crossingOwned.add(source.view.junction.id());}}
        }
        Mesh assembly;
        if(diamonds.isEmpty())assembly=DiamondGeometry.guards(runs);
        else {
            // One assembly owns the crossing steel and every check-side run that shares its
            // style: a guard on one view and a crossing wing on another then meet as one rail.
            // Banking runs over both road sets exactly once keeps each vertex in its own frame.
            var roads=diamonds.stream().flatMap(d->d.junction().tracks().stream()).distinct().toList();
            Mesh combined=DiamondGeometry.combine(diamonds,runs);
            assembly=RailSampler.bank(combined,next.get(0).view.junction,union(roads,runs));
        }
        Mesh supports=new Mesh();Mesh untaggedRails=new Mesh();var railSegments=new ArrayList<RailCuts.Segment>();
        for(var source:next)for(var q:source.view.mesh().quads){
            boolean sharedFixedCrossing=source.group==null&&(source.view.junction.kind()==Junction.Kind.DIAMOND||source.view.junction.kind()==Junction.Kind.Y&&!source.settings.movableFrog());
            if(sharedFixedCrossing&&(q.part().equals("frog")||q.part().equals("wing")))continue;
            if(q.part().equals("rail")){
                if(q.rail()==null)untaggedRails.quad(q);
                else {var tag=q.rail().canonical();double lo=tag.start(),hi=tag.end();
                    // A scissors seam really clips the tagged source face. Ordinary views
                    // retain the exact construction stations; re-projecting lateral rail
                    // vertices onto a curve shortens every .24 m cell and creates cracks.
                    if(source.group!=null){lo=Double.MAX_VALUE;hi=-Double.MAX_VALUE;
                        for(var v:List.of(q.a(),q.b(),q.c(),q.d())){double at=tag.road().nearest(v);lo=Math.min(lo,at);hi=Math.max(hi,at);}
                        lo=Math.max(tag.start(),lo);hi=Math.min(tag.end(),hi);
                    }
                    if(hi>lo+1e-7)railSegments.add(new RailCuts.Segment(tag.road(),lo,hi,tag.offset(),source.profile.tune(source.settings),source.settings));
                }
            }else if(q.part().equals("sleeper")||q.part().equals("fastener"))supports.quad(q);
            // A scissors component routes its fixed crossing steel through the shared-region clip
            // instead of DiamondGeometry.combine, so no pooled request owns it. Dropping it here
            // left every wide scissors/crossover with raw rail ends and no frog, wing or blade
            // steel at all. The pooled check runs still own the guard steel, so guards stay out.
            else if(source.group!=null&&!q.part().equals("guard"))supports.quad(q);
            // A view's own check steel is deleted from its GPU mesh: drawGpu always calls
            // PointGpu.update() with removeGuards=true, which skips every guard, wing, rail,
            // sleeper and fastener face the view itself owns. What replaces it is the shared
            // assembly, and only some of these views make the assembly own their check steel.
            // The pooled check runs are built from GuardRails.forJunction() for every view, so a
            // turnout's guard band is always re-supplied -- but they are skipped for a three-way
            // fan, and a fixed crossing additionally pools the turnout's wings through
            // DiamondGeometry.combine(). A movable-frog turnout has no fixed crossing request
            // (fixedY() is empty by design) and a three-way fan has no pooled runs at all, so
            // both lost their whole check assembly and a three-way even lost its guard band.
            // Re-add only what nothing else owns; the crossing paths above keep ownership
            // wherever they have it, so no steel is emitted twice.
            else if(source.group==null&&!crossingOwned.contains(source.view.junction.id())
                &&(q.part().equals("wing")||source.view.junction.kind()==Junction.Kind.THREE&&q.part().equals("guard")))supports.quad(q);
        }
        supports.quads.addAll(RailCuts.assemble(railSegments,untaggedRails,cuts).quads);
        // The assembled check steel is added exactly once. A pooled crossing assembly already
        // carries every check-side run it consumed, so it is not a second render to be
        // suppressed: dropping it left fixed crossings with no guard or wing steel at all.
        supports.quads.addAll(assembly.quads);
        return SurfaceUnion.build(supports);
    }
    private static List<Track> union(List<Track> roads,List<GuardRails.Run> runs){
        var result=new ArrayList<Track>(roads);
        for(var run:runs)if(result.stream().noneMatch(t->t.id.equals(run.road().id)))result.add(run.road());
        return result;
    }
    /** Compile fixed crossing steel after topology/settings changes, outside the frame renderer. */
    public static void prepare(List<PointClient.View> views){guards(views.stream().filter(v->!v.styles.isEmpty()&&v.settings.enabled()).toList());}
    public static void preserve(org.mtr.mod.resource.RailResource resource,boolean flip,V3 a,V3 b){
        var attachments=Profiles.attachments(resource.getId());if(attachments.isEmpty())return;
        V3 f=b.sub(a).unit(),n=f.lateral().mul(flip?1:-1),center=a.lerp(b,.5).add(0,resource.getModelYOffset(),0);double sign=flip?-1:1;
        java.util.function.Function<V3,V3> transform=v->center.add(n.mul(v.x())).add(f.mul(v.z()*sign)).add(0,v.y(),0);
        for(var q:attachments)PRESERVED.add(new Mesh.Quad(transform.apply(q.a()),transform.apply(q.b()),transform.apply(q.c()),transform.apply(q.d()),q.surface(),q.part(),-1,q.uv()));
    }
    public static void render(){
        var mc=net.minecraft.client.Minecraft.getInstance();if(mc.level==null||mc.player==null){clear();return;}
        if(mc.screen instanceof BlueprintScreen||mc.screen instanceof PointSelectionScreen){PRESERVED.clear();return;}
        Map<String,List<Face>> batches=new LinkedHashMap<>();
        for(var q:PRESERVED)batches.computeIfAbsent(q.surface().texture(),k->new ArrayList<>()).add(face(q));PRESERVED.clear();
        Map<Long,Integer> lights=new HashMap<>();
        batches.forEach((texture,faces)->MainRenderer.scheduleRender(TEXTURES.computeIfAbsent(texture,Identifier::new),false,QueuedRenderLayer.EXTERIOR,(graphics,offset)->{
            var access=(GraphicsAccess)(Object)graphics;var out=access.point$vertices();var pose=access.point$poses().last();
            for(var f:faces){var q=f.source;var surface=q.surface();
                int color=partColor(q.part(),surface.color());
                int light=lights.computeIfAbsent(f.lightPos,k->net.minecraft.client.renderer.LevelRenderer.getLightColor(mc.level,net.minecraft.core.BlockPos.of(k)));

                for(int i=0;i<4;i++){V3 v=switch(i){case 0->q.a();case 1->q.b();case 2->q.c();default->q.d();};float u=q.uv()==null?(i==0||i==3?surface.u0():surface.u1()):q.uv().get(i*2),vv=q.uv()==null?(i<2?surface.v0():surface.v1()):q.uv().get(i*2+1);
                    out.vertex(pose.pose(),(float)(v.x()-offset.getXMapped()),(float)(v.y()-offset.getYMapped()),(float)(v.z()-offset.getZMapped()))
                        .color(color).uv(u,vv).overlayCoords(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).uv2(light).normal(pose.normal(),f.nx,f.ny,f.nz).endVertex();
                }
            }
        }));
    }
    public static void drawGpu(net.minecraftforge.client.event.RenderLevelStageEvent e){
        if(e.getStage()!=net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS)return;
        var mc=net.minecraft.client.Minecraft.getInstance();if(mc.level==null||mc.player==null||mc.screen instanceof BlueprintScreen||mc.screen instanceof PointSelectionScreen)return;
        if(knownViews!=PointClient.views){
            knownViews=PointClient.views;var retained=new HashSet<>(knownViews);
            for(var it=GPU.entrySet().iterator();it.hasNext();){var item=it.next();if(!retained.contains(item.getKey())){item.getValue().close();it.remove();}}
        }
        var active=PointClient.views.stream().filter(v->!v.styles.isEmpty()&&v.settings.enabled()).toList();
        for(Mesh mesh:ASSEMBLIES.values()){var gpu=ASSEMBLY_GPU.computeIfAbsent(mesh,k->new PointGpu());if(!gpu.visible(e))continue;gpu.update(mesh,0,false,false,false);gpu.draw(e);}
        for(var view:active){var gpu=GPU.computeIfAbsent(view,k->new PointGpu());if(gpu.hasSource(view.mesh)&&!gpu.visible(e))continue;Mesh mesh=view.mesh();boolean sharedFixedCrossing=view.scissors==null&&(view.junction.kind()==Junction.Kind.DIAMOND||view.junction.kind()==Junction.Kind.Y&&!view.settings.movableFrog());gpu.update(mesh,view.renderedPosition(),view.settings.movableFrog(),true,sharedFixedCrossing);gpu.draw(e);}
    }
    private static int partColor(String part,int normal){
        if(!PROBE_PART_COLORS)return normal;
        return switch(part){
            case "rail"->0xffff2020;
            case "guard"->0xff00ffff;
            case "wing"->0xffffff00;
            case "frog"->0xffff00ff;
            case "blade"->0xff00ff20;
            case "sleeper","fastener"->normal;
            default->0xffff8000;
        };
    }
    private static Face face(Mesh.Quad q){
        V3 u=q.b().sub(q.a()),v=q.c().sub(q.a());double x=u.y()*v.z()-u.z()*v.y(),y=u.z()*v.x()-u.x()*v.z(),z=u.x()*v.y()-u.y()*v.x(),length=Math.max(1e-12,Math.sqrt(x*x+y*y+z*z));V3 c=q.center();
        return new Face(q,(float)(x/length),(float)(y/length),(float)(z/length),net.minecraft.core.BlockPos.containing(c.x(),c.y()+.3,c.z()).asLong());
    }
    /** A view with no mutable node identity: final-assembly fixtures build their meshes up front. */
    static PointClient.View view(Junction junction,PointSettings settings,Profile profile,Mesh mesh,ScissorsLayout group){
        var view=new PointClient.View(junction,settings,profile,Set.of(profile.source()));view.mesh=mesh;view.scissors=group;return view;
    }
    /** Test access to the exact final assembly the frame renderer submits. */
    static Mesh assembleForTest(List<PointClient.View> active){
        var sources=active.stream().map(v->new GuardSource(v,v.settings,v.profile,v.scissors)).distinct().toList();
        return assemble(sources);
    }
}
