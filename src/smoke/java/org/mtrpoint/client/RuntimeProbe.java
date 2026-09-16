package org.mtrpointprobe;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import org.mtrpoint.geometry.*;
import org.mtrpoint.client.*;
import java.util.*;

@Mod("point_runtime_probe")
public final class RuntimeProbe {
    private boolean opened,worldStarted,worldReady,longMotionReceived;private int ticks,worldTicks;
    private final boolean world=Boolean.getBoolean("pointProbeWorld");
    private java.util.List<org.mtr.core.data.Rail> rails;
    public RuntimeProbe(){MinecraftForge.EVENT_BUS.addListener(this::tick);}
    private void tick(TickEvent.ClientTickEvent e){
        if(e.phase!=TickEvent.Phase.END)return;var mc=Minecraft.getInstance();
        if(world&&worldStarted){worldTick(mc);return;}
        if(!opened&&mc.screen instanceof TitleScreen&&mc.getOverlay()==null)try{
            Class.forName("org.mtr.mod.render.RenderRails");Class.forName("org.mtr.core.simulation.Simulator");Class.forName("org.mtr.core.data.Vehicle");Class.forName("org.mtr.mod.resource.RailResource");
            for(var resource:org.mtr.mod.client.CustomResourceLoader.getRails())System.out.println("POINT_PROFILE: "+resource.getId()+" "+Profiles.get(resource.getId()));
            if(!Profiles.get("default_3d").track())throw new AssertionError("Native track profile was not recognized");
            if(!Profiles.get("default_3d_siding").track())throw new AssertionError("Native siding profile was not recognized");
            checkMotionCodec();
            if(world){
                worldStarted=true;
                var settings=new net.minecraft.world.level.LevelSettings("Point isolated render probe",net.minecraft.world.level.GameType.CREATIVE,false,net.minecraft.world.Difficulty.PEACEFUL,true,new net.minecraft.world.level.GameRules(),net.minecraft.world.level.WorldDataConfiguration.DEFAULT);
                mc.createWorldOpenFlows().createFreshLevel("point-probe-"+System.currentTimeMillis(),settings,new net.minecraft.world.level.levelgen.WorldOptions(403L,false,false),registries->registries.registryOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET).getOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.FLAT).createWorldDimensions());return;
            }
            var a=new ArrayList<V3>();var b=new ArrayList<V3>();for(int i=0;i<=120;i++){double z=i*.25;a.add(new V3(-.008*z*z,0,z));b.add(new V3(.008*z*z,0,z));}
            var junction=Detector.find(List.of(new Track("a","0,0,0","-7,0,30",a),new Track("b","0,0,0","7,0,30",b))).get(0);
            var view=new PointClient.View(junction,PointSettings.DEFAULT,Profile.STANDARD,Set.of("default"));
            mc.setScreen(new BlueprintScreen(view));opened=true;System.out.println("POINT_PROBE: mixins loaded and blueprint opened");
        }catch(Throwable error){error.printStackTrace();mc.stop();}
        else if(opened&&++ticks==40)Screenshot.grab(mc.gameDirectory,"point-blueprint.png",mc.getMainRenderTarget(),m->System.out.println("POINT_PROBE: screenshot "+m.getString()));
        else if(opened&&ticks==65){System.out.println("POINT_PROBE: PASS");mc.stop();}
    }
    private void worldTick(Minecraft mc){
        if(mc.level==null||mc.player==null)return;
        if(!worldReady){
            worldReady=true;rails=new ArrayList<>();
            rails.add(rail(0,0,-7,30,90,112.5F));rails.add(rail(0,0,7,30,90,67.5F));rails.add(rail(0,-15,0,0,90,90));
            rails.add(rail(12,5,40,5,0,0));rails.add(rail(26,-9,26,19,90,90));
            mc.getSingleplayerServer().execute(()->{
                var server=mc.getSingleplayerServer();var level=server.overworld();
                for(int x=-14;x<43;x++)for(int z=-17;z<34;z++)level.setBlock(new net.minecraft.core.BlockPos(x,63,z),net.minecraft.world.level.block.Blocks.SMOOTH_STONE.defaultBlockState(),3);
                level.setDayTime(6000);var player=server.getPlayerList().getPlayers().get(0);player.getAbilities().flying=true;player.onUpdateAbilities();player.connection.teleport(20,80,-14,18,37);
            });
        }
        var data=org.mtr.mod.client.MinecraftClientData.getInstance();data.rails.addAll(rails);data.sync();data.railWrapperList.values().forEach(r->r.shouldRender=true);
        if(worldTicks>=65&&worldTicks<=80)try{
            var field=PointClient.class.getDeclaredField("motion");field.setAccessible(true);
            var received=(org.mtrpoint.PointNetwork.Motion)field.get(null);
            if(received!=null&&received.timestamp()==403101L)longMotionReceived=true;
        }catch(ReflectiveOperationException ex){throw new AssertionError(ex);}
        if(++worldTicks==40){PointClient.invalidate();PointClient.rebuild();System.out.println("POINT_WORLD: junctions="+PointClient.views.size());if(PointClient.views.size()!=2)throw new AssertionError("Expected Y + diamond");}
        if(worldTicks==60&&net.minecraftforge.fml.ModList.get().isLoaded("mtr_brsignal_addon")){
            var view=PointClient.views.stream().filter(v->v.junction.kind()==Junction.Kind.Y).findFirst().orElseThrow();
            String dimension=mc.level.dimension().location().toString();
            if(!org.mtrpoint.compat.BrObserver.latest.containsKey(dimension)||view.state.equals("mtrpoint.waiting"))throw new AssertionError("BR snapshot transport/dimension failed");
            String node=view.junction.a().startNode;
            var right=new org.mtrpoint.PointNetwork.Movement(node,"approach",view.junction.b().id,true,1,1);
            var left=new org.mtrpoint.PointNetwork.Movement(node,"approach",view.junction.a().id,false,2,0);
            PointClient.motion(new org.mtrpoint.PointNetwork.Motion(dimension,true,System.currentTimeMillis(),List.of(left,right)));PointClient.tick();
            if(view.target!=1)throw new AssertionError("Occupied branch did not take priority");
            left=new org.mtrpoint.PointNetwork.Movement(node,view.junction.a().id,"approach",true,2,0);
            PointClient.motion(new org.mtrpoint.PointNetwork.Motion(dimension,true,System.currentTimeMillis(),List.of(left,right)));PointClient.tick();
            if(view.target!=1||!view.state.equals("mtrpoint.ambiguous"))throw new AssertionError("Conflicting movements should hold");
            PointClient.motion(new org.mtrpoint.PointNetwork.Motion(dimension,true,System.currentTimeMillis(),List.of(left)));PointClient.tick();
            if(view.target!=0)throw new AssertionError("Reverse movement did not select left branch");
            System.out.println("POINT_MOTION: PASS live BR snapshot transport + synthetic occupied/conflict/reverse selection");
        }
        if(worldTicks==65)mc.getSingleplayerServer().execute(()->{
            try{
                var field=org.mtrpoint.PointNetwork.class.getDeclaredField("CHANNEL");field.setAccessible(true);
                var channel=(net.minecraftforge.network.simple.SimpleChannel)field.get(null);
                var player=mc.getSingleplayerServer().getPlayerList().getPlayers().get(0);
                channel.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(()->player),longMotion());
            }catch(ReflectiveOperationException ex){throw new AssertionError(ex);}
        });
        if(worldTicks==80){if(!longMotionReceived)throw new AssertionError("101-character rail ID packet not received");System.out.println("POINT_NETWORK: PASS 101-character rail IDs delivered through Forge channel");mc.options.hideGui=true;Screenshot.grab(mc.gameDirectory,"point-world.png",mc.getMainRenderTarget(),m->System.out.println("POINT_WORLD: screenshot"));}
        if(worldTicks==82)mc.getSingleplayerServer().execute(()->mc.getSingleplayerServer().getPlayerList().getPlayers().get(0).connection.teleport(1,69,7,0,45));
        if(worldTicks==96)Screenshot.grab(mc.gameDirectory,"point-close.png",mc.getMainRenderTarget(),m->System.out.println("POINT_WORLD: close screenshot"));
        if(worldTicks==100){mc.options.hideGui=false;var view=PointClient.views.get(0);mc.setScreen(new BlueprintScreen(view));var center=view.junction.center();org.mtrpoint.PointNetwork.send(new org.mtrpoint.PointNetwork.Edit(view.junction.id(),net.minecraft.core.BlockPos.containing(center.x(),center.y(),center.z()),org.mtrpoint.client.PointClient.revision(view.junction.id()),org.mtrpoint.AppearanceData.JSON.toJson(PointSettings.DEFAULT.with(11,.02))));}
        if(worldTicks==120)Screenshot.grab(mc.gameDirectory,"point-blueprint-world.png",mc.getMainRenderTarget(),m->System.out.println("POINT_WORLD: UI screenshot"));
        if(worldTicks==140){if(PointClient.revision(PointClient.views.get(0).junction.id())!=1)throw new AssertionError("Appearance save/sync failed");System.out.println("POINT_WORLD: PASS including server appearance save/sync");mc.stop();}
    }
    private static org.mtrpoint.PointNetwork.Motion longMotion(){
        String from=org.mtr.core.data.TwoPositionsBase.getHexId(new org.mtr.core.data.Position(-30000000,-64,30000000),new org.mtr.core.data.Position(-29999999,320,29999999));
        String to=org.mtr.core.data.TwoPositionsBase.getHexId(new org.mtr.core.data.Position(-29999999,320,29999999),new org.mtr.core.data.Position(30000000,-64,-30000000));
        if(from.length()!=101||to.length()!=101)throw new AssertionError("Unexpected MTR rail ID format");
        return new org.mtrpoint.PointNetwork.Motion("minecraft:overworld",true,403101L,List.of(new org.mtrpoint.PointNetwork.Movement("-29999999,320,29999999",from,to,true,42,1.25)));
    }
    private static void checkMotionCodec(){
        var expected=longMotion();var buffer=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try{
            boolean reproduced=false;
            try{buffer.writeUtf(expected.entries().get(0).from(),100);}catch(io.netty.handler.codec.EncoderException ex){reproduced=true;}
            if(!reproduced)throw new AssertionError("Original 100-character limit did not reproduce crash");
            buffer.clear();org.mtrpoint.MotionCodec.encode(expected,buffer);
            if(!expected.equals(org.mtrpoint.MotionCodec.decode(buffer))||buffer.isReadable())throw new AssertionError("Motion packet round-trip changed data");
            System.out.println("POINT_CODEC: PASS reproduced original EncoderException; actual MTR 101-character IDs round-trip intact");
        }finally{buffer.release();}
    }
    private org.mtr.core.data.Rail rail(int x1,int z1,int x2,int z2,float a,float b){
        var p=new org.mtr.core.data.Position(x1,64,z1);var q=new org.mtr.core.data.Position(x2,64,z2);var angles=org.mtr.core.data.Rail.getAngles(p,a,q,b);
        var result=org.mtr.core.data.Rail.newRail(p,angles.left(),q,angles.right(),org.mtr.core.data.Rail.Shape.QUADRATIC,0,org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList.of("default_3d"),80,80,false,false,true,false,true,org.mtr.core.data.TransportMode.TRAIN);
        if(result==null)throw new AssertionError("Probe rail creation failed");return result;
    }
}
