package org.mtrpointprobe;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.mtr.core.data.*;
import org.mtrpoint.client.*;
import org.mtrpoint.geometry.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Isolated visual/runtime probe for resource-pack continuous guard tracks. */
final class GuardPackProbe {
    private static final String OUTER="citizons_outer_guard_1435",CENTER="citizons_center_guard_1435";
    private static List<Rail> rails;
    private static int ticks;
    private static long builds,frames,uploads;
    private static Object outerBefore,centerBefore;
    private static CompletableFuture<Void> reload;

    static void tick(Minecraft mc){try{run(mc);}catch(Throwable failure){failure.printStackTrace();System.out.println("GUARD_RUNTIME: FAIL");mc.stop();}}
    private static void run(Minecraft mc)throws Exception{
        if(rails==null){
            resources();rails=new ArrayList<>();
            // Same-style joins use both start/end orientations; the mixed join must retain both noses.
            rails.add(rail(-12,-18,-12,0,OUTER));rails.add(rail(-12,18,-12,0,OUTER));
            rails.add(rail(0,-18,0,0,CENTER));rails.add(rail(0,0,0,18,CENTER));
            rails.add(rail(12,-18,12,0,OUTER));rails.add(rail(12,0,12,18,CENTER));
            turnout(24,OUTER);turnout(38,CENTER);
            mc.options.hideGui=true;
            mc.getSingleplayerServer().execute(()->{
                var level=mc.getSingleplayerServer().overworld();for(int x=-19;x<48;x++)for(int z=-24;z<27;z++)level.setBlock(new net.minecraft.core.BlockPos(x,66,z),net.minecraft.world.level.block.Blocks.SMOOTH_STONE.defaultBlockState(),3);
                level.setDayTime(6000);var player=mc.getSingleplayerServer().getPlayerList().getPlayers().get(0);player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.NIGHT_VISION,1200,0,false,false));player.getAbilities().flying=true;player.onUpdateAbilities();player.connection.teleport(-8,68,-20,0,35);
            });
        }
        var data=org.mtr.mod.client.MinecraftClientData.getInstance();data.rails.addAll(rails);data.sync();data.railWrapperList.values().forEach(r->r.shouldRender=true);
        if(++ticks==35){PointClient.invalidate();PointClient.rebuild();checkConnections();checkTurnouts();System.out.println("GUARD_FIXTURE: PASS live styles, reversals, same-style seams, mixed seam, and guarded turnouts");}
        if(ticks==38)look(mc,-8,71,-21,-11.5,67.2,-17);
        if(ticks==60)shot(mc,"guard-outer-endpoint.png");
        if(ticks==63)look(mc,4,71,-21,.5,67.2,-17);
        if(ticks==85)shot(mc,"guard-center-endpoint.png");
        if(ticks==88)look(mc,4,71,-3,.5,67.2,0);
        if(ticks==110)shot(mc,"guard-center-joined-seam.png");
        if(ticks==113)look(mc,16,71,-3,12.5,67.2,0);
        if(ticks==135)shot(mc,"guard-mixed-endpoints.png");
        if(ticks==138)camera(mc,28,70,-5,24,48);
        if(ticks==160)shot(mc,"guard-outer-turnout-fallback.png");
        if(ticks==163)camera(mc,42,70,-5,24,48);
        if(ticks==175){builds=counter("RailCellCache","builds");frames=counter("RailCellCache","frame");uploads=counter("PointGpu","uploads");}
        if(ticks==185)shot(mc,"guard-center-turnout-fallback.png");
        if(ticks==185)checkPerformance();
        if(ticks==186)look(mc,-8,71,-3,-11.5,67.2,0);
        if(ticks==197)shot(mc,"guard-outer-joined-seam.png");
        if(ticks==198){outerBefore=Profiles.model(OUTER);centerBefore=Profiles.model(CENTER);reload=mc.reloadResourcePacks();}
        if(ticks>200&&reload!=null&&reload.isDone()&&mc.getOverlay()==null){
            reload.join();resources();if(Profiles.model(OUTER)==outerBefore||Profiles.model(CENTER)==centerBefore)throw new AssertionError("Resource reload retained stale continuous-guard models");
            PointClient.rebuild();System.out.println("GUARD_RELOAD: PASS both profile models replaced after resource reload");System.out.println("GUARD_RUNTIME: PASS");mc.stop();
        }
        if(ticks>600)throw new AssertionError("Guard pack probe timed out");
    }
    private static void resources(){
        checkModel(OUTER,.4,.1,0,true);checkModel(CENTER,1.8,.286,.55,false);
        System.out.println("GUARD_PROFILE: PASS outer shared supports and center independent supports; endpoint/nose dimensions validated");
    }
    private static void checkModel(String style,double length,double inset,double nose,boolean shared){
        var adapted=Profiles.get(style);var model=Profiles.model(style);
        if(!adapted.track()||adapted.profile()==null||model==null||model.continuousGuard()==null)throw new AssertionError("Continuous guard profile missing: "+style);
        var guard=model.continuousGuard();
        if(guard.rails().isEmpty()||guard.supports().isEmpty()||guard.endpoint().isEmpty())throw new AssertionError("Continuous guard roles missing: "+style);
        if(Math.abs(guard.length()-length)>1e-6||Math.abs(guard.supportInset()-inset)>1e-6||Math.abs(guard.noseLength()-nose)>1e-6||guard.sharedSupports()!=shared)throw new AssertionError("Continuous guard contract differs: "+style+" length="+guard.length()+" inset="+guard.supportInset()+" nose="+guard.noseLength()+" shared="+guard.sharedSupports());
        if(model.detail()==null||model.detail().fittings().isEmpty())throw new AssertionError("Turnout ordinary model fittings missing: "+style);
    }
    private static void checkConnections()throws Exception{
        var outer=rails.stream().filter(r->styleOf(r).equals(OUTER)).toList();var center=rails.stream().filter(r->styleOf(r).equals(CENTER)).toList();
        boolean outerJoin=false,centerJoin=false,mixedJoin=false;
        for(var a:outer)for(var b:outer)if(a!=b&&shares(a,b)){var ea=exposed(a,OUTER);var eb=exposed(b,OUTER);if(!ea[0]||!ea[1]||!eb[0]||!eb[1])outerJoin=true;}
        for(var a:center)for(var b:center)if(a!=b&&shares(a,b)){var ea=exposed(a,CENTER);var eb=exposed(b,CENTER);if(!ea[0]||!ea[1]||!eb[0]||!eb[1])centerJoin=true;}
        for(var a:outer)for(var b:center)if(shares(a,b)){var ea=exposed(a,OUTER);var eb=exposed(b,CENTER);if(ea[0]&&ea[1]&&eb[0]&&eb[1])mixedJoin=true;}
        if(!outerJoin||!centerJoin)throw new AssertionError("Same-style endpoint joins were not detected");
        if(!mixedJoin)throw new AssertionError("Mixed-style node suppressed an endpoint");
        System.out.println("GUARD_ENDPOINTS: PASS joined outer/center seams suppress noses; mixed seam retains both noses");
    }
    private static String styleOf(Rail r){return r.getStyles().get(0).replace("_2","");}
    private static boolean shares(Rail a,Rail b){var x=RailSampler.sample(a);var y=RailSampler.sample(b);return x.sharesNode(y);}
    private static boolean[] exposed(Rail rail,String style)throws Exception{
        Class<?> type=Class.forName("org.mtrpoint.client.ContinuousGuards");Method method=type.getDeclaredMethod("exposed",Rail.class,String.class);method.setAccessible(true);Object ends=method.invoke(null,rail,style);
        Method start=ends.getClass().getDeclaredMethod("start"),end=ends.getClass().getDeclaredMethod("end");start.setAccessible(true);end.setAccessible(true);return new boolean[]{(boolean)start.invoke(ends),(boolean)end.invoke(ends)};
    }
    private static void checkTurnouts(){
        for(String style:List.of(OUTER,CENTER)){
            var matching=PointClient.views.stream().filter(v->v.profile.source().equals(style)).toList();if(matching.isEmpty())throw new AssertionError("No turnout detected for "+style);
            for(var view:matching){if(view.profile.detail()==null||view.mesh().quads.stream().noneMatch(q->q.part().startsWith("fastener")))throw new AssertionError("Turnout fallback lacks ordinary fittings: "+style);}
        }
        System.out.println("GUARD_TURNOUT: PASS both guard styles use ordinary detailed turnout fallback; views="+PointClient.views.size());
    }
    private static void checkPerformance()throws Exception{
        long elapsed=counter("RailCellCache","frame")-frames,newBuilds=counter("RailCellCache","builds")-builds,newUploads=counter("PointGpu","uploads")-uploads;
        if(elapsed<1||newBuilds!=0||newUploads!=0)throw new AssertionError("Stationary guard view rebuilt geometry: frames="+elapsed+" builds="+newBuilds+" uploads="+newUploads);
        System.out.println("GUARD_PERFORMANCE: PASS stationary frames="+elapsed+" geometryBuilds="+newBuilds+" uploads="+newUploads);
    }
    private static void turnout(int x,String style){rails.add(rail(x,-18,x,0,style));rails.add(rail(x,0,x-6,22,style));rails.add(rail(x,0,x+6,22,style));}
    private static Rail rail(int x1,int z1,int x2,int z2,String style){
        var p=new Position(x1,67,z1);var q=new Position(x2,67,z2);var angles=Rail.getAngles(p,90,q,90);String selected=z1>z2?style+"_2":style;
        var rail=Rail.newRail(p,angles.left(),q,angles.right(),Rail.Shape.QUADRATIC,0,org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList.of(selected),80,80,false,false,true,false,true,TransportMode.TRAIN);
        if(rail==null)throw new AssertionError("Cannot create guard fixture rail");return rail;
    }
    private static long counter(String type,String name)throws Exception{var field=Class.forName("org.mtrpoint.client."+type).getDeclaredField(name);field.setAccessible(true);return field.getLong(null);}
    private static void camera(Minecraft mc,double x,double y,double z,float yaw,float pitch){mc.getSingleplayerServer().execute(()->mc.getSingleplayerServer().getPlayerList().getPlayers().get(0).connection.teleport(x,y,z,yaw,pitch));}
    private static void look(Minecraft mc,double x,double y,double z,double tx,double ty,double tz){double dx=tx-x,dy=ty-y-1.62,dz=tz-z;camera(mc,x,y,z,(float)Math.toDegrees(Math.atan2(-dx,dz)),(float)-Math.toDegrees(Math.atan2(dy,Math.hypot(dx,dz))));}
    private static void shot(Minecraft mc,String name){Screenshot.grab(mc.gameDirectory,name,mc.getMainRenderTarget(),m->System.out.println("GUARD_SCREENSHOT: "+name));}
}
