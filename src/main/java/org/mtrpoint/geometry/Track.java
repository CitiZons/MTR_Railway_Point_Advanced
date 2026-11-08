package org.mtrpoint.geometry;

import java.util.*;

/** Detached sampled geometry: never holds or mutates a simulation Rail. */
public final class Track {
    public final String id, startNode, endNode;
    public final List<V3> points;
    public final double[] distance;
    public final double length;
    private final Node tree;
    private Track reversed;
    private record Node(int start,int end,double x0,double y0,double z0,double x1,double y1,double z1,Node left,Node right) {}
    public Track(String id,String startNode,String endNode,List<V3> points) {
        if(points.size()<2) throw new IllegalArgumentException("A track needs two points");
        this.id=id; this.startNode=startNode; this.endNode=endNode; this.points=List.copyOf(points);
        distance=new double[points.size()];
        for(int i=1;i<points.size();i++) distance[i]=distance[i-1]+points.get(i).distance(points.get(i-1));
        length=distance[distance.length-1];
        tree=tree(1,points.size());
    }
    public V3 at(double s) {
        s=Math.max(0,Math.min(length,s)); int p=Arrays.binarySearch(distance,s);
        if(p>=0) return points.get(p);
        p=Math.max(1,Math.min(points.size()-1,-p-1));
        double d=distance[p]-distance[p-1]; return points.get(p-1).lerp(points.get(p),d<1e-9?0:(s-distance[p-1])/d);
    }
    public V3 tangent(double s) { return at(s+.03).sub(at(s-.03)).unit(); }
    public Track reverse() { if(reversed==null){var p=new ArrayList<>(points);Collections.reverse(p);reversed=new Track(id,endNode,startNode,p);reversed.reversed=this;}return reversed; }
    public Track from(String node) { return startNode.equals(node)?this:reverse(); }
    public boolean overlapsXZ(Track other,double margin){
        return overlapsXZ(tree,other.tree,margin);
    }
    private static boolean overlapsXZ(Node a,Node b,double margin){
        return a.x0<=b.x1+margin&&b.x0<=a.x1+margin
            &&a.z0<=b.z1+margin&&b.z0<=a.z1+margin;
    }
    /** Prune whole runs of segments before testing exact intersections. */
    public boolean crossesAtGrade(Track other,double minSine,double maxHeight){
        return crossesAtGrade(tree,other,other.tree,minSine,maxHeight);
    }
    private boolean crossesAtGrade(Node a,Track other,Node b,double minSine,double maxHeight){
        if(!overlapsXZ(a,b,0))return false;
        double heightMargin=maxHeight+1e-8;
        if(a.y0>b.y1+heightMargin||b.y0>a.y1+heightMargin)return false;
        if(a.left!=null&&(b.left==null||a.end-a.start>=b.end-b.start)){
            return crossesAtGrade(a.left,other,b,minSine,maxHeight)||crossesAtGrade(a.right,other,b,minSine,maxHeight);
        }
        if(b.left!=null){
            return crossesAtGrade(a,other,b.left,minSine,maxHeight)||crossesAtGrade(a,other,b.right,minSine,maxHeight);
        }
        for(int i=a.start;i<a.end;i++){
            V3 u=points.get(i-1),v=points.get(i);
            for(int k=b.start;k<b.end;k++){
                V3 x=other.points.get(k-1),y=other.points.get(k);
                if(Math.max(u.x(),v.x())<Math.min(x.x(),y.x())||Math.max(x.x(),y.x())<Math.min(u.x(),v.x())
                    ||Math.max(u.z(),v.z())<Math.min(x.z(),y.z())||Math.max(x.z(),y.z())<Math.min(u.z(),v.z()))continue;
                if(Math.abs(V3.crossXZ(v.sub(u).unit(),y.sub(x).unit()))<minSine)continue;
                double[] at=Detector.intersection(u,v,x,y);
                if(at==null)continue;
                double heightA=u.y()*(1-at[0])+v.y()*at[0];
                double heightB=x.y()*(1-at[1])+y.y()*at[1];
                if(Math.abs(heightA-heightB)<=maxHeight)return true;
            }
        }
        return false;
    }
    private Node tree(int start,int end){
        double x0=Double.MAX_VALUE,y0=x0,z0=x0,x1=-x0,y1=x1,z1=x1;
        for(int i=start-1;i<end;i++){V3 p=points.get(i);x0=Math.min(x0,p.x());y0=Math.min(y0,p.y());z0=Math.min(z0,p.z());x1=Math.max(x1,p.x());y1=Math.max(y1,p.y());z1=Math.max(z1,p.z());}
        int mid=(start+end)/2;return new Node(start,end,x0,y0,z0,x1,y1,z1,end-start>8?tree(start,mid):null,end-start>8?tree(mid,end):null);
    }
    private static double bound(Node n,V3 p,boolean horizontal){double x=Math.max(0,Math.max(n.x0-p.x(),p.x()-n.x1)),y=horizontal?0:Math.max(0,Math.max(n.y0-p.y(),p.y()-n.y1)),z=Math.max(0,Math.max(n.z0-p.z(),p.z()-n.z1));return x*x+y*y+z*z;}
    public double nearest(V3 p){double[] best={Double.MAX_VALUE,0};nearest(tree,p,best,false);return best[1];}
    public double nearestHorizontal(V3 p){double[] best={Double.MAX_VALUE,0};nearest(tree,p,best,true);return best[1];}
    private void nearest(Node n,V3 p,double[] best,boolean horizontal){
        if(bound(n,p,horizontal)>best[0])return;
        if(n.left!=null){Node first=bound(n.left,p,horizontal)<=bound(n.right,p,horizontal)?n.left:n.right;nearest(first,p,best,horizontal);nearest(first==n.left?n.right:n.left,p,best,horizontal);return;}
        for(int i=n.start;i<n.end;i++){
            V3 a=points.get(i-1),b=points.get(i);double x=b.x()-a.x(),y=horizontal?0:b.y()-a.y(),z=b.z()-a.z();
            double t=Math.max(0,Math.min(1,((p.x()-a.x())*x+(p.y()-a.y())*y+(p.z()-a.z())*z)/Math.max(1e-12,x*x+y*y+z*z)));
            double dx=p.x()-a.x()-x*t,dy=horizontal?0:p.y()-a.y()-y*t,dz=p.z()-a.z()-z*t,dist=dx*dx+dy*dy+dz*dz;
            if(dist<best[0]){best[0]=dist;best[1]=distance[i-1]+t*(distance[i]-distance[i-1]);}
        }
    }
    public boolean sharesNode(Track b) { return startNode.equals(b.startNode)||startNode.equals(b.endNode)||endNode.equals(b.startNode)||endNode.equals(b.endNode); }
}
