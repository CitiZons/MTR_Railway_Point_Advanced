package org.mtrpoint.geometry;

public record V3(double x, double y, double z) {
    public V3 add(V3 b) { return new V3(x+b.x,y+b.y,z+b.z); }
    public V3 add(double a,double b,double c) { return new V3(x+a,y+b,z+c); }
    public V3 sub(V3 b) { return new V3(x-b.x,y-b.y,z-b.z); }
    public V3 mul(double s) { return new V3(x*s,y*s,z*s); }
    public double dot(V3 b) { return x*b.x+y*b.y+z*b.z; }
    public double length() { return Math.sqrt(dot(this)); }
    public double distance(V3 b) { return sub(b).length(); }
    public V3 unit() { double l=length(); return l<1e-9?new V3(1,0,0):mul(1/l); }
    public V3 lateral() { double l=Math.hypot(x,z); return l<1e-9?new V3(0,0,1):new V3(-z/l,0,x/l); }
    public V3 lerp(V3 b,double t) { return mul(1-t).add(b.mul(t)); }
    public static double crossXZ(V3 a,V3 b) { return a.x*b.z-a.z*b.x; }
}
