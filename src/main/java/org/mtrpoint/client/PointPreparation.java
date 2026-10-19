package org.mtrpoint.client;

import net.minecraft.client.Minecraft;
import org.mtrpoint.PointConfig;
import org.mtrpoint.geometry.V3;
import java.util.*;
import java.util.function.Supplier;

/** Bounded look-ahead and cold-start measurements; presentation distance remains unchanged. */
final class PointPreparation {
    static final class Timing {
        final long start=System.nanoTime();volatile long cpuStart,cpuEnd;long frames,assembly,gpu;
        <T>T build(Supplier<T> supplier){cpuStart=System.nanoTime();try{return supplier.get();}finally{cpuEnd=System.nanoTime();}}
    }
    private static final Map<PointClient.View,Timing> TIMES=new IdentityHashMap<>();
    private static final double[] LATENCIES=new double[32];private static int samples,cursor;
    private static V3 lastCamera;private static long lastCameraNs;private static double speed,lookAhead=24;private static int startsThisFrame;
    private static final boolean TRACE=Boolean.getBoolean("mtrpoint.tracePreparation");
    static void beginFrame(){
        startsThisFrame=0;
        var mc=Minecraft.getInstance();if(mc==null||mc.gameRenderer==null)return;
        var camera=mc.gameRenderer.getMainCamera().getPosition();V3 current=new V3(camera.x,camera.y,camera.z);long now=System.nanoTime();
        if(lastCamera!=null){double seconds=(now-lastCameraNs)/1e9;if(seconds>0&&seconds<1){double distance=current.distance(lastCamera);double measured=distance/seconds;if(distance<32)speed=Math.min(40,speed*.9+Math.min(40,measured)*.1);else speed=0;}}
        lastCamera=current;lastCameraNs=now;
        lookAhead=lead(speed,latency());
    }
    static double lead(double movementSpeed,double preparationSeconds){return Math.max(24,Math.min(96,Math.max(0,movementSpeed)*Math.max(1,Math.min(6,preparationSeconds))+8));}
    private static double latency(){if(samples==0)return 3;double[] values=Arrays.copyOf(LATENCIES,Math.min(samples,LATENCIES.length));Arrays.sort(values);return values[Math.min(values.length-1,(int)Math.ceil(values.length*.95)-1)];}
    static double cpuDistance(){double distance=PointConfig.takeoverDistance();return distance<=0?0:distance+lookAhead;}
    static double gpuDistance(){double distance=PointConfig.takeoverDistance();return distance<=0?0:distance+Math.min(24,lookAhead);}
    static double distanceSquared(PointClient.View view){V3 camera=lastCamera;if(camera==null)return 0;V3 delta=view.junction.center().sub(camera);return delta.dot(delta);}
    static boolean eligible(PointClient.View view,double distance){
        if(distance<=0)return false;var mc=Minecraft.getInstance();if(mc==null||mc.level==null||mc.gameRenderer==null)return true;
        V3 center=view.junction.center();if(!mc.level.hasChunk((int)Math.floor(center.x()/16),(int)Math.floor(center.z()/16)))return false;
        var camera=mc.gameRenderer.getMainCamera().getPosition();double dx=center.x()-camera.x,dy=center.y()-camera.y,dz=center.z()-camera.z;
        return dx*dx+dy*dy+dz*dz<=distance*distance;
    }
    static Timing started(PointClient.View view){return TIMES.computeIfAbsent(view,key->new Timing());}
    static boolean admit(PointClient.View view){
        var mc=Minecraft.getInstance();if(mc==null||mc.level==null||TIMES.containsKey(view))return true;
        if(startsThisFrame>=1)return false;
        long pending=TIMES.entrySet().stream().filter(e->e.getValue().cpuEnd==0&&eligible(e.getKey(),cpuDistance())).count();
        if(pending>=2)return false;startsThisFrame++;started(view);return true;
    }
    static void framesReady(PointClient.View view){Timing t=started(view);if(t.frames==0)t.frames=System.nanoTime();}
    static void assemblyReady(PointClient.View view){Timing t=started(view);if(t.assembly==0)t.assembly=System.nanoTime();}
    static void gpuReady(PointClient.View view){
        Timing t=started(view);if(t.gpu!=0)return;t.gpu=System.nanoTime();double seconds=(t.gpu-t.start)/1e9;
        LATENCIES[cursor++%LATENCIES.length]=seconds;samples=Math.min(samples+1,LATENCIES.length);
        if(TRACE)System.out.printf(Locale.ROOT,"POINT_PREPARATION: id=%s readyMs=%.2f frameQueueMs=%.2f frameCpuMs=%.2f framesMs=%.2f assemblyMs=%.2f%n",view.junction.id(),seconds*1000,
            t.cpuStart==0?0:(t.cpuStart-t.start)/1e6,t.cpuEnd==0?0:(t.cpuEnd-t.cpuStart)/1e6,t.frames==0?0:(t.frames-t.start)/1e6,t.assembly==0?0:(t.assembly-t.start)/1e6);
    }
    static void retain(Collection<PointClient.View> views){TIMES.keySet().retainAll(views);}
    static void clear(){TIMES.clear();Arrays.fill(LATENCIES,0);samples=cursor=0;lastCamera=null;lastCameraNs=0;speed=0;lookAhead=24;startsThisFrame=0;}
}
