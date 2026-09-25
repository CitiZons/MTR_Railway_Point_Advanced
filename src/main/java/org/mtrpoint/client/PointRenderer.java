package org.mtrpoint.client;

import org.mtr.mapping.holder.Identifier;
import org.mtr.mod.render.*;
import org.mtrpoint.geometry.*;
import org.mtrpoint.mixin.GraphicsAccess;
import java.util.*;

/** Persistent world geometry, merged check rails and preserved third-party attachments. */
public final class PointRenderer {
    private static final List<Mesh.Quad> PRESERVED=new ArrayList<>();
    private static final Map<String,Identifier> TEXTURES=new HashMap<>();
    private record GuardSource(PointClient.View view,PointSettings settings,Profile profile,ScissorsLayout group) {}
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
        var next=active.stream().map(v->new GuardSource(v,v.settings,v.profile,v.scissors)).toList();
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
        var runs=new ArrayList<GuardRails.Run>();var steel=new ArrayList<DiamondGeometry.Steel>();
        for(var source:next){
            Profile tuned=source.profile.tune(source.settings);
            // One cut source per view: its swept running steel plus the roads whose flange
            // channels have to stay clear of any check steel that crosses them.
            steel.add(new DiamondGeometry.Steel(source.view.mesh(),tuned.top()+source.settings.verticalOffset(),tuned,source.settings,
                source.view.crossingRoads()));
            for(var original:GuardRails.assembled(source.view.junction,source.settings,source.profile,source.group,
                source.view.junction.kind()==Junction.Kind.THREE&&!source.settings.guardEdits().isEmpty()?RailSampler.yBoundary(source.view.junction,source.settings):null)){
                GuardRails.Run run=original;
                if(source.group!=null&&source.view.junction.kind()!=Junction.Kind.DIAMOND){
                    var group=source.group;boolean before=source.view.junction.center().sub(group.crossing().center()).dot(group.axis())<0;
                    run=run.clip(group.crossing().center().add(group.axis().mul(before?group.lo():group.hi())),group.axis().mul(before?1:-1));
                }
                if(run!=null)runs.add(run);
            }
        }
        // The pool holds check steel from every source at once, so it is cut once against the whole
        // component: a guard that runs into a neighbour's crossing, stock rail or blade is severed
        // exactly where the two swept sections meet instead of passing through it.
        Mesh merged=DiamondGeometry.guards(runs,steel);
        if(!runs.isEmpty())merged=RailSampler.bank(merged,next.get(0).view.junction,runs.stream().map(GuardRails.Run::road).distinct().toList());
        Mesh supports=new Mesh();
        for(int i=0;i<next.size();i++){
            var source=next.get(i);Profile tuned=source.profile.tune(source.settings);
            var cutters=new ArrayList<DiamondGeometry.Steel>(steel);
            Mesh check=new Mesh();
            for(var q:source.view.mesh().quads){
                if(q.part().equals("sleeper")||q.part().equals("fastener"))supports.quad(q);
                else if(q.part().equals("wing"))check.quad(q);
            }
            // The wings are cut by every road of the component, including this view's own: a Y
            // turnout emits its wings from the crossing geometry and never bakes them against its
            // own flange ways, so the wing really does run into the other route's channel. Only the
            // head spans of rail, blade and frog faces cut, so a wing never cuts its own copy.
            supports.quads.addAll(DiamondGeometry.cutSteel(check,cutters,tuned,source.settings,tuned.top()+source.settings.verticalOffset()).quads);
        }
        supports.quads.addAll(merged.quads);merged=SurfaceUnion.build(supports);
        return merged;
    }
    /** Test access to the exact final assembly the frame renderer submits. */
    static Mesh assembleForTest(List<PointClient.View> active){
        return assemble(active.stream().map(v->new GuardSource(v,v.settings,v.profile,v.scissors)).toList());
    }
    /** Test access to everything the world draws for these views: the shared component assembly plus
     *  each view's own steel, filtered exactly like the per-view draw filters it. */
    static Mesh worldForTest(List<PointClient.View> active){
        Mesh world=assemble(active.stream().map(v->new GuardSource(v,v.settings,v.profile,v.scissors)).toList());
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
        PointClient.refreshCrossings(out);
        return out;
    }
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
                int light=lights.computeIfAbsent(f.lightPos,k->net.minecraft.client.renderer.LevelRenderer.getLightColor(mc.level,net.minecraft.core.BlockPos.of(k)));

                for(int i=0;i<4;i++){V3 v=switch(i){case 0->q.a();case 1->q.b();case 2->q.c();default->q.d();};float u=q.uv()==null?(i==0||i==3?surface.u0():surface.u1()):q.uv().get(i*2),vv=q.uv()==null?(i<2?surface.v0():surface.v1()):q.uv().get(i*2+1);
                    out.vertex(pose.pose(),(float)(v.x()-offset.getXMapped()),(float)(v.y()-offset.getYMapped()),(float)(v.z()-offset.getZMapped()))
                        .color(surface.color()).uv(u,vv).overlayCoords(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).uv2(light).normal(pose.normal(),f.nx,f.ny,f.nz).endVertex();
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
        guards(active);
        for(Mesh mesh:ASSEMBLIES.values()){var gpu=ASSEMBLY_GPU.computeIfAbsent(mesh,k->new PointGpu());if(!gpu.visible(e))continue;gpu.update(mesh,0,false,false);gpu.draw(e);}
        for(var view:active){var gpu=GPU.computeIfAbsent(view,k->new PointGpu());if(gpu.hasSource(view.mesh)&&!gpu.visible(e))continue;Mesh mesh=view.mesh();gpu.update(mesh,view.renderedPosition(),view.settings.movableFrog(),true);gpu.draw(e);}
    }
    private static Face face(Mesh.Quad q){
        V3 u=q.b().sub(q.a()),v=q.c().sub(q.a());double x=u.y()*v.z()-u.z()*v.y(),y=u.z()*v.x()-u.x()*v.z(),z=u.x()*v.y()-u.y()*v.x(),length=Math.max(1e-12,Math.sqrt(x*x+y*y+z*z));V3 c=q.center();
        return new Face(q,(float)(x/length),(float)(y/length),(float)(z/length),net.minecraft.core.BlockPos.containing(c.x(),c.y()+.3,c.z()).asLong());
    }
}
