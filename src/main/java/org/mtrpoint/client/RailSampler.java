package org.mtrpoint.client;

import org.mtr.core.data.*;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArraySet;
import org.mtrpoint.geometry.*;
import java.util.*;

public final class RailSampler {
    private record Bank(double start,double end,Object frame) {}
    private record Sample(Track track,List<Bank> banks) {}
    private static final Map<String,Sample> SAMPLES=new HashMap<>();
    private static java.lang.reflect.Field frameField;
    public static void clear(){SAMPLES.clear();}
    public static Track sample(Rail rail){
        var ends=new ObjectArraySet<Position>();rail.writePositions(ends);if(ends.size()!=2)return null;Position[] p=ends.toArray(new Position[0]);
        var start=rail.railMath.getPosition(0,false);
        if(Math.hypot(p[0].getX()+.5-start.x,p[0].getZ()+.5-start.z)>Math.hypot(p[1].getX()+.5-start.x,p[1].getZ()+.5-start.z)){Position t=p[0];p[0]=p[1];p[1]=t;}
        var points=new ArrayList<V3>();var banks=new ArrayList<Bank>();double[] cumulative={0};
        RailMath.RenderRail callback=(x1,z1,x2,z2,x3,z3,x4,z4,y1,y2)->{V3 a=new V3(x1,y1,z1),b=new V3(x3,y2,z3);if(points.isEmpty())points.add(a);points.add(b);double end=cumulative[0]+a.distance(b);if(frameField!=null)try{Object frame=((ThreadLocal<?>)frameField.get(null)).get();if(frame!=null)banks.add(new Bank(cumulative[0],end,frame));}catch(IllegalAccessException ignored){}cumulative[0]=end;};
        boolean optional=net.minecraftforge.fml.ModList.get().isLoaded("mtr_optional_rail_addon");
        if(optional)try{Class<?> geometry=Class.forName("org.mtroptional.client.RailGeometry");frameField=geometry.getField("FRAME");geometry.getMethod("render",Rail.class,RailMath.RenderRail.class,double.class,float.class,float.class).invoke(null,rail,callback,.4,0F,0F);}catch(ReflectiveOperationException ex){org.mtrpoint.PointMod.LOG.warn("Optional Rail geometry adapter failed",ex);return null;}
        else rail.railMath.render(callback,.4,0,0);
        if(points.size()<2)return null;Track track=new Track(rail.getHexId(),node(p[0]),node(p[1]),points);SAMPLES.put(track.id,new Sample(track,List.copyOf(banks)));return track;
    }
    public static Mesh bank(Mesh mesh,Junction j){
        Sample a=SAMPLES.get(j.a().id),b=SAMPLES.get(j.b().id);if(a==null||b==null||a.banks.isEmpty()&&b.banks.isEmpty())return mesh;
        Mesh transformed=new Mesh();Map<V3,V3> cache=new HashMap<>();
        for(var q:mesh.quads)transformed.quad(cache.computeIfAbsent(q.a(),p->bankPoint(p,a,b)),cache.computeIfAbsent(q.b(),p->bankPoint(p,a,b)),cache.computeIfAbsent(q.c(),p->bankPoint(p,a,b)),cache.computeIfAbsent(q.d(),p->bankPoint(p,a,b)),q.surface(),q.part(),q.index());
        return transformed;
    }
    private static V3 bankPoint(V3 p,Sample a,Sample b){double da=a.track.nearest(p),db=b.track.nearest(p);Sample s=p.distance(a.track.at(da))<=p.distance(b.track.at(db))?a:b;double distance=s==a?da:db;
        for(Bank bank:s.banks)if(distance>=bank.start-1e-6&&distance<=bank.end+1e-6)try{var v=(org.mtr.core.tool.Vector)bank.frame.getClass().getMethod("bank",double.class,double.class,double.class).invoke(bank.frame,p.x(),p.y(),p.z());return new V3(v.x,v.y,v.z);}catch(ReflectiveOperationException ex){return p;}return p;
    }
    public static String node(Position p){return p.getX()+","+p.getY()+","+p.getZ();}
}
