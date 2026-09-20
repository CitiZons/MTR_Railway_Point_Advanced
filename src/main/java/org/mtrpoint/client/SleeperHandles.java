package org.mtrpoint.client;

import org.mtrpoint.geometry.*;
import java.util.*;
import java.util.function.Function;

/** Whole-bearer hit regions; selection does not depend on whichever mesh face came first. */
final class SleeperHandles {
    record Handle(int index,V3 center,List<Mesh.Quad> faces) {}
    private SleeperHandles(){}

    static List<Handle> collect(Mesh mesh){
        var groups=new LinkedHashMap<Integer,List<Mesh.Quad>>();
        // Fasteners sit directly below every rail and made almost the whole railway selectable as
        // a sleeper. Only the sleeper body defines its hit region; fasteners remain visual mesh.
        for(var q:mesh.quads)if(q.index()>=0&&q.part().equals("sleeper"))groups.computeIfAbsent(q.index(),k->new ArrayList<>()).add(q);
        var result=new ArrayList<Handle>();
        groups.forEach((index,faces)->{
            V3 sum=new V3(0,0,0);int count=0;for(var q:faces)for(V3 v:List.of(q.a(),q.b(),q.c(),q.d())){sum=sum.add(v);count++;}
            result.add(new Handle(index,sum.mul(1D/count),List.copyOf(faces)));
        });
        return List.copyOf(result);
    }

    static Handle hit(List<Handle> handles,Function<V3,double[]> project,double x,double y,double limit){
        Handle best=null;double distance=limit;
        for(var handle:handles){double candidate=distance(handle,project,x,y);if(candidate<distance-1e-7||Math.abs(candidate-distance)<=1e-7&&best!=null&&handle.index>best.index){distance=candidate;best=handle;}}
        return best;
    }

    private static double distance(Handle handle,Function<V3,double[]> project,double x,double y){
        double best=Double.MAX_VALUE;boolean inside=false;
        for(var q:handle.faces){V3[] vertices={q.a(),q.b(),q.c(),q.d()};for(int i=0;i<4;i++){
            double[] a=project.apply(vertices[i]),b=project.apply(vertices[(i+1)%4]);double dx=b[0]-a[0],dy=b[1]-a[1],length=dx*dx+dy*dy;
            double t=length<1e-9?0:Math.max(0,Math.min(1,((x-a[0])*dx+(y-a[1])*dy)/length));best=Math.min(best,Math.hypot(x-a[0]-dx*t,y-a[1]-dy*t));
        }double[] a=project.apply(q.a()),b=project.apply(q.b()),c=project.apply(q.c()),d=project.apply(q.d());inside|=triangle(a,b,c,x,y)||triangle(a,c,d,x,y);}
        if(inside){double[] center=project.apply(handle.center);return (handle.index>=256?-2:0)-1/(1+Math.hypot(x-center[0],y-center[1]));}
        return best;
    }
    private static boolean triangle(double[] a,double[] b,double[] c,double x,double y){
        double ab=(b[0]-a[0])*(y-a[1])-(b[1]-a[1])*(x-a[0]),bc=(c[0]-b[0])*(y-b[1])-(c[1]-b[1])*(x-b[0]),ca=(a[0]-c[0])*(y-c[1])-(a[1]-c[1])*(x-c[0]);
        return ab>=-1e-7&&bc>=-1e-7&&ca>=-1e-7||ab<=1e-7&&bc<=1e-7&&ca<=1e-7;
    }
}
