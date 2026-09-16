package org.mtrpoint.geometry;

import java.util.*;

public final class Mesh {
    public record Quad(V3 a,V3 b,V3 c,V3 d,Profile.Surface surface,String part,int index) {
        public V3 center(){return a.add(b).add(c).add(d).mul(.25);}
    }
    public final List<Quad> quads=new ArrayList<>();
    public void quad(V3 a,V3 b,V3 c,V3 d,Profile.Surface surface,String part,int index){quads.add(new Quad(a,b,c,d,surface,part,index));}
    public void beam(V3 a,V3 b,double w1,double w2,double bottom,double top,Profile.Surface surface,String part,int index) {
        V3 n=b.sub(a).lateral();
        V3 al=a.add(n.mul(-w1/2)).add(0,bottom,0),ar=a.add(n.mul(w1/2)).add(0,bottom,0),bl=b.add(n.mul(-w2/2)).add(0,bottom,0),br=b.add(n.mul(w2/2)).add(0,bottom,0);
        double h=top-bottom;V3 au=al.add(0,h,0),av=ar.add(0,h,0),bu=bl.add(0,h,0),bv=br.add(0,h,0);
        quad(av,bv,bu,au,surface,part,index);quad(bl,br,ar,al,surface,part,index);
        quad(au,bu,bl,al,surface,part,index);quad(br,bv,av,ar,surface,part,index);
        quad(ar,av,au,al,surface,part,index);quad(bu,bv,br,bl,surface,part,index);
    }
    public void rail(V3 a,V3 b,double taperA,double taperB,Profile p,PointSettings s,String part) {
        double top=p.top()+s.verticalOffset(),base=top-p.railHeight();
        beam(a,b,p.footWidth()*taperA,p.footWidth()*taperB,base,base+.025,p.steel(),part,-1);
        beam(a,b,.022*taperA,.022*taperB,base+.025,top-.036,p.steel(),part,-1);
        beam(a,b,p.headWidth()*taperA,p.headWidth()*taperB,top-.036,top,p.steel(),part,-1);
    }
}
