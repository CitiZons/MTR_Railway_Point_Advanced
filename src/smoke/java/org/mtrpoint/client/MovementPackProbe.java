package org.mtrpointprobe;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import org.mtr.core.data.*;
import org.mtrpoint.client.*;
import org.mtrpoint.geometry.Mesh;
import org.mtrpoint.geometry.V3;
import java.lang.reflect.*;
import java.util.*;

/** Isolated client movement probe for handoff readiness and persistent ordinary-rail geometry. */
final class MovementPackProbe {
    private static final String CUSTOM="citizons_mainline_1435";
    private static final String CENTER_GUARD="citizons_center_guard_1435",OUTER_GUARD="citizons_outer_guard_1435";
    private static final double START_Z=-20, CAMERA_X=4;
    private static final double[] SPEEDS={0,1,4,10,20,20,10,4,1};
    private static final int[] DIRECTIONS={0,1,1,1,1,-1,-1,-1,-1};
    private static final List<Double> FRAME_MS=new ArrayList<>();
    private static final IdentityHashMap<PointClient.View,long[]> FIRST_STAGE_FRAME=new IdentityHashMap<>(),FIRST_STAGE_MS=new IdentityHashMap<>();
    private static List<Rail> rails;
    private static int ticks,phase=-1,settleTicks,stableTicks,preparationFrame;
    private static long lastTickNs,lastRenderNs,phaseStartNs,phaseFrames,uploadBase,buildBase,frameBase,preparationStartNs;
    private static long stationaryUploads,stationaryBuilds,lastStableBuild=-1,lastStableUpload=-1,returnStableBuild=-1,returnStableUpload=-1;
    private static double phaseStartZ,requestedZ=START_Z;
    private static boolean initialized,readyScreenshot,finalScreenshot;
    private static boolean centerGuardPrecommit,outerGuardPrecommit,centerGuardFast,outerGuardFast,outerGuardShot;
    private static boolean centerPrecommitRequested,outerPrecommitRequested,centerLoggedPrecommit,outerLoggedPrecommit,centerLoggedFast,outerLoggedFast;
    private static int centerGuardReadySamples,outerGuardReadySamples,centerGuardRequestedSamples,outerGuardRequestedSamples;
    private static String ballastStyle,slabStyle,directStyle;

    private MovementPackProbe() {}
    static void tick(Minecraft mc){try{run(mc);}catch(Throwable failure){failure.printStackTrace();System.out.println("MOVEMENT_PACK: FAIL");mc.stop();}}
    private static void run(Minecraft mc)throws Exception{
        if(mc.level==null||mc.player==null)return;
        if(!initialized)initialize(mc);
        var data=org.mtr.mod.client.MinecraftClientData.getInstance();data.rails.addAll(rails);data.sync();data.railWrapperList.values().forEach(r->r.shouldRender=true);
        long now=System.nanoTime();
        if(!initialized)return;
        ticks++;
        checkBudgets();
        if(ticks==25){preparationStartNs=now;preparationFrame=0;PointClient.invalidate();PointClient.rebuild();long yCount=PointClient.views.stream().filter(v->v.junction.kind()==org.mtrpoint.geometry.Junction.Kind.Y).count();if(yCount<2)throw new AssertionError("Adjacent Y fixtures did not produce two turnout views");System.out.println("MOVEMENT_FIXTURE: rails="+rails.size()+" styles="+ballastStyle+","+slabStyle+","+directStyle+" yViews="+yCount);}
        if(ticks>=25&&phase<0){
            boolean ready=settled(mc);
            long observedBuild=buildCount(),observedUpload=uploadCount();
            if(ready&&observedBuild==lastStableBuild&&observedUpload==lastStableUpload)stableTicks++;else stableTicks=0;
            lastStableBuild=observedBuild;lastStableUpload=observedUpload;
            if(stableTicks>=30){
                var turnout=turnout();if(turnout==null)throw new AssertionError("Live MTR rails did not produce the Y turnout");
                if(!hasCommittedMetadata())throw new AssertionError("Rail-cell scene did not reach committed metadata");
                if(uploadCount()<=0)throw new AssertionError("No PointGpu uploads were observed");
                stationaryUploads=uploadCount();stationaryBuilds=buildCount();frameBase=cellFrame();
                System.out.println("MOVEMENT_WARM: PASS views="+PointClient.views.size()+" committedRegions="+committedCount()+" uploads="+stationaryUploads+" builds="+stationaryBuilds);
                camera(mc,CAMERA_X,START_Z,26);shot(mc,"movement-pack-ready.png");shot(mc,"movement-pack-center-guard-terminal.png");readyScreenshot=true;
                phase=0;beginPhase(mc,now);
            }
            if(ticks>425)throw new AssertionError("Runtime geometry/readiness did not settle within 400 ticks");
            return;
        }
        if(phase>=0){
            if(phase==SPEEDS.length){
                camera(mc,CAMERA_X,START_Z,26);
                long observedBuild=buildCount(),observedUpload=uploadCount();
                if(settled(mc)&&observedBuild==returnStableBuild&&observedUpload==returnStableUpload)settleTicks++;else settleTicks=0;
                returnStableBuild=observedBuild;returnStableUpload=observedUpload;
                if(settleTicks>=35){
                    if(!hasCommittedMetadata())throw new AssertionError("Metadata was lost after movement");
                    if(PointClient.views.stream().noneMatch(v->v.junction.kind()==org.mtrpoint.geometry.Junction.Kind.Y))throw new AssertionError("Turnout view disappeared from unchanged loaded rail set");
                    long finalBuilds=buildCount(),finalUploads=uploadCount();
                    if(cellFrame()<=frameBase)throw new AssertionError("No ordinary rail frames were rendered");
                if(!centerGuardPrecommit||!outerGuardPrecommit)throw new AssertionError("Guard terminal cells never reached GPU readiness ahead of their ordinary RailCellCache region commit: center="+centerGuardPrecommit+" outer="+outerGuardPrecommit);
                if(!centerPrecommitRequested||!outerPrecommitRequested)throw new AssertionError("Native render never requested both guard fallback parts before their ordinary scene committed: center="+centerPrecommitRequested+" outer="+outerPrecommitRequested);
                if(!centerGuardFast||!outerGuardFast)throw new AssertionError("Guard terminal readiness was not observed near both endpoints during 10/20 m/s movement: center="+centerGuardFast+" outer="+outerGuardFast);
                    shot(mc,"movement-pack-return.png");finalScreenshot=true;
                    printPreparationStages();printLatency();System.out.println("MOVEMENT_GUARD_TERMINALS: PASS centerReady="+centerGuardReadySamples+" outerReady="+outerGuardReadySamples+" centerRequested="+centerGuardRequestedSamples+" outerRequested="+outerGuardRequestedSamples+" precommit=center,outer fastMovement=center,outer screenshots=center-ready,outer-fast");
                    System.out.println("MOVEMENT_PACK: PASS returnedStationaryTicks="+settleTicks+" builds="+finalBuilds+" uploads="+finalUploads+" committedRegions="+committedCount()+" screenshots=ready,center-guard,outer-guard,return");mc.stop();return;
                }
                return;
            }
            move(mc,now);
            if(now-phaseStartNs>=1_500_000_000L){
                double actualZ=mc.player.getZ();double seconds=Math.max(1e-6,(now-phaseStartNs)/1e9);double speed=(actualZ-phaseStartZ)/seconds;
                System.out.printf(Locale.ROOT,"MOVEMENT_PHASE: speed=%.0f requestedDirection=%d actualDisplacement=%.2fm elapsed=%.3fs measured=%.2fm/s frames=%d builds=%d uploads=%d%n",SPEEDS[phase],DIRECTIONS[phase],actualZ-phaseStartZ,seconds,speed,phaseFrames,buildCount()-buildBase,uploadCount()-uploadBase);
                phase++;if(phase<SPEEDS.length)beginPhase(mc,now);else{settleTicks=0;phaseStartNs=now;}
            }
        }
        lastTickNs=now;
    }
    private static void initialize(Minecraft mc)throws Exception{
        var resources=org.mtr.mod.client.CustomResourceLoader.getRails();
        if(!Profiles.get(CUSTOM).track())throw new AssertionError("Custom ballast profile unavailable: "+CUSTOM);
        if(!Profiles.get(CENTER_GUARD).track()||!Profiles.get(OUTER_GUARD).track())throw new AssertionError("Continuous guard test profiles are unavailable");
        ballastStyle=CUSTOM;
        slabStyle=resources.stream().map(r->r.getId()).filter(id->id.toLowerCase(Locale.ROOT).contains("slab")).findFirst().orElse("default_3d");
        directStyle=resources.stream().map(r->r.getId()).filter(id->id.equals("citizons_direct_1435")).findFirst().orElse("default_3d");
        rails=new ArrayList<>();
        // Three real MTR rails exercise custom ballast, the available slab profile (or built-in
        // 3D rail fallback), and native direct track across one continuous loaded corridor.
        rails.add(rail(-8,-48,-8,96,ballastStyle));rails.add(rail(0,-48,0,96,slabStyle));rails.add(rail(8,-48,8,96,directStyle));
        // Real continuous-guard rails end beside the moving path. Their terminal chunks
        // are tested while the larger ordinary regional scene is still preparing.
        rails.add(rail(4,-8,4,0,CENTER_GUARD));rails.add(rail(4,22,4,30,OUTER_GUARD));
        // Two adjacent live Y turnouts share an overlapping preparation component, letting the
        // probe observe per-view fallback before the pooled component can take over.
        rails.add(rail(18,-20,18,0,90,90,ballastStyle));rails.add(rail(18,0,11,30,90,112.5F,ballastStyle));rails.add(rail(18,0,25,30,90,67.5F,ballastStyle));
        rails.add(rail(22,-20,22,0,90,90,ballastStyle));rails.add(rail(22,0,15,30,90,112.5F,ballastStyle));rails.add(rail(22,0,29,30,90,67.5F,ballastStyle));
        mc.options.hideGui=true;
        mc.getSingleplayerServer().execute(()->{
            var server=mc.getSingleplayerServer();var level=server.overworld();
            for(int x=-16;x<34;x++)for(int z=-56;z<105;z++)level.setBlock(new BlockPos(x,63,z),Blocks.SMOOTH_STONE.defaultBlockState(),3);
            level.setDayTime(6000);var player=server.getPlayerList().getPlayers().get(0);player.getAbilities().flying=true;player.onUpdateAbilities();player.connection.teleport(CAMERA_X,76,START_Z,0,26);
        });
        MinecraftForge.EVENT_BUS.addListener(MovementPackProbe::renderStage);
        initialized=true;lastTickNs=System.nanoTime();
        System.out.println("MOVEMENT_FIXTURE: isolated flat world; corridor z=-48..96, guard endpoints center=0 outer=30, styles="+ballastStyle+","+slabStyle+","+directStyle+","+CENTER_GUARD+","+OUTER_GUARD);
    }
    private static Rail rail(int x1,int z1,int x2,int z2,String style){return rail(x1,z1,x2,z2,90,90,style);}
    private static Rail rail(int x1,int z1,int x2,int z2,float startAngle,float endAngle,String style){
        var p=new Position(x1,67,z1);var q=new Position(x2,67,z2);var angles=Rail.getAngles(p,startAngle,q,endAngle);
        Rail rail=Rail.newRail(p,angles.left(),q,angles.right(),Rail.Shape.QUADRATIC,0,
            org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList.of(z1>z2?style+"_2":style),80,80,false,false,true,false,true,TransportMode.TRAIN);
        if(rail==null)throw new AssertionError("Cannot create movement-probe rail for "+style);return rail;
    }
    private static void beginPhase(Minecraft mc,long now)throws Exception{
        phaseStartNs=now;phaseStartZ=mc.player.getZ();phaseFrames=0;uploadBase=uploadCount();buildBase=buildCount();lastTickNs=now;
        System.out.println("MOVEMENT_PHASE_START: speed="+SPEEDS[phase]+" direction="+DIRECTIONS[phase]);
    }
    private static void move(Minecraft mc,long now){
        long previous=lastTickNs==0?now:lastTickNs;double seconds=Math.max(0,Math.min(.5,(now-previous)/1e9));
        if(DIRECTIONS[phase]!=0){requestedZ+=DIRECTIONS[phase]*SPEEDS[phase]*seconds;requestedZ=Math.max(-40,Math.min(40,requestedZ));}
        double z=requestedZ;
        mc.getSingleplayerServer().execute(()->mc.getSingleplayerServer().getPlayerList().getPlayers().get(0).connection.teleport(CAMERA_X,76,z,0,26));
        lastTickNs=now;
    }
    private static void renderStage(RenderLevelStageEvent event){
        if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_ENTITIES)return;
        long now=System.nanoTime();if(lastRenderNs!=0&&phase>=0&&phase<SPEEDS.length){FRAME_MS.add((now-lastRenderNs)/1e6);phaseFrames++;}
        lastRenderNs=now;preparationFrame++;
        try{
            if(uploadBatches()>8)throw new AssertionError("PointGpu upload budget exceeded 8 batches in one render frame");
            if(queueCount()>96)throw new AssertionError("Geometry job queue exceeded 96");
            sampleGuardTerminal(event,CENTER_GUARD,0,true);
            sampleGuardTerminal(event,OUTER_GUARD,30,false);
            if(phase==4&&!outerGuardShot){shot(Minecraft.getInstance(),"movement-pack-outer-guard-terminal.png");outerGuardShot=true;}
            for(var view:turnouts())sampleTakeoverStage(view,now);
        }catch(Throwable failure){throw new RuntimeException("movement probe render readiness",failure);}
    }
    private static void sampleTakeoverStage(PointClient.View view,long now)throws Exception{
        if(preparationStartNs==0)return;
        int stage=PointRenderer.takeoverStage(view);if(stage<0||stage>2)throw new AssertionError("Invalid turnout takeover stage: "+stage);
        long[] frames=FIRST_STAGE_FRAME.computeIfAbsent(view,key->{long[] values=new long[3];Arrays.fill(values,-1);return values;});
        long[] elapsed=FIRST_STAGE_MS.computeIfAbsent(view,key->{long[] values=new long[3];Arrays.fill(values,-1);return values;});
        if(frames[stage]<0){frames[stage]=preparationFrame;elapsed[stage]=Math.max(0,(now-preparationStartNs)/1_000_000L);System.out.println("PREPARATION_STAGE_FIRST: turnout="+turnouts().indexOf(view)+" stage="+stage+" frame="+frames[stage]+" elapsedMs="+elapsed[stage]);}
        if(stage==0){if(suppressesTurnout(view))throw new AssertionError("Native turnout cells were suppressed before either GPU replacement was ready");return;}
        if(stage==1){
            Map<?,?> fallbackGpus=(Map<?,?>)field(PointRenderer.class,"FALLBACK_GPU").get(null),fallbacks=(Map<?,?>)field(PointRenderer.class,"FALLBACKS").get(null);
            Object gpu=fallbackGpus.get(view),fallback=fallbacks.get(view);if(gpu==null||fallback==null)throw new AssertionError("Stage 1 was selected without an independent fallback GPU");
            Mesh mesh=(Mesh)field(fallback.getClass(),"mesh").get(fallback);if(!gpuReady(gpu)||!gpuHasSource(gpu,mesh))throw new AssertionError("Stage 1 fallback GPU is not ready for its complete source mesh");
            Map<?,?> attachments=(Map<?,?>)field(fallback.getClass(),"attachments").get(fallback);
            for(Object attachment:attachments.values()){
                Object attachmentGpu=field(attachment.getClass(),"gpu").get(attachment);Mesh attachmentMesh=(Mesh)field(attachment.getClass(),"mesh").get(attachment);
                if(!gpuReady(attachmentGpu)||!gpuHasSource(attachmentGpu,attachmentMesh))throw new AssertionError("Stage 1 suppressed native cells before its ballast/concrete attachment GPU was ready");
            }
            return;
        }
        Map<?,?> pooled=(Map<?,?>)field(PointRenderer.class,"GPU").get(null);Object gpu=pooled.get(view);Mesh mesh=view.mesh();
        if(gpu==null||mesh==null||!gpuReady(gpu)||!gpuHasSource(gpu,mesh))throw new AssertionError("Stage 2 pooled takeover selected before this view's thin GPU was ready");
    }
    private static void sampleGuardTerminal(RenderLevelStageEvent event,String style,double endpointZ,boolean center)throws Exception{
        GuardTarget target=findGuardTarget(style,endpointZ);if(target==null)return;
        Object terminalEntry=((Map<?,?>)field(Class.forName("org.mtrpoint.client.GuardTerminalCache"),"ENTRIES").get(null)).get(target.owner);
        if(terminalEntry==null)return;Map<?,?> parts=(Map<?,?>)field(terminalEntry.getClass(),"parts").get(terminalEntry);if(parts==null)return;
        Object chunk=ordinaryChunk(target.position);Object part=parts.get(chunk);if(part==null)return;
        Object gpu=field(part.getClass(),"gpu").get(part),mesh=field(part.getClass(),"mesh").get(part);
        boolean requestedBefore=(boolean)field(part.getClass(),"requested").get(part);
        Method ready=Class.forName("org.mtrpoint.client.GuardTerminalCache").getDeclaredMethod("ready",V3.class,String.class);ready.setAccessible(true);
        boolean terminalReady=(boolean)ready.invoke(null,target.position,target.owner);
        Method hasSourceMethod=gpu.getClass().getDeclaredMethod("hasSource",Mesh.class);hasSourceMethod.setAccessible(true);boolean hasSource=(boolean)hasSourceMethod.invoke(gpu,mesh);
        boolean gpuReady=gpuReady(gpu);
        if(terminalReady&&(!hasSource||!gpuReady))throw new AssertionError("Guard terminal reported ready without its exact uploaded GPU mesh: "+style);
        Class<?> railCellCache=cacheClass();Method nativeMethod=railCellCache.getDeclaredMethod("ready",V3.class,String.class);nativeMethod.setAccessible(true);
        Method committedMethod=railCellCache.getDeclaredMethod("committedReady",V3.class,String.class);committedMethod.setAccessible(true);
        boolean nativeReady=(boolean)nativeMethod.invoke(null,target.position,target.owner);
        boolean committedCell=(boolean)committedMethod.invoke(null,target.position,target.owner),committedScene=sceneCommitted(target.position);
        if(!terminalReady&&!committedCell){if(nativeReady)throw new AssertionError("Native cells suppressed before either replacement was ready: "+style);return;}
        if(!nativeReady)throw new AssertionError("Neither committed scene nor guard terminal GPU made native per-cell readiness true: "+style);
        if(center){if(terminalReady)centerGuardReadySamples++;if(requestedBefore)centerGuardRequestedSamples++;if(!committedScene&&terminalReady){centerGuardPrecommit=true;if(requestedBefore)centerPrecommitRequested=true;if(!centerLoggedPrecommit){System.out.println("MOVEMENT_GUARD_PRECOMMIT: PASS style="+style+" sceneCommitted="+committedScene+" cellCommitted="+committedCell+" requested="+requestedBefore);centerLoggedPrecommit=true;}}}
        else{if(terminalReady)outerGuardReadySamples++;if(requestedBefore)outerGuardRequestedSamples++;if(!committedScene&&terminalReady){outerGuardPrecommit=true;if(requestedBefore)outerPrecommitRequested=true;if(!outerLoggedPrecommit){System.out.println("MOVEMENT_GUARD_PRECOMMIT: PASS style="+style+" sceneCommitted="+committedScene+" cellCommitted="+committedCell+" requested="+requestedBefore);outerLoggedPrecommit=true;}}}
        if(phase>=0&&phase<SPEEDS.length&&SPEEDS[phase]>=10&&Math.abs(Minecraft.getInstance().player.getZ()-target.position.z())<=12){if(center){centerGuardFast=true;if(!centerLoggedFast){System.out.println("MOVEMENT_GUARD_FAST: PASS style="+style+" requestedSpeed="+SPEEDS[phase]+" z="+target.position.z()+" terminalReady="+terminalReady+" committedCell="+committedCell);centerLoggedFast=true;}}else{outerGuardFast=true;if(!outerLoggedFast){System.out.println("MOVEMENT_GUARD_FAST: PASS style="+style+" requestedSpeed="+SPEEDS[phase]+" z="+target.position.z()+" terminalReady="+terminalReady+" committedCell="+committedCell);outerLoggedFast=true;}}}
    }
    private record GuardTarget(String owner,V3 position){}
    private static GuardTarget findGuardTarget(String style,double endpointZ)throws Exception{
        Map<?,?> ordinary=(Map<?,?>)field(OrdinaryRailCache.class,"ENTRIES").get(null);
        for(Object entry:ordinary.values()){
            Object resource=field(entry.getClass(),"resource").get(entry);String id=((org.mtr.mod.resource.RailResource)resource).getId().replace("_2","");if(!id.equals(style))continue;
            String owner=(String)field(entry.getClass(),"owner").get(entry);Object terminal=((Map<?,?>)field(Class.forName("org.mtrpoint.client.GuardTerminalCache"),"ENTRIES").get(null)).get(owner);if(terminal==null)continue;
            Map<?,?> parts=(Map<?,?>)field(terminal.getClass(),"parts").get(terminal);if(parts==null||parts.isEmpty())continue;
            V3 nearest=null;double best=Double.POSITIVE_INFINITY;
            for(Object chunk:parts.keySet()){V3 center=chunkCenter(chunk);double distance=Math.abs(center.z()-endpointZ)+Math.abs(center.x()-CAMERA_X);if(distance<best){best=distance;nearest=center;}}
            if(nearest!=null)return new GuardTarget(owner,nearest);
        }
        return null;
    }
    private static Object ordinaryChunk(V3 position)throws Exception{Class<?> type=Class.forName("org.mtrpoint.client.OrdinaryRailCache$Chunk");Method of=type.getDeclaredMethod("of",V3.class);of.setAccessible(true);return of.invoke(null,position);}
    private static V3 chunkCenter(Object chunk)throws Exception{Method center=chunk.getClass().getDeclaredMethod("center");center.setAccessible(true);return (V3)center.invoke(chunk);}
    private static boolean sceneCommitted(V3 position)throws Exception{
        int x=(int)Math.floor(position.x()/16),z=(int)Math.floor(position.z()/16);long key=((long)x<<32)^(z&0xffffffffL);
        return ((Map<?,?>)field(cacheClass(),"COMMITTED").get(null)).containsKey(key);
    }
    private static boolean suppressesTurnout(PointClient.View view)throws Exception{
        var data=org.mtr.mod.client.MinecraftClientData.getInstance();
        for(Rail rail:data.rails)if(rail.getStyles().stream().anyMatch(s->s.replace("_2","").equals(ballastStyle))){
            if(rail.getHexId().equals(view.junction.a().id)&&PointClient.hasCandidates(rail,ballastStyle))return PointClient.suppress(rail,ballastStyle,view.junction.center(),0);
        }
        return false;
    }
    private static boolean settled(Minecraft mc)throws Exception{
        if(!hasCommittedMetadata()||uploadCount()==0)return false;
        var views=turnouts();if(views.size()<2)return false;
        Map<?,?> components=(Map<?,?>)field(PointRenderer.class,"COMPONENT_BY_VIEW").get(null);
        for(var view:views){Object component=components.get(view);if(!(component instanceof List<?> members))return false;
            if(PointRenderer.takeoverStage(view)!=(members.size()==1?1:2))return false;
        }
        return queueCount()==0;
    }
    private static PointClient.View turnout(){return PointClient.views.stream().filter(v->v.junction.kind()==org.mtrpoint.geometry.Junction.Kind.Y&&v.settings.enabled()).findFirst().orElse(null);}
    private static List<PointClient.View> turnouts(){return PointClient.views.stream().filter(v->v.junction.kind()==org.mtrpoint.geometry.Junction.Kind.Y&&v.settings.enabled()).toList();}
    private static void printPreparationStages(){
        List<PointClient.View> views=turnouts();for(int i=0;i<views.size();i++){long[] f=FIRST_STAGE_FRAME.get(views.get(i)),ms=FIRST_STAGE_MS.get(views.get(i));if(f==null){System.out.println("PREPARATION_STAGE_SUMMARY: turnout="+i+" noStagesObserved");continue;}System.out.println("PREPARATION_STAGE_SUMMARY: turnout="+i+" stage0Frame="+f[0]+" stage0Ms="+ms[0]+" stage1Frame="+f[1]+" stage1Ms="+ms[1]+" stage2Frame="+f[2]+" stage2Ms="+ms[2]+" fallbackBeforePooled="+(f[1]>=0&&f[2]>=0&&f[1]<f[2]));}
    }
    private static void camera(Minecraft mc,double x,double z,float pitch){mc.getSingleplayerServer().execute(()->mc.getSingleplayerServer().getPlayerList().getPlayers().get(0).connection.teleport(x,76,z,0,pitch));}
    private static void shot(Minecraft mc,String name){Screenshot.grab(mc.gameDirectory,name,mc.getMainRenderTarget(),m->System.out.println("MOVEMENT_SCREENSHOT: "+name));}
    private static boolean hasCommittedMetadata()throws Exception{return committedCount()>0;}
    private static int committedCount()throws Exception{return ((Map<?,?>)field(cacheClass(),"COMMITTED").get(null)).size();}
    private static long uploadCount()throws Exception{return field(gpuClass(),"uploads").getLong(null);}
    private static long buildCount()throws Exception{return field(cacheClass(),"builds").getLong(null);}
    private static long cellFrame()throws Exception{return field(cacheClass(),"frame").getLong(null);}
    private static int uploadBatches()throws Exception{Object budget=field(gpuClass(),"UPLOAD_BUDGET").get(null);Method m=budget.getClass().getDeclaredMethod("batches");m.setAccessible(true);return (int)m.invoke(budget);}
    private static int queueCount()throws Exception{Class<?> jobs=Class.forName("org.mtrpoint.client.GeometryJobs");Method m=jobs.getDeclaredMethod("queueCount");m.setAccessible(true);return (int)m.invoke(null);}
    private static boolean gpuReady(Object gpu)throws Exception{Method m=gpu.getClass().getDeclaredMethod("readyForDraw");m.setAccessible(true);return (boolean)m.invoke(gpu);}
    private static boolean gpuHasSource(Object gpu,Mesh mesh)throws Exception{Method m=gpu.getClass().getDeclaredMethod("hasSource",Mesh.class);m.setAccessible(true);return (boolean)m.invoke(gpu,mesh);}
    private static Class<?> cacheClass()throws ClassNotFoundException{return Class.forName("org.mtrpoint.client.RailCellCache");}
    private static Class<?> gpuClass()throws ClassNotFoundException{return Class.forName("org.mtrpoint.client.PointGpu");}
    private static Field field(Class<?> type,String name)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private static void checkBudgets()throws Exception{if(queueCount()>96)throw new AssertionError("Geometry queue bound exceeded");if(uploadBatches()>8)throw new AssertionError("Per-frame upload batch bound exceeded");}
    private static void printLatency(){
        if(FRAME_MS.isEmpty()){System.out.println("MOVEMENT_FRAME_INTERVAL: no render intervals recorded");return;}
        double[] values=FRAME_MS.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        System.out.printf(Locale.ROOT,"MOVEMENT_FRAME_INTERVAL_MS: samples=%d p50=%.2f p95=%.2f p99=%.2f (render-stage intervals; not world-FPS claims)%n",values.length,percentile(values,.50),percentile(values,.95),percentile(values,.99));
    }
    private static double percentile(double[] values,double q){return values[Math.min(values.length-1,(int)Math.ceil(values.length*q)-1)];}
}
