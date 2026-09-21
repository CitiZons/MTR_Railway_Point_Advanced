package org.mtrpoint.client;

import net.minecraft.client.Minecraft;
import org.mtr.core.data.*;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.resource.RailResource;
import org.mtrpoint.*;
import org.mtrpoint.geometry.*;
import org.mtrpoint.mixin.VehicleProgressAccess;
import java.util.*;

public final class PointClient {
    public static final class View {
        public final Junction junction;public PointSettings settings;public Profile profile;public Set<String> styles;public double position,target,previewPosition=Double.NaN;public Mesh mesh;public String state="mtrpoint.idle";private double lastPosition=-1;private PointSettings lastSettings;
        public View(Junction j,PointSettings s,Profile p,Set<String> styles){junction=j;settings=s;profile=p;this.styles=Set.copyOf(styles);}
        private Mesh left,middle,right;private int[] moving;private double lastFrame=-1;
        public long builds;public ScissorsLayout scissors;
        private Mesh buildAt(double position,PointMesh.YBoundary boundary){
            Mesh result=scissors!=null&&junction.kind()==Junction.Kind.DIAMOND?scissors.centerMesh(settings,profile):PointMesh.build(junction,settings,profile,position,boundary);
            if(scissors!=null&&junction.kind()!=Junction.Kind.DIAMOND)result=scissors.clip(result,junction,settings);
            return RailSampler.bank(result,junction,scissors==null?junction.tracks():scissors.tracks());
        }
        public Mesh mesh(){
            double visual=Double.isFinite(previewPosition)?previewPosition:position;
            if(left==null||lastSettings!=settings){
                var boundary=scissors!=null&&junction.kind()==Junction.Kind.Y?scissors.boundary(junction,settings):RailSampler.yBoundary(junction,settings);
                left=buildAt(0,boundary);
                middle=junction.kind()==Junction.Kind.THREE?buildAt(.5,boundary):null;
                right=junction.kind()!=Junction.Kind.DIAMOND?buildAt(1,boundary):left;
                if(left.quads.size()!=right.quads.size()||middle!=null&&middle.quads.size()!=left.quads.size())throw new IllegalStateException("Animation topology changed");
                var indices=new ArrayList<Integer>();for(int i=0;i<left.quads.size();i++)if(!left.quads.get(i).equals(right.quads.get(i))||middle!=null&&!left.quads.get(i).equals(middle.quads.get(i)))indices.add(i);else if(right!=left)right.quads.set(i,left.quads.get(i));
                moving=indices.stream().mapToInt(Integer::intValue).toArray();mesh=new Mesh();mesh.quads.addAll(left.quads);lastSettings=settings;lastFrame=-1;builds++;
            }
            // Static steelwork and sleepers are built once. Only blade/frog vertices interpolate.
            if(Math.abs(lastFrame-visual)>.005){
                double blend=middle==null?visual:visual<.5?visual*2:visual*2-1;Mesh start=middle==null||visual<.5?left:middle,end=middle==null||visual>=.5?right:middle;
                for(int i:moving){var a=start.quads.get(i);var b=end.quads.get(i);mesh.quads.set(i,new Mesh.Quad(a.a().lerp(b.a(),blend),a.b().lerp(b.b(),blend),a.c().lerp(b.c(),blend),a.d().lerp(b.d(),blend),a.surface(),a.part(),a.index(),a.uv(),a.rail()));}
                lastFrame=visual;lastPosition=visual;
            }return mesh;
        }
        public double renderedPosition(){return lastPosition;}
        public void preview(PointSettings s){if(settings.equals(s)&&left!=null)return;settings=s;profile=profileFor(junction,s);styles=stylesFor(junction,s);left=null;mesh=null;geometryDirty=true;refreshScissors();}
    }
    public static List<View> views=List.of();
    private static final Map<String,AppearanceData.Entry> SETTINGS=new HashMap<>();
    private static final Map<String,List<View>> BY_RAIL=new HashMap<>();
    private static List<Junction> groupJunctions=List.of();
    private static Map<String,ScissorsLayout> groupsById=Map.of();
    private static Map<String,List<PointNetwork.Movement>> movements=Map.of();
    private static PointNetwork.Motion motion;private static long motionReceived;
    private static int ticks;private static Object level;private static long signature;private static boolean refreshProfiles,geometryDirty;
    public static String message="";
    public static void clear(){views=List.of();SETTINGS.clear();motion=null;signature=0;Profiles.clear();RailSampler.clear();BY_RAIL.clear();NORMALISED.clear();groupJunctions=List.of();groupsById=Map.of();movements=Map.of();geometryDirty=false;PointRenderer.clear();}
    public static long revision(String id){return SETTINGS.getOrDefault(id,new AppearanceData.Entry(PointSettings.DEFAULT,0)).revision();}
    public static PointSettings saved(String id){return SETTINGS.getOrDefault(id,new AppearanceData.Entry(PointSettings.DEFAULT,0)).value();}
    public static void receive(PointNetwork.State m){
        var mc=Minecraft.getInstance();if(mc.level==null||!mc.level.dimension().location().toString().equals(m.dimension()))return;level=mc.level;
        if(m.clear()){SETTINGS.clear();signature=0;return;}
        SETTINGS.put(m.id(),new AppearanceData.Entry(AppearanceData.decode(m.json()),m.revision()));message=m.message();
        for(View v:views)if(v.junction.id().equals(m.id()))v.preview(saved(m.id()));
        if(mc.screen instanceof BlueprintScreen screen)screen.acknowledge(m.id(),m.message());
        if(mc.screen instanceof PointSelectionScreen screen)screen.acknowledge(m.id(),m.message());
    }
    /** The joined level's whole plan in one message: every setting is stored first and the views are
     * then scanned once, instead of once per entry as the old per-entry sync did. */
    public static void receiveBatch(PointNetwork.Batch m){
        var mc=Minecraft.getInstance();if(mc.level==null||!mc.level.dimension().location().toString().equals(m.dimension()))return;
        level=mc.level;var ids=new HashSet<String>();
        for(var entry:m.entries()){SETTINGS.put(entry.id(),new AppearanceData.Entry(AppearanceData.decode(entry.json()),entry.revision()));ids.add(entry.id());signature=0;}
        for(View v:views)if(ids.contains(v.junction.id()))v.preview(saved(v.junction.id()));
    }
    public static void motion(PointNetwork.Motion m){motion=m;motionReceived=System.currentTimeMillis();movements=index(m.entries());}
    public static void tick(){
        var mc=Minecraft.getInstance();if(mc.level!=level){level=mc.level;clear();}if(mc.level==null)return;
        if(++ticks%20==0)rebuild();
        // Blade targeting needs the movements of the trains around this point, not a fresh walk of
        // every vehicle's whole path twenty times a second: the scan is by far the most expensive
        // per-tick work in this class, and a 0.2 s stale target is invisible next to the throw time.
        if(ticks%4==0&&!net.minecraftforge.fml.ModList.get().isLoaded("mtr_brsignal_addon")&&!views.isEmpty())movements=index(nativeMovements());
        for(View v:views){choose(v);double step=.05/v.settings.animationSeconds();v.position+=Math.max(-step,Math.min(step,v.target-v.position));}
        // Leaving the editor only starts the compile; a component still owing its assembly is
        // drained by the next ticks, so the frame that closes the screen is never the one that
        // bakes the whole region.
        if((geometryDirty||PointRenderer.pending())&&!(mc.screen instanceof BlueprintScreen)&&!(mc.screen instanceof PointSelectionScreen)){PointRenderer.prepare(views);geometryDirty=false;}
    }
    public static void invalidate(){signature=0;refreshProfiles=true;Profiles.clear();}
    public static void rebuild(){
        var mc=Minecraft.getInstance();if(mc.player==null)return;
        List<Rail> rails=MinecraftClientData.getInstance().railIdMap.values().stream().filter(r->r.getTransportMode()==TransportMode.TRAIN&&r.railMath.getLength()>1).filter(r->{var m=r.railMath;return mc.player.getX()>=m.minX-96&&mc.player.getX()<=m.maxX+96&&mc.player.getZ()>=m.minZ-96&&mc.player.getZ()<=m.maxZ+96;}).sorted(Comparator.comparing(Rail::getHexId)).toList();
        // Check identities and Optional Rail settings; unchanged tracks reuse their samples.
        var tracks=new ArrayList<Track>();long sig=1;
        for(Rail r:rails){Track t=RailSampler.sample(r);if(t==null)continue;tracks.add(t);sig=31*sig+System.identityHashCode(t);sig=31*sig+r.getStyles().hashCode();}
        if(sig==signature)return;signature=sig;
        Map<String,View> previous=new HashMap<>();views.forEach(v->previous.put(v.junction.id(),v));var next=new ArrayList<View>();
        for(Junction j:Detector.find(tracks)){
            PointSettings s=saved(j.id());View old=previous.get(j.id());
            if(old!=null&&!refreshProfiles&&old.junction.equals(j)&&old.settings.equals(s)&&old.styles.equals(stylesFor(j,s))&&old.profile==profileFor(j,s)){next.add(old);continue;}
            var v=new View(j,s,profileFor(j,s),stylesFor(j,s));if(old!=null){v.position=old.position;v.target=old.target;}
            if(mc.screen instanceof BlueprintScreen editor&&editor.pointId().equals(j.id())){View existing=previous.get(j.id());if(existing!=null){if(refreshProfiles){existing.left=null;existing.preview(existing.settings);}next.add(existing);continue;}}next.add(v);
        }
        refreshProfiles=false;views=List.copyOf(next);geometryDirty=true;refreshScissors();
        RailSampler.retain(tracks.stream().map(t->t.id).collect(java.util.stream.Collectors.toSet()));
    }
    private static void refreshScissors(){
        var junctions=views.stream().filter(v->v.settings.enabled()).map(v->v.junction).toList();
        if(!junctions.equals(groupJunctions)){
            groupJunctions=junctions;var members=new HashMap<String,ScissorsLayout>();
            for(var group:ScissorsLayout.find(junctions)){members.put(group.crossing().id(),group);for(var y:group.turnouts())members.put(y.id(),group);}
            groupsById=members;
        }
        BY_RAIL.clear();for(View v:views){
            ScissorsLayout group=groupsById.get(v.junction.id());if(!Objects.equals(group,v.scissors)){v.scissors=group;v.left=null;v.mesh=null;geometryDirty=true;}
            for(Track road:group!=null&&v.junction.kind()==Junction.Kind.DIAMOND?group.tracks():v.junction.tracks())BY_RAIL.computeIfAbsent(road.id,k->new ArrayList<>()).add(v);
        }
    }
    public static List<String> styleIds(Junction j){var result=new LinkedHashSet<String>();for(Track track:j.tracks()){Rail r=MinecraftClientData.getInstance().railIdMap.get(track.id);if(r!=null)for(String id:r.getStyles()){id=RailResource.getIdWithoutDirection(id);if(id.equals("default")){id=org.mtr.mapping.mapper.OptimizedRenderer.hasOptimizedRendering()&&org.mtr.mod.config.Config.getClient().getDefaultRail3D()?(r.isSiding()?"default_3d_siding":"default_3d"):"default";}result.add(id);}}return List.copyOf(result);}
    private static Profile profileFor(Junction j,PointSettings s){if(!s.profileStyle().isBlank())return Profiles.forced(s.profileStyle());for(String id:styleIds(j)){var a=Profiles.get(id);if(a.track()&&a.profile()!=null)return a.profile();}return new Profile(1.435,.264,.068,.14,.165,Profile.STEEL,Profile.TIMBER,"unmapped",false);}
    private static Set<String> stylesFor(Junction j,PointSettings s){var styles=new HashSet<String>();if(!s.profileStyle().isBlank())styles.add(s.profileStyle());for(String id:styleIds(j))if(Profiles.get(id).track())styles.add(id);return styles;}
    public static V3 editCenter(View v){return v.junction.kind()!=Junction.Kind.DIAMOND?v.junction.a().at(Math.min(5,PointMesh.extent(v.junction,v.settings)/2)).lerp(v.junction.b().at(Math.min(5,PointMesh.extent(v.junction,v.settings)/2)),.5):v.junction.center();}
    public static View nearest(V3 p){return views.stream().filter(v->v.junction.center().distance(p)<64).min(Comparator.comparingDouble(v->editCenter(v).distance(p))).orElse(null);}
    /**
     * True while a point view owns this rail cell, i.e. the native model must be hidden.
     *
     * <p>Called once per rendered rail cell per frame, so the answer is gated on the rail owning a
     * view at all before the style id is normalised: a rail that no view covers -- the large
     * majority of every world -- only pays one map lookup instead of a string normalisation, and
     * every distinct style is normalised once instead of once per cell.
     */
    public static boolean suppress(Rail rail,String style,V3 p,double margin){
        if(rail==null)return false;
        List<View> candidates=BY_RAIL.get(rail.getHexId());
        if(candidates==null||candidates.isEmpty())return false;
        return suppress(candidates,rail.getHexId(),normalised(style),p,margin);
    }
    /** The pre-gate implementation, kept as the reference the gated path is proven against. */
    static boolean suppressReference(Rail rail,String style,V3 p,double margin){
        if(rail==null)return false;
        return suppress(BY_RAIL.getOrDefault(rail.getHexId(),List.of()),rail.getHexId(),RailResource.getIdWithoutDirection(style),p,margin);
    }
    private static boolean suppress(List<View> candidates,String railId,String style,V3 p,double margin){
        for(View v:candidates)if(v.settings.enabled()&&!v.styles.isEmpty()&&v.styles.contains(style)){
            Junction j=v.junction;
            if(v.scissors!=null){if(v.scissors.owns(j,railId,p))return true;continue;}
            if(j.contains(railId,p,margin,PointMesh.extent(j,v.settings)))return true;
        }return false;
    }
    private static final Map<String,String> NORMALISED=new HashMap<>();
    private static String normalised(String style){return NORMALISED.computeIfAbsent(style,RailResource::getIdWithoutDirection);}
    /** Test access: the same query by rail id, so a fixture does not need a real MTR Rail. Takes the
     * same ownership gate the per-cell path takes, so the gate itself is observable. */
    static boolean suppressForTest(String railId,String style,V3 p,double margin){
        List<View> candidates=BY_RAIL.get(railId);
        if(candidates==null||candidates.isEmpty())return false;
        return suppress(candidates,railId,normalised(style),p,margin);
    }
    static boolean suppressReferenceForTest(String railId,String style,V3 p,double margin){return suppress(BY_RAIL.getOrDefault(railId,List.of()),railId,RailResource.getIdWithoutDirection(style),p,margin);}
    static void installForTest(List<View> next){views=List.copyOf(next);refreshScissors();}
    static Set<String> gatedRails(){return BY_RAIL.keySet();}
    static int normalisedForTest(){return NORMALISED.size();}
    private static void choose(View v){
        if(v.junction.kind()==Junction.Kind.DIAMOND)return;List<PointNetwork.Movement> candidates;
        boolean br=net.minecraftforge.fml.ModList.get().isLoaded("mtr_brsignal_addon");
        if(br){if(motion==null||Minecraft.getInstance().level==null||!motion.dimension().equals(Minecraft.getInstance().level.dimension().location().toString())||System.currentTimeMillis()-motionReceived>3000){v.state="mtrpoint.waiting";return;}candidates=movements.getOrDefault(v.junction.a().startNode,List.of());}
        else candidates=movements.getOrDefault(v.junction.a().startNode,List.of());
        String node=v.junction.a().startNode;List<PointNetwork.Movement> matches=candidates.stream().filter(m->m.node().equals(node)&&v.junction.tracks().stream().anyMatch(t->uses(m,t.id))).sorted(Comparator.comparing((PointNetwork.Movement m)->!m.occupied()).thenComparingDouble(PointNetwork.Movement::distance).thenComparingLong(PointNetwork.Movement::vehicle)).toList();
        if(matches.isEmpty()){v.state="mtrpoint.idle";return;}var first=matches.get(0);
        double selected=branch(v.junction,first);
        if(matches.stream().anyMatch(m->m.vehicle()!=first.vehicle()&&m.occupied()==first.occupied()&&branch(v.junction,m)!=selected)){v.state="mtrpoint.ambiguous";return;}
        v.target=selected;v.state=first.occupied()?"mtrpoint.occupied":br?"mtrpoint.authorized":"mtrpoint.observed";
    }
    private static double branch(Junction j,PointNetwork.Movement m){return uses(m,j.a().id)?0:j.third()!=null&&uses(m,j.third().id)?.5:1;}
    private static Map<String,List<PointNetwork.Movement>> index(List<PointNetwork.Movement> entries){var result=new HashMap<String,List<PointNetwork.Movement>>();for(var m:entries)result.computeIfAbsent(m.node(),k->new ArrayList<>()).add(m);return result;}
    public static List<String> profileChoices(Junction j){var ids=new LinkedHashSet<String>();ids.add("");ids.add("default_3d");ids.add("default_3d_siding");ids.addAll(styleIds(j));for(var r:org.mtr.mod.client.CustomResourceLoader.getRails())if(Profiles.get(r.getId()).track())ids.add(r.getId());return List.copyOf(ids);}
    private static boolean uses(PointNetwork.Movement m,String id){return m.from().equals(id)||m.to().equals(id);}
    private static List<PointNetwork.Movement> nativeMovements(){
        var out=new ArrayList<PointNetwork.Movement>();
        for(var vehicle:MinecraftClientData.getInstance().vehicles){double head=((VehicleProgressAccess)vehicle).point$progress(),tail=head-vehicle.vehicleExtraData.getTotalVehicleLength();var path=vehicle.vehicleExtraData.immutablePath;
            for(int i=1;i<path.size();i++){PathData a=path.get(i-1),b=path.get(i);double distance=a.getEndDistance();if(distance<tail||distance>head+64||a.getRail()==null||b.getRail()==null)continue;Position end=a.reversePositions?a.getOrderedPosition1():a.getOrderedPosition2(),start=b.reversePositions?b.getOrderedPosition2():b.getOrderedPosition1();if(!end.equals(start))continue;out.add(new PointNetwork.Movement(RailSampler.node(end),a.getRail().getHexId(),b.getRail().getHexId(),distance<=head,vehicle.getId(),Math.abs(distance-head)));}
        }return out;
    }
}
