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
        public Mesh mesh(){double visual=Double.isFinite(previewPosition)?previewPosition:position;if(mesh==null||lastSettings!=settings||Math.abs(lastPosition-visual)>.005){mesh=RailSampler.bank(PointMesh.build(junction,settings,profile,visual),junction);lastSettings=settings;lastPosition=visual;}return mesh;}
        public void preview(PointSettings s){settings=s;profile=profileFor(junction,s);styles=stylesFor(junction,s);mesh=null;}
    }
    public static List<View> views=List.of();
    private static final Map<String,AppearanceData.Entry> SETTINGS=new HashMap<>();
    private static PointNetwork.Motion motion;private static long motionReceived;
    private static int ticks;private static Object level;private static long signature;private static String server="";
    public static String message="";
    public static void clear(){views=List.of();SETTINGS.clear();motion=null;signature=0;Profiles.clear();RailSampler.clear();}
    public static long revision(String id){return SETTINGS.getOrDefault(id,new AppearanceData.Entry(PointSettings.DEFAULT,0)).revision();}
    public static PointSettings saved(String id){return SETTINGS.getOrDefault(id,new AppearanceData.Entry(PointSettings.DEFAULT,0)).value();}
    public static void receive(PointNetwork.State m){
        var mc=Minecraft.getInstance();if(mc.level==null||!mc.level.dimension().location().toString().equals(m.dimension()))return;level=mc.level;
        if(m.clear()){SETTINGS.clear();signature=0;return;}
        SETTINGS.put(m.id(),new AppearanceData.Entry(AppearanceData.decode(m.json()),m.revision()));message=m.message();
        for(View v:views)if(v.junction.id().equals(m.id()))v.preview(saved(m.id()));
        if(mc.screen instanceof BlueprintScreen screen)screen.acknowledge(m.id(),m.message());
    }
    public static void motion(PointNetwork.Motion m){motion=m;motionReceived=System.currentTimeMillis();}
    public static void tick(){
        var mc=Minecraft.getInstance();if(mc.level!=level){level=mc.level;clear();}if(mc.level==null)return;
        if(++ticks%20==0)rebuild();
        for(View v:views){choose(v);double step=.05/v.settings.animationSeconds();v.position+=Math.max(-step,Math.min(step,v.target-v.position));}
    }
    public static void invalidate(){signature=0;Profiles.clear();}
    public static void rebuild(){
        var mc=Minecraft.getInstance();if(mc.player==null)return;
        List<Rail> rails=MinecraftClientData.getInstance().railIdMap.values().stream().filter(r->r.getTransportMode()==TransportMode.TRAIN&&r.railMath.getLength()>1).filter(r->{var m=r.railMath;return mc.player.getX()>=m.minX-96&&mc.player.getX()<=m.maxX+96&&mc.player.getZ()>=m.minZ-96&&mc.player.getZ()<=m.maxZ+96;}).sorted(Comparator.comparing(Rail::getHexId)).toList();
        // A bounded periodic resample includes changes made by Optional Rail and live resource reloads.
        var tracks=new ArrayList<Track>();long sig=1;
        for(Rail r:rails){Track t=RailSampler.sample(r);if(t==null)continue;tracks.add(t);sig=31*sig+t.points.hashCode();sig=31*sig+r.getStyles().hashCode();}
        if(sig==signature)return;signature=sig;
        Map<String,View> previous=new HashMap<>();views.forEach(v->previous.put(v.junction.id(),v));var next=new ArrayList<View>();
        for(Junction j:Detector.find(tracks)){
            PointSettings s=saved(j.id());var v=new View(j,s,profileFor(j,s),stylesFor(j,s));View old=previous.get(j.id());if(old!=null){v.position=old.position;v.target=old.target;}
            if(mc.screen instanceof BlueprintScreen editor&&editor.pointId().equals(j.id())){View existing=previous.get(j.id());if(existing!=null){next.add(existing);continue;}}next.add(v);
        }
        views=List.copyOf(next);
    }
    public static List<String> styleIds(Junction j){var result=new LinkedHashSet<String>();for(String rail:List.of(j.a().id,j.b().id)){Rail r=MinecraftClientData.getInstance().railIdMap.get(rail);if(r!=null)for(String id:r.getStyles()){id=RailResource.getIdWithoutDirection(id);if(id.equals("default")){id=org.mtr.mapping.mapper.OptimizedRenderer.hasOptimizedRendering()&&org.mtr.mod.config.Config.getClient().getDefaultRail3D()?(r.isSiding()?"default_3d_siding":"default_3d"):"default";}result.add(id);}}return List.copyOf(result);}
    private static Profile profileFor(Junction j,PointSettings s){if(!s.profileStyle().isBlank())return Profiles.forced(s.profileStyle());for(String id:styleIds(j)){var a=Profiles.get(id);if(a.track()&&a.profile()!=null)return a.profile();}return new Profile(1.435,.264,.068,.14,.165,Profile.STEEL,Profile.TIMBER,"unmapped",false);}
    private static Set<String> stylesFor(Junction j,PointSettings s){var styles=new HashSet<String>();if(!s.profileStyle().isBlank())styles.add(s.profileStyle());for(String id:styleIds(j))if(Profiles.get(id).track())styles.add(id);return styles;}
    public static View nearest(V3 p){return views.stream().filter(v->v.junction.center().distance(p)<64).min(Comparator.comparingDouble(v->v.junction.center().distance(p))).orElse(null);}
    public static boolean suppress(Rail rail,String style,V3 p,double margin){
        if(rail==null)return false;style=RailResource.getIdWithoutDirection(style);
        for(View v:views)if(v.settings.enabled()&&!v.styles.isEmpty()&&v.styles.contains(style)){
            Junction j=v.junction;Junction mask=new Junction(j.id(),j.kind(),j.a(),j.b(),j.center(),j.sa(),j.sb(),PointMesh.extent(j,v.settings));
            if(mask.contains(rail.getHexId(),p,margin))return true;
        }return false;
    }
    private static void choose(View v){
        if(v.junction.kind()!=Junction.Kind.Y)return;List<PointNetwork.Movement> candidates;
        boolean br=net.minecraftforge.fml.ModList.get().isLoaded("mtr_brsignal_addon");
        if(br){if(motion==null||Minecraft.getInstance().level==null||!motion.dimension().equals(Minecraft.getInstance().level.dimension().location().toString())||System.currentTimeMillis()-motionReceived>3000){v.state="mtrpoint.waiting";return;}candidates=motion.entries();}
        else candidates=nativeMovements();
        String node=v.junction.a().startNode;List<PointNetwork.Movement> matches=candidates.stream().filter(m->m.node().equals(node)&&(uses(m,v.junction.a().id)||uses(m,v.junction.b().id))).sorted(Comparator.comparing((PointNetwork.Movement m)->!m.occupied()).thenComparingDouble(PointNetwork.Movement::distance).thenComparingLong(PointNetwork.Movement::vehicle)).toList();
        if(matches.isEmpty()){v.state="mtrpoint.idle";return;}var first=matches.get(0);
        int selected=uses(first,v.junction.a().id)?0:1;
        if(matches.stream().anyMatch(m->m.vehicle()!=first.vehicle()&&m.occupied()==first.occupied()&&(uses(m,v.junction.a().id)?0:1)!=selected)){v.state="mtrpoint.ambiguous";return;}
        v.target=selected;v.state=first.occupied()?"mtrpoint.occupied":br?"mtrpoint.authorized":"mtrpoint.observed";
    }
    private static boolean uses(PointNetwork.Movement m,String id){return m.from().equals(id)||m.to().equals(id);}
    private static List<PointNetwork.Movement> nativeMovements(){
        var out=new ArrayList<PointNetwork.Movement>();
        for(var vehicle:MinecraftClientData.getInstance().vehicles){double head=((VehicleProgressAccess)vehicle).point$progress(),tail=head-vehicle.vehicleExtraData.getTotalVehicleLength();var path=vehicle.vehicleExtraData.immutablePath;
            for(int i=1;i<path.size();i++){PathData a=path.get(i-1),b=path.get(i);double distance=a.getEndDistance();if(distance<tail||distance>head+64||a.getRail()==null||b.getRail()==null)continue;Position end=a.reversePositions?a.getOrderedPosition1():a.getOrderedPosition2(),start=b.reversePositions?b.getOrderedPosition2():b.getOrderedPosition1();if(!end.equals(start))continue;out.add(new PointNetwork.Movement(RailSampler.node(end),a.getRail().getHexId(),b.getRail().getHexId(),distance<=head,vehicle.getId(),Math.abs(distance-head)));}
        }return out;
    }
}
