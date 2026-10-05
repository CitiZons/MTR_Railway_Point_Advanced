package org.mtrpoint.geometry;

import java.util.*;
import java.util.function.Function;

/** Faces read from the active MTR model, with original per-vertex texture coordinates. */
public record ModelDetail(List<Mesh.Quad> rails,List<Mesh.Quad> bearers,List<Mesh.Quad> fittings,
                          double railCenter,double railTop,double headWidth,double zMin,double zMax,
                          double halfBearer,double bearerTop,boolean siding,boolean nativeAtlas,Profile.Surface endSteel,
                          List<List<Mesh.Quad>> fittingLods) {
    public ModelDetail(List<Mesh.Quad> rails,List<Mesh.Quad> bearers,List<Mesh.Quad> fittings,double railCenter,double railTop,double headWidth,double zMin,double zMax,double halfBearer,double bearerTop,boolean siding,boolean nativeAtlas,Profile.Surface endSteel){
        this(rails,bearers,fittings,railCenter,railTop,headWidth,zMin,zMax,halfBearer,bearerTop,siding,nativeAtlas,endSteel,List.of());
    }
    public ModelDetail(List<Mesh.Quad> rails,List<Mesh.Quad> bearers,List<Mesh.Quad> fittings,double railCenter,double railTop,double headWidth,double zMin,double zMax,double halfBearer,double bearerTop,boolean siding){
        this(rails,bearers,fittings,railCenter,railTop,headWidth,zMin,zMax,halfBearer,bearerTop,siding,true,Profile.END_STEEL);
    }
    public ModelDetail {if(endSteel==null){endSteel=Profile.END_STEEL;nativeAtlas=true;}rails=List.copyOf(rails);bearers=List.copyOf(bearers);fittings=List.copyOf(fittings);fittingLods=fittingLods==null?List.of():fittingLods.stream().map(List::copyOf).toList();}
    public ModelDetail withFittingLods(List<List<Mesh.Quad>> levels){return new ModelDetail(rails,bearers,fittings,railCenter,railTop,headWidth,zMin,zMax,halfBearer,bearerTop,siding,nativeAtlas,endSteel,levels);}
    public void rail(Mesh mesh,V3 a,V3 b,double taperA,double taperB,Profile p,PointSettings s,String part){
        V3 n=b.sub(a).lateral();double width=p.headWidth()/headWidth;
        for(var face:rails)emit(mesh,face,v->{double t=(v.z()-zMin)/(zMax-zMin),taper=taperA+(taperB-taperA)*t;
            return a.lerp(b,t).add(n.mul((v.x()-railCenter)*width*taper)).add(0,v.y()-railTop+p.top()+s.verticalOffset(),0);},part,-1);
    }
    public void bearer(Mesh mesh,V3 c,V3 n,double lo,double hi,PointSettings s,Profile p,int index){
        bearer(mesh,c,n,lo,hi,s,p,index,false);
    }
    /** A long turnout bearer keeps the source sleeper's bevel and underside, but its top samples
     *  one clean atlas point instead of stretching the ordinary two-rail seat shadow. */
    public void bearer(Mesh mesh,V3 c,V3 n,double lo,double hi,PointSettings s,Profile p,int index,boolean turnout){
        // Longitudinal supports are swept by the cell renderer, not repeated as ties.
        if(!bearers.isEmpty()&&bearers.stream().allMatch(q->q.part().equals("track_bed")))return;
        if(seatedBlocks()){
            // Turnout blocks belong to the final fitting seats, not a stretched two-rail tie.
            if(!turnout)for(int sign:new int[]{-1,1})seatBearer(mesh,c.add(n.mul(sign*p.centerOffset())),n,0,0,s,p,index);
            return;
        }
        boolean flatten=turnout&&bearers.stream().noneMatch(q->q.part().equals("shaped_sleeper"));
        V3 along=new V3(n.z(),0,-n.x());double top=p.top()-railTop+bearerTop+s.verticalOffset();
        double bottom=bearers.stream().flatMap(q->java.util.stream.Stream.of(q.a(),q.b(),q.c(),q.d())).mapToDouble(V3::y).min().orElse(bearerTop);
        var ringTop=new HashMap<Long,Double>();if(flatten)for(var q:bearers)for(V3 v:List.of(q.a(),q.b(),q.c(),q.d()))ringTop.merge(Math.round(v.x()*10000),v.y(),Math::max);
        for(var original:bearers){
            var face=original;
            if(nativeAtlas&&original.uv()!=null&&Math.abs(original.a().y()-bearerTop)<.001&&Math.abs(original.c().y()-bearerTop)<.001){
                // The centre of the concrete atlas is clean: do not stretch baked rail-seat shadows.
                float min=Float.MAX_VALUE,max=-Float.MAX_VALUE;for(int i=0;i<8;i+=2){min=Math.min(min,original.uv().get(i));max=Math.max(max,original.uv().get(i));}
                var uv=new ArrayList<>(original.uv());for(int i=0;i<8;i+=2)uv.set(i,(min+max)/2+(uv.get(i)-(min+max)/2)*.18F);
                face=new Mesh.Quad(face.a(),face.b(),face.c(),face.d(),face.surface(),face.part(),face.index(),List.copyOf(uv));
            }
            emit(mesh,face,v->{double y=v.y();if(flatten){double ring=ringTop.getOrDefault(Math.round(v.x()*10000),bearerTop),span=ring-bottom;
                    if(span>1e-7){double weight=Math.max(0,Math.min(1,(y-bottom)/span));y+=(bearerTop-ring)*weight;}}
                return c.add(n.mul(lo+(v.x()+halfBearer)/(2*halfBearer)*(hi-lo))).add(along.mul(v.z()*s.sleeperWidth()/.24)).add(0,top+(y-bearerTop)*s.sleeperHeight()/.12,0);},"sleeper",index);
        }
    }
    public boolean seatedBlocks(){return bearers.stream().anyMatch(q->q.part().equals("shaped_sleeper"));}
    /** A recessed block follows its fitting's centre and frame. Shared plates widen the recess
     *  between seats while the two outer shoulders retain their original shape and dimensions. */
    public void seatBearer(Mesh mesh,V3 center,V3 n,double lo,double hi,PointSettings s,Profile p,int index){
        if(!seatedBlocks())return;
        var faces=bearers.stream().filter(q->q.center().x()>0).toList();
        double fittingBase=fittings.stream().flatMap(q->java.util.stream.Stream.of(q.a(),q.b(),q.c(),q.d())).mapToDouble(V3::y).min().orElseThrow();
        double recess=faces.stream().flatMap(q->java.util.stream.Stream.of(q.a(),q.b(),q.c(),q.d()))
            .filter(v->Math.abs(v.y()-fittingBase)<1e-6).mapToDouble(v->Math.abs(v.x()-railCenter)).max().orElse(.205);
        // The model's raised shoulders begin just outside its complete fastening footprint.
        V3 along=new V3(n.z(),0,-n.x());
        for(var face:faces)emit(mesh,face,v->{
            double x=v.x()-railCenter,t=Math.max(0,Math.min(1,(x+recess)/(2*recess)));
            return center.add(n.mul(x+lo+(hi-lo)*t)).add(along.mul(v.z()))
                .add(0,v.y()-railTop+p.top()+s.verticalOffset(),0);
        },"sleeper",index);
    }
    public void fitting(Mesh mesh,V3 center,V3 n,PointSettings s,Profile p,int index){
        V3 along=new V3(n.z(),0,-n.x());
        Function<V3,V3> transform=v->center.add(n.mul(v.x()-railCenter)).add(along.mul(v.z())).add(0,v.y()-railTop+p.top()+s.verticalOffset(),0);
        for(var face:fittings)emit(mesh,face,transform,siding?"sleeper":fittingLods.isEmpty()?"fastener":"fastener_near",index);
        for(int level=0;level<fittingLods.size();level++)for(var face:fittingLods.get(level))emit(mesh,face,transform,level==0?"fastener_mid":"fastener_far",index);
    }
    private static void emit(Mesh mesh,Mesh.Quad q,Function<V3,V3> transform,String part,int index){
        // Mapping model X to the left of the track reverses handedness.
        List<Float> uv=q.uv();List<Float> reversed=uv==null?null:List.of(uv.get(0),uv.get(1),uv.get(6),uv.get(7),uv.get(4),uv.get(5),uv.get(2),uv.get(3));
        mesh.quad(new Mesh.Quad(transform.apply(q.a()),transform.apply(q.d()),transform.apply(q.c()),transform.apply(q.b()),q.surface(),part,index,reversed));
    }
}
