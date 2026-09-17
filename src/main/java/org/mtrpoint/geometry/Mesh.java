package org.mtrpoint.geometry;

import java.util.*;

public final class Mesh {
    public record Quad(V3 a,V3 b,V3 c,V3 d,Profile.Surface surface,String part,int index,java.util.List<Float> uv) {
        public Quad(V3 a,V3 b,V3 c,V3 d,Profile.Surface surface,String part,int index){this(a,b,c,d,surface,part,index,null);}
        public V3 center(){return a.add(b).add(c).add(d).mul(.25);}
    }
    public final List<Quad> quads=new ArrayList<>();
    public void quad(V3 a,V3 b,V3 c,V3 d,Profile.Surface surface,String part,int index){quads.add(new Quad(a,b,c,d,surface,part,index));}
    public void quad(Quad q){quads.add(q);}
    public void beam(V3 a,V3 b,double w1,double w2,double bottom,double top,Profile.Surface surface,String part,int index) {
        V3 n=b.sub(a).lateral();
        V3 al=a.add(n.mul(-w1/2)).add(0,bottom,0),ar=a.add(n.mul(w1/2)).add(0,bottom,0),bl=b.add(n.mul(-w2/2)).add(0,bottom,0),br=b.add(n.mul(w2/2)).add(0,bottom,0);
        double h=top-bottom;V3 au=al.add(0,h,0),av=ar.add(0,h,0),bu=bl.add(0,h,0),bv=br.add(0,h,0);
        quad(av,bv,bu,au,surface,part,index);quad(bl,br,ar,al,surface,part,index);
        quad(au,bu,bl,al,surface,part,index);quad(br,bv,av,ar,surface,part,index);
        quad(ar,av,au,al,surface,part,index);quad(bu,bv,br,bl,surface,part,index);
    }
    public void rail(V3 a,V3 b,double taperA,double taperB,Profile p,PointSettings s,String part) {
        if(p.detail()!=null&&!p.detail().rails().isEmpty()){p.detail().rail(this,a,b,taperA,taperB,p,s,part);return;}
        double top=p.top()+s.verticalOffset(),base=top-p.railHeight();
        beam(a,b,p.footWidth()*taperA,p.footWidth()*taperB,base,base+.025,p.steel(),part,-1);
        beam(a,b,.022*taperA,.022*taperB,base+.025,top-.036,p.steel(),part,-1);
        beam(a,b,p.headWidth()*taperA,p.headWidth()*taperB,top-.036,top,p.steel(),part,-1);
    }
    private record Vertex(V3 p,float u,float v){Vertex lerp(Vertex b,double t){return new Vertex(p.lerp(b.p,t),(float)(u+(b.u-u)*t),(float)(v+(b.v-v)*t));}}
    public static void clip(Mesh out,Mesh.Quad q,V3 origin,V3 normal){
        var points=List.of(q.a(),q.b(),q.c(),q.d());var source=new ArrayList<Vertex>();
        for(int i=0;i<4;i++)source.add(new Vertex(points.get(i),q.uv()==null?0:q.uv().get(i*2),q.uv()==null?0:q.uv().get(i*2+1)));
        var poly=new ArrayList<Vertex>();
        for(int i=0;i<4;i++){Vertex a=source.get(i),b=source.get((i+1)%4);double da=a.p.sub(origin).dot(normal),db=b.p.sub(origin).dot(normal);if(da<=0)poly.add(a);if((da<0&&db>0)||(da>0&&db<0))poly.add(a.lerp(b,da/(da-db)));}
        for(int i=poly.size()-1;i>=0&&poly.size()>1;i--)if(poly.get(i).p.distance(poly.get((i+1)%poly.size()).p)<1e-10)poly.remove(i);
        if(poly.size()<3)return;
        if(poly.size()==4)emit(out,q,poly.get(0),poly.get(1),poly.get(2),poly.get(3));
        else for(int i=1;i+1<poly.size();i++)emit(out,q,poly.get(0),poly.get(i),poly.get(i+1),poly.get(i+1));
    }
    private static void emit(Mesh out,Mesh.Quad q,Vertex a,Vertex b,Vertex c,Vertex d){out.quad(new Mesh.Quad(a.p,b.p,c.p,d.p,q.surface(),q.part(),q.index(),q.uv()==null?null:List.of(a.u,a.v,b.u,b.v,c.u,c.v,d.u,d.v)));}
    /** Fixed face slots for animated inserts clipped to adjacent crossing cells. */
    public static Mesh clipAnimated(Mesh source,V3 origin,V3 normal){
        Mesh out=new Mesh();
        for(var q:source.quads)for(int[] corners:new int[][]{{0,1,2},{0,2,3}}){
            var vertices=List.of(q.a(),q.b(),q.c(),q.d());var uv=new ArrayList<Float>();
            for(int k:new int[]{corners[0],corners[1],corners[2],corners[2]})if(q.uv()!=null){uv.add(q.uv().get(k*2));uv.add(q.uv().get(k*2+1));}
            Quad triangle=new Quad(vertices.get(corners[0]),vertices.get(corners[1]),vertices.get(corners[2]),vertices.get(corners[2]),q.surface(),q.part(),q.index(),q.uv()==null?null:uv);
            Mesh clipped=new Mesh();clip(clipped,triangle,origin,normal);
            if(clipped.quads.isEmpty()){
                V3 v=triangle.a();double distance=v.sub(origin).dot(normal);if(distance>0)v=v.sub(normal.mul(distance/normal.dot(normal)));
                out.quad(new Quad(v,v,v,v,q.surface(),q.part(),q.index(),triangle.uv()));
            }else out.quad(clipped.quads.get(0));
        }
        return out;
    }
}
