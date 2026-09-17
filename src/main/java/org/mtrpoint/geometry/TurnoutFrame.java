package org.mtrpoint.geometry;

/** Stations on visual curves only; the rail and node identities stay untouched. */
public final class TurnoutFrame {
    private TurnoutFrame(){}
    public static double side(Junction j,double extent){
        for(double d=extent;d>0;d-=.25){V3 a=j.a().at(d),b=j.b().at(j.b().nearest(a));double lateral=b.sub(a).dot(j.a().tangent(d).lateral());if(Math.abs(lateral)>.01)return Math.signum(lateral);}
        return 1;
    }
    public static double start(Junction j,double extent){
        double last=0;
        for(double d=0;d<extent;d+=.2){V3 a=j.a().at(d);double b=j.b().nearest(a);if(a.distance(j.b().at(b))>.025)return Math.max(0,last);last=d;}
        return 0;
    }
    public static V3 blade(Track road,double station,double sign,double offset,double open,double start,double length,PointSettings s){
        double factor=Math.pow(Math.max(0,Math.min(1,1-(station-start)/length)),2);
        return road.at(station).add(road.tangent(station).lateral().mul(sign*(offset-s.throwDistance()*open*factor)));
    }
    public static V3 contact(Track road,double initial,double sign,double offset,double open,double start,double length,PointSettings s,V3 origin,V3 forward){
        // The stretcher is transverse in plan. A graded tangent's Y component must
        // not turn a difference in rail height into a longitudinal skew.
        forward=new V3(forward.x(),0,forward.z()).unit();
        double d=initial;
        for(int i=0;i<16;i++){
            V3 p=blade(road,d,sign,offset,open,start,length,s);double error=p.sub(origin).dot(forward);if(Math.abs(error)<1e-9)break;
            double lo=Math.max(0,d-.01),hi=Math.min(road.length,d+.01);
            double den=blade(road,hi,sign,offset,open,start,length,s).sub(blade(road,lo,sign,offset,open,start,length,s)).dot(forward)/(hi-lo);
            if(Math.abs(den)<.1)break;d=Math.max(0,Math.min(road.length,d-error/den));
        }
        return blade(road,d,sign,offset,open,start,length,s);
    }
}
