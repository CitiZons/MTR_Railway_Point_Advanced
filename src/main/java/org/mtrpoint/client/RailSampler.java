package org.mtrpoint.client;

import org.mtr.core.data.*;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArraySet;
import org.mtrpoint.geometry.*;
import java.util.*;

public final class RailSampler {
    private record Bank(double start,double end,Object frame) {}
    private record Sample(Track track,List<Bank> banks,Rail rail,Object nodeA,Object nodeB) {}
    private static final Map<String,Sample> SAMPLES=new HashMap<>();
    private static java.lang.reflect.Field frameField;
    private static java.lang.reflect.Method renderMethod,nodeMethod,bankMethod,cantAMethod,cantBMethod;
    private static boolean initialized;
    public static long sampleBuilds;
    private static void initialize()throws ReflectiveOperationException{if(initialized)return;if(net.minecraftforge.fml.ModList.get().isLoaded("mtr_optional_rail_addon")){Class<?> geometry=Class.forName("org.mtroptional.client.RailGeometry");frameField=geometry.getField("FRAME");renderMethod=geometry.getMethod("render",Rail.class,RailMath.RenderRail.class,double.class,float.class,float.class);nodeMethod=Class.forName("org.mtroptional.client.ClientNodes").getMethod("get",long.class);Class<?> frame=Class.forName("org.mtroptional.client.RailGeometry$Frame");bankMethod=frame.getMethod("bank",double.class,double.class,double.class);cantAMethod=frame.getMethod("cantA");cantBMethod=frame.getMethod("cantB");}initialized=true;}
    public static void clear(){SAMPLES.clear();}
    public static Track sample(Rail rail){
        try{initialize();}catch(ReflectiveOperationException ex){return null;}
        // Without the Optional Rail adapter the cache depends on the Rail instance alone, because
        // both node handles stay null. Answer from the cache before writing positions and doing two
        // reflective node lookups per rail: the full rebuild calls this for every nearby rail.
        Sample fast=SAMPLES.get(rail.getHexId());
        if(fast!=null&&fast.rail==rail&&nodeMethod==null)return fast.track;
        var ends=new ObjectArraySet<Position>();rail.writePositions(ends);if(ends.size()!=2)return null;Position[] p=ends.toArray(new Position[0]);
        Object na=null,nb=null;
        try{if(nodeMethod!=null){na=nodeMethod.invoke(null,net.minecraft.core.BlockPos.asLong((int)p[0].getX(),(int)p[0].getY(),(int)p[0].getZ()));nb=nodeMethod.invoke(null,net.minecraft.core.BlockPos.asLong((int)p[1].getX(),(int)p[1].getY(),(int)p[1].getZ()));}}catch(ReflectiveOperationException ex){return null;}
        Sample cached=SAMPLES.get(rail.getHexId());
        if(cached!=null&&cached.rail==rail&&Objects.equals(cached.nodeA,na)&&Objects.equals(cached.nodeB,nb))return cached.track;
        sampleBuilds++;
        var start=rail.railMath.getPosition(0,false);
        if(Math.hypot(p[0].getX()+.5-start.x,p[0].getZ()+.5-start.z)>Math.hypot(p[1].getX()+.5-start.x,p[1].getZ()+.5-start.z)){Position t=p[0];p[0]=p[1];p[1]=t;}
        var points=new ArrayList<V3>();var banks=new ArrayList<Bank>();double[] cumulative={0};
        RailMath.RenderRail callback=(x1,z1,x2,z2,x3,z3,x4,z4,y1,y2)->{V3 a=new V3(x1,y1,z1),b=new V3(x3,y2,z3);if(points.isEmpty())points.add(a);points.add(b);double end=cumulative[0]+a.distance(b);if(frameField!=null)try{Object frame=((ThreadLocal<?>)frameField.get(null)).get();if(frame!=null&&(Math.abs(((Number)cantAMethod.invoke(frame)).doubleValue())>1e-8||Math.abs(((Number)cantBMethod.invoke(frame)).doubleValue())>1e-8))banks.add(new Bank(cumulative[0],end,frame));}catch(ReflectiveOperationException ignored){}cumulative[0]=end;};
        boolean optional=net.minecraftforge.fml.ModList.get().isLoaded("mtr_optional_rail_addon");
        if(optional)try{renderMethod.invoke(null,rail,callback,.4,0F,0F);}catch(ReflectiveOperationException ex){org.mtrpoint.PointMod.LOG.warn("Optional Rail geometry adapter failed",ex);return null;}
        else rail.railMath.render(callback,.4,0,0);
        if(points.size()<2)return null;Track track=new Track(rail.getHexId(),node(p[0]),node(p[1]),points);SAMPLES.put(track.id,new Sample(track,List.copyOf(banks),rail,na,nb));return track;
    }
    /** Match the actual MTR/Optional Rail repeat cells, including reversed branch orientation. */
    public static PointMesh.YBoundary yBoundary(Junction j,PointSettings settings){
        var fallback=PointMesh.YBoundary.nominal(j,settings);
        if(j.kind()==Junction.Kind.DIAMOND)return fallback;
        double[] a=lastCell(j.a(),settings,fallback.aEnd(),fallback.aLast());
        double[] b=lastCell(j.b(),settings,fallback.bEnd(),fallback.bLast());
        double[] c=j.third()==null?new double[]{fallback.thirdEnd(),fallback.thirdLast()}:lastCell(j.third(),settings,fallback.thirdEnd(),fallback.thirdLast());
        return new PointMesh.YBoundary(a[0],b[0],a[1],b[1],c[0],c[1]);
    }
    private static double[] lastCell(Track track,PointSettings settings,double nominal,double fallbackLast){
        Sample sample=SAMPLES.get(track.id);if(sample==null)return new double[]{nominal,fallbackLast};
        Rail rail=sample.rail;var intervals=new LinkedHashSet<Double>();
        for(String raw:rail.getStyles()){
            String id=org.mtr.mod.resource.RailResource.getIdWithoutDirection(raw);
            if(id.equals("default")&&org.mtr.mapping.mapper.OptimizedRenderer.hasOptimizedRendering()&&org.mtr.mod.config.Config.getClient().getDefaultRail3D())id=rail.isSiding()?"default_3d_siding":"default_3d";
            if(!Profiles.get(id).track()&&!id.equals(settings.profileStyle()))continue;
            if(id.equals("default"))intervals.add(.5);
            else org.mtr.mod.client.CustomResourceLoader.getRailById(id,r->intervals.add(r.getRepeatInterval()));
        }
        double[] found={-1,-1};
        RailMath.RenderRail callback=(x1,z1,x2,z2,x3,z3,x4,z4,y1,y2)->{
            V3 a=new V3(x1,y1,z1),b=new V3(x3,y2,z3);double center=track.nearest(a.lerp(b,.5));
            if(center<=nominal){found[0]=Math.max(found[0],Math.max(track.nearest(a),track.nearest(b)));found[1]=Math.max(found[1],center);}
        };
        try{
            for(double interval:intervals){if(renderMethod!=null)renderMethod.invoke(null,rail,callback,interval,0F,0F);else rail.railMath.render(callback,interval,0,0);}
        }catch(ReflectiveOperationException ex){org.mtrpoint.PointMod.LOG.warn("Could not sample the turnout end cells",ex);return new double[]{nominal,fallbackLast};}
        return found[1]<0?new double[]{nominal,fallbackLast}:found;
    }
    public static Mesh bank(Mesh mesh,Junction j){return bank(mesh,j,j.tracks());}
    public static Mesh bank(Mesh mesh,Junction j,List<Track> roads){
        Map<String,Sample> byRoad=new java.util.LinkedHashMap<>();
        for(Track t:roads){Sample s=SAMPLES.get(t.id);if(s!=null)byRoad.put(t.id,s);}
        if(byRoad.values().stream().allMatch(v->v.banks.isEmpty()))return mesh;
        List<Sample> samples=List.copyOf(byRoad.values());
        Mesh transformed=new Mesh();Map<V3,V3> cache=new HashMap<>();
        // Tagged vertices are banked in their own road's frame, which is a function of the vertex
        // and that road alone. Caching it by (position, road id) keeps the per-branch correctness
        // while paying one reflective call per distinct vertex instead of one per face corner.
        Map<String,Map<V3,V3>> ownCaches=new HashMap<>();
        for(var q:mesh.quads){
            // A vertex of a tagged running rail is banked in ITS OWN road's frame. The nearest-sample
            // rule cannot tell two branches apart where they run close together, and a rail moved into
            // the other branch's frame follows that branch's curve and slips out of its own faces.
            Sample own=q.rail()==null?null:byRoad.get(q.rail().road().id);
            Map<V3,V3> ownCache=own==null?null:ownCaches.computeIfAbsent(q.rail().road().id,k->new HashMap<>());
            V3 a=ownPoint(q.a(),own,ownCache,cache,samples);
            V3 b=ownPoint(q.b(),own,ownCache,cache,samples);
            V3 c=ownPoint(q.c(),own,ownCache,cache,samples);
            V3 d=ownPoint(q.d(),own,ownCache,cache,samples);
            transformed.quad(new Mesh.Quad(a,b,c,d,q.surface(),q.part(),q.index(),q.uv(),q.rail()));
        }
        return transformed;
    }
    /** Test/verification switch: false restores the pre-cache per-corner path, for the counter's
     * negative control. */
    static boolean ownRoadCache=true;
    /** Entries into the per-sample banker. */
    public static long bankCalls;
    private static V3 ownPoint(V3 p,Sample own,Map<V3,V3> ownCache,Map<V3,V3> cache,List<Sample> samples){
        if(own==null)return cache.computeIfAbsent(p,v->bankPoint(v,samples));
        return ownRoadCache?ownCache.computeIfAbsent(p,v->bankPoint(v,own)):bankPoint(p,own);
    }
    private static V3 bankPoint(V3 p,List<Sample> samples){
        Sample s=null;double best=Double.MAX_VALUE;
        for(Sample candidate:samples){double d=candidate.track.nearest(p),error=p.distance(candidate.track.at(d));if(error<best){best=error;s=candidate;}}
        return bankPoint(p,s);
    }
    private static V3 bankPoint(V3 p,Sample s){
        if(s==null)return p;
        bankCalls++;
        double distance=s.track.nearest(p);
        for(Bank bank:s.banks)if(distance>=bank.start-1e-6&&distance<=bank.end+1e-6)try{var v=(org.mtr.core.tool.Vector)bankMethod.invoke(bank.frame,p.x(),p.y(),p.z());return new V3(v.x,v.y,v.z);}catch(ReflectiveOperationException ex){return p;}return p;
    }
    public static void retain(Set<String> ids){SAMPLES.keySet().retainAll(ids);}
    public static String node(Position p){return p.getX()+","+p.getY()+","+p.getZ();}
}
