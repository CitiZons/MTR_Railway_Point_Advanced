package org.mtrpoint.geometry;

import java.util.*;

public final class Mesh {
    public record Quad(V3 a,V3 b,V3 c,V3 d,Profile.Surface surface,String part,int index,java.util.List<Float> uv) {
        public Quad(V3 a,V3 b,V3 c,V3 d,Profile.Surface surface,String part,int index){this(a,b,c,d,surface,part,index,null);}
        public V3 center(){return a.add(b).add(c).add(d).mul(.25);}
    }
    public final List<Quad> quads=new ArrayList<>();
    /** Texture coordinates a face falls back to: the middle of its surface's atlas square. A face
     *  without uv renders untextured, which is exactly what made end faces read as open holes, so
     *  every emission path is funnelled through this instead of passing null. */
    public static List<Float> centerUv(Profile.Surface s){
        float u=(s.u0()+s.u1())/2,v=(s.v0()+s.v1())/2;return List.of(u,v,u,v,u,v,u,v);
    }
    public void quad(V3 a,V3 b,V3 c,V3 d,Profile.Surface surface,String part,int index){quads.add(new Quad(a,b,c,d,surface,part,index,centerUv(surface)));}
    public void quad(Quad q){quads.add(q.uv()!=null?q:new Quad(q.a(),q.b(),q.c(),q.d(),q.surface(),q.part(),q.index(),centerUv(q.surface())));}
    public void beam(V3 a,V3 b,double w1,double w2,double bottom,double top,Profile.Surface surface,String part,int index) {
        V3 n=b.sub(a).lateral();
        V3 al=a.add(n.mul(-w1/2)).add(0,bottom,0),ar=a.add(n.mul(w1/2)).add(0,bottom,0),bl=b.add(n.mul(-w2/2)).add(0,bottom,0),br=b.add(n.mul(w2/2)).add(0,bottom,0);
        double h=top-bottom;V3 au=al.add(0,h,0),av=ar.add(0,h,0),bu=bl.add(0,h,0),bv=br.add(0,h,0);
        // The two section faces of a beam carry a constant texture coordinate of their surface, the
        // same way cap(...) does it: without uv an end face renders untextured and reads as a hole.
        float su=(surface.u0()+surface.u1())/2,sv=(surface.v0()+surface.v1())/2;
        List<Float> euv=List.of(su,sv,su,sv,su,sv,su,sv);
        quad(av,bv,bu,au,surface,part,index);quad(bl,br,ar,al,surface,part,index);
        quad(au,bu,bl,al,surface,part,index);quad(br,bv,av,ar,surface,part,index);
        quad(new Quad(ar,av,au,al,surface,part,index,euv));quad(new Quad(bu,bv,br,bl,surface,part,index,euv));
    }
    public void rail(V3 a,V3 b,double taperA,double taperB,Profile p,PointSettings s,String part) {
        if(p.detail()!=null&&!p.detail().rails().isEmpty()){p.detail().rail(this,a,b,taperA,taperB,p,s,part);return;}
        double top=p.top()+s.verticalOffset(),base=top-p.railHeight();
        beam(a,b,p.footWidth()*taperA,p.footWidth()*taperB,base,base+.025,p.steel(),part,-1);
        beam(a,b,.022*taperA,.022*taperB,base+.025,top-.036,p.steel(),part,-1);
        beam(a,b,p.headWidth()*taperA,p.headWidth()*taperB,top-.036,top,p.steel(),part,-1);
    }
    /** Close an exposed end of a rail drawn from the native model. The cap is the model's own zMin
     *  cross-section, triangulated from its boundary, so an I-beam end face keeps its concave
     *  notches empty instead of filling them, and it is drawn with the mod's steel material that
     *  every renderer path resolves. Repeating rail models carry no end faces of their own, which
     *  is why every visible cut end looked open before. */
    public void railCap(V3 center,V3 tangent,double taper,Profile p,PointSettings s,String part,boolean start){
        ModelDetail detail=p.detail();if(detail==null||detail.rails().isEmpty())return;
        var loops=endOutline(detail);if(loops.isEmpty())return;
        V3 normal=normalFor(tangent);double width=p.headWidth()/detail.headWidth();
        for(List<V3> loop:loops){
            var shape=new ArrayList<V3>(loop.size());
            for(V3 v:loop)shape.add(new V3((v.x()-detail.railCenter())*width*taper,v.y()-detail.railTop()+p.top()+s.verticalOffset(),0));
            cap(center,normal,shape,p.steel(),part,start);
        }
    }
    /** Close a cut in a rail: the native section when the profile carries a model, otherwise the
     *  built-in three-part I-beam section, whose web notch stays empty as well. */
    public void railCutCap(V3 center,V3 tangent,Profile p,PointSettings s,boolean start){
        railCutCap(center,tangent,p,s,"rail",start);
    }
    /** Same, but the end face keeps the part of the rail it closes: a cut guard or wing must not
     *  turn into a piece of plain running rail in the merged mesh, where a plain "rail" face is
     *  neither hidden nor cut and therefore survives as a leftover shard. */
    public void railCutCap(V3 center,V3 tangent,Profile p,PointSettings s,String part,boolean start){
        if(p.detail()!=null&&!p.detail().rails().isEmpty()){railCap(center,tangent,1,p,s,part,start);return;}
        double top=p.top()+s.verticalOffset(),base=top-p.railHeight();
        double foot=p.footWidth()/2,web=.011,head=p.headWidth()/2,footTop=base+.025,headBottom=top-.036;
        cap(center,normalFor(tangent),List.of(
            new V3(-foot,base,0),new V3(foot,base,0),new V3(foot,footTop,0),
            new V3(web,footTop,0),new V3(web,headBottom,0),new V3(head,headBottom,0),
            new V3(head,top,0),new V3(-head,top,0),new V3(-head,headBottom,0),
            new V3(-web,headBottom,0),new V3(-web,footTop,0),new V3(-foot,footTop,0)
        ),p.steel(),part,start);
    }
    /** Emit a planar end face from its boundary shape, given in the cap frame (x across the rail
     *  along the lateral, y vertical). The shape is wound counter-clockwise about that frame, which
     *  faces backwards along the tangent, so a start cap is emitted as wound and an end cap is
     *  reversed; material and part stay the caller's. */
    private void cap(V3 center,V3 normal,List<V3> raw,Profile.Surface surface,String part,boolean start){
        var shape=wound(raw);if(shape.size()<3)return;
        float u=(surface.u0()+surface.u1())/2,v=(surface.v0()+surface.v1())/2;List<Float> uv=List.of(u,v,u,v,u,v,u,v);
        for(int[] triangle:triangulate(shape)){
            V3 a=at(center,normal,shape.get(triangle[0])),b=at(center,normal,shape.get(triangle[1])),c=at(center,normal,shape.get(triangle[2]));
            if(start)quad(new Quad(a,b,c,c,surface,part,-1,uv));else quad(new Quad(c,b,a,a,surface,part,-1,uv));
        }
    }
    private static V3 at(V3 center,V3 normal,V3 shape){return center.add(normal.mul(shape.x())).add(0,shape.y(),0);}
    /** Forward direction of an end cap: the lateral of the horizontal tangent. */
    private static V3 normalFor(V3 tangent){V3 along=new V3(tangent.x(),0,tangent.z()).unit();return along.lateral();}
    /** Drop repeated points and force counter-clockwise winding, so concave shapes triangulate the
     *  same way whichever direction the source model wound its faces. */
    private static List<V3> wound(List<V3> raw){
        var shape=new ArrayList<V3>(raw.size());
        for(V3 v:raw)if(shape.isEmpty()||shape.get(shape.size()-1).distance(v)>1e-9)shape.add(v);
        while(shape.size()>1&&shape.get(0).distance(shape.get(shape.size()-1))<1e-9)shape.remove(shape.size()-1);
        if(shape.size()<3)return List.of();
        double area=0;for(int i=0;i<shape.size();i++){V3 a=shape.get(i),b=shape.get((i+1)%shape.size());area+=a.x()*b.y()-b.x()*a.y();}
        if(area>=0)return shape;
        var reversed=new ArrayList<>(shape);Collections.reverse(reversed);return reversed;
    }
    /** Ear clipping over the boundary, which keeps every triangle a sub-polygon of the shape, so the
     *  concave notches of an I-beam section are never covered. */
    private static List<int[]> triangulate(List<V3> shape){
        var remaining=new ArrayList<Integer>(shape.size());for(int i=0;i<shape.size();i++)remaining.add(i);
        var result=new ArrayList<int[]>();
        for(int guard=shape.size()*shape.size()+8;remaining.size()>3&&guard>0;guard--){
            boolean clipped=false;
            for(int i=0;i<remaining.size();i++){
                int previous=remaining.get((i+remaining.size()-1)%remaining.size()),current=remaining.get(i),next=remaining.get((i+1)%remaining.size());
                V3 a=shape.get(previous),b=shape.get(current),c=shape.get(next);
                if(turn(a,b,c)<=1e-12)continue;
                boolean empty=true;
                for(int other:remaining){if(other==previous||other==current||other==next)continue;if(inside(shape.get(other),a,b,c)){empty=false;break;}}
                if(!empty)continue;
                result.add(new int[]{previous,current,next});remaining.remove(i);clipped=true;break;
            }
            if(!clipped)break;
        }
        if(remaining.size()==3)result.add(new int[]{remaining.get(0),remaining.get(1),remaining.get(2)});
        else for(int i=1;i+1<remaining.size();i++)result.add(new int[]{remaining.get(0),remaining.get(i),remaining.get(i+1)});
        return result;
    }
    private static double turn(V3 a,V3 b,V3 c){return (b.x()-a.x())*(c.y()-a.y())-(b.y()-a.y())*(c.x()-a.x());}
    private static boolean inside(V3 p,V3 a,V3 b,V3 c){return turn(a,b,p)>=-1e-12&&turn(b,c,p)>=-1e-12&&turn(c,a,p)>=-1e-12;}
    /** Boundary of a custom rail model's end section, read from the edges that lie on zMin. Each
     *  edge is counted once and followed into the sharpest remaining turn, so touching faces of the
     *  section merge into the outline of their union and separate pieces stay separate. */
    private static List<List<V3>> endOutline(ModelDetail detail){
        double edge=detail.zMin(),epsilon=Math.max(1e-6,(detail.zMax()-detail.zMin())*1e-4);
        var points=new ArrayList<V3>();var ids=new LinkedHashMap<String,Integer>();var edges=new LinkedHashSet<Long>();
        for(var q:detail.rails()){var corners=List.of(q.a(),q.b(),q.c(),q.d());
            for(int i=0;i<4;i++){
                V3 a=corners.get(i),b=corners.get((i+1)%4);
                if(Math.abs(a.z()-edge)>epsilon||Math.abs(b.z()-edge)>epsilon)continue;
                int first=key(points,ids,a),second=key(points,ids,b);
                if(first!=second)edges.add(join(first,second));
            }
        }
        if(edges.isEmpty())return List.of();
        var incident=new LinkedHashMap<Integer,List<Integer>>();
        for(long id:edges){int a=(int)(id>>32),b=(int)id;incident.computeIfAbsent(a,k->new ArrayList<>()).add(b);incident.computeIfAbsent(b,k->new ArrayList<>()).add(a);}
        var used=new HashSet<Long>();var loops=new ArrayList<List<V3>>();
        for(long id:edges){
            if(used.contains(id))continue;
            int from=(int)(id>>32),current=(int)id;used.add(id);
            var loop=new ArrayList<V3>();loop.add(points.get(from));
            for(int guard=edges.size()+1;guard>0;guard--){
                loop.add(points.get(current));
                V3 back=points.get(current).sub(points.get(from));int next=-1;double best=Double.MAX_VALUE;
                for(int candidate:incident.getOrDefault(current,List.of())){
                    long step=join(current,candidate);if(used.contains(step))continue;
                    V3 forward=points.get(candidate).sub(points.get(current));
                    double bend=Math.atan2(back.x()*forward.y()-back.y()*forward.x(),back.x()*forward.x()+back.y()*forward.y());
                    if(bend<best){best=bend;next=candidate;}
                }
                if(next<0)break;
                used.add(join(current,next));from=current;current=next;
                if(current==(int)(id>>32))break;
            }
            if(loop.size()>=3)loops.add(loop);
        }
        return loops;
    }
    private static int key(ArrayList<V3> points,LinkedHashMap<String,Integer> ids,V3 v){
        String id=Math.round(v.x()*1e6)+":"+Math.round(v.y()*1e6);
        Integer known=ids.get(id);if(known!=null)return known;
        points.add(v);ids.put(id,points.size()-1);return points.size()-1;
    }
    private static long join(int a,int b){return ((long)Math.min(a,b)<<32)|Math.max(a,b);}
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
