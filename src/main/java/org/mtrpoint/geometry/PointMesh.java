package org.mtrpoint.geometry;

import java.util.*;

/** Cross sections are swept along the actual sampled MTR curves. No simulation geometry is edited. */
public final class PointMesh {
    private record Running(Track track,double sign,int branch) {}
    public static double extent(Junction j,PointSettings s){return Math.min(j.tracks().stream().mapToDouble(t->t.length).min().orElse(0),j.extent()*s.lengthScale());}
    public record YBoundary(double aEnd,double bEnd,double aLast,double bLast,double thirdEnd,double thirdLast) {
        public YBoundary(double aEnd,double bEnd,double aLast,double bLast){this(aEnd,bEnd,aLast,bLast,(aEnd+bEnd)/2,(aLast+bLast)/2);}
        public static YBoundary nominal(Junction j,PointSettings s){double end=extent(j,s),last=Math.max(s.sleeperSpacing()/2,end-s.sleeperSpacing()/2);return new YBoundary(end,end,last,last);}
    }
    public static Mesh build(Junction j,PointSettings s,Profile raw,double position) {
        return build(j,s,raw,position,YBoundary.nominal(j,s));
    }
    public static Mesh build(Junction j,PointSettings s,Profile raw,double position,YBoundary boundary) {
        if(j.kind()==Junction.Kind.THREE)return ThreeWayMesh.build(j,s,raw,position,boundary);
        Mesh mesh=new Mesh();if(!s.enabled())return mesh;Profile p=raw.tune(s);
        double extent=extent(j,s),offset=p.centerOffset();
        if(j.kind()==Junction.Kind.DIAMOND){DiamondGeometry.build(mesh,j,s,p,extent);sleepers(mesh,j,s,p,extent,boundary);SleeperEdits.finish(mesh,j,s,p);return mesh;}
        List<Running> runs=List.of(new Running(j.a(),-1,0),new Running(j.a(),1,0),new Running(j.b(),-1,1),new Running(j.b(),1,1));
        double frog=extent*.65;
        if(j.kind()==Junction.Kind.Y)for(double d=.25;d<=extent;d+=.1)if(j.a().at(d).distance(j.b().at(d))>2*offset){frog=d;break;}
        frog=Math.max(1,Math.min(extent-1,frog+s.frogShift()));
        FrogGeometry crossing=j.kind()==Junction.Kind.Y?new FrogGeometry(j,s,p,extent):null;
        if(crossing!=null)frog=(crossing.sa+crossing.sb)/2;
        double bladeStart=TurnoutFrame.start(j,extent);
        double blade=s.bladeLength()>0?s.bladeLength():Math.max(2,Math.min((frog-bladeStart)*.65,9));
        double secondStart=j.b().nearest(j.a().at(bladeStart));
        // Determine which branch lies to the positive lateral side in the common frame.
        double side=TurnoutFrame.side(j,extent);
        for(Running r:runs) {
            double c=r.branch==0?j.sa():j.sb(),start=j.kind()==Junction.Kind.Y?0:Math.max(0,c-extent),end=j.kind()==Junction.Kind.Y?(r.branch==0?boundary.aEnd():boundary.bEnd()):Math.min(r.track.length,c+extent);
            boolean inner=j.kind()==Junction.Kind.Y&&r.sign==(r.branch==0?side:-side);
            double localStart=r.branch==0?bladeStart:secondStart;
            var stations=new TreeSet<Double>();stations.add(start);stations.add(end);for(double d=start+.24;d<end;d+=.24)stations.add(d);
            if(localStart>start&&localStart<end)stations.add(localStart);if(localStart+blade>start&&localStart+blade<end)stations.add(localStart+blade);
            if(inner&&crossing!=null){stations.add(Math.max(start,Math.min(end,crossing.toe(r.branch))));stations.add(Math.max(start,Math.min(end,crossing.heel(r.branch))));}
            var distances=new ArrayList<>(stations);
            for(int i=1;i<distances.size();i++) {
                double d=distances.get(i-1),e=distances.get(i);
                if(r.branch==1&&inner&&e<=localStart)continue;
                if(inner&&crossing!=null){double toe=crossing.toe(r.branch),heel=crossing.heel(r.branch);if(d>=toe&&e<=heel)continue;if(d<toe&&e>toe)e=toe;else if(d<heel&&e>heel)d=heel;}
                V3 a=running(r,d,offset),b=running(r,e,offset);
                if(inner&&e>localStart&&d<localStart+blade) {
                    double open=r.branch==0?position:1-position;
                    Track stock=r.branch==0?j.b():j.a();
                    var bladeA=TurnoutFrame.blade(r.track,stock,d,r.sign,offset,p.headWidth(),open,localStart,blade,s);
                    var bladeB=TurnoutFrame.blade(r.track,stock,e,r.sign,offset,p.headWidth(),open,localStart,blade,s);
                    mesh.blade(bladeA.point(),bladeB.point(),bladeA.cut(),bladeB.cut(),p,s);continue;
                }
                // The outer branch follows its native node-centred curve. A straight bridge
                // from the common road to the blade toe creates a visible spike on curves.
                mesh.rail(a,b,1,1,p,s,"rail",new Mesh.RailTag(r.track,d,e,r.sign*offset));
            }
        }
        if(j.kind()==Junction.Kind.Y) {
            crossing.build(mesh,position);
            // A paired stretcher bar translates with the blades, entirely in the visual mesh.
            double at=bladeStart+Math.min(1,blade/3);V3 origin=j.a().at(at),forward=j.a().tangent(at);
            V3 a=TurnoutFrame.contact(j.a(),j.b(),at,side,offset,p.headWidth(),position,bladeStart,blade,s,origin,forward);
            V3 b=TurnoutFrame.contact(j.b(),j.a(),j.b().nearest(origin),-side,offset,p.headWidth(),1-position,secondStart,blade,s,origin,forward);
            mesh.beam(a,b,.09,.09,p.top()+s.verticalOffset()-.07,p.top()+s.verticalOffset()-.03,p.steel(),"stretcher",-1);
        }
        sleepers(mesh,j,s,p,extent,boundary);
        EndSleepers.finish(mesh,j,s,p,boundary);
        SleeperEdits.finish(mesh,j,s,p);
        return mesh;
    }
    private static V3 running(Running r,double s,double offset){return r.track.at(s).add(r.track.tangent(s).lateral().mul(r.sign*offset));}
    public static V3 sleeperNormal(Junction j,PointSettings s,double distance,double start,double end){
        double t=Math.max(0,Math.min(1,(distance-start)/Math.max(.001,end-start)));
        Track reference=SleeperEdits.path(j.tracks(),s.sleeperPath());V3 sample=j.a().at(Math.max(0,Math.min(j.a().length,distance)));
        double at=reference.nearest(sample),begin=reference.nearest(j.a().at(Math.max(0,Math.min(j.a().length,start))));
        V3 direction=s.sleeperMode()==0?reference.tangent(at):reference.tangent(begin);
        double blend=s.sleeperMode()==2?t:s.sleeperMode()==3?t*t*(3-2*t):0;
        double angle=Math.toRadians(s.sleeperAngle()+(s.sleeperEndAngle()-s.sleeperAngle())*blend);
        V3 n=direction.lateral();return new V3(n.x()*Math.cos(angle)-n.z()*Math.sin(angle),0,n.x()*Math.sin(angle)+n.z()*Math.cos(angle));
    }
    /** Preserve the interior rhythm and distribute only the final two gaps to the native last tie. */
    public static List<Double> sleeperDistances(double last,double spacing){
        var rows=new ArrayList<Double>();double first=spacing/2;
        if(last<=first){rows.add(Math.max(0,last));return rows;}
        for(double d=first;d<last-2*spacing;d+=spacing)rows.add(d);
        double anchor=rows.isEmpty()?first:rows.get(rows.size()-1);
        if(rows.isEmpty())rows.add(anchor);
        int count=Math.max(1,(int)Math.ceil((last-anchor)/spacing));
        for(int i=1;i<=count;i++)rows.add(anchor+(last-anchor)*i/count);
        return rows;
    }
    private static void sleepers(Mesh m,Junction j,PointSettings s,Profile p,double extent,YBoundary boundary) {
        if(j.kind()==Junction.Kind.DIAMOND&&s.sleeperMode()==4){VSleepers.diamond(m,j,s,p,extent);return;}
        Track base=j.a();double start=j.kind()==Junction.Kind.Y?0:j.sa()-extent,end=j.kind()==Junction.Kind.Y?extent:j.sa()+extent;
        int index=0;
        List<Double> rows=j.kind()==Junction.Kind.Y?sleeperDistances(boundary.aLast(),s.sleeperSpacing()):new ArrayList<>();
        if(j.kind()!=Junction.Kind.Y)for(double d=start+s.sleeperSpacing()/2;d<end;d+=s.sleeperSpacing())rows.add(d);
        for(double d:rows) {
            double shifted=d+s.sleeperShifts().getOrDefault(index,0D);
            if(j.kind()==Junction.Kind.Y&&s.sleeperMode()==4){double blend=Math.max(0,Math.min(1,(d-boundary.aLast()+2*s.sleeperSpacing())/(2*s.sleeperSpacing())));
                double other=shifted+(boundary.bLast()-boundary.aLast())*blend;
                VSleepers.add(m,j,s,p,shifted,other,extent,index++);continue;}
            V3 c=base.at(shifted),n=sleeperNormal(j,s,shifted,start,end);
            double lo=-p.centerOffset()-s.sleeperOverhang(),hi=-lo;
            if(j.kind()==Junction.Kind.Y) {
                V3 other=j.b().at(Math.min(j.b().length,shifted)); double lateral=other.sub(c).dot(n);
                lo=Math.min(lo,lateral-p.centerOffset()-s.sleeperOverhang());hi=Math.max(hi,lateral+p.centerOffset()+s.sleeperOverhang());
                c=c.add(0,(other.y()-c.y())/2,0);
            } else {
                // Slice the second track's footprint in this bearer's plane. One unified family
                // carries both roads, including perpendicular diamonds, with no crossed ties.
                V3 forward=new V3(n.z(),0,-n.x());double width=p.centerOffset()+s.sleeperOverhang();
                V3 b0=j.b().at(j.sb()-extent),b1=j.b().at(j.sb()+extent),n0=j.b().tangent(j.sb()-extent).lateral(),n1=j.b().tangent(j.sb()+extent).lateral();
                V3[] corners={b0.add(n0.mul(-width)),b1.add(n1.mul(-width)),b1.add(n1.mul(width)),b0.add(n0.mul(width))};
                for(int k=0;k<4;k++){V3 u=corners[k].sub(c),v=corners[(k+1)%4].sub(c);double du=u.dot(forward),dv=v.dot(forward);if(du*dv<=0&&Math.abs(du-dv)>1e-8){double lateral=u.lerp(v,du/(du-dv)).dot(n);lo=Math.min(lo,lateral);hi=Math.max(hi,lateral);}}
            }
            double top=Math.max(.03,p.top()-p.railHeight())+s.verticalOffset();
            // Intersect the actual four running rails with the bearer plane, so rotated
            // ties and supports remain directly under the rails instead of drifting sideways.
            V3 forward=new V3(n.z(),0,-n.x());var seats=new ArrayList<V3>();
            for(Track road:List.of(j.a(),j.b()))for(int sign:new int[]{-1,1}){
                double near=road.nearest(c);
                for(int k=0;k<5;k++){V3 at=road.at(near).add(road.tangent(near).lateral().mul(sign*p.centerOffset()));double denominator=road.tangent(near).dot(forward);if(Math.abs(denominator)<.1)break;near=Math.max(0,Math.min(road.length,near-at.sub(c).dot(forward)/denominator));}
                V3 seat=road.at(near).add(road.tangent(near).lateral().mul(sign*p.centerOffset()));
                if(Math.abs(seat.sub(c).dot(forward))<.15&&seats.stream().noneMatch(v->v.distance(seat)<.18)){seats.add(seat);double lateral=seat.sub(c).dot(n);lo=Math.min(lo,lateral-s.sleeperOverhang());hi=Math.max(hi,lateral+s.sleeperOverhang());}
            }
            if(SleeperEdits.split(s,index)){
                for(Track road:j.tracks())ordinarySleeper(m,road,road.nearest(c),n,s,p,index);
            }else if(p.detail()!=null){
                if(!p.detail().siding())p.detail().bearer(m,c,n,lo,hi,s,p,index);
                for(V3 seat:seats)p.detail().fitting(m,seat,n,s,p,index);
            }else m.beam(c.add(n.mul(lo)),c.add(n.mul(hi)),s.sleeperWidth(),s.sleeperWidth(),top-s.sleeperHeight(),top,p.sleeper(),"sleeper",index);
            index++;
        }
    }
    private static void ordinarySleeper(Mesh mesh,Track road,double distance,V3 normal,PointSettings s,Profile p,int index){
        V3 center=road.at(distance);double half=p.centerOffset()+s.sleeperOverhang();
        if(p.detail()!=null){if(!p.detail().siding())p.detail().bearer(mesh,center,normal,-half,half,s,p,index);for(int sign:new int[]{-1,1})p.detail().fitting(mesh,center.add(normal.mul(sign*p.centerOffset())),normal,s,p,index);}
        else {double top=p.top()-p.railHeight()+s.verticalOffset();mesh.beam(center.sub(normal.mul(half)),center.add(normal.mul(half)),s.sleeperWidth(),s.sleeperWidth(),top-s.sleeperHeight(),top,p.sleeper(),"sleeper",index);}
    }
}
