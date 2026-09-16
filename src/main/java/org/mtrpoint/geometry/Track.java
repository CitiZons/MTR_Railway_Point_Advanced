package org.mtrpoint.geometry;

import java.util.*;

/** Detached sampled geometry: never holds or mutates a simulation Rail. */
public final class Track {
    public final String id, startNode, endNode;
    public final List<V3> points;
    public final double[] distance;
    public final double length;
    public Track(String id,String startNode,String endNode,List<V3> points) {
        if(points.size()<2) throw new IllegalArgumentException("A track needs two points");
        this.id=id; this.startNode=startNode; this.endNode=endNode; this.points=List.copyOf(points);
        distance=new double[points.size()];
        for(int i=1;i<points.size();i++) distance[i]=distance[i-1]+points.get(i).distance(points.get(i-1));
        length=distance[distance.length-1];
    }
    public V3 at(double s) {
        s=Math.max(0,Math.min(length,s)); int p=Arrays.binarySearch(distance,s);
        if(p>=0) return points.get(p);
        p=Math.max(1,Math.min(points.size()-1,-p-1));
        double d=distance[p]-distance[p-1]; return points.get(p-1).lerp(points.get(p),d<1e-9?0:(s-distance[p-1])/d);
    }
    public V3 tangent(double s) { return at(s+.03).sub(at(s-.03)).unit(); }
    public Track reverse() { var p=new ArrayList<>(points); Collections.reverse(p); return new Track(id,endNode,startNode,p); }
    public Track from(String node) { return startNode.equals(node)?this:reverse(); }
    public double nearest(V3 p) {
        double best=Double.MAX_VALUE,s=0;
        for(int i=1;i<points.size();i++) {
            V3 a=points.get(i-1),d=points.get(i).sub(a); double t=Math.max(0,Math.min(1,p.sub(a).dot(d)/Math.max(1e-9,d.dot(d))));
            double dist=p.distance(a.add(d.mul(t)));
            if(dist<best) { best=dist; s=distance[i-1]+t*(distance[i]-distance[i-1]); }
        }
        return s;
    }
    public boolean sharesNode(Track b) { return startNode.equals(b.startNode)||startNode.equals(b.endNode)||endNode.equals(b.startNode)||endNode.equals(b.endNode); }
}
