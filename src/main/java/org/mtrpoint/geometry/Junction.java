package org.mtrpoint.geometry;

public record Junction(String id,Kind kind,Track a,Track b,V3 center,double sa,double sb,double extent) {
    public enum Kind { Y, DIAMOND }
    public boolean contains(String rail,V3 position,double margin) {
        Track t=rail.equals(a.id)?a:rail.equals(b.id)?b:null; if(t==null)return false;
        double s=t.nearest(position), c=rail.equals(a.id)?sa:sb;
        return position.distance(t.at(s))<5 && (kind==Kind.Y?s<=extent+margin:Math.abs(s-c)<=extent+margin);
    }
}
