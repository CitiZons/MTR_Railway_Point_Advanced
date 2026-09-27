package org.mtrpointprobe;

import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.nbt.NbtIo;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import org.mtr.core.data.Rail;
import org.mtr.core.serializer.MessagePackReader;
import org.mtr.libraries.org.msgpack.core.MessagePack;
import org.mtrpoint.*;
import org.mtrpoint.client.*;
import org.mtrpoint.geometry.*;

/** Read saved rail definitions into an isolated client fixture; never open the source world. */
public final class SavedLayoutProbe {
    private static final List<Rail> rails=new ArrayList<>();
    private static int ticks,frames,phase;
    private static boolean ready;
    private static V3 capTarget,capCamera;
    public static void tick(Minecraft mc){
        if(ticks++==0)try{
            Path root=Path.of(System.getProperty("pointProbeLayout"));
            try(var paths=Files.walk(root.resolve("mtr/minecraft/overworld/rails"))){
                for(Path path:paths.filter(Files::isRegularFile).toList())try(var unpack=MessagePack.newDefaultUnpacker(Files.readAllBytes(path))){
                    Rail rail=new Rail(new MessagePackReader(unpack));
                    if(rail.isValid()&&rail.railMath.maxX>=35&&rail.railMath.minX<=110&&rail.railMath.maxZ>=-50&&rail.railMath.minZ<=8)rails.add(rail);
                }
            }
            var saved=AppearanceData.load(NbtIo.readCompressed(root.resolve("data/mtrpoint_appearance.dat").toFile()).getCompound("data"));
            PointClient.clear();for(var entry:saved.entries.entrySet())PointClient.receive(new PointNetwork.State("minecraft:overworld",false,entry.getKey(),entry.getValue().revision(),AppearanceData.JSON.toJson(entry.getValue().value()),""));
            mc.options.hideGui=true;
            mc.getSingleplayerServer().execute(()->{
                var server=mc.getSingleplayerServer();server.overworld().setDayTime(6000);
                var player=server.getPlayerList().getPlayers().get(0);player.getAbilities().flying=true;player.onUpdateAbilities();
                player.connection.teleport(51,-43,-6,0,90);
            });
            MinecraftForge.EVENT_BUS.addListener(SavedLayoutProbe::render);
            System.out.println("POINT_SAVED_LAYOUT: loaded "+rails.size()+" rails, "+saved.entries.size()+" saved appearances");
        }catch(Exception ex){throw new AssertionError(ex);}
        var data=org.mtr.mod.client.MinecraftClientData.getInstance();data.rails.clear();data.rails.addAll(rails);data.sync();data.railWrapperList.values().forEach(r->r.shouldRender=true);
        if(ticks==40){PointClient.invalidate();PointClient.rebuild();
            Mesh.CAP_CALLS=Mesh.CAP_LOOPS=Mesh.CAP_FACES=Mesh.CAP_EDGE_CANDIDATES=Mesh.CAP_EDGE_UNIQUE=Mesh.CAP_OUTLINE_POINTS=0;
            try{
                var profiles=new LinkedHashMap<String,Profile>();for(var view:PointClient.views)profiles.put(view.junction.id(),view.profile);
                Files.writeString(mc.gameDirectory.toPath().resolve("saved-profiles.json"),AppearanceData.JSON.toJson(profiles));
            }catch(java.io.IOException ex){throw new AssertionError(ex);}
            for(var view:PointClient.views){view.mesh();var d=view.profile.detail();System.out.println("LAYOUT_VIEW "+view.junction.kind()+" "+view.junction.center()+" scale="+view.settings.lengthScale()+" region="+view.region+" channels="+view.channels.size()+" detail="+(d==null?"null":("rails="+d.rails().size()+" z="+d.zMin()+".."+d.zMax())));}
            checkCrossings();
            ready=true;
        }
    }
    private static void checkCrossings(){
        try{
            var method=PointRenderer.class.getDeclaredMethod("worldForTest",List.class);method.setAccessible(true);
            Mesh world=(Mesh)method.invoke(null,PointClient.views);
            var bins=new HashMap<Long,List<Mesh.Quad>>();
            for(var q:world.quads){if(q.part().equals("sleeper")||q.part().equals("fastener"))continue;
                var vertices=List.of(q.a(),q.b(),q.c(),q.d());
                int x0=(int)Math.floor(vertices.stream().mapToDouble(V3::x).min().orElse(0)),x1=(int)Math.floor(vertices.stream().mapToDouble(V3::x).max().orElse(0));
                int z0=(int)Math.floor(vertices.stream().mapToDouble(V3::z).min().orElse(0)),z1=(int)Math.floor(vertices.stream().mapToDouble(V3::z).max().orElse(0));
                for(int x=x0;x<=x1;x++)for(int z=z0;z<=z1;z++)bins.computeIfAbsent(cell(x,z),k->new ArrayList<>()).add(q);
            }
            int count=0,blocked=0;
            for(var view:PointClient.views)if(view.junction.kind()==Junction.Kind.DIAMOND){
                var p=view.profile.tune(view.settings);double channel=p.centerOffset()-p.headWidth()/2-(view.settings.flangeway()+view.settings.wingGapDelta())/2;
                for(var road:view.junction.tracks())for(int side:new int[]{-1,1})for(double d=road.nearest(view.junction.center())-1;d<=road.nearest(view.junction.center())+1;d+=.025){
                    V3 q=road.at(d).add(road.tangent(d).lateral().mul(side*channel)).add(0,p.top()+view.settings.verticalOffset(),0);count++;
                    for(var face:bins.getOrDefault(cell((int)Math.floor(q.x()),(int)Math.floor(q.z())),List.of()))
                        if(hit(q,face.a(),face.b(),face.c())||hit(q,face.a(),face.c(),face.d())){if(blocked++<8){
                            System.out.println("SAVED_BLOCKED "+q+" part="+face.part()+" road="+road.id+" face="+face);
                            for(var owner:PointClient.views)if(owner.mesh().quads.contains(face))System.out.println("SAVED_OWNER "+owner.junction.id()+" channels="+owner.channels.stream().map(t->t.id).toList());
                        }break;}
                }
            }
            System.out.println("POINT_SAVED_GEOMETRY: flange samples="+count+" blocked="+blocked);
            if(blocked>0)throw new AssertionError("Saved layout still has blocked flange samples: "+blocked);
            int frogSamples=0,frogBlocked=0,joints=0,jointMissing=0;
            for(var view:PointClient.views)if(view.junction.kind()!=Junction.Kind.DIAMOND){
                var roads=view.junction.tracks();var p=view.profile.tune(view.settings);
                double channel=p.centerOffset()-p.headWidth()/2-Math.max(.02,view.settings.flangeway()+view.settings.wingGapDelta())/2;
                for(int a=0;a<roads.size();a++)for(int b=a+1;b<roads.size();b++){
                    var pair=new Junction(view.junction.id(),Junction.Kind.Y,roads.get(a),roads.get(b),view.junction.center(),0,0,view.junction.extent());
                    var frog=new FrogGeometry(pair,view.settings,p,PointMesh.extent(pair,view.settings));
                    double side=TurnoutFrame.side(pair,PointMesh.extent(pair,view.settings));
                    for(int branch=0;branch<2;branch++){
                        var road=branch==0?pair.a():pair.b();double top=p.top()+view.settings.verticalOffset();
                        for(int sign:new int[]{-1,1})for(double d=frog.toe(branch)+.1;d<frog.heel(branch)-.1;d+=.04){
                            V3 q=road.at(d).add(road.tangent(d).lateral().mul(sign*channel)).add(0,top,0);frogSamples++;
                            if(covered(bins,q)){if(frogBlocked++<8)System.out.println("SAVED_FROG_BLOCKED "+q+" node="+pair.center());}
                        }
                        var wing=frog.checkWing(branch);double knee=frog.knee(branch);
                        for(int k=0;k<=15;k++)for(boolean before:new boolean[]{true,false}){
                            double d=before?knee-k*.02:wing.start()+k*.02;
                            V3 q=before?road.at(d).add(road.tangent(d).lateral().mul((branch==0?side:-side)*p.centerOffset())):wing.center(d);
                            q=q.add(0,top,0);joints++;
                            V3 normal=(before?road:wing.road()).tangent(d).lateral();
                            // Curved native heads use chords between their sampled sections.
                            if(!covered(bins,q)&&!covered(bins,q.add(normal.mul(.008)))&&!covered(bins,q.sub(normal.mul(.008)))){
                                if(jointMissing++<8)System.out.println("SAVED_JOINT_MISSING "+q+" node="+pair.center()+" branch="+branch);
                            }
                        }
                    }
                }
            }
            System.out.println("POINT_SAVED_FROGS: samples="+frogSamples+" blocked="+frogBlocked+" joint samples="+joints+" missing="+jointMissing);
            if(frogBlocked>0||jointMissing>0)throw new AssertionError("Saved frog/wing connection failed");
            var runs=new ArrayList<GuardRails.Run>();var steel=new ArrayList<DiamondGeometry.Steel>();
            for(var view:PointClient.views){
                var tuned=view.profile.tune(view.settings);
                steel.add(new DiamondGeometry.Steel(view.mesh(),tuned.top()+view.settings.verticalOffset(),tuned,view.settings,view.crossingRoads()));
                for(var original:GuardRails.assembled(view.junction,view.settings,view.profile,view.scissors,null)){
                    var run=original;
                    if(run!=null)runs.add(run);
                }
            }
            int mouths=0,missing=0;double nearest=Double.MAX_VALUE;
            for(var run:DiamondGeometry.finishedGuards(runs,steel))for(boolean start:new boolean[]{true,false}){
                if(!(start?run.flareStart():run.flareEnd()))continue;
                double end=start?run.start():run.end();double d=end+(start?.04:-.04);
                V3 q=run.point(d).add(0,run.profile().top()+run.settings().verticalOffset(),0);mouths++;
                if(bins.getOrDefault(cell((int)Math.floor(q.x()),(int)Math.floor(q.z())),List.of()).stream()
                    .noneMatch(face->hit(q,face.a(),face.b(),face.c())||hit(q,face.a(),face.c(),face.d()))){missing++;System.out.println("SAVED_MOUTH_MISSING "+q);}
                double distance=run.point(end).distance(new V3(47.6,-60,-6.4));
                if(distance<nearest){
                    V3 target=run.point(end).add(0,run.profile().top()+run.settings().verticalOffset()-.08,0);
                    V3 away=run.point(end).sub(run.point(end+(start?.02:-.02))).unit();
                    V3 camera=target.add(away.mul(.55)).add(away.lateral().mul(.025)).add(0,.12,0);
                    boolean blockedView=false;
                    for(var face:world.quads)if(ray(camera,target,face.a(),face.b(),face.c())||ray(camera,target,face.a(),face.c(),face.d())){blockedView=true;break;}
                    if(!blockedView){nearest=distance;capTarget=target;capCamera=camera;}
                }
            }
            System.out.println("POINT_SAVED_GUARDS: mouth samples="+mouths+" missing="+missing);
            if(mouths==0||missing>0)throw new AssertionError("Saved guard mouths missing: "+missing+" / "+mouths);
            if(capTarget==null)throw new AssertionError("No unobstructed end section camera");
            long capFaces=world.quads.stream().filter(q->q.surface().equals(Profile.END_STEEL)).count();
            var perView=PointClient.views.stream().map(v->v.mesh().quads.stream().filter(q->q.surface().equals(Profile.END_STEEL)).count()).toList();
            var perPart=world.quads.stream().filter(q->q.surface().equals(Profile.END_STEEL)).collect(java.util.stream.Collectors.groupingBy(Mesh.Quad::part,java.util.stream.Collectors.counting()));
            System.out.println("POINT_SAVED_CAP_DIAGNOSTIC: calls="+Mesh.CAP_CALLS+" edgeCandidates="+Mesh.CAP_EDGE_CANDIDATES+" uniqueEdges="+Mesh.CAP_EDGE_UNIQUE+" outlinePoints="+Mesh.CAP_OUTLINE_POINTS+" loops="+Mesh.CAP_LOOPS+" emitted="+Mesh.CAP_FACES+" viewFaces="+perView+" worldParts="+perPart);
            if(capFaces==0||Minecraft.getInstance().getResourceManager().getResource(new net.minecraft.resources.ResourceLocation(Profile.END_STEEL.texture())).isEmpty())
                throw new AssertionError("Cut-steel end material missing from the rendered scene");
            System.out.println("POINT_SAVED_CAPS: cut-steel faces="+capFaces+" target="+capTarget);
        }catch(ReflectiveOperationException ex){throw new AssertionError(ex);}
    }
    private static long cell(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
    private static V3 cross(V3 a,V3 b){return new V3(a.y()*b.z()-a.z()*b.y(),a.z()*b.x()-a.x()*b.z(),a.x()*b.y()-a.y()*b.x());}
    private static boolean ray(V3 from,V3 to,V3 a,V3 b,V3 c){
        V3 direction=to.sub(from),e=b.sub(a),f=c.sub(a),h=cross(direction,f);double det=e.dot(h);
        if(Math.abs(det)<1e-12)return false;
        V3 offset=from.sub(a);double u=offset.dot(h)/det;if(u<0||u>1)return false;
        V3 q=cross(offset,e);double v=direction.dot(q)/det;if(v<0||u+v>1)return false;
        double t=f.dot(q)/det;return t>.001&&t<.98;
    }
    private static boolean covered(Map<Long,List<Mesh.Quad>> bins,V3 q){
        return bins.getOrDefault(cell((int)Math.floor(q.x()),(int)Math.floor(q.z())),List.of()).stream()
            .anyMatch(face->hit(q,face.a(),face.b(),face.c())||hit(q,face.a(),face.c(),face.d()));
    }
    private static boolean hit(V3 q,V3 a,V3 b,V3 c){
        double area=V3.crossXZ(b.sub(a),c.sub(a));if(Math.abs(area)<1e-10)return false;
        double u=V3.crossXZ(q.sub(a),c.sub(a))/area,v=V3.crossXZ(b.sub(a),q.sub(a))/area;
        return u>=-1e-7&&v>=-1e-7&&u+v<=1+1e-7&&Math.abs(q.y()-a.y()-(b.y()-a.y())*u-(c.y()-a.y())*v)<1e-5;
    }
    private static void render(TickEvent.RenderTickEvent event){
        if(!ready||event.phase!=TickEvent.Phase.END||++frames<30)return;frames=0;Minecraft mc=Minecraft.getInstance();
        try{
            if(phase==0){Screenshot.grab(mc.gameDirectory,"saved-layout-world.png",mc.getMainRenderTarget(),m->{});mc.options.hideGui=false;mc.setScreen(new PointSelectionScreen());}
            if(phase==1){Screenshot.grab(mc.gameDirectory,"saved-layout-plan.png",mc.getMainRenderTarget(),m->{});mc.setScreen(null);mc.options.hideGui=true;
                mc.getSingleplayerServer().execute(()->mc.getSingleplayerServer().getPlayerList().getPlayers().get(0).connection.teleport(50,-54,-9,0,65));}
            if(phase==2){Screenshot.grab(mc.gameDirectory,"saved-layout-detail.png",mc.getMainRenderTarget(),m->{});
                mc.getSingleplayerServer().execute(()->{
                    var player=mc.getSingleplayerServer().getPlayerList().getPlayers().get(0);player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
                    V3 direction=capTarget.sub(capCamera);
                    float yaw=(float)Math.toDegrees(Math.atan2(-direction.x(),direction.z()));
                    float pitch=(float)Math.toDegrees(Math.atan2(-direction.y(),Math.hypot(direction.x(),direction.z())));
                    player.connection.teleport(capCamera.x(),capCamera.y()-player.getEyeHeight(),capCamera.z(),yaw,pitch);
                });
            }
            if(phase==3){Screenshot.grab(mc.gameDirectory,"saved-layout-end-section.png",mc.getMainRenderTarget(),m->{});
                mc.getSingleplayerServer().execute(()->mc.getSingleplayerServer().getPlayerList().getPlayers().get(0).connection.teleport(50,-60,-9,0,25));
            }
            if(phase==4){Screenshot.grab(mc.gameDirectory,"saved-layout-side.png",mc.getMainRenderTarget(),m->{});System.out.println("POINT_SAVED_LAYOUT: PASS rendered source rail geometry and saved settings");mc.stop();}
            phase++;
        }catch(Exception ex){throw new AssertionError(ex);}
    }
}
