package org.mtrpoint.client;

import org.mtrpoint.geometry.*;
import net.minecraft.client.gui.GuiGraphics;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.GameRenderer;
import java.util.*;
import java.util.function.Function;

/** One GPU batch for the entire wire preview; no per-pixel GUI fills. */
public final class BlueprintWire {
    private record Edge(V3 a,V3 b,String part,int index) {}
    private Mesh lastMesh;private double lastPosition=-100;private List<Edge> edges=List.of();
    public long builds;public int edgeCount(){return edges.size();}
    private void build(Mesh mesh){
        var unique=new LinkedHashSet<Edge>();
        for(var q:mesh.quads){V3[] p={q.a(),q.b(),q.c(),q.d()};for(int i=0;i<4;i++){V3 a=p[i],b=p[(i+1)%4];if(a.equals(b))continue;
            if(compare(a,b)>0){V3 swap=a;a=b;b=swap;}unique.add(new Edge(a,b,q.part(),q.index()));}}
        edges=List.copyOf(unique);builds++;
    }
    private static int compare(V3 a,V3 b){int x=Double.compare(a.x(),b.x());if(x!=0)return x;int y=Double.compare(a.y(),b.y());return y!=0?y:Double.compare(a.z(),b.z());}
    public void draw(GuiGraphics g,Mesh mesh,double position,Function<V3,double[]> project,int selected,int right,int bottom){
        if(lastMesh!=mesh||lastPosition!=position){lastMesh=mesh;lastPosition=position;build(mesh);}
        g.flush();RenderSystem.setShader(GameRenderer::getPositionColorShader);RenderSystem.enableBlend();RenderSystem.disableCull();
        var buffer=Tesselator.getInstance().getBuilder();buffer.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_COLOR);var pose=g.pose().last().pose();
        for(var edge:edges){double[] projectedA=project.apply(edge.a),projectedB=project.apply(edge.b),clipped=clip(projectedA[0],projectedA[1],projectedB[0],projectedB[1],8,65,right-6,bottom);if(clipped==null)continue;double[] a={clipped[0],clipped[1]},b={clipped[2],clipped[3]};double dx=b[0]-a[0],dy=b[1]-a[1],length=Math.hypot(dx,dy);if(length<.25)continue;
            int color=switch(edge.part){case "sleeper"->edge.index==selected?0xffffc467:0xff649499;case "blade"->0xffffc467;case "guard"->0xff89d8e5;default->0xffc9e4e9;};
            float nx=(float)(-dy/length*.4),ny=(float)(dx/length*.4);
            buffer.vertex(pose,(float)a[0]+nx,(float)a[1]+ny,0).color(color).endVertex();buffer.vertex(pose,(float)a[0]-nx,(float)a[1]-ny,0).color(color).endVertex();
            buffer.vertex(pose,(float)b[0]-nx,(float)b[1]-ny,0).color(color).endVertex();buffer.vertex(pose,(float)b[0]+nx,(float)b[1]+ny,0).color(color).endVertex();
        }
        BufferUploader.drawWithShader(buffer.end());RenderSystem.enableCull();
    }
    /** CPU clip as well as GUI scissoring. GuiGraphics scissor coordinates are framebuffer based
     * on some Forge paths and can disagree with the scaled virtual UI, while clipped endpoints
     * remain correct for every UI scale. */
    private static double[] clip(double ax,double ay,double bx,double by,double left,double top,double right,double bottom){
        double dx=bx-ax,dy=by-ay,t0=0,t1=1;double[] p={-dx,dx,-dy,dy},q={ax-left,right-ax,ay-top,bottom-ay};
        for(int i=0;i<4;i++){if(Math.abs(p[i])<1e-12){if(q[i]<0)return null;continue;}double r=q[i]/p[i];if(p[i]<0){if(r>t1)return null;t0=Math.max(t0,r);}else{if(r<t0)return null;t1=Math.min(t1,r);}}
        return new double[]{ax+dx*t0,ay+dy*t0,ax+dx*t1,ay+dy*t1};
    }
}
