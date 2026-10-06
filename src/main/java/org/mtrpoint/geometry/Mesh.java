package org.mtrpoint.geometry;

import java.util.*;

public final class Mesh {
    /** Diagnostics used by the saved-layout smoke probe; reset by the probe before rebuilding. */
    public static long CAP_CALLS, CAP_LOOPS, CAP_FACES, CAP_EDGE_CANDIDATES, CAP_EDGE_UNIQUE, CAP_OUTLINE_POINTS;
    private static final java.util.Map<Profile.Surface,List<Float>> CENTER_UV_CACHE=new java.util.HashMap<>();
    public record Quad(V3 a,V3 b,V3 c,V3 d,Profile.Surface surface,String part,int index,java.util.List<Float> uv,java.util.List<V3> normals) {
        public Quad(V3 a,V3 b,V3 c,V3 d,Profile.Surface surface,String part,int index,java.util.List<Float> uv){this(a,b,c,d,surface,part,index,uv,null);}
        public Quad(V3 a,V3 b,V3 c,V3 d,Profile.Surface surface,String part,int index){this(a,b,c,d,surface,part,index,null);}
        public V3 center(){return a.add(b).add(c).add(d).mul(.25);}
    }
    public final List<Quad> quads=new ArrayList<>();
    /** Texture coordinates a face falls back to: the middle of its surface's atlas square. A face
     *  without uv renders untextured, which is exactly what made end faces read as open holes, so
     *  every emission path is funnelled through this instead of passing null. */
    public static List<Float> centerUv(Profile.Surface s){
        return CENTER_UV_CACHE.computeIfAbsent(s,key->{float u=(key.u0()+key.u1())/2,v=(key.v0()+key.v1())/2;return List.of(u,v,u,v,u,v,u,v);});
    }
    public void quad(V3 a,V3 b,V3 c,V3 d,Profile.Surface surface,String part,int index){quads.add(new Quad(a,b,c,d,surface,part,index,centerUv(surface)));}
    public void quad(Quad q){quads.add(q.uv()!=null?q:new Quad(q.a(),q.b(),q.c(),q.d(),q.surface(),q.part(),q.index(),centerUv(q.surface()),q.normals()));}
    public void beam(V3 a,V3 b,double w1,double w2,double bottom,double top,Profile.Surface surface,String part,int index) {
        V3 n=b.sub(a).lateral();
        V3 al=a.add(n.mul(-w1/2)).add(0,bottom,0),ar=a.add(n.mul(w1/2)).add(0,bottom,0),bl=b.add(n.mul(-w2/2)).add(0,bottom,0),br=b.add(n.mul(w2/2)).add(0,bottom,0);
        double h=top-bottom;V3 au=al.add(0,h,0),av=ar.add(0,h,0),bu=bl.add(0,h,0),bv=br.add(0,h,0);
        // Generic beam ends use the middle of the surface. Exposed rail ends are replaced with
        // a mapped section by railCutCap rather than these overlapping rectangular beam ends.
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
    /** Adjacent curve segments share a cross-section frame, including native model vertices. */
    public void rail(V3 a,V3 b,V3 normalA,V3 normalB,double taperA,double taperB,Profile p,PointSettings s,String part) {
        sweptRail(a,b,normalA,normalB,taperA,taperB,p,s,part,0);
    }
    public void blade(V3 a,V3 b,V3 normalA,V3 normalB,double taperA,double taperB,Profile p,PointSettings s,double stockSide){
        sweptRail(a,b,normalA,normalB,taperA,taperB,p,s,"blade",stockSide);
    }
    private void sweptRail(V3 a,V3 b,V3 normalA,V3 normalB,double taperA,double taperB,Profile p,PointSettings s,String part,double stockSide){
        Mesh section=new Mesh();section.rail(a,b,taperA,taperB,p,s,part);
        // This sweep joins adjacent fixed sections and native rendering windows.
        // The built-in beam's transverse faces are not physical rail ends.
        if(p.detail()==null||p.detail().rails().isEmpty())
            for(int i=section.quads.size()-1;i>=0;i--)if(i%6>=4)section.quads.remove(i);
        V3 delta=b.sub(a),normal=delta.lateral();
        double length2=delta.x()*delta.x()+delta.z()*delta.z();
        if(length2<1e-16){quads.addAll(section.quads);return;}
        double footWidth=p.detail()==null?p.footWidth():p.detail().rails().stream()
            .flatMap(q->java.util.stream.Stream.of(q.a(),q.b(),q.c(),q.d()))
            .mapToDouble(v->Math.abs(v.x()-p.detail().railCenter())*2*p.headWidth()/p.detail().headWidth()).max().orElse(p.footWidth());
        java.util.function.Function<V3,V3> frame=v->{
            V3 relative=v.sub(a);double t=(relative.x()*delta.x()+relative.z()*delta.z())/length2;
            // Snap endpoints so both segments calculate exactly the same frame and centre.
            if(Math.abs(t)<1e-9)t=0;else if(Math.abs(t-1)<1e-9)t=1;
            V3 center=t==0?a:t==1?b:a.lerp(b,t);
            double across=relative.dot(normal);
            if(stockSide!=0){
                double taper=taperA+(taperB-taperA)*t;
                double top=p.top()+s.verticalOffset(),headBottom=top-.036;
                double lower=Math.max(0,Math.min(1,(headBottom-(v.y()-center.y()))/Math.max(.001,p.railHeight()-.036)));
                // Plane away the stock-facing flange and move the lower web inward.
                // Retain the opposite flange: the tip is an L section, not a tiny I.
                double clearance=(footWidth-p.headWidth())/2+.001;
                double original=across/Math.max(TurnoutFrame.TIP_TAPER,taper);
                across-=stockSide*(clearance+(footWidth-p.headWidth())*taper)*(1-taper)*lower;
                if(original*stockSide<0)across+=original*(1-taper)*lower;
            }
            return center.add(normalA.lerp(normalB,t).mul(across)).add(0,v.y()-center.y(),0);
        };
        for(var q:section.quads)quad(new Quad(frame.apply(q.a()),frame.apply(q.b()),frame.apply(q.c()),frame.apply(q.d()),q.surface(),part,q.index(),q.uv()));
    }
    /** Close an exposed end of a rail drawn from the native model. The cap is the model's own zMin
     *  cross-section, triangulated from its boundary, so an I-beam end face keeps its concave
     *  notches empty instead of filling them, with section coordinates mapped to the cut-steel
     *  material. Repeating rail models carry no end faces of their own, which
     *  is why every visible cut end looked open before. */
    public void railCap(V3 center,V3 tangent,double taper,Profile p,PointSettings s,String part,boolean start){
        CAP_CALLS++;
        ModelDetail detail=p.detail();if(detail==null||detail.rails().isEmpty())return;
        var loops=endOutline(detail);
        // MTR's OBJ rail contains the five longitudinal surfaces but deliberately omits
        // the zMin cap. Those surfaces therefore have no topological loop to walk. Rebuild
        // the same I-section from their measured foot/web/head extents in that case.
        if(loops.isEmpty())loops=modelSection(detail);
        CAP_LOOPS+=loops.size();if(loops.isEmpty())return;
        V3 normal=normalFor(tangent);double width=p.headWidth()/detail.headWidth();
        for(List<V3> loop:loops){
            CAP_FACES+=triangulateCount(loop);
            var shape=new ArrayList<V3>(loop.size());
            for(V3 v:loop)shape.add(new V3((v.x()-detail.railCenter())*width*taper,v.y()-detail.railTop()+p.top()+s.verticalOffset(),0));
            cap(center,normal,shape,detail.endSteel(),part,start);
        }
    }
    private static long triangulateCount(List<V3> loop){
        return loop.size()<3?0:loop.size()-2;
    }
    private static List<List<V3>> modelSection(ModelDetail detail){
        // Open OBJ sections still define their exact side silhouette. Do not
        // invent a 36 mm head below a model whose head is only 1.28 mm deep:
        // shearing that invented section makes pale plates along the rail side.
        var levels=new TreeMap<Double,double[]>();
        for(var q:detail.rails())for(V3 v:List.of(q.a(),q.b(),q.c(),q.d())){
            var range=levels.computeIfAbsent(v.y(),k->new double[]{Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY});
            range[0]=Math.min(range[0],v.x());range[1]=Math.max(range[1],v.x());
        }
        if(levels.size()<2)return List.of();
        var shape=new ArrayList<V3>();
        for(var level:levels.entrySet())shape.add(new V3(level.getValue()[1],level.getKey(),0));
        for(var level:levels.descendingMap().entrySet())shape.add(new V3(level.getValue()[0],level.getKey(),0));
        return List.of(shape);
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
        ),Profile.END_STEEL,part,start);
    }
    /** Emit a planar end face from its boundary shape, given in the cap frame (x across the rail
     *  along the lateral, y vertical). The shape is wound counter-clockwise about that frame, which
     *  faces backwards along the tangent, so a start cap is emitted as wound and an end cap is
     *  reversed; material and part stay the caller's. */
    private void cap(V3 center,V3 normal,List<V3> raw,Profile.Surface surface,String part,boolean start){
        var shape=wound(raw);if(shape.size()<3)return;
        double x0=shape.stream().mapToDouble(V3::x).min().orElse(0),x1=shape.stream().mapToDouble(V3::x).max().orElse(1);
        double y0=shape.stream().mapToDouble(V3::y).min().orElse(0),y1=shape.stream().mapToDouble(V3::y).max().orElse(1);
        for(int[] triangle:triangulate(shape)){
            V3 a=shape.get(triangle[start?0:2]),b=shape.get(triangle[1]),c=shape.get(triangle[start?2:0]);
            var uv=new ArrayList<Float>();for(V3 v:List.of(a,b,c,c)){
                uv.add((float)(surface.u0()+(surface.u1()-surface.u0())*(v.x()-x0)/Math.max(1e-9,x1-x0)));
                uv.add((float)(surface.v1()-(surface.v1()-surface.v0())*(v.y()-y0)/Math.max(1e-9,y1-y0)));
            }
            quad(new Quad(at(center,normal,a),at(center,normal,b),at(center,normal,c),at(center,normal,c),surface,part,-1,List.copyOf(uv)));
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
                CAP_EDGE_CANDIDATES++;
                int first=key(points,ids,a),second=key(points,ids,b);
                if(first!=second)edges.add(join(first,second));
            }
        }
        CAP_EDGE_UNIQUE+=edges.size();CAP_OUTLINE_POINTS+=points.size();if(edges.isEmpty())return List.of();
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
            // An open chain is not a closed cross-section. Closing it with an
            // arbitrary diagonal creates cap triangles outside the native rail.
            if(current==(int)(id>>32)&&loop.size()>=3)loops.add(loop);
        }
        return loops;
    }
    private static int key(ArrayList<V3> points,LinkedHashMap<String,Integer> ids,V3 v){
        // MTR's native OBJ sections have independent face vertices.  The head/web
        // contact is sometimes off by about 1 mm after model quantisation, so exact
        // coordinate keys split one physical I-section into open chains.  Snap only
        // the topology key; emitted cap vertices retain their original coordinates.
        final double snap=.0025;
        String id=Math.round(v.x()/snap)+":"+Math.round(v.y()/snap);
        Integer known=ids.get(id);if(known!=null)return known;
        for(var entry:ids.entrySet()){
            Integer candidate=entry.getValue();V3 p=points.get(candidate);
            if(Math.hypot(p.x()-v.x(),p.y()-v.y())<=snap){ids.put(id,candidate);return candidate;}
        }
        points.add(v);ids.put(id,points.size()-1);return points.size()-1;
    }
    private static long join(int a,int b){return ((long)Math.min(a,b)<<32)|Math.max(a,b);}
    private record Vertex(V3 p,float u,float v){Vertex lerp(Vertex b,double t){return new Vertex(p.lerp(b.p,t),(float)(u+(b.u-u)*t),(float)(v+(b.v-v)*t));}}
    public static void clip(Mesh out,Mesh.Quad q,V3 origin,V3 normal){
        var points=List.of(q.a(),q.b(),q.c(),q.d());var source=new ArrayList<Vertex>();
        for(int i=0;i<4;i++)source.add(new Vertex(points.get(i),q.uv()==null?0:q.uv().get(i*2),q.uv()==null?0:q.uv().get(i*2+1)));
        var poly=new ArrayList<Vertex>();
        for(int i=0;i<4;i++){Vertex a=source.get(i),b=source.get((i+1)%4);double da=a.p.sub(origin).dot(normal),db=b.p.sub(origin).dot(normal);
            double tolerance=1e-9*normal.length();if(Math.abs(da)<tolerance)da=0;if(Math.abs(db)<tolerance)db=0;
            if(da<=0)poly.add(a);if((da<0&&db>0)||(da>0&&db<0))poly.add(a.lerp(b,da/(da-db)));}
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
