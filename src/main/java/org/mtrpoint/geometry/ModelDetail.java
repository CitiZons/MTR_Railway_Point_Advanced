package org.mtrpoint.geometry;

import java.util.*;
import java.util.function.Function;

/** Faces read from the active MTR model, with original per-vertex texture coordinates. */
public record ModelDetail(List<Mesh.Quad> rails,List<Mesh.Quad> bearers,List<Mesh.Quad> fittings,
                          double railCenter,double railTop,double headWidth,double zMin,double zMax,
                          double halfBearer,double bearerTop,boolean siding) {
    public ModelDetail {rails=List.copyOf(rails);bearers=List.copyOf(bearers);fittings=List.copyOf(fittings);}
    public void rail(Mesh mesh,V3 a,V3 b,double taperA,double taperB,Profile p,PointSettings s,String part){
        V3 n=b.sub(a).lateral();double width=p.headWidth()/headWidth;
        for(var face:rails)emit(mesh,face,v->{double t=(v.z()-zMin)/(zMax-zMin),taper=taperA+(taperB-taperA)*t;
            return a.lerp(b,t).add(n.mul((v.x()-railCenter)*width*taper)).add(0,v.y()-railTop+p.top()+s.verticalOffset(),0);},part,-1);
    }
    public void bearer(Mesh mesh,V3 c,V3 n,double lo,double hi,PointSettings s,Profile p,int index){
        V3 along=new V3(n.z(),0,-n.x());double top=p.top()-railTop+bearerTop+s.verticalOffset();
        for(var original:bearers){
            var face=original;
            if(original.uv()!=null&&Math.abs(original.a().y()-bearerTop)<.001&&Math.abs(original.c().y()-bearerTop)<.001){
                // The centre of the concrete atlas is clean: do not stretch baked rail-seat shadows.
                float min=Float.MAX_VALUE,max=-Float.MAX_VALUE;for(int i=0;i<8;i+=2){min=Math.min(min,original.uv().get(i));max=Math.max(max,original.uv().get(i));}
                var uv=new ArrayList<>(original.uv());for(int i=0;i<8;i+=2)uv.set(i,(min+max)/2+(uv.get(i)-(min+max)/2)*.18F);
                face=new Mesh.Quad(face.a(),face.b(),face.c(),face.d(),face.surface(),face.part(),face.index(),List.copyOf(uv));
            }
            emit(mesh,face,v->c.add(n.mul(lo+(v.x()+halfBearer)/(2*halfBearer)*(hi-lo)))
                .add(along.mul(v.z()*s.sleeperWidth()/.24)).add(0,top+(v.y()-bearerTop)*s.sleeperHeight()/.12,0),"sleeper",index);
        }
    }
    public void fitting(Mesh mesh,V3 center,V3 n,PointSettings s,Profile p,int index){
        V3 along=new V3(n.z(),0,-n.x());
        for(var face:fittings)emit(mesh,face,v->center.add(n.mul(v.x()-railCenter)).add(along.mul(v.z()))
                .add(0,v.y()-railTop+p.top()+s.verticalOffset(),0),siding?"sleeper":"fastener",index);
    }
    private static void emit(Mesh mesh,Mesh.Quad q,Function<V3,V3> transform,String part,int index){
        // Mapping model X to the left of the track reverses handedness.
        List<Float> uv=q.uv();List<Float> reversed=uv==null?null:List.of(uv.get(0),uv.get(1),uv.get(6),uv.get(7),uv.get(4),uv.get(5),uv.get(2),uv.get(3));
        mesh.quad(new Mesh.Quad(transform.apply(q.a()),transform.apply(q.d()),transform.apply(q.c()),transform.apply(q.b()),q.surface(),part,index,reversed));
    }
}
