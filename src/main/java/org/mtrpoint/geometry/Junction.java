package org.mtrpoint.geometry;

public record Junction(String id,Kind kind,Track a,Track b,V3 center,double sa,double sb,double extent,Track third) {
    public Junction(String id,Kind kind,Track a,Track b,V3 center,double sa,double sb,double extent){this(id,kind,a,b,center,sa,sb,extent,null);}
    public java.util.List<Track> tracks(){return third==null?java.util.List.of(a,b):java.util.List.of(a,third,b);}
    public enum Kind { Y, THREE, DIAMOND }
    public boolean contains(String rail,V3 position,double margin) {
        return contains(rail,position,margin,extent);
    }
    public boolean contains(String rail,V3 position,double margin,double limit) {
        Track t=rail.equals(a.id)?a:rail.equals(b.id)?b:third!=null&&rail.equals(third.id)?third:null; if(t==null)return false;
        double s=t.nearest(position), c=rail.equals(a.id)?sa:sb;
        return position.distance(t.at(s))<5 && (kind!=Kind.DIAMOND?s<=limit+margin:Math.abs(s-c)<=limit+margin);
    }
}
