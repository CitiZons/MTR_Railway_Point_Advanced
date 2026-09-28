package org.mtrpointprobe;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.mtr.core.data.*;
import org.mtrpoint.client.*;
import org.mtrpoint.geometry.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Isolated resource-pack integration probe; never reads or edits a user world. */
final class RailPackProbe {
    private static final String STYLE="citizons_mainline_1435";
    private static List<Rail> rails;
    private static int ticks;
    private static CompletableFuture<Void> reload;
    private static ModelDetail beforeReload;
    private static long cacheBuilds,cacheFrames,gpuUploads;
    static void resources(){
        var adapted=Profiles.get(STYLE);if(!adapted.track()||adapted.profile()==null||adapted.profile().detail()==null)throw new AssertionError("Custom profile detail missing");
        var p=adapted.profile();if(p.detail().rails().size()!=28||p.detail().fittings().size()<300||p.detail().nativeAtlas())throw new AssertionError("Custom model roles incorrect");
        if(Profiles.attachments(STYLE).isEmpty()||!Profiles.model(STYLE).alignSleepers())throw new AssertionError("Ballast or seam model missing");
        for(var q:Profiles.attachments(STYLE))if(q.uv()==null||!q.surface().texture().startsWith("citizons_railway:"))throw new AssertionError("Ballast material/UV missing");
        if(p.detail().fittingLods().size()!=2)throw new AssertionError("Distance LOD templates missing");
        if(!Profiles.choose(List.of("default_3d",STYLE),"").source().equals(STYLE)||!Profiles.choose(List.of(STYLE,"default_3d"),"").source().equals(STYLE))throw new AssertionError("Style order chose native rails");
        if(Profiles.get("mtrsteamloco:"+STYLE+"_2").profile()!=p)throw new AssertionError("Legacy style ID failed to resolve");
        if(!Profiles.choose(List.of(STYLE),"missing_old_style").source().equals(STYLE))throw new AssertionError("Missing saved style overrides enabled rail pack");
        System.out.println("PACK_STYLE_SELECTION: PASS explicit custom profile wins automatic order; legacy and missing saved IDs resolve");
        System.out.println("PACK_PROFILE: PASS active resource manager, detailed section, sleeper, fastener, ballast, atlas UV");
    }
    static void tick(Minecraft mc){
        try{run(mc);}catch(Throwable failure){failure.printStackTrace();System.out.println("PACK_RUNTIME: FAIL");mc.stop();}
    }
    private static void run(Minecraft mc)throws Exception{
        if(rails==null){
            resources();rails=new ArrayList<>();
            rails.add(rail(0,-13,0,0,90,90));rails.add(rail(0,0,0,24,90,90));rails.add(rail(0,0,7,24,90,67.5F));
            rails.add(rail(-10,-14,-10,-4,90,90));rails.add(rail(-10,-4,-10,9,90,90));rails.add(rail(-10,22,-10,9,90,90));
            rails.add(rail(16,-12,16,23,90,90));
            rails.add(rail(26,67,-12,32,73,23,90,67.5F));
            mc.options.hideGui=true;
            mc.getSingleplayerServer().execute(()->{
                var level=mc.getSingleplayerServer().overworld();for(int x=-16;x<25;x++)for(int z=-18;z<29;z++)level.setBlock(new net.minecraft.core.BlockPos(x,63,z),net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState(),3);
                level.setDayTime(6000);var player=mc.getSingleplayerServer().getPlayerList().getPlayers().get(0);player.getAbilities().flying=true;player.onUpdateAbilities();player.connection.teleport(6,84,-11,12,57);
            });
        }
        var data=org.mtr.mod.client.MinecraftClientData.getInstance();data.rails.addAll(rails);data.sync();data.railWrapperList.values().forEach(r->r.shouldRender=true);
        if(++ticks==30){
            bank(mc,16,-12,15);bank(mc,16,23,15);PointClient.invalidate();PointClient.rebuild();
            bank(mc,26,67,-12,-12);bank(mc,32,73,23,22);PointClient.invalidate();PointClient.rebuild();checkContinuous();
            checkBanking();
            if(PointClient.views.isEmpty())throw new AssertionError("No custom turnout detected");
            for(var view:PointClient.views){if(!view.profile.source().equals(STYLE)||view.profile.detail()==null)throw new AssertionError("Turnout selected default style");
                var mesh=view.mesh();if(mesh.quads.stream().noneMatch(q->q.part().startsWith("fastener")))throw new AssertionError("Turnout detailed fasteners missing");
                if(mesh.quads.stream().anyMatch(q->q.surface().texture().contains("timber")))throw new AssertionError("Turnout fell back to timber");}
            System.out.println("PACK_TURNOUT: PASS detected custom style and model materials; views="+PointClient.views.size());
            checkHandoff();
        }
        if(ticks==32)camera(mc,3.5,92,6,0,90);
        if(ticks==55){shot(mc,"pack-turnout-overview.png");shot(mc,"pack-turnout-top.png");}
        if(ticks==58)camera(mc,2,63.2,2,0,35);
        if(ticks==78)shot(mc,"pack-turnout-close.png");
        if(ticks==80){camera(mc,-7,68,-8,25,58);checkSeams();}
        if(ticks==100)shot(mc,"pack-segment-seam.png");
        if(ticks==103)camera(mc,12,65,-2,-90,22);
        if(ticks==125)shot(mc,"pack-banked-ballast.png");
        if(ticks==128){
            bank(mc,0,-13,8);bank(mc,0,0,8);bank(mc,0,24,8);bank(mc,7,24,8);PointClient.invalidate();PointClient.rebuild();camera(mc,2,69,-2,0,40);
        }
        if(ticks==152)shot(mc,"pack-banked-turnout.png");
        if(ticks==154)camera(mc,-8,63.3,-3.5,90,46);
        if(ticks==165){cacheBuilds=counter("RailCellCache","builds");cacheFrames=counter("RailCellCache","frame");gpuUploads=counter("PointGpu","uploads");}
        if(ticks==176){shot(mc,"pack-fastener-close.png");checkPerformance();}
        if(ticks==179)camera(mc,30,68,-8,8,32);
        if(ticks==201)shot(mc,"pack-grade-transition.png");
        if(ticks==204)camera(mc,-7,72,-8,25,58);
        if(ticks==224)shot(mc,"pack-lod-distance.png");
        if(ticks==226){
            bank(mc,0,-13,0);bank(mc,0,0,0);bank(mc,0,24,0);bank(mc,7,24,0);
            PointClient.invalidate();PointClient.rebuild();camera(mc,.6,69,2.8,0,90);
        }
        if(ticks==246)shot(mc,"pack-turnout-toe-top.png");
        if(ticks==248){
            checkTurnoutSupports();var view=turnout();double at=TurnoutFrame.start(view.junction,PointMesh.extent(view.junction,view.settings))+.9;
            V3 target=view.junction.a().at(at).add(view.junction.a().tangent(at).lateral().mul(-view.profile.centerOffset())).add(0,.12,0);
            supportCamera(mc,target,view.junction.a().tangent(at),1.1,1.1,1.05);
        }
        if(ticks==268)shot(mc,"pack-slide-bed-close.png");
        if(ticks==270){
            var view=turnout();var run=GuardRails.assembled(view.junction,view.settings,view.profile,null,null).stream().filter(r->r.part().equals("guard")).findFirst().orElseThrow();
            double at=(run.start()+run.end())/2;V3 target=run.point(at).add(0,.12,0);
            supportCamera(mc,target,run.road().tangent(at),Math.signum(run.offset())*1.2,1.05,1.2);
        }
        if(ticks==290)shot(mc,"pack-guard-base-close.png");
        if(ticks==292){
            var view=turnout();var frog=new FrogGeometry(view.junction,view.settings,view.profile,PointMesh.extent(view.junction,view.settings));
            supportCamera(mc,frog.center().add(0,.12,0),view.junction.a().tangent(frog.sa),.55,1.8,1.7);
        }
        if(ticks==312)shot(mc,"pack-frog-base-close.png");
        if(ticks==314){
            var view=turnout();var run=GuardRails.assembled(view.junction,view.settings,view.profile,null,null).stream().filter(r->r.part().equals("wing")).findFirst().orElseThrow();
            double at=(run.start()+run.end())/2;
            supportCamera(mc,run.point(at).add(0,.10,0),run.road().tangent(at),-Math.signum(run.offset())*.8,1.15,.85);
        }
        if(ticks==334)shot(mc,"pack-wing-base-close.png");
        if(ticks==336){beforeReload=Profiles.get(STYLE).profile().detail();reload=mc.reloadResourcePacks();}
        if(ticks>338&&reload!=null&&reload.isDone()&&mc.getOverlay()==null){
            reload.join();resources();if(Profiles.get(STYLE).profile().detail()==beforeReload)throw new AssertionError("Resource reload kept stale templates");
            PointClient.rebuild();System.out.println("PACK_RELOAD: PASS cache replaced after full resource reload");
            System.out.println("PACK_RUNTIME: PASS");mc.stop();
        }
        if(ticks>600)throw new AssertionError("Pack probe timed out");
    }
    private static PointClient.View turnout(){return PointClient.views.stream().filter(v->v.junction.kind()==Junction.Kind.Y).findFirst().orElseThrow();}
    private static void checkTurnoutSupports(){
        int checked=0;
        for(var view:PointClient.views){
            double previous=view.previewPosition;view.previewPosition=0;
            var supports=view.mesh().quads.stream().filter(q->q.part().equals("sleeper")||q.part().startsWith("fastener")).toList();
            if(supports.isEmpty())throw new AssertionError("No turnout supports in active renderer");
            for(double position:new double[]{.5,1}){
                view.previewPosition=position;
                var other=view.mesh().quads.stream().filter(q->q.part().equals("sleeper")||q.part().startsWith("fastener")).toList();
                if(!supports.equals(other))throw new AssertionError("Fixed slide beds or bearers move with blades");
                checked++;
            }
            view.previewPosition=previous;view.mesh();
        }
        System.out.println("PACK_SUPPORTS: PASS active renderer retains stationary bearers and fittings across blade poses; comparisons="+checked);
    }
    private static void supportCamera(Minecraft mc,V3 target,V3 forward,double side,double back,double height){
        V3 eye=target.add(forward.lateral().mul(side)).sub(forward.mul(back)).add(0,height,0),delta=target.sub(eye);
        float yaw=(float)Math.toDegrees(Math.atan2(-delta.x(),delta.z())),pitch=(float)-Math.toDegrees(Math.atan2(delta.y(),Math.hypot(delta.x(),delta.z())));
        mc.getSingleplayerServer().execute(()->mc.getSingleplayerServer().getPlayerList().getPlayers().get(0).connection.teleport(eye.x(),eye.y()-1.62,eye.z(),yaw,pitch));
    }
    private static long counter(String type,String name)throws Exception{var field=Class.forName("org.mtrpoint.client."+type).getDeclaredField(name);field.setAccessible(true);return field.getLong(null);}
    private static void checkPerformance()throws Exception{
        long frames=counter("RailCellCache","frame")-cacheFrames,builds=counter("RailCellCache","builds")-cacheBuilds,uploads=counter("PointGpu","uploads")-gpuUploads;
        if(frames<1||builds!=0||uploads!=0)throw new AssertionError("Stationary view still rebuilds geometry: frames="+frames+" builds="+builds+" uploads="+uploads);
        if(Arrays.stream(RailLod.draws).anyMatch(n->n==0))throw new AssertionError("Native support LOD did not draw every level: "+Arrays.toString(RailLod.draws));
        var field=Class.forName("org.mtrpoint.client.PointGpu").getDeclaredField("lodDraws");field.setAccessible(true);long[] draws=(long[])field.get(null);
        if(Arrays.stream(draws).anyMatch(n->n==0))throw new AssertionError("Turnout LOD did not draw every level: "+Arrays.toString(draws));
        System.out.println("PACK_PERFORMANCE: PASS stationary frames="+frames+" geometryBuilds="+builds+" uploads="+uploads+" supportLodDraws="+Arrays.toString(RailLod.draws)+" turnoutLodDraws="+Arrays.toString(draws));
    }
    private static void checkContinuous(){
        var resource=new org.mtr.mod.resource.RailResource[1];org.mtr.mod.client.CustomResourceLoader.getRailById(STYLE,r->resource[0]=r);double worst=0;int points=0;String context="";
        for(Rail rail:rails){RailSampler.sample(rail);RailSweep previous=null;boolean flip=rail.getStyles().contains(STYLE+"_2");
            for(var cell:RailSampler.cells(rail,resource[0].getRepeatInterval())){var next=RailSampler.sweep(rail,resource[0],cell.a(),cell.b());
                if(previous!=null)for(double x:new double[]{-1.85,-.7515,0,.7515,1.85})for(double y:new double[]{-.304,.026,.26428}){
                    V3 end=previous.model(new V3(x,y,flip?-.3:.3),flip,0,-.3,.3),start=next.model(new V3(x,y,flip?.3:-.3),flip,0,-.3,.3);if(end.distance(start)>worst){worst=end.distance(start);context="rail="+rails.indexOf(rail)+" flip="+flip+" cell="+cell+" previous="+previous+" next="+next;}points++;
                }previous=next;
            }
        }
        if(worst>1e-7){
            System.out.println("PACK_CONTINUITY_DIAGNOSTIC: worst="+worst+" "+context);
            throw new AssertionError("Grade/cant cells have a visible gap: "+worst+" "+context);
        }
        System.out.println("PACK_CONTINUITY: PASS actual render cells, grade/cant transitions and reversed styles; points="+points+" maxGap="+worst);
    }
    private static void checkHandoff(){
        int hidden=0,outside=0;
        for(Rail rail:rails)for(var cell:RailSampler.cells(rail,.6)){
            V3 center=cell.a().lerp(cell.b(),.5);boolean expected=PointClient.suppress(rail,STYLE,center,0);
            for(String alias:List.of(STYLE+"_1",STYLE+"_2","mtrsteamloco:"+STYLE,"mtrsteamloco:"+STYLE+"_2"))
                if(PointClient.suppress(rail,alias,center,0)!=expected)throw new AssertionError("Native handoff differs for style alias "+alias);
            if(expected)hidden++;else outside++;
        }
        if(hidden==0||outside==0)throw new AssertionError("Handoff fixture does not exercise both render paths");
        System.out.println("PACK_HANDOFF: PASS native/custom boundary agrees for all four style aliases; hiddenCells="+hidden+" ordinaryCells="+outside);
    }
    private static void checkSeams()throws Exception{
        org.mtr.mod.resource.RailResource[] resource={null};org.mtr.mod.client.CustomResourceLoader.getRailById(STYLE,r->resource[0]=r);
        int changed=0;for(Rail rail:rails.subList(3,6)){SleeperSeams.begin();for(var cell:RailSampler.cells(rail,.6))if(SleeperSeams.render(rail,resource[0],false,cell.a(),cell.b()))changed++;}
        if(changed==0)throw new AssertionError("Real MTR endpoint overrun was not corrected");
        // Discard test-submitted faces; the real frame will submit them once.
        var discard=Class.forName("org.mtrpoint.client.RailCellCache").getDeclaredMethod("discard");discard.setAccessible(true);discard.invoke(null);
        System.out.println("PACK_SEAMS: PASS real native cells adjusted="+changed+" including reversed segment");
    }
    private static void checkBanking()throws Exception{
        var resource=new org.mtr.mod.resource.RailResource[1];org.mtr.mod.client.CustomResourceLoader.getRailById(STYLE,r->resource[0]=r);
        var points=new ArrayList<V3>();var expected=new ArrayList<V3>();Rail rail=rails.get(6);Track track=RailSampler.sample(rail);
        RailMath.RenderRail callback=(x1,z1,x2,z2,x3,z3,x4,z4,y1,y2)->{
            V3 a=new V3(x1,y1,z1),b=new V3(x3,y2,z3),center=a.lerp(b,.5),right=b.sub(a).lateral().mul(-1);
            var nativeFrame=RailSampler.sweep(rail,resource[0],a,b);
            for(double x:new double[]{-1.32,-.7515,0,.7515,1.32})for(double y:new double[]{-.304,.034,.085422,.26428}){
                points.add(center.add(right.mul(x)).add(0,y,0));expected.add(nativeFrame.model(new V3(x,y,0),false,0,-.3,.3));
            }
        };
        if(net.minecraftforge.fml.ModList.get().isLoaded("mtr_optional_rail_addon"))Class.forName("org.mtroptional.client.RailGeometry").getMethod("render",Rail.class,RailMath.RenderRail.class,double.class,float.class,float.class).invoke(null,rail,callback,.6,0F,0F);
        else rail.railMath.render(callback,.6,0,0);
        Mesh input=new Mesh();for(V3 p:points)input.quad(p,p,p,p,Profile.STEEL,"bank-check",-1);
        Mesh output=RailSampler.bank(input,null,List.of(track),STYLE);double worst=0;
        for(int i=0;i<points.size();i++)worst=Math.max(worst,output.quads.get(i).a().distance(expected.get(i)));
        if(worst>1e-7)throw new AssertionError("Native / turnout layer drift: "+worst);
        System.out.println("PACK_BANKING: PASS actual native cells, ballast/sleeper/rail axes agree; maxError="+worst+" points="+points.size());
    }
    private static void bank(Minecraft mc,int x,int z,double cant)throws Exception{
        bank(mc,x,67,z,cant);
    }
    private static void bank(Minecraft mc,int x,int y,int z,double cant)throws Exception{
        if(!net.minecraftforge.fml.ModList.get().isLoaded("mtr_optional_rail_addon"))return;
        Class<?> settings=Class.forName("org.mtroptional.NodeSettings"),state=Class.forName("org.mtroptional.RailNetwork$State");
        Object value=settings.getConstructor(double.class,double.class,double.class,double.class,double.class).newInstance(0D,0D,0D,0D,cant);
        Object packet=state.getConstructor(String.class,boolean.class,long.class,settings,long.class,int.class).newInstance(mc.level.dimension().location().toString(),false,net.minecraft.core.BlockPos.asLong(x,y,z),value,1L,0);
        Class.forName("org.mtroptional.client.ClientNodes").getMethod("receive",state).invoke(null,packet);
    }
    private static Rail rail(int x1,int z1,int x2,int z2,float a,float b){
        return rail(x1,67,z1,x2,67,z2,a,b);
    }
    private static Rail rail(int x1,int y1,int z1,int x2,int y2,int z2,float a,float b){
        var p=new Position(x1,y1,z1);var q=new Position(x2,y2,z2);var angles=Rail.getAngles(p,a,q,b);
        var rail=Rail.newRail(p,angles.left(),q,angles.right(),Rail.Shape.QUADRATIC,0,org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList.of(z1>z2?STYLE+"_2":STYLE),80,80,false,false,true,false,true,TransportMode.TRAIN);
        if(rail==null)throw new AssertionError("Cannot create pack test rail");return rail;
    }
    private static void camera(Minecraft mc,double x,double y,double z,float yaw,float pitch){mc.getSingleplayerServer().execute(()->mc.getSingleplayerServer().getPlayerList().getPlayers().get(0).connection.teleport(x,y+3,z,yaw,pitch));}
    private static void shot(Minecraft mc,String name){Screenshot.grab(mc.gameDirectory,name,mc.getMainRenderTarget(),m->System.out.println("PACK_SCREENSHOT: "+name));}
}
