package org.mtrpoint.geometry;

import java.util.*;

/** Two perpendicular bearer arms meet at a shared mitre, without overlapping full sleepers. */
public final class VSleepers {
    private record Arm(Track road,double distance,V3 center,V3 normal) {}
    /** One bearer family across every road, including diagonals inside a scissors. */
    public static void across(Mesh out,List<Track> tracks,V3 spine,V3 forward,double angle,PointSettings s,Profile p,int index){
        V3 transverse=forward.lateral();var arms=new ArrayList<Arm>();
        for(Track original:tracks){
            Track road=original;double d=road.nearest(spine);
            if(road.tangent(d).dot(forward)<0){road=road.reverse();d=road.length-d;}
            // First place the road in the common transverse slice. Then place the arm
            // through a joint halfway to each neighbor, rather than equal arc lengths.
            for(int k=0;k<10;k++){double den=road.tangent(d).dot(forward);if(Math.abs(den)<.1)break;d=Math.max(0,Math.min(road.length,d-road.at(d).sub(spine).dot(forward)/den));}
            arms.add(new Arm(road,d,road.at(d),rotate(road.tangent(d).lateral(),angle)));
        }
        arms.sort(Comparator.comparingDouble(a->a.center.sub(spine).dot(transverse)));
        // Coincident roads need a single supporting arm, not a zero-width wedge.
        for(int i=arms.size()-1;i>0;i--)if(arms.get(i).center.distance(arms.get(i-1).center)<.08)arms.remove(i);
        var joints=new ArrayList<V3>();
        for(int i=1;i<arms.size();i++){
            Arm previous=arms.get(i-1),next=arms.get(i);
            double middle=(previous.center.sub(spine).dot(transverse)+next.center.sub(spine).dot(transverse))/2;
            V3 joint=previous.center.add(previous.normal.mul((middle-previous.center.sub(spine).dot(transverse))/previous.normal.dot(transverse)));
            double d=station(next.road,joint,next.distance,angle);
            arms.set(i,new Arm(next.road,d,next.road.at(d),rotate(next.road.tangent(d).lateral(),angle)));joints.add(joint);
        }
        var seats=new ArrayList<V3>();
        for(int i=0;i<arms.size();i++){
            Arm a=arms.get(i);double half=p.centerOffset()+s.sleeperOverhang();
            V3 left=i==0?null:joints.get(i-1),right=i+1==arms.size()?null:joints.get(i);
            double d=a.distance;V3 c=a.center,n=a.normal;
            double lo=-half,hi=half;
            if(!SleeperEdits.split(s,index))for(V3 limit:new V3[]{left,right})if(limit!=null){double at=limit.sub(c).dot(n);lo=Math.min(lo,at-.5);hi=Math.max(hi,at+.5);}
            Mesh arm=new Mesh();
            if(p.detail()!=null){if(!p.detail().siding())p.detail().bearer(arm,c,n,lo,hi,s,p,index);}
            else {double top=p.top()-p.railHeight()+s.verticalOffset();arm.beam(c.add(n.mul(lo)),c.add(n.mul(hi)),s.sleeperWidth(),s.sleeperWidth(),top-s.sleeperHeight(),top,p.sleeper(),"sleeper",index);}
            // Both arms use the same bisector, so their full widths meet without a step.
            for(int side:new int[]{-1,1}){V3 limit=side<0?left:right;if(limit==null||SleeperEdits.split(s,index)||SleeperEdits.full(s,index))continue;
                V3 cut=n.add(arms.get(i+side).normal).unit();if(cut.dot(transverse)<0)cut=cut.mul(-1);
                Mesh clipped=new Mesh();for(var q:arm.quads)Mesh.clip(clipped,q,limit,cut.mul(side));arm=clipped;
            }
            out.quads.addAll(arm.quads);
            if(p.detail()!=null)for(int sign:new int[]{-1,1}){
                V3 seat=a.road.at(d).add(a.road.tangent(d).lateral().mul(sign*p.centerOffset()));
                if(seats.stream().noneMatch(v->v.distance(seat)<.18)){seats.add(seat);p.detail().fitting(out,seat,n,s,p,index);}
            }
        }
    }
    public static void diamond(Mesh out,Junction j,PointSettings s,Profile p,double extent){
        Track a=j.a(),b=j.b();double ca=j.sa(),cb=j.sb();
        if(a.tangent(ca).dot(b.tangent(cb))<0){b=b.reverse();cb=b.length-cb;}
        V3 forward=a.tangent(ca).add(b.tangent(cb)).unit();int index=0;Mesh family=new Mesh();
        double projection=Math.max(.5,Math.abs(forward.dot(a.tangent(ca))));
        // Project the spacing ONCE onto the common spine. Averaging equal road arc
        // lengths and then projecting again halved the pitch at a right-angle crossing.
        double step=s.sleeperSpacing()*projection,limit=extent*projection;
        for(double d=-limit+step/2;d<limit;d+=step){
            double along=d+s.sleeperShifts().getOrDefault(index,0D)*projection;
            double angle=Math.toRadians(s.sleeperAngle()+(s.sleeperEndAngle()-s.sleeperAngle())*(d+limit)/(2*limit));
            across(family,List.of(a,b),j.center().add(forward.mul(along)),forward,angle,s,p,index++);
        }
        out.quads.addAll(SurfaceUnion.build(family).quads);
    }
    public static void add(Mesh out,Junction j,PointSettings s,Profile p,double d,double extent,int index){
        add(out,j,s,p,d,d,extent,index);
    }
    public static void add(Mesh out,Junction j,PointSettings s,Profile p,double d,double otherDistance,double extent,int index){
        double angle=Math.toRadians(s.sleeperAngle()+(s.sleeperEndAngle()-s.sleeperAngle())*Math.max(0,Math.min(1,d/extent)));
        // Equal arc lengths on different curves are not the same cross-section. Intersecting
        // their normals can put the joint metres outside the track, then abruptly hit a
        // fallback threshold. Choose the interior spine first and project each arm onto it.
        V3 joint=j.a().at(d).lerp(j.b().at(otherDistance),.5);
        d=station(j.a(),joint,d,angle);otherDistance=station(j.b(),joint,otherDistance,angle);
        V3 a=j.a().at(d),b=j.b().at(otherDistance);
        V3 na=rotate(j.a().tangent(d).lateral(),angle),nb=rotate(j.b().tangent(otherDistance).lateral(),angle);
        V3 separation=b.sub(a);
        if(separation.length()<1e-7)separation=j.b().at(Math.min(j.b().length,otherDistance+3)).sub(j.a().at(Math.min(j.a().length,d+3)));
        V3 cut=na.add(nb).unit();if(cut.dot(separation)<0)cut=cut.mul(-1);
        var seats=new ArrayList<V3>();
        for(int branch=0;branch<2;branch++){
            V3 c=branch==0?a:b,n=branch==0?na:nb;Track road=branch==0?j.a():j.b();
            double half=p.centerOffset()+s.sleeperOverhang(),join=joint.sub(c).dot(n);
            double lo=SleeperEdits.split(s,index)?-half:Math.min(-half,join-.4),hi=SleeperEdits.split(s,index)?half:Math.max(half,join+.4);
            Mesh arm=new Mesh();
            if(p.detail()!=null){if(!p.detail().siding())p.detail().bearer(arm,c,n,lo,hi,s,p,index);}
            else {double top=Math.max(.03,p.top()-p.railHeight())+s.verticalOffset();arm.beam(c.add(n.mul(lo)),c.add(n.mul(hi)),s.sleeperWidth(),s.sleeperWidth(),top-s.sleeperHeight(),top,p.sleeper(),"sleeper",index);}
            for(var q:arm.quads)if(SleeperEdits.split(s,index)||SleeperEdits.full(s,index))out.quad(q);else Mesh.clip(out,q,joint,branch==0?cut:cut.mul(-1));
            V3 forward=new V3(n.z(),0,-n.x());
            for(int sign:new int[]{-1,1}){
                double near=branch==0?d:otherDistance;
                for(int i=0;i<5;i++){V3 seat=road.at(near).add(road.tangent(near).lateral().mul(sign*p.centerOffset()));double denom=road.tangent(near).dot(forward);if(Math.abs(denom)<.1)break;near=Math.max(0,Math.min(road.length,near-seat.sub(c).dot(forward)/denom));}
                V3 seat=road.at(near).add(road.tangent(near).lateral().mul(sign*p.centerOffset()));
                if(p.detail()!=null&&seats.stream().noneMatch(v->v.distance(seat)<.18)){seats.add(seat);p.detail().fitting(out,seat,n,s,p,index);}
            }
        }
    }
    public static double station(Track road,V3 joint,double initial,double angle){
        double d=Math.max(0,Math.min(road.length,initial));
        for(int i=0;i<10;i++){
            V3 n=rotate(road.tangent(d).lateral(),angle),forward=new V3(n.z(),0,-n.x());
            double error=road.at(d).sub(joint).dot(forward);if(Math.abs(error)<1e-7)break;
            double h=.01,lo=Math.max(0,d-h),hi=Math.min(road.length,d+h);
            V3 nl=rotate(road.tangent(lo).lateral(),angle),nh=rotate(road.tangent(hi).lateral(),angle);
            double derivative=(road.at(hi).sub(joint).dot(new V3(nh.z(),0,-nh.x()))-road.at(lo).sub(joint).dot(new V3(nl.z(),0,-nl.x())))/(hi-lo);
            if(Math.abs(derivative)<.1)break;
            d=Math.max(0,Math.min(road.length,d-Math.max(-.5,Math.min(.5,error/derivative))));
        }
        return d;
    }
    private static V3 rotate(V3 n,double a){return new V3(n.x()*Math.cos(a)-n.z()*Math.sin(a),0,n.x()*Math.sin(a)+n.z()*Math.cos(a));}
}
