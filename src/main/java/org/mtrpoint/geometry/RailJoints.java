package org.mtrpoint.geometry;

import java.util.*;

/** Fill only the narrow open seam between otherwise adjoining rail or ballast faces. */
public final class RailJoints {
    private static final double LIMIT=.006;
    private static final Set<String> STEEL=Set.of("rail","frog","wing","guard");
    public static boolean nativeSteel(String part){return part.startsWith("rail_")&&!part.equals("rail_joint")&&!part.endsWith("_end");}
    public static boolean steel(String part){return STEEL.contains(part)||part.equals("blade_heel")||nativeSteel(part);}
    public static boolean seamSurface(String part){return steel(part)||part.equals("ballast");}
    private record Key(long x,long y,long z) implements Comparable<Key>{
        static Key of(V3 v){return new Key(Math.round(v.x()*1e7),Math.round(v.y()*1e7),Math.round(v.z()*1e7));}
        public int compareTo(Key k){int c=Long.compare(x,k.x);if(c==0)c=Long.compare(y,k.y);return c==0?Long.compare(z,k.z):c;}
    }
    private record EdgeKey(Key a,Key b){static EdgeKey of(V3 a,V3 b){Key x=Key.of(a),y=Key.of(b);return x.compareTo(y)<0?new EdgeKey(x,y):new EdgeKey(y,x);}}
    private record Edge(V3 a,V3 b,V3 normal,Mesh.Quad face,int corner){V3 center(){return a.lerp(b,.5);}}
    private record Cell(long x,long y,long z){static Cell of(V3 v){return new Cell((long)Math.floor(v.x()/.25),(long)Math.floor(v.y()/.25),(long)Math.floor(v.z()/.25));}}
    private record Pair(Edge a,Edge b,boolean reverse,double gap){}
    private static V3 cross(V3 a,V3 b){return new V3(a.y()*b.z()-a.z()*b.y(),a.z()*b.x()-a.x()*b.z(),a.x()*b.y()-a.y()*b.x());}
    public static Mesh bridges(Mesh steel){
        return bridges(steel,false);
    }
    public static Mesh bridges(Mesh steel,boolean nativeOnly){
        var edges=new LinkedHashMap<EdgeKey,List<Edge>>();
        for(var q:steel.quads){
            if(!seamSurface(q.part()))continue;
            V3 normal=cross(q.b().sub(q.a()),q.c().sub(q.a())).unit();
            var vs=List.of(q.a(),q.b(),q.c(),q.d());
            for(int i=0;i<4;i++){if(q.part().equals("blade_heel")&&i!=q.index())continue;
                V3 a=vs.get(i),b=vs.get((i+1)%4);if(a.distance(b)<1e-6)continue;
                edges.computeIfAbsent(EdgeKey.of(a,b),k->new ArrayList<>()).add(new Edge(a,b,normal,q,i));}
        }
        var bins=new HashMap<Cell,List<Edge>>();var pairs=new ArrayList<Pair>();
        for(var owners:edges.values())if(owners.size()==1){
            Edge edge=owners.get(0);Cell cell=Cell.of(edge.center());
            for(int x=-1;x<=1;x++)for(int y=-1;y<=1;y++)for(int z=-1;z<=1;z++)for(Edge other:bins.getOrDefault(new Cell(cell.x+x,cell.y+y,cell.z+z),List.of())){
                if(edge.face==other.face||edge.normal.dot(other.normal)<.995||!edge.face.surface().texture().equals(other.face.surface().texture()))continue;
                if(edge.face.part().equals("ballast")!=other.face.part().equals("ballast"))continue;
                if(nativeOnly&&!nativeSteel(edge.face.part())&&!nativeSteel(other.face.part())&&!edge.face.part().equals("ballast"))continue;
                double same=Math.max(edge.a.distance(other.a),edge.b.distance(other.b)),reverse=Math.max(edge.a.distance(other.b),edge.b.distance(other.a));
                double gap=Math.min(same,reverse);if(gap>LIMIT||gap<1e-7)continue;
                V3 axis=cross(edge.b.sub(edge.a).unit(),edge.normal),middle=edge.center().lerp(other.center(),.5);
                // Both faces must continue away from opposite sides of the joint.
                // Adjacent bevel faces and coincident overlays are not rail gaps.
                double a=edge.face.center().sub(middle).dot(axis),b=other.face.center().sub(middle).dot(axis);
                if(a*b>=-1e-10)continue;
                pairs.add(new Pair(edge,other,reverse<same,gap));
            }
            bins.computeIfAbsent(cell,k->new ArrayList<>()).add(edge);
        }
        pairs.sort(Comparator.comparingDouble(Pair::gap));var used=new HashSet<Edge>();Mesh out=new Mesh();
        for(Pair pair:pairs){if(used.contains(pair.a)||used.contains(pair.b))continue;
            Edge a=pair.a,b=pair.b;V3 ba=pair.reverse?b.b:b.a,bb=pair.reverse?b.a:b.b;
            V3 axis=cross(a.b.sub(a.a).unit(),a.normal);
            V3 outward=axis.mul(a.face.center().sub(a.center()).dot(axis)>0?-1:1);
            double first=ba.sub(a.a).dot(outward),last=bb.sub(a.b).dot(outward);
            if(Math.max(first,last)<1e-8)continue;
            double from=0,to=1;
            // Rotated endpoint sections can cross: fill the open wedge only,
            // leaving their overlapping half untouched to avoid coplanar flicker.
            if(first<0)from=first/(first-last);else if(last<0)to=first/(first-last);
            V3 x=a.a.lerp(a.b,from),y=a.a.lerp(a.b,to),c=ba.lerp(bb,to),d=ba.lerp(bb,from);
            var uv=a.face.uv();int i=a.corner,j=(i+1)%4;
            float u0=uv==null?0:uv.get(i*2),v0=uv==null?0:uv.get(i*2+1),u1=uv==null?0:uv.get(j*2),v1=uv==null?0:uv.get(j*2+1);
            float uf=(float)(u0+(u1-u0)*from),vf=(float)(v0+(v1-v0)*from),ut=(float)(u0+(u1-u0)*to),vt=(float)(v0+(v1-v0)*to);
            var mapped=uv==null?Mesh.centerUv(a.face.surface()):List.of(uf,vf,ut,vt,ut,vt,uf,vf);
            var points=List.of(x,y,c,d);
            for(int[] triangle:new int[][]{{0,1,2},{0,2,3}}){
                int i0=triangle[0],i1=triangle[1],i2=triangle[2];
                V3 normal=cross(points.get(i1).sub(points.get(i0)),points.get(i2).sub(points.get(i0)));
                if(normal.length()<1e-12)continue;
                if(normal.dot(a.normal)<0){int swap=i1;i1=i2;i2=swap;}
                out.quad(new Mesh.Quad(points.get(i0),points.get(i1),points.get(i2),points.get(i2),a.face.surface(),a.face.part().equals("ballast")?"ballast_joint":"rail_joint",-1,
                    List.of(mapped.get(i0*2),mapped.get(i0*2+1),mapped.get(i1*2),mapped.get(i1*2+1),mapped.get(i2*2),mapped.get(i2*2+1),mapped.get(i2*2),mapped.get(i2*2+1))));
            }
            used.add(a);used.add(b);
        }
        return out;
    }
}
