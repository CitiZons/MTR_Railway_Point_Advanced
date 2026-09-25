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
        public long builds;public ScissorsLayout scissors;public ScissorsLayout shared;
        /** The native repeat cell window of a plain crossing, on both sides of it. */
        public PointMesh.DiamondBoundary diamond;
        /** The native cells a turnout covers: its steel ends on a cell edge, not at the nominal
         *  radius, and the renderer has to hide exactly the cells the steel ends on. */
        public PointMesh.YBoundary boundary;
        /** The crossing point this view belongs to, and the roads it draws there when it owns it. */
        public String region;public List<Track> centreRoads;public boolean centreHidden;
        /** The rails whose flange channels have to cut this view's steel, drawn here or not. */
        public List<Track> channels=List.of();
        /** The roads this view sweeps itself, as opposed to the ones it only cuts. */
        public List<Track> drawnRoads(){
            if(scissors!=null)return scissors.tracks();
            if(centreRoads!=null)return centreRoads;
            return junction.tracks();
        }
        /** Every road this view is responsible for: drawn steel plus the channels that shape it. */
        public List<Track> crossingRoads(){
            var out=new LinkedHashMap<String,Track>();
            if(scissors!=null)for(Track t:scissors.tracks())out.putIfAbsent(t.id,t);
            if(centreRoads!=null)for(Track t:centreRoads)out.putIfAbsent(t.id,t);
            else for(Track t:junction.tracks())out.putIfAbsent(t.id,t);
            for(Track t:channels)out.putIfAbsent(t.id,t);
            return List.copyOf(out.values());
        }
        private Mesh buildAt(double position,PointMesh.YBoundary boundary){
            if(centreHidden)return new Mesh();
            Mesh result=centreRoads!=null?PointMesh.centre(junction,centreRoads,diamond,channels,settings,profile,position)
                :scissors!=null&&junction.kind()==Junction.Kind.DIAMOND?scissors.centerMesh(settings,profile)
                :junction.kind()==Junction.Kind.DIAMOND?PointMesh.build(junction,settings,profile,position,diamond)
                :PointMesh.build(junction,settings,profile,position,boundary);
            if(scissors!=null&&junction.kind()!=Junction.Kind.DIAMOND)result=scissors.clip(result,junction);
            // A neighbour that only reaches into the shared region keeps its own steel, but shared
            // flangeways still run over it.
            else if(shared!=null)result=shared.cutShared(result,settings,profile);
            // Rails that cross this one at the same height cut it even where no junction was made:
            // a crossing rail is pieced or short, and the flangeway has to be clear either way.
            if(centreRoads==null&&!channels.isEmpty())result=DiamondGeometry.cutChannels(result,channels,profile.tune(settings),settings,profile.tune(settings).top()+settings.verticalOffset(),junction.kind()==Junction.Kind.THREE);
            return RailSampler.bank(result,junction,scissors==null?junction.tracks():scissors.tracks());
        }
        public Mesh mesh(){
            double visual=Double.isFinite(previewPosition)?previewPosition:position;
            if(left==null||lastSettings!=settings){
                var boundary=claimed(this)!=null?claimed(this):scissors!=null&&junction.kind()==Junction.Kind.Y?scissors.boundary(junction,settings):RailSampler.yBoundary(junction,settings);
                left=buildAt(0,boundary);
                middle=junction.kind()==Junction.Kind.THREE?buildAt(.5,boundary):null;
                right=junction.kind()!=Junction.Kind.DIAMOND?buildAt(1,boundary):left;
                if(left.quads.size()!=right.quads.size()||middle!=null&&middle.quads.size()!=left.quads.size())throw new IllegalStateException("Animation topology changed for "+junction.id()+" "+junction.kind()+" scissors="+(scissors!=null)+" left="+left.quads.size()+" middle="+(middle==null?0:middle.quads.size())+" right="+right.quads.size()+" channels="+channels.size());
                var indices=new ArrayList<Integer>();for(int i=0;i<left.quads.size();i++)if(!left.quads.get(i).equals(right.quads.get(i))||middle!=null&&!left.quads.get(i).equals(middle.quads.get(i)))indices.add(i);else if(right!=left)right.quads.set(i,left.quads.get(i));
                moving=indices.stream().mapToInt(Integer::intValue).toArray();mesh=new Mesh();mesh.quads.addAll(left.quads);lastSettings=settings;lastFrame=-1;builds++;
            }
            // Static steelwork and sleepers are built once. Only blade/frog vertices interpolate.
            if(Math.abs(lastFrame-visual)>.005){
                double blend=middle==null?visual:visual<.5?visual*2:visual*2-1;Mesh start=middle==null||visual<.5?left:middle,end=middle==null||visual>=.5?right:middle;
                for(int i:moving){var a=start.quads.get(i);var b=end.quads.get(i);mesh.quads.set(i,new Mesh.Quad(a.a().lerp(b.a(),blend),a.b().lerp(b.b(),blend),a.c().lerp(b.c(),blend),a.d().lerp(b.d(),blend),a.surface(),a.part(),a.index(),a.uv()));}
                lastFrame=visual;lastPosition=visual;
            }return mesh;
        }
        public double renderedPosition(){return lastPosition;}
        public void preview(PointSettings s){if(settings.equals(s)&&left!=null)return;settings=s;profile=profileFor(junction,s);styles=stylesFor(junction,s);left=null;mesh=null;refreshScissors();}
    }
    public static List<View> views=List.of();
    private static final Map<String,AppearanceData.Entry> SETTINGS=new HashMap<>();
    private static final Map<String,List<View>> BY_RAIL=new HashMap<>();
    private static List<Junction> groupJunctions=List.of();
    private static Map<String,ScissorsLayout> groupsById=Map.of();
    private static List<ScissorsLayout> groups=List.of();
    private static Map<String,List<PointNetwork.Movement>> movements=Map.of();
    private static PointNetwork.Motion motion;private static long motionReceived;
    private static int ticks;private static Object level;private static long signature;private static boolean refreshProfiles;
    public static String message="";
    public static void clear(){views=List.of();SETTINGS.clear();motion=null;signature=0;Profiles.clear();RailSampler.clear();BY_RAIL.clear();groupJunctions=List.of();groupsById=Map.of();movements=Map.of();PointRenderer.clear();}
    public static long revision(String id){return SETTINGS.getOrDefault(id,new AppearanceData.Entry(PointSettings.DEFAULT,0)).revision();}
    public static PointSettings saved(String id){return SETTINGS.getOrDefault(id,new AppearanceData.Entry(PointSettings.DEFAULT,0)).value();}
    public static void receive(PointNetwork.State m){
        var mc=Minecraft.getInstance();if(mc.level==null||!mc.level.dimension().location().toString().equals(m.dimension()))return;level=mc.level;
        if(m.clear()){SETTINGS.clear();signature=0;return;}
        SETTINGS.put(m.id(),new AppearanceData.Entry(AppearanceData.decode(m.json()),m.revision()));message=m.message();
        for(View v:views)if(v.junction.id().equals(m.id()))v.preview(saved(m.id()));
        if(mc.screen instanceof BlueprintScreen screen)screen.acknowledge(m.id(),m.message());
    }
    public static void motion(PointNetwork.Motion m){motion=m;motionReceived=System.currentTimeMillis();movements=index(m.entries());}
    public static void tick(){
        var mc=Minecraft.getInstance();if(mc.level!=level){level=mc.level;clear();}if(mc.level==null)return;
        if(++ticks%20==0)rebuild();
        if(!net.minecraftforge.fml.ModList.get().isLoaded("mtr_brsignal_addon")&&!views.isEmpty())movements=index(nativeMovements());
        for(View v:views){choose(v);double step=.05/v.settings.animationSeconds();v.position+=Math.max(-step,Math.min(step,v.target-v.position));}
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
        var junctions=Detector.find(tracks);
        var known=ScissorsLayout.find(junctions.stream().filter(j->saved(j.id()).enabled()).toList());
        for(Junction j:junctions){
            // An interior crossing a shared centre already draws in full must not build a second,
            // un-notched copy of the same rails inside the shared flangeways.
            if(ScissorsLayout.absorbedByCentre(known,j))continue;
            PointSettings s=saved(j.id());View old=previous.get(j.id());
            if(old!=null&&!refreshProfiles&&old.junction.equals(j)&&old.settings.equals(s)&&old.styles.equals(stylesFor(j,s))&&old.profile==profileFor(j,s)){next.add(old);continue;}
            var v=new View(j,s,profileFor(j,s),stylesFor(j,s));if(old!=null){v.position=old.position;v.target=old.target;}
            if(mc.screen instanceof BlueprintScreen editor&&editor.pointId().equals(j.id())){View existing=previous.get(j.id());if(existing!=null){if(refreshProfiles){existing.left=null;existing.preview(existing.settings);}next.add(existing);continue;}}next.add(v);
        }
        refreshProfiles=false;views=List.copyOf(next);refreshScissors();
        RailSampler.retain(tracks.stream().map(t->t.id).collect(java.util.stream.Collectors.toSet()));
    }
    private static void refreshScissors(){
        var junctions=views.stream().filter(v->v.settings.enabled()).map(v->v.junction).toList();
        if(!junctions.equals(groupJunctions)){
            groupJunctions=junctions;var members=new HashMap<String,ScissorsLayout>();
            groups=ScissorsLayout.find(junctions);
            for(var group:groups){members.put(group.crossing().id(),group);for(var y:group.turnouts())members.put(y.id(),group);}
            groupsById=members;
        }
        BY_RAIL.clear();for(View v:views){
            ScissorsLayout group=groupsById.get(v.junction.id());if(!Objects.equals(group,v.scissors)){v.scissors=group;v.left=null;v.mesh=null;}
            ScissorsLayout shared=group;
            if(shared==null)shared=ScissorsLayout.reachedBy(groups,v.junction);
            if(!Objects.equals(shared,v.shared)){v.shared=shared;v.left=null;v.mesh=null;}
        }
        refreshCrossings(views);
        buildByRail(views);
    }
    /** MTR's own cells are hidden per rail, so the index is built from every road a view answers
     *  for - the steel it draws and the channels that cut it - not from its junction alone. */
    private static void buildByRail(List<View> views){
        BY_RAIL.clear();
        for(View v:views)for(Track road:v.crossingRoads())BY_RAIL.computeIfAbsent(road.id,k->new ArrayList<>()).add(v);
    }
    /** The native hand-over window of every plain crossing. It is read by the renderer mixin while
     *  MTR draws its own rails, so it is prepared with the view set, not lazily with the mesh. */
    static void refreshCrossings(List<View> views){
        var junctions=new ArrayList<Junction>();
        for(View v:views)if(v.settings.enabled())junctions.add(v.junction);
        List<Track> all=RailSampler.tracks();
        // A crossing next to a turnout shares the road with it. Its window is cut back to where the
        // turnout's own mesh ends, so the steel between them is drawn exactly once and the turnout
        // keeps its blades: a plane clip would also cut the other roads of the same mesh.
        var natural=new HashMap<String,PointMesh.YBoundary>();
        for(View v:views)if(v.scissors==null&&v.junction.kind()!=Junction.Kind.DIAMOND)natural.put(v.junction.id(),RailSampler.yBoundary(v.junction,v.settings));
        var regions=new ArrayList<Crossings.Region>();
        for(var region:Crossings.regions(junctions,PointSettings.DEFAULT)){
            var windows=new double[region.roads().size()][];boolean changed=false;
            for(int i=0;i<region.roads().size();i++){
                Track t=region.roads().get(i);double[] w=region.windows().window(i).clone();
                for(View o:views){
                    if(o.scissors!=null||o.junction.kind()==Junction.Kind.DIAMOND)continue;
                    Junction oj=o.junction;Track road=null;double end=0;
                    if(oj.a().id.equals(t.id)){road=oj.a();end=natural.get(oj.id()).aEnd();}
                    else if(oj.b().id.equals(t.id)){road=oj.b();end=natural.get(oj.id()).bEnd();}
                    else if(oj.third()!=null&&oj.third().id.equals(t.id)){road=oj.third();end=natural.get(oj.id()).thirdEnd();}
                    if(road==null)continue;
                    // The turnout's window is measured on its own copy of the road, so the hand-over
                    // station is carried over as a point.
                    double at=t.nearest(road.at(Math.min(end,road.length)));
                    if(at<=w[0]||at>=w[1])continue;
                    if(t.nearest(oj.center())<=(w[0]+w[1])/2)w[0]=at;else w[1]=at;
                    changed=true;
                }
                windows[i]=w;
            }
            regions.add(changed?new Crossings.Region(region.owner(),region.junctions(),region.roads(),new PointMesh.DiamondBoundary(windows)):region);
        }
        var byJunction=new HashMap<String,Crossings.Region>();
        for(var region:regions)for(var member:region.junctions())byJunction.put(member.id(),region);
        for(View v:views){
            Junction j=v.junction;
            if(v.junction.kind()==Junction.Kind.DIAMOND&&v.scissors==null){
                var region=byJunction.get(j.id());
                if(region!=null&&!region.owner().id().equals(v.region)){
                    v.region=region.owner().id();v.diamond=region.windows();
                    v.centreRoads=region.roads();v.centreHidden=!region.owner().id().equals(j.id());
                    v.left=null;v.mesh=null;
                }
            }else if(v.junction.kind()!=Junction.Kind.DIAMOND&&v.scissors==null&&v.boundary==null){
                v.boundary=RailSampler.yBoundary(j,v.settings);
            }
            // A crossing rail cuts this steel by its flange channels even when the two are pieced
            // on different views, so the cut set is worked out from the rails, not from the view.
            List<Track> scope=v.scissors!=null?v.scissors.tracks():j.tracks();
            var cut=Crossings.cutters(scope,v.scissors==null?all:without(all,scope));
            if(!cut.equals(v.channels)){v.channels=cut;v.left=null;v.mesh=null;}
        }
        // A turnout hands the far end of a road over to the crossing or the facing turnout that
        // owns it. Capping the sweep window (not a plane, which would also cut the other roads of
        // the same mesh) is what stops two views drawing the same running rail over each other.
        for(View v:views){
            if(v.scissors!=null||v.junction.kind()==Junction.Kind.DIAMOND)continue;
            Junction j=v.junction;var b=RailSampler.yBoundary(j,v.settings);
            var a=window(v,views,j.a());var bb=window(v,views,j.b());
            var third=j.third()==null?new double[]{0,b.thirdEnd()}:window(v,views,j.third());
            var capped=b.window(Math.max(b.aStart(),a[0]),Math.max(b.bStart(),bb[0]),Math.max(b.thirdStart(),third[0]))
                .withEnds(Math.min(b.aEnd(),a[1]),Math.min(b.bEnd(),bb[1]),Math.min(b.thirdEnd(),third[1]));
            if(!capped.equals(v.boundary)){v.boundary=capped;v.left=null;v.mesh=null;}
        }
    }
    /** The stretch of a road this view may sweep towards a facing turnout that draws the same road.
     *  The crossing windows are already cut back from the other side, so only the midpoint of two
     *  turnouts sharing one rail is decided here. A plane clip would cut every other road of the
     *  same mesh as well, which is why the window is capped instead. */
    private static double[] window(View v,List<View> views,Track t){
        double own=t.nearest(v.junction.center()),start=0,end=Double.MAX_VALUE;
        for(View o:views){
            if(o==v||o.scissors!=null)continue;
            if(o.drawnRoads().stream().noneMatch(r->r.id.equals(t.id)))continue;
            double other=t.nearest(o.junction.center());
            if(other<own)start=Math.max(start,(own+other)/2);else if(other>own)end=Math.min(end,(own+other)/2);
        }
        return new double[]{start,end};
    }
    /** The window a view really claims. The frog casting is drawn to its own heel whatever earlier
     *  clipping did to the boundary, so the rule that hides the native rail has to reach that same
     *  station - otherwise the last stretch of frog overlaps the native rail it should replace. */
    private static PointMesh.YBoundary claimed(View v){
        var b=v.boundary!=null?v.boundary:v.scissors!=null&&v.junction.kind()==Junction.Kind.Y?v.scissors.boundary(v.junction,v.settings):null;
        if(b==null||v.junction.kind()!=Junction.Kind.Y)return b;
        var frog=new FrogGeometry(v.junction,v.settings,v.profile.tune(v.settings),PointMesh.extent(v.junction,v.settings));
        return b.withEnds(Math.max(b.aEnd(),frog.heel(0)),Math.max(b.bEnd(),frog.heel(1)),b.thirdEnd());
    }
    private static List<Track> without(List<Track> all,List<Track> scope){
        var out=new ArrayList<Track>();
        for(Track t:all)if(scope.stream().noneMatch(o->o.id.equals(t.id)))out.add(t);
        return List.copyOf(out);
    }
    /** The stretch of a turnout road the modded mesh really covers. The steel is swept and hidden
     *  over the same window, so a native cell is never hidden where no modded rail was drawn. */
    private static boolean inWindow(Junction j,View v,String rail,V3 p,double margin){
        PointMesh.YBoundary b=claimed(v);
        if(b==null)return j.contains(rail,p,margin,PointMesh.extent(j,v.settings));
        Track t;double start,end;
        if(rail.equals(j.a().id)){t=j.a();start=b.aStart();end=b.aEnd();}
        else if(rail.equals(j.b().id)){t=j.b();start=b.bStart();end=b.bEnd();}
        else if(j.third()!=null&&rail.equals(j.third().id)){t=j.third();start=b.thirdStart();end=b.thirdEnd();}
        else return j.contains(rail,p,margin,PointMesh.extent(j,v.settings));
        double d=t.nearest(p);
        return d>=start-margin&&d<=end+margin&&p.distance(t.at(d))<5;
    }
    public static List<String> styleIds(Junction j){var result=new LinkedHashSet<String>();for(Track track:j.tracks()){Rail r=MinecraftClientData.getInstance().railIdMap.get(track.id);if(r!=null)for(String id:r.getStyles()){id=RailResource.getIdWithoutDirection(id);if(id.equals("default")){id=org.mtr.mapping.mapper.OptimizedRenderer.hasOptimizedRendering()&&org.mtr.mod.config.Config.getClient().getDefaultRail3D()?(r.isSiding()?"default_3d_siding":"default_3d"):"default";}result.add(id);}}return List.copyOf(result);}
    private static Profile profileFor(Junction j,PointSettings s){if(!s.profileStyle().isBlank())return Profiles.forced(s.profileStyle());for(String id:styleIds(j)){var a=Profiles.get(id);if(a.track()&&a.profile()!=null)return a.profile();}return new Profile(1.435,.264,.068,.14,.165,Profile.STEEL,Profile.TIMBER,"unmapped",false);}
    private static Set<String> stylesFor(Junction j,PointSettings s){var styles=new HashSet<String>();if(!s.profileStyle().isBlank())styles.add(s.profileStyle());for(String id:styleIds(j))if(Profiles.get(id).track())styles.add(id);return styles;}
    public static V3 editCenter(View v){return v.junction.kind()!=Junction.Kind.DIAMOND?v.junction.a().at(Math.min(5,PointMesh.extent(v.junction,v.settings)/2)).lerp(v.junction.b().at(Math.min(5,PointMesh.extent(v.junction,v.settings)/2)),.5):v.junction.center();}
    public static View nearest(V3 p){return views.stream().filter(v->v.junction.center().distance(p)<64).min(Comparator.comparingDouble(v->editCenter(v).distance(p))).orElse(null);}
    /** Whether the mod draws this native cell instead of MTR: the same decision the renderer mixin
     *  takes per rail cell, so the hidden region and the modded mesh can never disagree. */
    public static boolean suppress(Rail rail,String style,V3 p,double margin){
        return rail!=null&&suppress(BY_RAIL.getOrDefault(rail.getHexId(),List.of()),rail.getHexId(),style,p,margin);
    }
    /** The native-cell decision for an explicit view set, shared with the renderer mixin so the
     *  rule that hides a native cell and the boundary the mod draws to stay one decision. */
    static boolean suppress(List<View> candidates,String railId,String style,V3 p,double margin){
        if(railId==null)return false;style=RailResource.getIdWithoutDirection(style);
        for(View v:candidates)if(v.settings.enabled()&&!v.styles.isEmpty()&&v.styles.contains(style)){
            Junction j=v.junction;
            if(v.scissors!=null){if(v.scissors.owns(j,railId,p))return true;continue;}
            if(j.kind()==Junction.Kind.DIAMOND){
                // The mesh covers the cells it hides and stops on the next cell edge, on both sides.
                var roads=v.centreRoads!=null?v.centreRoads:j.tracks();
                if(v.diamond!=null)for(int road=0;road<roads.size();road++){
                    Track t=roads.get(road);if(!t.id.equals(railId))continue;
                    double[] window=v.diamond.window(road);double d=t.nearest(p);
                    if(d>=Math.min(window[0],window[1])&&d<=Math.max(window[0],window[1]))return true;
                }
                continue;
            }
            if(inWindow(j,v,railId,p,margin))return true;
        }return false;
    }
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
