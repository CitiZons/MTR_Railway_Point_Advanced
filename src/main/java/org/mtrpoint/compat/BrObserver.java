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
    private static final Map<String,java.lang.reflect.Method> METHODS=new ConcurrentHashMap<>();
    private static Class<?> managerType;
    private static boolean warned;
    public static void clear(){latest.clear();lastRead.clear();warned=false;}
    public static void observe(Simulator simulator){
        long now=System.currentTimeMillis();if(now-lastRead.getOrDefault(simulator.dimension,0L)<150)return;lastRead.put(simulator.dimension,now);
        if(!net.minecraftforge.fml.ModList.get().isLoaded("mtr_brsignal_addon"))return;
        // MTR stores namespace/path; Minecraft network dimensions use namespace:path.
        int separator=simulator.dimension.indexOf('/');
        String dimension=separator<0?simulator.dimension:simulator.dimension.substring(0,separator)+":"+simulator.dimension.substring(separator+1);
        try{
            Class<?> manager=managerType();
            var out=new ArrayList<PointNetwork.Movement>();
            for(Object vehicle:(List<?>)method(manager,"getVehicleSnapshots",Simulator.class).invoke(null,simulator)){
                double head=num(vehicle,"head"),tail=num(vehicle,"tail");Object path=call(vehicle,"path");
                collect(out,(List<?>)call(path,"getTraversals"),tail,head,true,((Number)call(vehicle,"vehicleId")).longValue(),head);
            }
            for(Object authorization:(List<?>)method(manager,"getAuthorizedPaths",Simulator.class).invoke(null,simulator)){
                // Authorized traversal subsets omit the incoming rail when a permission
                // starts exactly at a node. Use immutable path context, but only emit
                // boundaries inside the published active permission interval.
                collectAuthorization(out,authorization);
            }
            latest.put(dimension,new PointNetwork.Motion(dimension,true,now,List.copyOf(out.subList(0,Math.min(8192,out.size())))));
        }catch(ReflectiveOperationException|RuntimeException ex){if(!warned){warned=true;PointMod.LOG.warn("BR read-only point observer unavailable; holding visual positions",ex);}latest.put(dimension,new PointNetwork.Motion(dimension,true,now,List.of()));}
    }
    private static void collectAuthorization(List<PointNetwork.Movement> out,Object authorization)throws ReflectiveOperationException{
        collect(out,(List<?>)call(call(authorization,"path"),"getTraversals"),num(authorization,"startDistance"),num(authorization,"endDistance"),false,((Number)call(authorization,"vehicleId")).longValue(),num(authorization,"startDistance"));
    }
    private static void collect(List<PointNetwork.Movement> out,List<?> traversals,double begin,double end,boolean occupied,long vehicle,double head)throws ReflectiveOperationException{
        for(int i=1;i<traversals.size();i++){
            Object a=traversals.get(i-1),b=traversals.get(i);double boundary=num(a,"endDistance");
            if(boundary<begin-.01||boundary>end+.01||!occupied&&boundary>=end-1e-6||!call(a,"endNode").equals(call(b,"startNode")))continue;
            net.minecraft.core.BlockPos node=(net.minecraft.core.BlockPos)call(a,"endNode");
            out.add(new PointNetwork.Movement(node.getX()+","+node.getY()+","+node.getZ(),(String)call(a,"sectionId"),(String)call(b,"sectionId"),occupied,vehicle,Math.abs(boundary-head)));
        }
    }
    private static Object call(Object o,String name)throws ReflectiveOperationException{return method(o.getClass(),name).invoke(o);}
    private static double num(Object o,String name)throws ReflectiveOperationException{return ((Number)call(o,name)).doubleValue();}
    private static Class<?> managerType()throws ClassNotFoundException{if(managerType==null)managerType=Class.forName("org.mtrbr.server.RouteRequestManager");return managerType;}
    /** BR snapshots are read every 150 ms and every element of them was re-resolved by name with
     * {@code getClass().getMethod(...)}. That lookup is far more expensive than the call itself and
     * dominated the server tick, so each Method is resolved once per class and parameter shape. */
    private static java.lang.reflect.Method method(Class<?> owner,String name,Class<?>... parameters)throws NoSuchMethodException{
        String key=owner.getName()+'#'+name+'#'+parameters.length;
        var cached=METHODS.get(key);
        if(cached!=null)return cached;
        var resolved=owner.getMethod(name,parameters);
        METHODS.put(key,resolved);
        return resolved;
    }
}
