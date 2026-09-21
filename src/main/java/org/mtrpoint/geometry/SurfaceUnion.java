package org.mtrpoint.geometry;

import java.util.*;

/** Removes coplanar overlap in the final support assembly, preserving each face's UVs.
 * Built only when the visible geometry changes, never per animation frame. */
public final class SurfaceUnion {
    private SurfaceUnion(){}
    private record Face(Mesh.Quad q,V3 normal,double x0,double x1,double z0,double z1) {}
    public static Mesh build(Mesh source){
        Mesh result=new Mesh();var bins=new HashMap<Long,List<Face>>();var seen=new HashSet<Mesh.Quad>();
        for(var q:source.quads){
            if(!seen.add(q))continue;
            V3 n=normal(q);if(Math.abs(n.y())<.2){result.quad(q);continue;}
            V3[] corners={q.a(),q.b(),q.c(),q.d()};
            double x0=Double.MAX_VALUE,x1=-x0,z0=x0,z1=-x0;
            for(V3 v:corners){x0=Math.min(x0,v.x());x1=Math.max(x1,v.x());z0=Math.min(z0,v.z());z1=Math.max(z1,v.z());}
            Face face=new Face(q,n,x0,x1,z0,z1);var nearby=new LinkedHashSet<Face>();
            var cells=cells(face);
            for(long key:cells)nearby.addAll(bins.getOrDefault(key,List.of()));
            Mesh remaining=new Mesh();remaining.quad(q);
            for(Face old:nearby){
                if(old.x1<=x0+1e-8||old.x0>=x1-1e-8||old.z1<=z0+1e-8||old.z0>=z1-1e-8||old.normal.dot(n)<.999999)continue;
                // The four corners are reused for every candidate: allocating the list and the
                // stream per candidate was the dominant cost of the whole assembly bake.
                boolean coplanar=true;
                for(V3 v:corners)if(Math.abs(v.sub(old.q.a()).dot(old.normal))>1e-6){coplanar=false;break;}
                if(!coplanar)continue;
                remaining=subtract(remaining,old.q,old.normal);if(remaining.quads.isEmpty())break;
            }
            result.quads.addAll(remaining.quads);
            // Use the original polygon as the mask: its removed area is owned by an earlier face.
            for(long key:cells)bins.computeIfAbsent(key,k->new ArrayList<>()).add(face);
        }
        return result;
    }
    private static List<Long> cells(Face f){var keys=new ArrayList<Long>();for(int x=(int)Math.floor(f.x0/2);x<=(int)Math.floor(f.x1/2);x++)for(int z=(int)Math.floor(f.z0/2);z<=(int)Math.floor(f.z1/2);z++)keys.add(((long)x<<32)^(z&0xffffffffL));return keys;}
    private static V3 cross(V3 a,V3 b){return new V3(a.y()*b.z()-a.z()*b.y(),a.z()*b.x()-a.x()*b.z(),a.x()*b.y()-a.y()*b.x());}
    private static V3 normal(Mesh.Quad q){return cross(q.b().sub(q.a()),q.c().sub(q.a())).unit();}
    private static Mesh subtract(Mesh source,Mesh.Quad mask,V3 normal){
        Mesh outside=new Mesh(),inside=source;V3[] points={mask.a(),mask.b(),mask.c(),mask.d()};
        for(int i=0;i<4;i++){
            V3 a=points[i],b=points[(i+1)%4];if(a.distance(b)<1e-9)continue;
            V3 plane=cross(b.sub(a),normal).unit();Mesh next=new Mesh();
            for(var q:inside.quads){
                // Four explicit dot products instead of a per-quad vertex list: this loop runs once
                // per candidate mask plane over every remaining fragment of the face.
                V3 va=q.a().sub(a),vb=q.b().sub(a),vc=q.c().sub(a),vd=q.d().sub(a);
                double d0=va.dot(plane),d1=vb.dot(plane),d2=vc.dot(plane),d3=vd.dot(plane);
                double lo=Math.min(Math.min(d0,d1),Math.min(d2,d3)),hi=Math.max(Math.max(d0,d1),Math.max(d2,d3));
                if(hi<=1e-8)next.quad(q);
                else if(lo>=-1e-8)outside.quad(q);
                else {Mesh.clip(outside,q,a,plane.mul(-1));Mesh.clip(next,q,a,plane);}
            }
            inside=next;if(inside.quads.isEmpty())break;
        }
        return outside;
    }
}
