package org.mtrpoint.client;

import com.mojang.blaze3d.vertex.*;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.mtrpoint.geometry.*;
import java.util.*;

/** Persistent GPU geometry: stationary turnouts submit draw calls, not every vertex. */
final class PointGpu implements AutoCloseable {
    private record Batch(VertexBuffer buffer,RenderType type,int lod,V3 center,int faces){}
    private record Group(String texture,int lod,int x,int y,int z){}
    // BufferBuilder owns native memory; reuse one staging buffer instead of allocating per throw.
    private static final BufferBuilder STAGING=new BufferBuilder(262144);
    private final List<Batch> fixed=new ArrayList<>(),moving=new ArrayList<>();
    private final List<Integer> staticIds=new ArrayList<>(),movingIds=new ArrayList<>();
    private Mesh source;private double frame;private long lightTick=-100;private V3 origin;private AABB bounds;
    private final Map<Long,Integer> bakedLight=new HashMap<>();
    static long uploads,draws,vertices;
    static long submittedFaces;
    static final long[] lodDraws=new long[3];
    private static final org.joml.Vector3f WORLD_LIGHT0=new org.joml.Vector3f(.2F,1,-.7F).normalize();
    private static final org.joml.Vector3f WORLD_LIGHT1=new org.joml.Vector3f(-.2F,1,.7F).normalize();
    private static final org.joml.Vector3f PREVIOUS_LIGHT0=new org.joml.Vector3f();
    private static final org.joml.Vector3f PREVIOUS_LIGHT1=new org.joml.Vector3f();
    /** Parts the per-view draw hides because the shared component assembly owns them instead. */
    static boolean hiddenInView(Mesh.Quad q){
        String part=q.part();return part.equals("guard")||part.equals("sleeper")||part.equals("track_bed")||part.startsWith("fastener")||part.equals("wing");
    }
    void update(Mesh mesh,double position,boolean movable,boolean removeGuards){
        var mc=Minecraft.getInstance();long tick=mc.level.getGameTime();boolean changed=source!=mesh;
        if(changed){
            source=mesh;bakedLight.clear();staticIds.clear();movingIds.clear();origin=mesh.quads.isEmpty()?new V3(0,0,0):mesh.quads.get(0).a();
            double x0=origin.x(),x1=x0,y0=origin.y(),y1=y0,z0=origin.z(),z1=z0;
            for(int i=0;i<mesh.quads.size();i++){
                var q=mesh.quads.get(i);if(removeGuards&&hiddenInView(q))continue;
                boolean motion=q.part().equals("blade")||q.part().equals("stretcher")||movable&&q.part().equals("frog");
                (motion?movingIds:staticIds).add(i);
                for(V3 v:List.of(q.a(),q.b(),q.c(),q.d())){x0=Math.min(x0,v.x());x1=Math.max(x1,v.x());y0=Math.min(y0,v.y());y1=Math.max(y1,v.y());z0=Math.min(z0,v.z());z1=Math.max(z1,v.z());}
            }
            bounds=new AABB(x0-.5,y0-.5,z0-.5,x1+.5,y1+.5,z1+.5);
        }
        // Block light updates are infrequent; sky brightness itself is applied by the lightmap.
        boolean light=false;
        if(tick-lightTick>=20){for(var entry:bakedLight.entrySet()){int value=LevelRenderer.getLightColor(mc.level,BlockPos.of(entry.getKey()));if(value!=entry.getValue()){entry.setValue(value);light=true;}}lightTick=tick;}
        if(changed||light)upload(fixed,staticIds);
        if(changed||light||frame!=position)upload(moving,movingIds);
        if(changed)lightTick=tick-(Math.abs(System.identityHashCode(this))%20);frame=position;
    }
    private void upload(List<Batch> target,List<Integer> ids){
        target.forEach(b->b.buffer.close());target.clear();if(ids.isEmpty())return;
        var groups=new LinkedHashMap<Group,List<Integer>>();for(int i:ids){var q=source.quads.get(i);int lod=switch(q.part()){case "fastener_near"->0;case "fastener_mid"->1;case "fastener_far"->2;default->-1;};V3 c=q.center();
            var group=new Group(q.surface().texture(),lod,lod<0?0:(int)Math.floor(c.x()/4),lod<0?0:(int)Math.floor(c.y()/4),lod<0?0:(int)Math.floor(c.z()/4));groups.computeIfAbsent(group,k->new ArrayList<>()).add(i);}
        var lights=bakedLight;var mc=Minecraft.getInstance();
        for(var entry:groups.entrySet()){
            BufferBuilder b=STAGING;b.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.NEW_ENTITY);
            for(int id:entry.getValue()){
                var q=source.quads.get(id);var s=q.surface();
                V3 ab=q.b().sub(q.a()),ac=q.c().sub(q.a());
                V3 n=new V3(ab.y()*ac.z()-ab.z()*ac.y(),ab.z()*ac.x()-ab.x()*ac.z(),ab.x()*ac.y()-ab.y()*ac.x()).unit();
                V3 center=q.center();long key=BlockPos.containing(center.x(),center.y()+.3,center.z()).asLong();
                int light=lights.computeIfAbsent(key,k->LevelRenderer.getLightColor(mc.level,BlockPos.of(k)));
                for(int i=0;i<4;i++){
                    V3 v=switch(i){case 0->q.a();case 1->q.b();case 2->q.c();default->q.d();};
                    float u=q.uv()==null?(i==0||i==3?s.u0():s.u1()):q.uv().get(i*2),vv=q.uv()==null?(i<2?s.v0():s.v1()):q.uv().get(i*2+1);
                    // The resource pack intentionally places the bed top on the block
                    // plane. Lift only support faces by a tiny epsilon to win the depth
                    // test against the coplanar world surface and prevent z-fighting.
                    double depthBias=q.part().equals("track_bed")||q.part().equals("sleeper")||q.part().equals("direct_bearer")?0.00015:0;
                    b.vertex(v.x()-origin.x(),v.y()-origin.y()+depthBias,v.z()-origin.z()).color(s.color()).uv(u,vv).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal((float)n.x(),(float)n.y(),(float)n.z()).endVertex();vertices++;
                }
            }
            VertexBuffer gpu=new VertexBuffer(VertexBuffer.Usage.STATIC);gpu.bind();gpu.upload(b.end());VertexBuffer.unbind();
            Group group=entry.getKey();target.add(new Batch(gpu,PointRenderType.texture(new ResourceLocation(group.texture)),group.lod,new V3(group.x*4+2,group.y*4+2,group.z*4+2),entry.getValue().size()));uploads++;
        }
    }
    boolean visible(RenderLevelStageEvent e){return bounds==null||e.getFrustum().isVisible(bounds);}
    boolean hasSource(Mesh mesh){return source==mesh;}
    void draw(RenderLevelStageEvent e){
        if(bounds==null||!e.getFrustum().isVisible(bounds))return;
        var camera=e.getCamera().getPosition();Matrix4f pose=new Matrix4f(e.getPoseStack().last().pose()).translate((float)(origin.x()-camera.x),(float)(origin.y()-camera.y),(float)(origin.z()-camera.z));
        var distances=RailLod.distances();
        draw(fixed,pose,e.getProjectionMatrix(),camera,distances);draw(moving,pose,e.getProjectionMatrix(),camera,distances);
    }
    private static void draw(List<Batch> batches,Matrix4f pose,Matrix4f projection,net.minecraft.world.phys.Vec3 camera,RailDetailDistances distances){
        if(batches.isEmpty())return;
        var ordered=new ArrayList<>(batches);
        ordered.sort(Comparator.comparingInt(b->System.identityHashCode(b.type)));
        RenderType activeType=null;org.joml.Vector3f previous0=null,previous1=null;ShaderInstance shader=null;
        try{
            for(var b:ordered){
                if(b.lod>=0){double x=b.center.x()-camera.x,y=b.center.y()-camera.y,z=b.center.z()-camera.z;if(distances.level(x*x+y*y+z*z)!=b.lod)continue;}
                if(activeType!=b.type){
                    if(activeType!=null){if(previous0!=null)RenderSystem.setShaderLights(previous0,previous1);activeType.clearRenderState();}
                    activeType=b.type;activeType.setupRenderState();shader=RenderSystem.getShader();RenderSystem.setupShaderLights(shader);
                    var light0=shader.getUniform("Light0_Direction");var light1=shader.getUniform("Light1_Direction");previous0=null;previous1=null;
                    if(light0!=null&&light1!=null){previous0=PREVIOUS_LIGHT0;previous1=PREVIOUS_LIGHT1;previous0.set(light0.getFloatBuffer());previous1.set(light1.getFloatBuffer());RenderSystem.setShaderLights(WORLD_LIGHT0,WORLD_LIGHT1);}
                }
                b.buffer.bind();b.buffer.drawWithShader(pose,projection,shader);VertexBuffer.unbind();
                draws++;submittedFaces+=b.faces;if(b.lod>=0)lodDraws[b.lod]++;
            }
        }finally{
            VertexBuffer.unbind();if(activeType!=null){if(previous0!=null)RenderSystem.setShaderLights(previous0,previous1);activeType.clearRenderState();}
        }
    }
    /** Compatibility entry point retained for the isolated runtime probe. */
    static void draw(List<Batch> batches,Matrix4f pose,Matrix4f projection){
        var camera=Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        draw(batches,pose,projection,camera,RailLod.distances());
    }
    public void close(){fixed.forEach(b->b.buffer.close());moving.forEach(b->b.buffer.close());fixed.clear();moving.clear();source=null;bounds=null;}
}
