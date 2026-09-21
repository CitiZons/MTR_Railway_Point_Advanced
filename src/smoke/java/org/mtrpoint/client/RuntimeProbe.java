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
    private boolean opened,worldStarted,worldReady;private static volatile boolean longMotionReceived;private int ticks,worldTicks;
    private String sleeperEditId;private int sleeperEditIndex;private double sleeperEditValue;
    private String panelEditId;private double panelEditValue;
    private final boolean world=Boolean.getBoolean("pointProbeWorld"),existing=Boolean.getBoolean("pointProbeExisting");
    private java.util.List<org.mtr.core.data.Rail> rails;
    public static void observeMotion(org.mtrpoint.PointNetwork.Motion received){if(received.timestamp()==403101L)longMotionReceived=true;}
    public RuntimeProbe(){MinecraftForge.EVENT_BUS.addListener(this::tick);}
    private void tick(TickEvent.ClientTickEvent e){
        if(e.phase!=TickEvent.Phase.END)return;var mc=Minecraft.getInstance();
        if(existing){if(mc.level!=null&&mc.player!=null)existingTick(mc);return;}
        if(world&&worldStarted){worldTick(mc);return;}
        if(!opened&&mc.screen instanceof TitleScreen&&mc.getOverlay()==null)try{
            Class.forName("org.mtr.mod.render.RenderRails");Class.forName("org.mtr.core.simulation.Simulator");Class.forName("org.mtr.core.data.Vehicle");Class.forName("org.mtr.mod.resource.RailResource");
            for(var resource:org.mtr.mod.client.CustomResourceLoader.getRails())System.out.println("POINT_PROFILE: "+resource.getId()+" track="+Profiles.get(resource.getId()).track());
            if(!Profiles.get("default_3d").track())throw new AssertionError("Native track profile was not recognized");
            if(!Profiles.get("default_3d_siding").track())throw new AssertionError("Native siding profile was not recognized");
            checkMotionCodec();checkProfiles();
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
        if(++worldTicks==40){PointClient.invalidate();PointClient.rebuild();System.out.println("POINT_WORLD: junctions="+PointClient.views.size());if(PointClient.views.size()!=2)throw new AssertionError("Expected Y + diamond");checkBoundary();}
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
        if(worldTicks==82)mc.getSingleplayerServer().execute(()->mc.getSingleplayerServer().getPlayerList().getPlayers().get(0).connection.teleport(29,69,1,37,48));
        if(worldTicks==90)Screenshot.grab(mc.gameDirectory,"point-diamond-close.png",mc.getMainRenderTarget(),m->{});
        if(worldTicks==92)mc.getSingleplayerServer().execute(()->mc.getSingleplayerServer().getPlayerList().getPlayers().get(0).connection.teleport(1,69,7,0,45));
        if(worldTicks==96)Screenshot.grab(mc.gameDirectory,"point-close.png",mc.getMainRenderTarget(),m->System.out.println("POINT_WORLD: close screenshot"));
        if(worldTicks==100){mc.options.hideGui=false;var view=PointClient.views.get(0);mc.setScreen(new BlueprintScreen(view));var center=view.junction.center();org.mtrpoint.PointNetwork.send(new org.mtrpoint.PointNetwork.Edit(view.junction.id(),net.minecraft.core.BlockPos.containing(center.x(),center.y(),center.z()),org.mtrpoint.client.PointClient.revision(view.junction.id()),org.mtrpoint.AppearanceData.JSON.toJson(PointSettings.DEFAULT.with(11,.02))));}
        if(worldTicks==160)Screenshot.grab(mc.gameDirectory,"point-blueprint-world.png",mc.getMainRenderTarget(),m->System.out.println("POINT_WORLD: UI screenshot"));
        if(worldTicks==140){checkEditor(mc);}
        if(worldTicks==170){
            click(mc,"mtrpoint.tab_2");chooseSleeper(mc,0);click(mc,"mtrpoint.tab_3");
            field(mc,"guard_shift","0.2");
        }
        if(worldTicks==173)Screenshot.grab(mc.gameDirectory,"point-angles.png",mc.getMainRenderTarget(),m->{});
        if(worldTicks==175){
            var selected=PointClient.views.stream().filter(v->v.junction.kind()==Junction.Kind.Y).findFirst().orElseThrow();
            click(mc,"mtrpoint.apply");
        }
        if(worldTicks==185){click(mc,"mtrpoint.close");mc.options.hideGui=true;}
        if(worldTicks>185&&mc.screen instanceof net.minecraft.client.gui.screens.PauseScreen)mc.setScreen(null);
        if(worldTicks==195)Screenshot.grab(mc.gameDirectory,"point-siding.png",mc.getMainRenderTarget(),m->{});
        if(worldTicks==205){if(PointClient.revision(PointClient.views.get(0).junction.id())!=2)throw new AssertionError("Appearance save/sync failed");if(PointClient.saved(PointClient.views.get(0).junction.id()).guardShift()!=.2)throw new AssertionError("New fine control save/sync");System.out.println("POINT_WORLD: PASS including server appearance save/sync and guard control");}
        if(worldTicks==206){data.rails.remove(rails.remove(2));rails.add(rail(-7,-30,0,0,67.5F,90));rails.add(rail(7,-30,0,0,112.5F,90));}
        if(worldTicks==211){
            PointClient.invalidate();PointClient.rebuild();
            var ys=PointClient.views.stream().filter(v->v.junction.kind()==Junction.Kind.Y).toList();
            if(ys.size()!=2)throw new AssertionError("Both Y sides not found in actual MTR scene: "+ys.size());
            for(var v:ys)if(PointClient.nearest(PointClient.editCenter(v))!=v)throw new AssertionError("Cannot select each Y side");
            System.out.println("POINT_DOUBLE_Y: PASS two independent Y views, each selectable by its side");
            var selected=ys.stream().filter(v->PointClient.editCenter(v).z()>0).findFirst().orElseThrow();
            selected.preview(PointSettings.DEFAULT.flags(true,true));selected.previewPosition=0;
        }
        if(worldTicks==220)Screenshot.grab(mc.gameDirectory,"point-movable-left.png",mc.getMainRenderTarget(),m->{});
        if(worldTicks==223)PointClient.views.stream().filter(v->v.junction.kind()==Junction.Kind.Y&&PointClient.editCenter(v).z()>0).findFirst().orElseThrow().previewPosition=1;
        if(worldTicks==230)Screenshot.grab(mc.gameDirectory,"point-movable-right.png",mc.getMainRenderTarget(),m->{});
        if(worldTicks==235){
            data.rails.removeAll(rails);rails.clear();rails.add(rail(0,0,-7,30,90,112.5F));rails.add(rail(0,0,7,30,90,67.5F));rails.add(rail(0,0,0,30,90,90));rails.add(rail(0,-15,0,0,90,90));
        }
        if(worldTicks==245){
            PointClient.invalidate();PointClient.rebuild();var view=PointClient.views.stream().filter(v->v.junction.kind()==Junction.Kind.THREE).findFirst().orElseThrow();
            view.previewPosition=0;var left=List.copyOf(view.mesh().quads);view.previewPosition=.5;var middle=List.copyOf(view.mesh().quads);view.previewPosition=1;var right=List.copyOf(view.mesh().quads);
            if(left.size()!=middle.size()||middle.size()!=right.size()||left.equals(middle)||middle.equals(right))throw new AssertionError("Three-way frame cache");
            if(net.minecraftforge.fml.ModList.get().isLoaded("mtr_brsignal_addon")){
                String dimension=mc.level.dimension().location().toString();
                for(int i=0;i<3;i++){Track branch=view.junction.tracks().get(i);PointClient.motion(new org.mtrpoint.PointNetwork.Motion(dimension,true,System.currentTimeMillis(),List.of(new org.mtrpoint.PointNetwork.Movement(branch.startNode,"approach",branch.id,true,99,0))));PointClient.tick();if(view.target!=i*.5)throw new AssertionError("Three-way BR branch selection");}
            }
            view.previewPosition=0;mc.options.hideGui=false;mc.setScreen(new BlueprintScreen(view));click(mc,"mtrpoint.test_motion");if(view.previewPosition!=.5)throw new AssertionError("Three-way preview middle");click(mc,"mtrpoint.test_motion");if(view.previewPosition!=1)throw new AssertionError("Three-way preview third");click(mc,"mtrpoint.apply");
        }
        if(worldTicks==254)Screenshot.grab(mc.gameDirectory,"point-three-ui.png",mc.getMainRenderTarget(),m->{});
        if(worldTicks==256){var view=PointClient.views.stream().filter(v->v.junction.kind()==Junction.Kind.THREE).findFirst().orElseThrow();if(PointClient.revision(view.junction.id())!=1)throw new AssertionError("Three-way save rejected");click(mc,"mtrpoint.close");mc.options.hideGui=true;view.previewPosition=.5;System.out.println("POINT_THREE: PASS six-rail model, 3 positions, UI, independent save, BR selection when present");}
        if(worldTicks==265)Screenshot.grab(mc.gameDirectory,"point-three.png",mc.getMainRenderTarget(),m->{});
        if(worldTicks==270){scissorsScene(mc,false);}
        if(worldTicks==280){checkScissors(mc);}
        if(worldTicks==290)Screenshot.grab(mc.gameDirectory,"point-scissors.png",mc.getMainRenderTarget(),m->{});
        if(worldTicks==292){var view=PointClient.views.stream().filter(v->v.junction.kind()==Junction.Kind.Y).findFirst().orElseThrow();mc.options.hideGui=false;mc.setScreen(new BlueprintScreen(view));click(mc,"mtrpoint.tab_2");field(mc,"spacing","0.55");click(mc,"mtrpoint.apply");}
        if(worldTicks==300){if(PointClient.views.stream().filter(v->v.junction.kind()==Junction.Kind.Y&&PointClient.saved(v.junction.id()).sleeperSpacing()==.55).count()!=1)throw new AssertionError("Scissors edit changed neighboring turnout");click(mc,"mtrpoint.close");mc.options.hideGui=true;}
        if(worldTicks==305){scissorsScene(mc,true);}
        if(worldTicks==315){checkScissors(mc);}
        if(worldTicks==318){mc.setScreen(new PointSelectionScreen());mc.screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_TAB,0,0);}
        if(worldTicks==320)Screenshot.grab(mc.gameDirectory,"point-selection-scissors.png",mc.getMainRenderTarget(),m->{});
        if(worldTicks==322){
            try{
                var selector=mc.screen;var selected=PointSelectionScreen.class.getDeclaredField("selected");selected.setAccessible(true);
                var list=PointSelectionScreen.class.getDeclaredField("points");list.setAccessible(true);
                var points=(List<PointClient.View>)list.get(selector);if(points.size()<5)throw new AssertionError("Complex plan lost points");
                var project=PointSelectionScreen.class.getDeclaredMethod("project",V3.class);project.setAccessible(true);
                for(var point:points){double[] pixel=(double[])project.invoke(selector,PointClient.editCenter(point));if(pixel[0]<8||pixel[0]>selector.width-198||pixel[1]<65||pixel[1]>selector.height-45)throw new AssertionError("Initial plan hides marker");}
                checkUnifiedEditor(mc);
                String expected=points.get(selected.getInt(selector)).junction.id();selector.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER,0,0);
                if(!(mc.screen instanceof BlueprintScreen editor)||!editor.pointId().equals(expected))throw new AssertionError("Plan selected a different point");
                click(mc,"mtrpoint.close");System.out.println("POINT_SELECTOR: PASS five scissors editors, Tab selection and Enter opens selected ID");
            }catch(ReflectiveOperationException ex){throw new AssertionError(ex);}
        }
        if(worldTicks>=324&&worldTicks<334&&panelEditId!=null&&Math.abs(PointClient.saved(panelEditId).values()[11]-panelEditValue)<=1e-9&&PointClient.revision(panelEditId)>=1){
            System.out.println("POINT_UNIFIED_SAVE: PASS per-turnout edit applied from the plan reached the server");panelEditId=null;
        }
        if(worldTicks==334&&panelEditId!=null)throw new AssertionError("Per-turnout save from the unified plan was not persisted within 12 client ticks");
        if(worldTicks==325)Screenshot.grab(mc.gameDirectory,"point-scissors-curved.png",mc.getMainRenderTarget(),m->{});
        if(worldTicks==327){var center=PointClient.views.stream().filter(v->v.junction.kind()==Junction.Kind.DIAMOND).findFirst().orElseThrow().junction.center();mc.getSingleplayerServer().execute(()->mc.getSingleplayerServer().getPlayerList().getPlayers().get(0).connection.teleport(center.x()+3,center.y()+7,center.z()-5,31,55));}
        if(worldTicks==338)Screenshot.grab(mc.gameDirectory,"point-crossing-close.png",mc.getMainRenderTarget(),m->{});
        if(worldTicks==340){var v=PointClient.views.stream().filter(t->t.junction.kind()==Junction.Kind.DIAMOND).findFirst().orElseThrow();v.preview(v.settings.style("default_3d_siding"));}
        if(worldTicks==350)Screenshot.grab(mc.gameDirectory,"point-crossing-siding.png",mc.getMainRenderTarget(),m->{});
        if(worldTicks==355){
            data.rails.removeAll(rails);rails.clear();rails.add(rail(0,0,0,30,90,90));rails.add(rail(0,0,7,30,90,67.5F));rails.add(rail(0,-15,0,0,90,90));
        }
        if(worldTicks==365){
            PointClient.invalidate();PointClient.rebuild();var view=PointClient.views.stream().filter(v->v.junction.kind()==Junction.Kind.Y).findFirst().orElseThrow();
            mc.options.hideGui=false;mc.setScreen(new BlueprintScreen(view));
        }
        if(worldTicks==373)Screenshot.grab(mc.gameDirectory,"point-asymmetric-blueprint.png",mc.getMainRenderTarget(),m->{});
        if(worldTicks==376){
            click(mc,"mtrpoint.close");mc.options.hideGui=true;
            var view=PointClient.views.stream().filter(v->v.junction.kind()==Junction.Kind.Y).findFirst().orElseThrow();
            var frog=new FrogGeometry(view.junction,view.settings,view.profile.tune(view.settings),PointMesh.extent(view.junction,view.settings));
            var at=view.junction.a().at(frog.sa).lerp(view.junction.b().at(frog.sb),.5);
            mc.getSingleplayerServer().execute(()->mc.getSingleplayerServer().getPlayerList().getPlayers().get(0).connection.teleport(at.x(),at.y()+4,at.z()-.5,0,83));
        }
        if(worldTicks==390){
            Screenshot.grab(mc.gameDirectory,"point-asymmetric-wing.png",mc.getMainRenderTarget(),m->{});
            checkGuardCache();System.out.println("POINT_ASYMMETRIC: PASS actual MTR straight/curved branch, blueprint and wing detail");
        }
        if(worldTicks==393){checkGpu();checkPermission(mc);checkLanguages(mc);mc.setScreen(new PointSelectionScreen());}
        if(worldTicks==395)editSleeper(mc);
        if(worldTicks==399)Screenshot.grab(mc.gameDirectory,"point-selection.png",mc.getMainRenderTarget(),m->{});
        if(worldTicks==401){
            double saved=PointClient.saved(sleeperEditId).sleeperShifts().getOrDefault(sleeperEditIndex,Double.NaN);
            if(Math.abs(saved-sleeperEditValue)>1e-9)throw new AssertionError("Selector tie edit was not saved");
            System.out.println("POINT_SLEEPER_EDITOR: PASS select, drag, preview and server save from area plan");
            mc.screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER,0,0);if(!(mc.screen instanceof BlueprintScreen))throw new AssertionError("Selector did not open explicit point");click(mc,"mtrpoint.tab_3");
        }
        if(worldTicks==407)Screenshot.grab(mc.gameDirectory,"point-direct-tabs.png",mc.getMainRenderTarget(),m->{});
        if(worldTicks==410){language(mc,"ja_jp");mc.setScreen(new BlueprintScreen(PointClient.views.get(0)));click(mc,"mtrpoint.tab_2");}
        if(worldTicks==414)Screenshot.grab(mc.gameDirectory,"point-ui-ja.png",mc.getMainRenderTarget(),m->{});
        if(worldTicks==416){click(mc,"mtrpoint.select_back");if(!(mc.screen instanceof PointSelectionScreen))throw new AssertionError("Return to plan failed");}
        if(worldTicks==419)Screenshot.grab(mc.gameDirectory,"point-selection-ja.png",mc.getMainRenderTarget(),m->{});
        if(worldTicks==421){language(mc,"en_us");mc.setScreen(new BlueprintScreen(PointClient.views.get(0)));click(mc,"mtrpoint.tab_1");}
        if(worldTicks==424)Screenshot.grab(mc.gameDirectory,"point-ui-en.png",mc.getMainRenderTarget(),m->{});
        if(worldTicks==426){click(mc,"mtrpoint.select_back");}
        if(worldTicks==430)Screenshot.grab(mc.gameDirectory,"point-selection-en.png",mc.getMainRenderTarget(),m->{});
        if(worldTicks==433){checkDenseGpu();mc.setScreen(null);mc.options.hideGui=true;scissorsScene(mc,false);}
        if(worldTicks==440){
            PointClient.invalidate();PointClient.rebuild();var v=PointClient.views.stream().filter(t->t.junction.kind()==Junction.Kind.DIAMOND).findFirst().orElseThrow();
            v.preview(v.settings.with(16,4));V3 c=v.junction.center();
            mc.getSingleplayerServer().execute(()->mc.getSingleplayerServer().getPlayerList().getPlayers().get(0).connection.teleport(c.x(),c.y()+14,c.z(),0,90));
        }
        if(worldTicks==451)Screenshot.grab(mc.gameDirectory,"point-v-composite-top.png",mc.getMainRenderTarget(),m->{});
        if(worldTicks==455){var v=PointClient.views.stream().filter(t->t.junction.kind()==Junction.Kind.DIAMOND).findFirst().orElseThrow();v.preview(v.settings.with(16,1));}
        if(worldTicks==465)Screenshot.grab(mc.gameDirectory,"point-parallel-composite-top.png",mc.getMainRenderTarget(),m->{});
        if(worldTicks==470){data.rails.removeAll(rails);rails.clear();rails.add(rail(0,0,0,65,90,90));rails.add(rail(0,0,7,65,90,67.5F));}
        if(worldTicks==480){
            PointClient.invalidate();PointClient.rebuild();var v=PointClient.views.stream().filter(t->t.junction.kind()==Junction.Kind.Y).findFirst().orElseThrow();
            double start=TurnoutFrame.start(v.junction,PointMesh.extent(v.junction,v.settings));
            if(start<10)throw new AssertionError("Real MTR long-approach fixture has no remote divergence");
            V3 c=v.junction.a().at(start+2);v.previewPosition=0;
            mc.getSingleplayerServer().execute(()->mc.getSingleplayerServer().getPlayerList().getPlayers().get(0).connection.teleport(c.x(),c.y()+7,c.z(),0,90));
            System.out.println("POINT_REMOTE: blade starts "+start+" m after node");
        }
        if(worldTicks==491)Screenshot.grab(mc.gameDirectory,"point-remote-bar-left.png",mc.getMainRenderTarget(),m->{});
        if(worldTicks==493)PointClient.views.stream().filter(t->t.junction.kind()==Junction.Kind.Y).findFirst().orElseThrow().previewPosition=1;
        if(worldTicks==499)Screenshot.grab(mc.gameDirectory,"point-remote-bar-right.png",mc.getMainRenderTarget(),m->{});
        if(worldTicks==502){
            data.rails.removeAll(rails);rails.clear();
            rails.add(rail(0,0,-7,40,90,112.5F));rails.add(rail(0,0,0,40,90,90));rails.add(rail(0,0,13,40,90,67.5F));
            rails.add(rail(0,-35,0,0,90,90));rails.add(rail(-9,-35,0,0,67.5F,90));
        }
        if(worldTicks==512){
            PointClient.invalidate();PointClient.rebuild();
            if(PointClient.views.stream().filter(v->v.junction.kind()==Junction.Kind.THREE).count()!=1||PointClient.views.stream().filter(v->v.junction.kind()==Junction.Kind.Y).count()!=1)throw new AssertionError("Real MTR 3+2 node detection");
            checkPartitionCache();
            mc.getSingleplayerServer().execute(()->mc.getSingleplayerServer().getPlayerList().getPlayers().get(0).connection.teleport(0,101,5,0,90));
            System.out.println("POINT_DUAL_ASYMMETRIC: PASS actual MTR 3+2 node with independent views");
        }
        if(worldTicks==524)Screenshot.grab(mc.gameDirectory,"point-dual-asymmetric-top.png",mc.getMainRenderTarget(),m->{});
        if(worldTicks==527){
            var view=PointClient.views.stream().filter(v->v.junction.kind()==Junction.Kind.THREE).findFirst().orElseThrow();
            mc.getSingleplayerServer().execute(()->mc.getSingleplayerServer().getPlayerList().getPlayers().get(0).connection.teleport(3,81,13,0,90));
        }
        if(worldTicks==539)Screenshot.grab(mc.gameDirectory,"point-asymmetric-three-close.png",mc.getMainRenderTarget(),m->{});
        if(worldTicks==542){
            var view=PointClient.views.stream().filter(v->v.junction.kind()==Junction.Kind.THREE).findFirst().orElseThrow();view.preview(view.settings.flags(true,true));
            view.previewPosition=0;int count=view.mesh().quads.size();view.previewPosition=.5;if(view.mesh().quads.size()!=count)throw new AssertionError("Native three moving topology");view.previewPosition=1;if(view.mesh().quads.size()!=count)throw new AssertionError("Native three moving topology");view.previewPosition=0;
            System.out.println("POINT_THREE_MOVABLE: PASS native profile stable three-position mesh");
        }
        if(worldTicks==551)Screenshot.grab(mc.gameDirectory,"point-asymmetric-three-moving-0.png",mc.getMainRenderTarget(),m->{});
        if(worldTicks==553)PointClient.views.stream().filter(v->v.junction.kind()==Junction.Kind.THREE).findFirst().orElseThrow().previewPosition=1;
        if(worldTicks==563)Screenshot.grab(mc.gameDirectory,"point-asymmetric-three-moving-1.png",mc.getMainRenderTarget(),m->{});
        if(worldTicks==566){System.out.println("POINT_WORLD_FINAL: PASS");mc.stop();}

    }
    private static void checkBoundary(){
        var view=PointClient.views.stream().filter(v->v.junction.kind()==Junction.Kind.Y).findFirst().orElseThrow();
        var boundary=RailSampler.yBoundary(view.junction,view.settings);double[] ends={boundary.aEnd(),boundary.bEnd()},ties={boundary.aLast(),boundary.bLast()};
        double[] repeat={.6};org.mtr.mod.client.CustomResourceLoader.getRailById("default_3d",r->repeat[0]=r.getRepeatInterval());
        for(int branch=0;branch<2;branch++){
            Track track=branch==0?view.junction.a():view.junction.b();var rail=org.mtr.mod.client.MinecraftClientData.getInstance().railIdMap.get(track.id);double[] hiddenEnd={0},nextTie={Double.POSITIVE_INFINITY};
            rail.railMath.render((x1,z1,x2,z2,x3,z3,x4,z4,y1,y2)->{
                V3 a=new V3(x1,y1,z1),b=new V3(x3,y2,z3),center=a.lerp(b,.5);double d=track.nearest(center);
                if(PointClient.suppress(rail,"default_3d",center,0))hiddenEnd[0]=Math.max(hiddenEnd[0],Math.max(track.nearest(a),track.nearest(b)));
                else if(d>PointMesh.extent(view.junction,view.settings))nextTie[0]=Math.min(nextTie[0],d);
            },repeat[0],0,0);
            if(Math.abs(hiddenEnd[0]-ends[branch])>.001||nextTie[0]-ties[branch]>repeat[0]*1.25)throw new AssertionError("Rail model seam or sleeper gap: "+(nextTie[0]-ties[branch]));
        }
        System.out.println("POINT_BOUNDARY: PASS generated rails reach hidden-cell ends; final-to-native tie gap <= 1.25 native periods");
    }
    private void scissorsScene(Minecraft mc,boolean curved){
        var data=org.mtr.mod.client.MinecraftClientData.getInstance();data.rails.removeAll(rails);rails.clear();
        float a=curved?67.5F:90,b=curved?112.5F:90;
        rails.add(rail(-3,-20,-3,20,a,b));rails.add(rail(3,-20,3,20,a,b));rails.add(rail(-3,-20,3,20,a,b));rails.add(rail(3,-20,-3,20,a,b));
        mc.getSingleplayerServer().execute(()->mc.getSingleplayerServer().getPlayerList().getPlayers().get(0).connection.teleport(3,89,-23,0,65));
    }
    private static void checkScissors(Minecraft mc){
        PointClient.invalidate();PointClient.rebuild();var views=PointClient.views.stream().filter(v->v.scissors!=null).toList();
        if(views.size()!=5)throw new AssertionError("Expected four turnout views + shared crossing, got "+views.size()+" / "+PointClient.views.size());
        for(var view:views){view.previewPosition=0;int size=view.mesh().quads.size();view.previewPosition=1;if(view.mesh().quads.size()!=size)throw new AssertionError("Scissors animation topology");view.previewPosition=Double.NaN;}
        var cross=views.stream().filter(v->v.junction.kind()==Junction.Kind.DIAMOND).findFirst().orElseThrow();var j=cross.junction;var p=cross.profile.tune(cross.settings);
        var faces=views.stream().flatMap(v->v.mesh().quads.stream()).filter(q->List.of(q.a(),q.b(),q.c(),q.d()).stream().allMatch(v->Math.abs(v.y()-j.center().y()-p.top()-cross.settings.verticalOffset())<1e-5)).toList();
        var random=new Random(403);int empty=0;
        for(int signA:new int[]{-1,1})for(int signB:new int[]{-1,1}){
            double a=j.sa(),b=j.sb();V3 at=j.center();
            for(int k=0;k<24;k++){at=j.a().at(a).add(j.a().tangent(a).lateral().mul(signA*p.centerOffset()));V3 other=j.b().at(b).add(j.b().tangent(b).lateral().mul(signB*p.centerOffset()));V3 u=j.a().tangent(a),v=j.b().tangent(b);double den=V3.crossXZ(u,v);a+=V3.crossXZ(other.sub(at),v)/den;b+=V3.crossXZ(other.sub(at),u)/den;}
            for(int k=0;k<160;k++){V3 q=at.add((random.nextDouble()-.5)*.3,0,(random.nextDouble()-.5)*.3);boolean gap=false;
                for(Track road:j.tracks()){double d=road.nearest(q),lateral=Math.abs(q.sub(road.at(d)).dot(road.tangent(d).lateral()));gap|=lateral<p.centerOffset()-p.headWidth()/2-.003&&lateral>p.centerOffset()-p.headWidth()/2-cross.settings.flangeway()+.003;}
                if(gap){for(var face:faces)if(inTriangle(q,face.a(),face.b(),face.c())||inTriangle(q,face.a(),face.c(),face.d()))throw new AssertionError("Composite world mesh fills a diamond flange channel");empty++;}
            }
        }
        if(empty<20)throw new AssertionError("Crossing flange coverage check did not sample channels");
        for(Track road:cross.scissors.tracks())for(int sign:new int[]{-1,1})for(double d=.1;d<road.length;d+=.09){
            V3 q=road.at(d).add(road.tangent(d).lateral().mul(sign*(p.centerOffset()-p.headWidth()/2-cross.settings.flangeway()/2)));
            if(!cross.scissors.central(q))continue;
            for(var face:faces)if(inTriangle(q,face.a(),face.b(),face.c())||inTriangle(q,face.a(),face.c(),face.d()))
                throw new AssertionError("Through/diagonal road flange blocked in shared region: "+road.id+" at "+q);
            empty++;
        }
        System.out.println("POINT_COMPOSITE: PASS "+empty+" empty flange samples across all five world meshes");
        System.out.println("POINT_SCISSORS: PASS graph group, curved sampling, 5 separate editors and stable animation");
    }
    private static void field(Minecraft mc,String key,String value){
        String label=net.minecraft.network.chat.Component.translatable("mtrpoint."+key).getString();
        var box=mc.screen.children().stream().filter(w->w instanceof net.minecraft.client.gui.components.EditBox b&&b.getMessage().getString().equals(label)).map(w->(net.minecraft.client.gui.components.EditBox)w).findFirst().orElseThrow();box.setValue(value);
    }
    private static void checkProfiles(){
        var regular=Profiles.get("default_3d").profile();var siding=Profiles.get("default_3d_siding").profile();
        if(regular.detail()==null||regular.detail().rails().size()!=5||regular.detail().bearers().size()!=6)throw new AssertionError("Native regular faces not extracted");
        if(siding.detail()==null||siding.detail().rails().size()!=22||!siding.detail().siding()||siding.detail().fittings().size()<20)throw new AssertionError("Native siding faces/supports not extracted");
        for(var face:siding.detail().rails())if(face.uv()==null||face.uv().size()!=8)throw new AssertionError("Native per-vertex UV missing");
        var json=org.mtrpoint.AppearanceData.JSON.toJsonTree(PointSettings.DEFAULT).getAsJsonObject();json.remove("sleeperAngle");json.remove("sleeperEndAngle");json.remove("sleeperMode");
        if(!org.mtrpoint.AppearanceData.decode(json.toString()).equals(PointSettings.DEFAULT.with(16,0)))throw new AssertionError("Old settings migration");
        System.out.println("POINT_MODELS: PASS original regular/siding faces + UV + support distinction; old appearance JSON retained");
        checkNativeCrossing(regular);checkNativeCrossing(siding);
    }
    private static void checkGuardCache(){
        try{
            var method=PointRenderer.class.getDeclaredMethod("guards",List.class);method.setAccessible(true);
            var counter=PointRenderer.class.getDeclaredField("guardBuilds");counter.setAccessible(true);
            var compiledField=PointRenderer.class.getDeclaredField("GUARDS");compiledField.setAccessible(true);
            Object compiled=compiledField.get(null);var meshField=compiled.getClass().getDeclaredField("mesh");meshField.setAccessible(true);
            method.invoke(null,PointClient.views);int single=((Mesh)meshField.get(compiled)).quads.size();
            var original=PointClient.views.get(0);var duplicate=new PointClient.View(original.junction,original.settings,original.profile,original.styles);
            method.invoke(null,List.of(original,duplicate));
            if(((Mesh)meshField.get(compiled)).quads.size()!=single)throw new AssertionError("World renderer duplicated coincident check rails");
            method.invoke(null,PointClient.views);long before=counter.getLong(null);
            for(int i=0;i<100;i++)method.invoke(null,PointClient.views);
            if(counter.getLong(null)!=before)throw new AssertionError("Guard union rebuilt without appearance changes");
            System.out.println("POINT_GUARD_CACHE: PASS overlapping views render guards once; 100 unchanged draws, union rebuilds=0");
        }catch(ReflectiveOperationException ex){throw new AssertionError(ex);}
    }
    public record ProbeTraversal(String sectionId,double endDistance,net.minecraft.core.BlockPos startNode,net.minecraft.core.BlockPos endNode){}
    public record ProbePath(List<ProbeTraversal> getTraversals){}
    public record ProbeAuthorization(ProbePath path,List<ProbeTraversal> traversals,double startDistance,double endDistance,long vehicleId){}
    private static void checkPermission(Minecraft mc){
        try{
            var view=PointClient.views.stream().filter(v->v.junction.kind()==Junction.Kind.Y).findFirst().orElseThrow();
            var node=view.junction.center();var at=net.minecraft.core.BlockPos.containing(node.x(),node.y(),node.z());
            var incoming=new ProbeTraversal("approach",20,at.offset(0,0,-20),at);
            var outgoing=new ProbeTraversal(view.junction.b().id,50,at,at.offset(7,0,30));
            var auth=new ProbeAuthorization(new ProbePath(List.of(incoming,outgoing)),List.of(outgoing),20,50,403);
            var collect=org.mtrpoint.compat.BrObserver.class.getDeclaredMethod("collectAuthorization",List.class,Object.class);collect.setAccessible(true);
            var movements=new ArrayList<org.mtrpoint.PointNetwork.Movement>();collect.invoke(null,movements,auth);
            if(movements.size()!=1||movements.get(0).occupied())throw new AssertionError("Permission boundary turnout lost before train arrival");
            var stop=new ArrayList<org.mtrpoint.PointNetwork.Movement>();collect.invoke(null,stop,new ProbeAuthorization(auth.path(),List.of(incoming),0,20,403));
            if(!stop.isEmpty())throw new AssertionError("Switch beyond permission end was selected");
            if(net.minecraftforge.fml.ModList.get().isLoaded("mtr_brsignal_addon")){
                view.position=0;view.target=0;PointClient.motion(new org.mtrpoint.PointNetwork.Motion(mc.level.dimension().location().toString(),true,System.currentTimeMillis(),movements));PointClient.tick();
                if(view.target!=1||view.position<=0||!view.state.equals("mtrpoint.authorized"))throw new AssertionError("Permitted route did not start animation before occupancy");
            }
            if(PointSettings.DEFAULT.animationSeconds()!=1)throw new AssertionError("Default throw duration");
            var left=PointMesh.build(view.junction,PointSettings.DEFAULT,view.profile,0).quads.stream().filter(q->q.part().equals("stretcher")).toList();
            var right=PointMesh.build(view.junction,PointSettings.DEFAULT,view.profile,1).quads.stream().filter(q->q.part().equals("stretcher")).toList();
            if(left.isEmpty()||left.equals(right))throw new AssertionError("Stretcher does not move with blades");
            System.out.println("POINT_PERMISSION: PASS start-boundary path context, stop-boundary exclusion, 1s default, moving stretcher; BR animation before occupancy when present");
        }catch(ReflectiveOperationException ex){throw new AssertionError(ex);}
    }
    private static void checkGpu(){
        try{
            Class<?> type=Class.forName("org.mtrpoint.client.PointGpu");var ctor=type.getDeclaredConstructor();ctor.setAccessible(true);Object gpu=ctor.newInstance();
            var update=type.getDeclaredMethod("update",Mesh.class,double.class,boolean.class,boolean.class,boolean.class);update.setAccessible(true);
            var close=type.getDeclaredMethod("close");close.setAccessible(true);var counter=type.getDeclaredField("uploads");counter.setAccessible(true);
            Mesh mesh=PointClient.views.get(0).mesh();update.invoke(gpu,mesh,0D,false,false,false);long count=counter.getLong(null),begin=System.nanoTime();
            for(int i=0;i<1000;i++)update.invoke(gpu,mesh,0D,false,false,false);
            if(counter.getLong(null)!=count)throw new AssertionError("Stationary geometry re-uploaded every frame");
            System.out.println("POINT_GPU: PASS 1000 stationary updates="+(System.nanoTime()-begin)/1e6+" ms, GPU uploads=0");close.invoke(gpu);
        }catch(ReflectiveOperationException ex){throw new AssertionError(ex);}
    }
    private static void language(Minecraft mc,String id){mc.getLanguageManager().setSelected(id);mc.getLanguageManager().onResourceManagerReload(mc.getResourceManager());}
    private static void checkDenseGpu(){
        var buffers=new ArrayList<Object>();
        try{
            Class<?> type=Class.forName("org.mtrpoint.client.PointGpu");var ctor=type.getDeclaredConstructor();ctor.setAccessible(true);
            var update=type.getDeclaredMethod("update",Mesh.class,double.class,boolean.class,boolean.class,boolean.class);update.setAccessible(true);
            var close=type.getDeclaredMethod("close");close.setAccessible(true);
            var draw=type.getDeclaredMethod("draw",List.class,org.joml.Matrix4f.class,org.joml.Matrix4f.class);draw.setAccessible(true);
            var fixed=type.getDeclaredField("fixed");fixed.setAccessible(true);var moving=type.getDeclaredField("moving");moving.setAccessible(true);
            var uploads=type.getDeclaredField("uploads");uploads.setAccessible(true);var vertices=type.getDeclaredField("vertices");vertices.setAccessible(true);
            Mesh mesh=PointClient.views.get(0).mesh();var pose=new org.joml.Matrix4f(com.mojang.blaze3d.systems.RenderSystem.getModelViewMatrix());var projection=com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix();
            try{
                for(int i=0;i<32;i++){Object gpu=ctor.newInstance();buffers.add(gpu);update.invoke(gpu,mesh,0D,false,false,false);}
                long count=uploads.getLong(null),vertexCount=vertices.getLong(null);var times=new double[30];
                for(int frame=0;frame<35;frame++){
                    long begin=System.nanoTime();
                    for(Object gpu:buffers){update.invoke(gpu,mesh,0D,false,false,false);draw.invoke(null,fixed.get(gpu),pose,projection);draw.invoke(null,moving.get(gpu),pose,projection);}
                    if(frame>=5)times[frame-5]=(System.nanoTime()-begin)/1e6;
                }
                if(uploads.getLong(null)!=count||vertices.getLong(null)!=vertexCount)throw new AssertionError("Dense stationary draws uploaded vertices");
                Arrays.sort(times);System.out.println("POINT_DENSE_GPU: PASS 32 complete turnouts, "+(32L*mesh.quads.size())+" quads/frame, median="+times[15]+" ms p95="+times[28]+" ms CPU submission; zero vertex uploads (isolated loop, not world FPS)");
            }finally{for(Object gpu:buffers)close.invoke(gpu);}
        }catch(ReflectiveOperationException ex){throw new AssertionError(ex);}
    }
    private static void checkLanguages(Minecraft mc){
        try{
            Set<String> keys=null;
            for(String lang:List.of("zh_cn","ja_jp","en_us")){
                var id=new net.minecraft.resources.ResourceLocation("mtr_railway_point_advanced","lang/"+lang+".json");
                try(var reader=mc.getResourceManager().getResourceOrThrow(id).openAsReader()){
                    var object=com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();if(keys==null)keys=object.keySet();else if(!keys.equals(object.keySet()))throw new AssertionError("Missing language keys: "+lang);
                    if(object.entrySet().stream().anyMatch(e->e.getValue().getAsString().isBlank()))throw new AssertionError("Empty translation");
                }
            }
            System.out.println("POINT_LANG: PASS Chinese/Japanese/English matching nonempty keys");
        }catch(java.io.IOException ex){throw new AssertionError(ex);}
    }
    private static void checkNativeCrossing(Profile p){
        V3 a=new V3(0,0,1),b=new V3(.5,0,Math.sqrt(.75));
        Track ta=new Track("probe-a","a0","a1",List.of(a.mul(-20),a.mul(20))),tb=new Track("probe-b","b0","b1",List.of(b.mul(-20),b.mul(20)));
        Junction j=new Junction("probe-diamond",Junction.Kind.DIAMOND,ta,tb,new V3(0,0,0),20,20,6);
        long started=System.nanoTime();Mesh mesh=PointMesh.build(j,PointSettings.DEFAULT,p,0);double ms=(System.nanoTime()-started)/1e6;
        var tops=mesh.quads.stream().filter(q->List.of(q.a(),q.b(),q.c(),q.d()).stream().allMatch(v->Math.abs(v.y()-p.top())<1e-7)).toList();
        // Siding has rounded shoulders: the flat running surface is narrower than the
        // nominal rail head. Use its actual OBJ top width in the independent footprint.
        double halfTop=0;for(var face:p.detail().rails())for(V3 v:List.of(face.a(),face.b(),face.c(),face.d()))
            if(Math.abs(v.y()-p.detail().railTop())<1e-7)halfTop=Math.max(halfTop,Math.abs(v.x()-p.detail().railCenter())*p.headWidth()/p.detail().headWidth());
        Random random=new Random(403);int checks=0;
        for(int sa:new int[]{-1,1})for(int sb:new int[]{-1,1}){
            V3 na=a.lateral(),nb=b.lateral();double ca=sa*p.centerOffset(),cb=sb*p.centerOffset(),den=V3.crossXZ(na,nb);
            V3 c=new V3((ca*nb.z()-cb*na.z())/den,0,(na.x()*cb-nb.x()*ca)/den);
            for(int k=0;k<350;k++){
                V3 q=c.add((random.nextDouble()-.5)*.4,0,(random.nextDouble()-.5)*.4);boolean steel=false,gap=false;
                for(V3 n:List.of(na,nb)){double d=Math.abs(q.dot(n)),offset=p.centerOffset(),head=p.headWidth(),flange=PointSettings.DEFAULT.flangeway();steel|=Math.abs(d-offset)<halfTop||Math.abs(d-offset+head+flange)<halfTop;gap|=d<offset-head/2&&d>offset-head/2-flange;}
                int hits=0;for(var face:tops)if(inTriangle(q,face.a(),face.b(),face.c())||inTriangle(q,face.a(),face.c(),face.d()))hits++;
                if((hits>0)!=(steel&&!gap)||hits>1)throw new AssertionError("Native crossing footprint "+p.source()+" at "+q+" hits="+hits);checks++;
            }
        }
        if(mesh.quads.stream().anyMatch(q->q.uv()==null))throw new AssertionError("Native diamond lost UVs");
        System.out.println("POINT_DIAMOND: PASS "+p.source()+" checks="+checks+" faces="+mesh.quads.size()+" build="+ms+"ms");
    }
    private static boolean inTriangle(V3 p,V3 a,V3 b,V3 c){double area=V3.crossXZ(b.sub(a),c.sub(a));if(Math.abs(area)<1e-12)return false;double u=V3.crossXZ(p.sub(a),c.sub(a))/area,v=V3.crossXZ(b.sub(a),p.sub(a))/area;return u>=-1e-9&&v>=-1e-9&&u+v<=1+1e-9;}
    private void checkEditor(Minecraft mc){
        var view=PointClient.views.stream().filter(v->v.junction.kind()==Junction.Kind.Y).findFirst().orElseThrow();
        mc.setScreen(new BlueprintScreen(view));click(mc,"mtrpoint.profile");
        if(!view.profile.source().equals("default_3d"))throw new AssertionError("First style click");
        click(mc,"mtrpoint.profile");if(!view.profile.source().equals("default_3d_siding"))throw new AssertionError("Siding style click");
        int count=view.mesh().quads.size();click(mc,"mtrpoint.undo");
        if(!view.profile.source().equals("default_3d")||count==view.mesh().quads.size())throw new AssertionError("Undo style/geometry");
        click(mc,"mtrpoint.tab_2");chooseSleeper(mc,0);
        if(view.settings.sleeperMode()!=0)throw new AssertionError("Parallel button");
        checkSleeperControls(mc,view);
        click(mc,"mtrpoint.close");if(mc.screen!=null||!view.settings.equals(PointClient.saved(view.junction.id())))throw new AssertionError("Exit did not discard draft");
        // Geometry build count is independent of animation frames; use actual native model.
        view.preview(PointSettings.DEFAULT.flags(true,true));view.mesh();long builds=view.builds;
        long start=System.nanoTime();for(int i=0;i<1000;i++){view.previewPosition=(i%100)/99D;view.mesh();}
        double ms=(System.nanoTime()-start)/1e6;
        if(view.builds!=builds)throw new AssertionError("Animation rebuilt static geometry");
        long samples=RailSampler.sampleBuilds;for(int i=0;i<20;i++)PointClient.rebuild();
        if(samples!=RailSampler.sampleBuilds)throw new AssertionError("Unchanged rails resampled");
        System.out.println("POINT_PERF: 1000 native animated updates="+ms+" ms; static rebuilds=0; 20 unchanged refreshes sampled=0");
        view.previewPosition=Double.NaN;view.preview(PointClient.saved(view.junction.id()));
        mc.setScreen(new BlueprintScreen(view));click(mc,"mtrpoint.profile");click(mc,"mtrpoint.profile");
        net.minecraft.client.gui.screens.Screen screen=mc.screen;
        var gui=new net.minecraft.client.gui.GuiGraphics(mc,mc.renderBuffers().bufferSource());
        for(int i=0;i<5;i++)screen.render(gui,0,0,0);
        var durations=new double[40];for(int i=0;i<durations.length;i++){long begin=System.nanoTime();screen.render(gui,0,0,0);gui.flush();durations[i]=(System.nanoTime()-begin)/1e6;}
        Arrays.sort(durations);System.out.println("POINT_UI_PERF: full siding blueprint CPU submit median="+durations[20]+" ms p95="+durations[38]+" ms (offscreen loop; not FPS)");
        System.out.println("POINT_UI: PASS style/undo/angle mode/explicit exit");
    }
    /**
     * Drives the sleeper-mode list of whichever host owns a {@link TurnoutPanel}. The panel is shared
     * by the isolated blueprint and the unified plan, and the currently selected turnout may carry any
     * persisted mode, so the mode button is located by the host's own draft rather than a fixed label.
     */
    private void chooseSleeper(Minecraft mc,int mode){
        var screen=mc.screen;
        try{
            Object model=field(screen.getClass(),"panel",screen);
            var x=TurnoutPanel.class.getDeclaredField("menuX");var y=TurnoutPanel.class.getDeclaredField("menuY");x.setAccessible(true);y.setAccessible(true);
            var draft=method(screen.getClass(),"draft",String.class);var junction=method(screen.getClass(),"junction");
            Object view=junction.invoke(screen);String id=(String)view.getClass().getField("junction").get(view).getClass().getMethod("id").invoke(view.getClass().getField("junction").get(view));
            int currentMode=((PointSettings)draft.invoke(screen,id)).sleeperMode();
            click(mc,net.minecraft.network.chat.Component.translatable("mtrpoint.sleeper_mode_"+currentMode).getString());
            if(((PointSettings)draft.invoke(screen,id)).sleeperMode()!=currentMode)throw new AssertionError("Opening sleeper list changed mode");
            double scale=screenScale(screen);((net.minecraft.client.gui.screens.Screen)screen).mouseClicked((x.getInt(model)+10)*scale,(y.getInt(model)+mode*22+10)*scale,0);
            if(((PointSettings)draft.invoke(screen,id)).sleeperMode()!=mode)throw new AssertionError("Sleeper list selection failed");
        }catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }

    /**
     * Milestone 1 of the unified editor: the plan assembles the full preview, clicking a turnout
     * selects it in place, the per-turnout panel edits that junction without navigating away, the
     * number toggle flips its label, and the shared save applies the turnout that is selected when
     * it is clicked - the product contract is per-selected-turnout persistence, not save-all.
     */
    private void checkUnifiedEditor(Minecraft mc){
        try{
            var selector=(PointSelectionScreen)mc.screen;
            var points=(List<PointClient.View>)field(PointSelectionScreen.class,"points",selector);
            var drafts=(Map<PointClient.View,PointSettings>)field(PointSelectionScreen.class,"drafts",selector);
            var panel=(TurnoutPanel)field(PointSelectionScreen.class,"panel",selector);
            var plan=(Mesh)field(PointSelectionScreen.class,"plan",selector);
            int selected=(Integer)field(PointSelectionScreen.class,"selected",selector);
            var view=points.get(selected);
            int before=((net.minecraft.client.gui.screens.Screen)selector).children().size();
            if(panel.view()!=view)throw new AssertionError("Plan panel is not bound to the selected turnout");
            if(drafts.get(view)!=view.settings)throw new AssertionError("Plan opened a divergent draft");
            for(String part:List.of("rail","guard","wing","blade","frog","sleeper"))if(plan.quads.stream().noneMatch(q->q.part().equals(part)))throw new AssertionError("Unified plan omitted "+part+" geometry");
            for(String key:List.of("mtrpoint.guard_start_minus","mtrpoint.guard_start_plus","mtrpoint.guard_end_minus","mtrpoint.guard_end_plus","mtrpoint.guard_merge","mtrpoint.guard_reset"))if(buttonCount(mc,net.minecraft.network.chat.Component.translatable(key).getString())!=1)throw new AssertionError("Missing guard editor action "+key);
            if(buttonCount(mc,net.minecraft.network.chat.Component.translatable("mtrpoint.apply").getString())!=1)
                throw new AssertionError("Unified plan must expose exactly one per-turnout save button");
            clickMessage(mc,net.minecraft.network.chat.Component.translatable("mtrpoint.tab_2").getString());
            chooseSleeper(mc,0);
            chooseSleeper(mc,4);
            clickMessage(mc,net.minecraft.network.chat.Component.translatable("mtrpoint.tab_0").getString());
            fieldOf(mc,"height","0.02");
            if(panel.view()!=view||drafts.get(view).values()[11]!=.02)throw new AssertionError("Fine control did not reach the selected junction draft");
            panelEditId=view.junction.id();panelEditValue=.02;
            // Per-selected-turnout save: hand the edited turnout to the server while it is still the
            // selection, because the panel button acts on the current view. Doing this only after
            // switching to the peer would save the peer and silently drop this draft.
            click(mc,"mtrpoint.apply");
            String expectHide=net.minecraft.network.chat.Component.translatable("mtrpoint.numbers_hide").getString();
            String expectShow=net.minecraft.network.chat.Component.translatable("mtrpoint.numbers_show").getString();
            if(!expectHide.equals("隐藏编号")||!expectShow.equals("显示编号"))throw new AssertionError("Number toggle is not Chinese in the default language");
            if(!(Boolean)field(PointSelectionScreen.class,"numbersVisible",selector))throw new AssertionError("Numbers must default to visible");
            clickMessage(mc,expectHide);
            if((Boolean)field(PointSelectionScreen.class,"numbersVisible",selector))throw new AssertionError("Hide numbers did not hide them");
            if(buttonCount(mc,expectHide)!=0||buttonCount(mc,expectShow)!=1)throw new AssertionError("Number toggle label did not flip");
            clickMessage(mc,expectShow);
            if(!(Boolean)field(PointSelectionScreen.class,"numbersVisible",selector))throw new AssertionError("Show numbers did not restore them");
            clickMessage(mc,expectHide);
            int peer=selected==0?1:0;var other=points.get(peer);
            double[] pixel=(double[])method(PointSelectionScreen.class,"project",V3.class).invoke(selector,PointClient.editCenter(other));
            double scale=screenScale(selector);
            ((net.minecraft.client.gui.screens.Screen)selector).mouseClicked(pixel[0]*scale,pixel[1]*scale,0);
            if(mc.screen!=selector)throw new AssertionError("Clicking a turnout navigated away from the plan");
            if((Integer)field(PointSelectionScreen.class,"selected",selector)!=peer)throw new AssertionError("Clicking a turnout did not select it in place");
            if(panel.view()!=other)throw new AssertionError("Per-turnout panel did not follow the in-place selection");
            if((Boolean)field(PointSelectionScreen.class,"numbersVisible",selector))throw new AssertionError("Hidden numbers came back without the toggle");
            if(((net.minecraft.client.gui.screens.Screen)selector).children().size()!=before)throw new AssertionError("Per-turnout controls appeared without the selected turnout");
            if(drafts.get(view).values()[11]!=.02||drafts.get(other)!=other.settings)throw new AssertionError("In-place selection disturbed the assembled preview drafts");
            // The shared save acts on the current selection only: the turnout that was edited is the
            // one pending server acknowledgement, and merely selecting the peer must not queue it.
            var dirty=(Set<PointClient.View>)field(PointSelectionScreen.class,"dirty",selector);
            if(!dirty.contains(view))throw new AssertionError("The saved turnout is no longer pending server acknowledgement");
            if(dirty.contains(other))throw new AssertionError("Selecting the peer turnout queued it for the shared save");
            click(mc,"mtrpoint.sleeper_isolate");
            if(!((List<?>)field(PointSelectionScreen.class,"sleepers",selector)).isEmpty())throw new AssertionError("Sleeper isolation rejected hits without removing the handle set");
            if(buttonCount(mc,net.minecraft.network.chat.Component.translatable("mtrpoint.sleeper_isolation_cancel").getString())!=1)throw new AssertionError("Sleeper isolation button did not change to cancel");
            click(mc,"mtrpoint.sleeper_isolation_cancel");
            if(((List<?>)field(PointSelectionScreen.class,"sleepers",selector)).isEmpty())throw new AssertionError("Cancelling sleeper isolation did not restore handle geometry");
            System.out.println("POINT_UNIFIED: PASS full assembly preview, in-place selection, per-selected-turnout save, shared controls and number toggle");
        }catch(ReflectiveOperationException ex){throw new AssertionError(ex);}
    }
    private static Object field(Class<?> type,String name,Object owner)throws ReflectiveOperationException{
        var declared=type.getDeclaredField(name);declared.setAccessible(true);return declared.get(owner);
    }
    private static java.lang.reflect.Method method(Class<?> type,String name,Class<?>... parameters)throws ReflectiveOperationException{
        var declared=type.getDeclaredMethod(name,parameters);declared.setAccessible(true);return declared;
    }
    private static int buttonCount(Minecraft mc,String message){
        return (int)mc.screen.children().stream().filter(w->w instanceof net.minecraft.client.gui.components.Button b&&b.getMessage().getString().equals(message)).count();
    }
    private static void fieldOf(Minecraft mc,String key,String value){
        String label=net.minecraft.network.chat.Component.translatable("mtrpoint."+key).getString();
        var box=mc.screen.children().stream().filter(w->w instanceof net.minecraft.client.gui.components.EditBox b&&b.getMessage().getString().equals(label)).map(w->(net.minecraft.client.gui.components.EditBox)w).findFirst().orElseThrow();
        box.setValue(value);
    }
    private void existingTick(Minecraft mc){
        if(++worldTicks==30){PointClient.invalidate();PointClient.rebuild();System.out.println("POINT_ACTUAL: views="+PointClient.views.size());
            for(var view:PointClient.views){var c=view.junction.center();System.out.println("POINT_ACTUAL_VIEW: "+view.junction.kind()+" id="+view.junction.id()+" center="+c+" tracks="+view.junction.tracks().stream().map(t->t.id).toList()+" mesh="+view.mesh().quads.size());}
              PointRenderer.prepare(PointClient.views);mc.options.hideGui=true;
              mc.getSingleplayerServer().execute(()->{var player=mc.getSingleplayerServer().getPlayerList().getPlayers().get(0);player.getAbilities().flying=true;player.onUpdateAbilities();player.connection.teleport(49.686,-55.887,-12.642,0,45);});
          }
        if(worldTicks==55)Screenshot.grab(mc.gameDirectory,"point-actual-v0.png",mc.getMainRenderTarget(),m->System.out.println("POINT_ACTUAL: v0 screenshot"));
        if(worldTicks==60)mc.getSingleplayerServer().execute(()->mc.getSingleplayerServer().getPlayerList().getPlayers().get(0).connection.teleport(49.686,-55.887,-12.642,90,45));
        if(worldTicks==75)Screenshot.grab(mc.gameDirectory,"point-actual-v1.png",mc.getMainRenderTarget(),m->System.out.println("POINT_ACTUAL: v1 screenshot"));
        if(worldTicks==80)mc.getSingleplayerServer().execute(()->mc.getSingleplayerServer().getPlayerList().getPlayers().get(0).connection.teleport(49.686,-55.887,-12.642,180,45));
        if(worldTicks==95)Screenshot.grab(mc.gameDirectory,"point-actual-v2.png",mc.getMainRenderTarget(),m->System.out.println("POINT_ACTUAL: v2 screenshot"));
        if(worldTicks==100)mc.getSingleplayerServer().execute(()->mc.getSingleplayerServer().getPlayerList().getPlayers().get(0).connection.teleport(49.686,-55.887,-12.642,270,45));
        if(worldTicks==115)Screenshot.grab(mc.gameDirectory,"point-actual-v3.png",mc.getMainRenderTarget(),m->System.out.println("POINT_ACTUAL: v3 screenshot"));
        if(worldTicks==120)mc.getSingleplayerServer().execute(()->mc.getSingleplayerServer().getPlayerList().getPlayers().get(0).connection.teleport(50,-56.5,-4.5,90,55));
        if(worldTicks==140)Screenshot.grab(mc.gameDirectory,"point-actual-close-0.png",mc.getMainRenderTarget(),m->System.out.println("POINT_ACTUAL: close 0 screenshot"));
        if(worldTicks==145)mc.getSingleplayerServer().execute(()->mc.getSingleplayerServer().getPlayerList().getPlayers().get(0).connection.teleport(52,-56.5,0,90,55));
        if(worldTicks==165)Screenshot.grab(mc.gameDirectory,"point-actual-close-1.png",mc.getMainRenderTarget(),m->System.out.println("POINT_ACTUAL: close 1 screenshot"));
        if(worldTicks==170)mc.getSingleplayerServer().execute(()->mc.getSingleplayerServer().getPlayerList().getPlayers().get(0).connection.teleport(50,-56.5,-8,270,55));
        if(worldTicks==190)Screenshot.grab(mc.gameDirectory,"point-actual-close-2.png",mc.getMainRenderTarget(),m->System.out.println("POINT_ACTUAL: close 2 screenshot"));
        if(worldTicks==195)mc.getSingleplayerServer().execute(()->mc.getSingleplayerServer().getPlayerList().getPlayers().get(0).connection.teleport(52,-53,-4.5,0,90));
        if(worldTicks==215)Screenshot.grab(mc.gameDirectory,"point-actual-close-top.png",mc.getMainRenderTarget(),m->System.out.println("POINT_ACTUAL: close top screenshot"));
        if(worldTicks==230){System.out.println("POINT_ACTUAL: PASS");mc.stop();}
    }
    private void editSleeper(Minecraft mc){
        try{
            var selector=(PointSelectionScreen)mc.screen;var sleepersField=PointSelectionScreen.class.getDeclaredField("sleepers");sleepersField.setAccessible(true);
            Object pick=((List<?>)sleepersField.get(selector)).get(0);var centerMethod=pick.getClass().getDeclaredMethod("center");centerMethod.setAccessible(true);V3 center=(V3)centerMethod.invoke(pick);
            var handleMethod=pick.getClass().getDeclaredMethod("handle");handleMethod.setAccessible(true);Object handle=handleMethod.invoke(pick);var facesMethod=handle.getClass().getDeclaredMethod("faces");facesMethod.setAccessible(true);
            var project=PointSelectionScreen.class.getDeclaredMethod("project",V3.class);project.setAccessible(true);double[] middle=(double[])project.invoke(selector,center),pixel=middle;V3 pixelWorld=center;double far=-1;
            for(var face:(List<Mesh.Quad>)facesMethod.invoke(handle))for(V3 vertex:List.of(face.a(),face.b(),face.c(),face.d())){double[] candidate=(double[])project.invoke(selector,vertex);double distance=Math.hypot(candidate[0]-middle[0],candidate[1]-middle[1]);if(distance>far){far=distance;pixel=candidate;pixelWorld=vertex;}}
            double scale=screenScale(selector);((net.minecraft.client.gui.screens.Screen)selector).mouseScrolled(pixel[0]*scale,pixel[1]*scale,1);double[] anchored=(double[])project.invoke(selector,pixelWorld);
            if(Math.hypot(anchored[0]-pixel[0],anchored[1]-pixel[1])>.001)throw new AssertionError("Selector zoom is not anchored at the mouse");pixel=anchored;
            ((net.minecraft.client.gui.screens.Screen)selector).mouseClicked(pixel[0]*scale,pixel[1]*scale,0);
            var viewField=PointSelectionScreen.class.getDeclaredField("sleeperView");viewField.setAccessible(true);var indexField=PointSelectionScreen.class.getDeclaredField("sleeperIndex");indexField.setAccessible(true);
            var view=(PointClient.View)viewField.get(selector);if(view==null)throw new AssertionError("Selector could not select a sleeper from its far end");int index=indexField.getInt(selector);double before=view.settings.sleeperShifts().getOrDefault(index,0D);List<Track> roads=view.scissors==null?view.junction.tracks():view.scissors.tracks();Track road=roads.stream().min(Comparator.comparingDouble(v->v.at(v.nearest(center)).distance(center))).orElseThrow();V3 tangent=road.tangent(road.nearest(center));double dx=tangent.x()*20,dy=tangent.z()*20;
            ((net.minecraft.client.gui.screens.Screen)selector).mouseDragged((pixel[0]+dx)*scale,(pixel[1]+dy)*scale,0,dx*scale,dy*scale);double value=view.settings.sleeperShifts().getOrDefault(index,0D);if(Math.abs(value-before)<1e-9)throw new AssertionError("Selector tie drag did not change preview");
            click(mc,"mtrpoint.undo");if(Math.abs(view.settings.sleeperShifts().getOrDefault(index,0D)-before)>1e-9)throw new AssertionError("Selector tie undo did not restore preview");
            ((net.minecraft.client.gui.screens.Screen)selector).mouseDragged((pixel[0]+dx)*scale,(pixel[1]+dy)*scale,0,dx*scale,dy*scale);value=view.settings.sleeperShifts().getOrDefault(index,0D);if(Math.abs(value-before)<1e-9)throw new AssertionError("Selector tie drag failed after undo");
            sleeperEditId=view.junction.id();sleeperEditIndex=index;sleeperEditValue=value;click(mc,"mtrpoint.select_save_sleeper");
        }catch(ReflectiveOperationException ex){throw new AssertionError(ex);}
    }
    private static void checkSleeperControls(Minecraft mc,PointClient.View view){
        try{
            var screen=(BlueprintScreen)mc.screen;var draftField=BlueprintScreen.class.getDeclaredField("draft");draftField.setAccessible(true);
            clickMessage(mc,net.minecraft.network.chat.Component.translatable("mtrpoint.sleeper_path",1).getString());
            PointSettings draft=(PointSettings)draftField.get(screen);if(draft.sleeperPath()!=1)throw new AssertionError("Sleeper reference path did not change");
            var prepare=BlueprintScreen.class.getDeclaredMethod("prepareProjection");prepare.setAccessible(true);prepare.invoke(screen);var project=BlueprintScreen.class.getDeclaredMethod("project",V3.class);project.setAccessible(true);
            double[] firstPixel=(double[])project.invoke(screen,sleeperCenter(view.mesh(),-1));clickVirtual(screen,firstPixel[0],firstPixel[1]);sleeperAction(screen,0);
            Track road=view.junction.tracks().get(1);double[] at=(double[])project.invoke(screen,road.at(Math.min(5,road.length)));clickVirtual(screen,at[0],at[1]);
            draft=(PointSettings)draftField.get(screen);if(draft.addedSleepers().size()!=1||!draft.addedSleepers().containsKey(256))throw new AssertionError("Manual sleeper was not added");
            for(int item:new int[]{3,4}){openSleeperMenu(screen,256);sleeperAction(screen,item);}
            draft=(PointSettings)draftField.get(screen);if(!draft.sleeper(256,PointSettings.SLEEPER_SPLIT)||!draft.sleeper(256,PointSettings.SLEEPER_FULL))throw new AssertionError("Individual sleeper split/full controls failed: "+draft.sleeperOverrides());
            openSleeperMenu(screen,256);sleeperAction(screen,2);draft=(PointSettings)draftField.get(screen);if(!draft.addedSleepers().isEmpty())throw new AssertionError("Manual sleeper delete failed");
            click(mc,"mtrpoint.undo");draft=(PointSettings)draftField.get(screen);if(!draft.addedSleepers().containsKey(256))throw new AssertionError("Manual sleeper delete cannot be undone");
            openSleeperMenu(screen,256);sleeperAction(screen,1);draft=(PointSettings)draftField.get(screen);if(draft.sleeperPath()!=0||!draft.addedSleepers().isEmpty()||!draft.sleeperOverrides().isEmpty())throw new AssertionError("Sleeper-only reset retained individual edits");
        }catch(ReflectiveOperationException ex){throw new AssertionError(ex);}
    }
    private static void sleeperAction(net.minecraft.client.gui.screens.Screen screen,int item)throws ReflectiveOperationException{
        TurnoutPanel panel=panelOf(screen);
        var x=TurnoutPanel.class.getDeclaredField("actionX");var y=TurnoutPanel.class.getDeclaredField("actionY");x.setAccessible(true);y.setAccessible(true);double scale=screenScale(screen);
        screen.mouseClicked((x.getInt(panel)+64)*scale,(y.getInt(panel)+item*22+(item>=2?2:0)+10)*scale,0);
        flushPreview(screen);
    }
    private static void openSleeperMenu(net.minecraft.client.gui.screens.Screen screen,int index)throws ReflectiveOperationException{
        TurnoutPanel panel=panelOf(screen);
        var selected=TurnoutPanel.class.getDeclaredField("selectedSleeper");var open=TurnoutPanel.class.getDeclaredField("actionMenu");var x=TurnoutPanel.class.getDeclaredField("actionX");var y=TurnoutPanel.class.getDeclaredField("actionY");
        selected.setAccessible(true);open.setAccessible(true);x.setAccessible(true);y.setAccessible(true);selected.setInt(panel,index);open.setBoolean(panel,true);x.setInt(panel,16);y.setInt(panel,80);
    }
    /** The popup state lives in the shared {@link TurnoutPanel}; every host keeps its own instance
     * in a private "panel" field, so both BlueprintScreen and PointSelectionScreen resolve here. */
    private static TurnoutPanel panelOf(net.minecraft.client.gui.screens.Screen screen)throws ReflectiveOperationException{
        return (TurnoutPanel)field(screen.getClass(),"panel",screen);
    }
    /** Only the isolated blueprint debounces typing through a flush; the unified plan previews
     * synchronously in {@link PointSelectionScreen#changeDraft}, so a host without one is already flushed. */
    private static void flushPreview(net.minecraft.client.gui.screens.Screen screen)throws ReflectiveOperationException{
        try{method(screen.getClass(),"flushPreview").invoke(screen);}catch(NoSuchMethodException ignored){}
    }
    private static void clickVirtual(net.minecraft.client.gui.screens.Screen screen,double x,double y){double scale=screenScale(screen);screen.mouseClicked(x*scale,y*scale,0);screen.mouseReleased(x*scale,y*scale,0);}
    private static V3 sleeperCenter(Mesh mesh,int requested){
        int index=requested<0?mesh.quads.stream().filter(q->q.index()>=0&&q.part().equals("sleeper")).mapToInt(Mesh.Quad::index).min().orElseThrow():requested;V3 sum=new V3(0,0,0);int count=0;
        if(requested>=0)for(var q:mesh.quads)if(q.index()==index&&q.part().equals("sleeper")&&Math.abs(q.a().y()-q.b().y())<1e-8&&Math.abs(q.a().y()-q.c().y())<1e-8&&Math.abs(q.a().y()-q.d().y())<1e-8)return q.center();
        for(var q:mesh.quads)if(q.index()==index&&(q.part().equals("sleeper")||q.part().equals("fastener")))for(V3 v:List.of(q.a(),q.b(),q.c(),q.d())){sum=sum.add(v);count++;}
        if(count==0)throw new AssertionError("Missing sleeper "+index);return sum.mul(1D/count);
    }
    private static void checkPartitionCache(){
        try{
            var method=PointRenderer.class.getDeclaredMethod("guards",List.class);method.setAccessible(true);
            var counter=PointRenderer.class.getDeclaredField("guardBuilds");counter.setAccessible(true);
            var field=PointRenderer.class.getDeclaredField("ASSEMBLIES");field.setAccessible(true);
            var original=PointClient.views.stream().filter(v->v.junction.kind()==Junction.Kind.Y).findFirst().orElseThrow();
            var j=original.junction;var tracks=new ArrayList<Track>();
            for(var road:j.tracks())tracks.add(new Track("far:"+road.id,"far:"+road.startNode,"far:"+road.endNode,road.points.stream().map(p->p.add(300,0,0)).toList()));
            var far=new PointClient.View(new Junction("far",Junction.Kind.Y,tracks.get(0),tracks.get(1),j.center().add(300,0,0),0,0,j.extent()),original.settings,original.profile,original.styles);
            long start=System.nanoTime();method.invoke(null,List.of(original,far));double first=(System.nanoTime()-start)/1e6;
            var meshes=new HashSet<>(((Map<?,?>)field.get(null)).values());if(meshes.size()!=2)throw new AssertionError("Distant assemblies not separated");
            long before=counter.getLong(null);start=System.nanoTime();far.settings=far.settings.with(17,.3);method.invoke(null,List.of(original,far));double changed=(System.nanoTime()-start)/1e6;
            if(counter.getLong(null)!=before+1||((Map<?,?>)field.get(null)).values().stream().filter(meshes::contains).count()!=1)throw new AssertionError("Editing one assembly rebuilt its distant neighbor");
            before=counter.getLong(null);start=System.nanoTime();for(int i=0;i<1000;i++)method.invoke(null,List.of(original,far));double cached=(System.nanoTime()-start)/1e6;
            if(counter.getLong(null)!=before)throw new AssertionError("Cached assembly rebuilt");
            method.invoke(null,PointClient.views);
            System.out.println("POINT_PARTITION_CACHE: PASS two independent regions; local edit rebuilds=1, retained=1; initial="+first+" ms edit="+changed+" ms 1000 cached="+cached+" ms");
        }catch(ReflectiveOperationException ex){throw new AssertionError(ex);}
    }
    private static void click(Minecraft mc,String key){
        String text=net.minecraft.network.chat.Component.translatable(key).getString();
        clickMessage(mc,text);
    }
    private static void clickMessage(Minecraft mc,String text){
        var button=mc.screen.children().stream().filter(w->w instanceof net.minecraft.client.gui.components.Button b&&b.getMessage().getString().equals(text)).map(w->(net.minecraft.client.gui.components.Button)w).findFirst().orElseThrow(()->new AssertionError("Missing button "+text));
        double scale=screenScale(mc.screen);mc.screen.mouseClicked((button.getX()+button.getWidth()/2D)*scale,(button.getY()+10)*scale,0);if(mc.screen!=null)mc.screen.mouseReleased((button.getX()+5)*scale,(button.getY()+5)*scale,0);
        if(mc.screen!=null)try{flushPreview(mc.screen);}catch(ReflectiveOperationException ex){throw new AssertionError(ex);}
    }
    private static double screenScale(net.minecraft.client.gui.screens.Screen screen){
        try{var field=screen.getClass().getDeclaredField("uiScale");field.setAccessible(true);return field.getDouble(screen);}catch(ReflectiveOperationException ignored){return 1;}
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
