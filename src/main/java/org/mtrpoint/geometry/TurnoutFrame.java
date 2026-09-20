package org.mtrpoint.geometry;

/** Stations on visual curves only; the rail and node identities stay untouched. */
public final class TurnoutFrame {
    public record Blade(V3 point,V3 cut) {}
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
    public static Blade blade(Track road,Track stock,double station,double sign,double offset,double headWidth,double open,double start,double length,PointSettings s){
        V3 natural=road.at(station).add(road.tangent(station).lateral().mul(sign*offset));
        double stockAt=stock.nearest(road.at(station));V3 stockCenter=stock.at(stockAt).add(stock.tangent(stockAt).lateral().mul(sign*offset));
        V3 inward=natural.sub(stockCenter);inward=new V3(inward.x(),0,inward.z());
        if(inward.length()<1e-7)inward=stock.tangent(stockAt).lateral().mul(-sign);else inward=inward.unit();
        double separation=Math.max(0,natural.sub(stockCenter).dot(inward));
        double factor=Math.pow(Math.max(0,Math.min(1,1-(station-start)/length)),2);
        V3 movement=inward.mul(s.throwDistance()*open*factor),point=natural.add(movement);
        // The blade keeps its original rail section. A vertical planing surface removes
        // only the side that would occupy the stock rail; it never shrinks symmetrically.
        V3 cut=separation<headWidth?stockCenter.add(inward.mul(headWidth/2)).add(movement):null;
        return new Blade(point,cut);
    }
    public static V3 contact(Track road,Track stock,double initial,double sign,double offset,double headWidth,double open,double start,double length,PointSettings s,V3 origin,V3 forward){
        // The stretcher is transverse in plan. A graded tangent's Y component must
        // not turn a difference in rail height into a longitudinal skew.
        forward=new V3(forward.x(),0,forward.z()).unit();
        double d=initial;
        for(int i=0;i<16;i++){
            V3 p=blade(road,stock,d,sign,offset,headWidth,open,start,length,s).point;double error=p.sub(origin).dot(forward);if(Math.abs(error)<1e-9)break;
            double lo=Math.max(0,d-.01),hi=Math.min(road.length,d+.01);
            double den=blade(road,stock,hi,sign,offset,headWidth,open,start,length,s).point.sub(blade(road,stock,lo,sign,offset,headWidth,open,start,length,s).point).dot(forward)/(hi-lo);
            if(Math.abs(den)<.1)break;d=Math.max(0,Math.min(road.length,d-error/den));
        }
        return blade(road,stock,d,sign,offset,headWidth,open,start,length,s).point;
    }
}
