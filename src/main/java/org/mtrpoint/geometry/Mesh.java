package org.mtrpoint.geometry;

import java.util.*;

public final class Mesh {
    /** Source interval for fixed running rail. Crossing cuts use this instead of guessing ownership from world-space faces. */
    public record RailTag(Track road,double start,double end,double offset) {
        public RailTag canonical(){return road.startNode.compareTo(road.endNode)<=0?this:new RailTag(road.reverse(),road.length-end,road.length-start,-offset);}
    }
    public record Quad(V3 a,V3 b,V3 c,V3 d,Profile.Surface surface,String part,int index,java.util.List<Float> uv,RailTag rail) {
        public Quad(V3 a,V3 b,V3 c,V3 d,Profile.Surface surface,String part,int index){this(a,b,c,d,surface,part,index,null,null);}
        public Quad(V3 a,V3 b,V3 c,V3 d,Profile.Surface surface,String part,int index,java.util.List<Float> uv){this(a,b,c,d,surface,part,index,uv,null);}
        public Quad withRail(RailTag value){return new Quad(a,b,c,d,surface,part,index,uv,value);}
        public V3 center(){return a.add(b).add(c).add(d).mul(.25);}
        // Ownership is construction metadata and must not prevent removal of duplicate visible faces.
        @Override public boolean equals(Object value){return value instanceof Quad q&&a.equals(q.a)&&b.equals(q.b)&&c.equals(q.c)&&d.equals(q.d)&&surface.equals(q.surface)&&part.equals(q.part)&&index==q.index&&java.util.Objects.equals(uv,q.uv);}
        @Override public int hashCode(){return java.util.Objects.hash(a,b,c,d,surface,part,index,uv);}
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
    public void rail(V3 a,V3 b,double taperA,double taperB,Profile p,PointSettings s,String part,RailTag tag) {
        int first=quads.size();rail(a,b,taperA,taperB,p,s,part);
        for(int i=first;i<quads.size();i++)quads.set(i,quads.get(i).withRail(tag));
    }
    /** Plane a switch rail from its stock-rail side while retaining the native section. */
    public void blade(V3 a,V3 b,V3 cutA,V3 cutB,Profile p,PointSettings s){
        Mesh section=new Mesh();section.rail(a,b,1,1,p,s,"blade");
        if(cutA==null&&cutB==null){quads.addAll(section.quads);return;}
        V3 origin=cutA!=null?cutA:cutB,along=cutA!=null&&cutB!=null?cutB.sub(cutA):b.sub(a),normal=along.lateral();
        V3 away=(a.sub(cutA!=null?cutA:origin)).add(b.sub(cutB!=null?cutB:origin));if(away.dot(normal)>0)normal=normal.mul(-1);
        quads.addAll(clipAnimated(section,origin,normal).quads);
    }
    /** Close exposed custom-profile check-rail ends; repeating rail models normally omit them.
     * The cap is the model's own zMin cross-section, triangulated from its boundary, so an
     * I-beam end face keeps its concave notches empty instead of filling them. */
    public void railCap(V3 center,V3 tangent,double taper,Profile p,PointSettings s,String part,boolean start){
        ModelDetail detail=p.detail();if(detail==null||detail.rails().isEmpty())return;
        var outlines=endOutline(detail);if(outlines.isEmpty())return;
        V3 normal=normalFor(tangent);double width=p.headWidth()/detail.headWidth();
        for(CapOutline outline:outlines){
            var shape=new ArrayList<V3>(outline.loop().size());
            for(V3 v:outline.loop())shape.add(new V3((v.x()-detail.railCenter())*width*taper,v.y()-detail.railTop()+p.top()+s.verticalOffset(),0));
            // The native section is closed with its own material: the faces that form the zMin
            // outline already carry the model's texture, and the mod's plain steel is only the
            // fallback for a profile whose detail carries no surface at all.
            cap(center,normal,shape,outline.surface()==null?p.steel():outline.surface(),part,start);
        }
    }
    /** Close a cut in a stock rail, including the built-in three-part profile. */
    public void railCutCap(V3 center,V3 tangent,Profile p,PointSettings s,boolean start){
        if(p.detail()!=null&&!p.detail().rails().isEmpty()){railCap(center,tangent,1,p,s,"rail",start);return;}
        double top=p.top()+s.verticalOffset(),base=top-p.railHeight();
        double foot=p.footWidth()/2,web=.011,head=p.headWidth()/2,footTop=base+.025,headBottom=top-.036;
        cap(center,normalFor(tangent),List.of(
            new V3(-foot,base,0),new V3(foot,base,0),new V3(foot,footTop,0),
            new V3(web,footTop,0),new V3(web,headBottom,0),new V3(head,headBottom,0),
            new V3(head,top,0),new V3(-head,top,0),new V3(-head,headBottom,0),
            new V3(-web,headBottom,0),new V3(-web,footTop,0),new V3(-foot,footTop,0)
        ),p.steel(),"rail",start);
    }
    public void railCutCap(V3 center,V3 tangent,Profile p,PointSettings s,boolean start,RailTag tag){
        int first=quads.size();railCutCap(center,tangent,p,s,start);
        for(int i=first;i<quads.size();i++)quads.set(i,quads.get(i).withRail(tag));
    }
    /** Emit a planar end face from its boundary shape, given in the cap frame
     * (x across the rail along the lateral, y vertical). The shape is wound counter-clockwise
     * about that frame, which faces backwards along the tangent, so a start cap is emitted as
     * wound and an end cap is reversed; the material, part and texture stay the caller's. */
    private void cap(V3 center,V3 normal,List<V3> raw,Profile.Surface surface,String part,boolean start){
        var shape=wound(raw);if(shape.size()<3)return;
        float u=(surface.u0()+surface.u1())/2,v=(surface.v0()+surface.v1())/2;List<Float> uv=List.of(u,v,u,v,u,v,u,v);
        for(int[] triangle:triangulate(shape)){
            V3 a=at(center,normal,shape.get(triangle[0])),b=at(center,normal,shape.get(triangle[1])),c=at(center,normal,shape.get(triangle[2]));
            if(start)quad(new Quad(a,b,c,c,surface,part,-1,uv));else quad(new Quad(c,b,a,a,surface,part,-1,uv));
        }
    }
    private static V3 at(V3 center,V3 normal,V3 shape){return center.add(normal.mul(shape.x())).add(0,shape.y(),0);}
    /** Forward direction of an end cap: the lateral of the horizontal tangent. A shape wound
     * about it faces backwards, so the cap at the start of a rail is the forward-facing one. */
    private static V3 normalFor(V3 tangent){V3 along=new V3(tangent.x(),0,tangent.z()).unit();return along.lateral();}
    /** Drop repeated points and force counter-clockwise winding, so concave shapes triangulate
     * the same way whichever direction the source model wound its faces. */
    private static List<V3> wound(List<V3> raw){
        var shape=new ArrayList<V3>(raw.size());
        for(V3 v:raw)if(shape.isEmpty()||shape.get(shape.size()-1).distance(v)>1e-9)shape.add(v);
        while(shape.size()>1&&shape.get(0).distance(shape.get(shape.size()-1))<1e-9)shape.remove(shape.size()-1);
        if(shape.size()<3)return List.of();
        double area=0;for(int i=0;i<shape.size();i++){V3 a=shape.get(i),b=shape.get((i+1)%shape.size());area+=a.x()*b.y()-b.x()*a.y();}
        if(area>=0)return shape;
        var reversed=new ArrayList<>(shape);Collections.reverse(reversed);return reversed;
    }
    /** Ear clipping over the boundary, which keeps every triangle a sub-polygon of the shape, so
     * the concave notches of a rail section are never covered. */
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
    /** One closed outline of the model's zMin section, with the surface of the native faces that
     * form it, so a cut face is drawn with the rail's own material instead of the mod's steel. */
    private record CapOutline(List<V3> loop,Profile.Surface surface){}
    /** Boundary of a custom rail model's end section, read from the edges that lie on zMin. Each
     * edge is counted once and followed into the sharpest remaining turn, so touching faces of
     * the section merge into the outline of their union and separate pieces stay separate. */
    private static List<CapOutline> endOutline(ModelDetail detail){
        double edge=detail.zMin(),epsilon=Math.max(1e-6,(detail.zMax()-detail.zMin())*1e-4);
        var points=new ArrayList<V3>();var ids=new LinkedHashMap<String,Integer>();var surfaces=new LinkedHashMap<Long,Profile.Surface>();
        for(var q:detail.rails()){var corners=List.of(q.a(),q.b(),q.c(),q.d());
            for(int i=0;i<4;i++){
                V3 a=corners.get(i),b=corners.get((i+1)%4);
                if(Math.abs(a.z()-edge)>epsilon||Math.abs(b.z()-edge)>epsilon)continue;
                int first=key(points,ids,a),second=key(points,ids,b);
                if(first!=second)surfaces.putIfAbsent(join(first,second),q.surface());
            }
        }
        var edges=new LinkedHashSet<>(surfaces.keySet());
        if(edges.isEmpty())return List.of();
        var incident=new LinkedHashMap<Integer,List<Integer>>();
        for(long id:edges){int a=(int)(id>>32),b=(int)id;incident.computeIfAbsent(a,k->new ArrayList<>()).add(b);incident.computeIfAbsent(b,k->new ArrayList<>()).add(a);}
        var used=new HashSet<Long>();var loops=new ArrayList<CapOutline>();
        for(long id:edges){
            if(used.contains(id))continue;
            int from=(int)(id>>32),current=(int)id;used.add(id);
            var loop=new ArrayList<V3>();loop.add(points.get(from));var taken=new ArrayList<Profile.Surface>();taken.add(surfaces.get(id));
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
                long step=join(current,next);used.add(step);taken.add(surfaces.get(step));from=current;current=next;
                if(current==(int)(id>>32))break;
            }
            if(loop.size()>=3)loops.add(new CapOutline(loop,dominant(taken)));
        }
        return loops;
    }
    /** The material most of one outline's own edges are drawn with; ties keep the first edge's. */
    private static Profile.Surface dominant(List<Profile.Surface> surfaces){
        Profile.Surface best=null;int count=0;
        for(Profile.Surface candidate:surfaces){
            int hits=0;for(Profile.Surface other:surfaces)if(java.util.Objects.equals(candidate,other))hits++;
            if(hits>count){count=hits;best=candidate;}
        }
        return best;
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
    private static void emit(Mesh out,Mesh.Quad q,Vertex a,Vertex b,Vertex c,Vertex d){out.quad(new Mesh.Quad(a.p,b.p,c.p,d.p,q.surface(),q.part(),q.index(),q.uv()==null?null:List.of(a.u,a.v,b.u,b.v,c.u,c.v,d.u,d.v),q.rail()));}
    /** Fixed face slots for animated inserts clipped to adjacent crossing cells. */
    public static Mesh clipAnimated(Mesh source,V3 origin,V3 normal){
        Mesh out=new Mesh();
        for(var q:source.quads)for(int[] corners:new int[][]{{0,1,2},{0,2,3}}){
            var vertices=List.of(q.a(),q.b(),q.c(),q.d());var uv=new ArrayList<Float>();
            for(int k:new int[]{corners[0],corners[1],corners[2],corners[2]})if(q.uv()!=null){uv.add(q.uv().get(k*2));uv.add(q.uv().get(k*2+1));}
            Quad triangle=new Quad(vertices.get(corners[0]),vertices.get(corners[1]),vertices.get(corners[2]),vertices.get(corners[2]),q.surface(),q.part(),q.index(),q.uv()==null?null:uv,q.rail());
            Mesh clipped=new Mesh();clip(clipped,triangle,origin,normal);
            if(clipped.quads.isEmpty()){
                V3 v=triangle.a();double distance=v.sub(origin).dot(normal);if(distance>0)v=v.sub(normal.mul(distance/normal.dot(normal)));
                out.quad(new Quad(v,v,v,v,q.surface(),q.part(),q.index(),triangle.uv(),q.rail()));
            }else out.quad(clipped.quads.get(0));
        }
        return out;
    }
}
