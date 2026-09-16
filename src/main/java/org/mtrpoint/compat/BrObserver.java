package org.mtrpoint.compat;

import org.mtr.core.simulation.Simulator;
import org.mtrpoint.PointMod;
import org.mtrpoint.PointNetwork;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Reads only BR's published immutable snapshots. No reserve, release, path or display API calls. */
public final class BrObserver {
    public static final Map<String,PointNetwork.Motion> latest=new ConcurrentHashMap<>();
    private static final Map<String,Long> lastRead=new ConcurrentHashMap<>();
    private static boolean warned;
    public static void clear(){latest.clear();lastRead.clear();warned=false;}
    public static void observe(Simulator simulator){
        long now=System.currentTimeMillis();if(now-lastRead.getOrDefault(simulator.dimension,0L)<150)return;lastRead.put(simulator.dimension,now);
        if(!net.minecraftforge.fml.ModList.get().isLoaded("mtr_brsignal_addon"))return;
        // MTR stores namespace/path; Minecraft network dimensions use namespace:path.
        int separator=simulator.dimension.indexOf('/');
        String dimension=separator<0?simulator.dimension:simulator.dimension.substring(0,separator)+":"+simulator.dimension.substring(separator+1);
        try{
            Class<?> manager=Class.forName("org.mtrbr.server.RouteRequestManager");
            var out=new ArrayList<PointNetwork.Movement>();
            for(Object vehicle:(List<?>)manager.getMethod("getVehicleSnapshots",Simulator.class).invoke(null,simulator)){
                double head=num(vehicle,"head"),tail=num(vehicle,"tail");Object path=call(vehicle,"path");
                collect(out,(List<?>)call(path,"getTraversals"),tail,head,true,((Number)call(vehicle,"vehicleId")).longValue(),head);
            }
            for(Object authorization:(List<?>)manager.getMethod("getAuthorizedPaths",Simulator.class).invoke(null,simulator)){
                collect(out,(List<?>)call(authorization,"traversals"),num(authorization,"startDistance"),num(authorization,"endDistance"),false,((Number)call(authorization,"vehicleId")).longValue(),num(authorization,"startDistance"));
            }
            latest.put(dimension,new PointNetwork.Motion(dimension,true,now,List.copyOf(out.subList(0,Math.min(8192,out.size())))));
        }catch(ReflectiveOperationException|RuntimeException ex){if(!warned){warned=true;PointMod.LOG.warn("BR read-only point observer unavailable; holding visual positions",ex);}latest.put(dimension,new PointNetwork.Motion(dimension,true,now,List.of()));}
    }
    private static void collect(List<PointNetwork.Movement> out,List<?> traversals,double begin,double end,boolean occupied,long vehicle,double head)throws ReflectiveOperationException{
        for(int i=1;i<traversals.size();i++){
            Object a=traversals.get(i-1),b=traversals.get(i);double boundary=num(a,"endDistance");
            if(boundary<begin-.01||boundary>end+.01||!call(a,"endNode").equals(call(b,"startNode")))continue;
            net.minecraft.core.BlockPos node=(net.minecraft.core.BlockPos)call(a,"endNode");
            out.add(new PointNetwork.Movement(node.getX()+","+node.getY()+","+node.getZ(),(String)call(a,"sectionId"),(String)call(b,"sectionId"),occupied,vehicle,Math.abs(boundary-head)));
        }
    }
    private static Object call(Object o,String method)throws ReflectiveOperationException{return o.getClass().getMethod(method).invoke(o);}
    private static double num(Object o,String method)throws ReflectiveOperationException{return ((Number)call(o,method)).doubleValue();}
}
