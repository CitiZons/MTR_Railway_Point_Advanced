package org.mtrpoint.geometry;

import java.util.*;

/** Cross sections are swept along the actual sampled MTR curves. No simulation geometry is edited. */
public final class PointMesh {
    private record Running(Track track,double sign,int branch) {}
    public static double extent(Junction j,PointSettings s){return Math.min(j.tracks().stream().mapToDouble(t->t.length).min().orElse(0),j.extent()*s.lengthScale());}
    public record YBoundary(double aEnd,double bEnd,double aLast,double bLast,double thirdEnd,double thirdLast,double aStart,double bStart,double thirdStart) {
        public YBoundary(double aEnd,double bEnd,double aLast,double bLast){this(aEnd,bEnd,aLast,bLast,(aEnd+bEnd)/2,(aLast+bLast)/2,0,0,0);}
        public YBoundary(double aEnd,double bEnd,double aLast,double bLast,double thirdEnd,double thirdLast){this(aEnd,bEnd,aLast,bLast,thirdEnd,thirdLast,0,0,0);}
        public static YBoundary nominal(Junction j,PointSettings s){double end=extent(j,s),last=Math.max(s.sleeperSpacing()/2,end-s.sleeperSpacing()/2);return new YBoundary(end,end,last,last);}
        /** The station this road starts being drawn at, and the end plus the far sleeper row. */
        public YBoundary window(double aStart,double bStart,double thirdStart){
            return new YBoundary(aEnd,bEnd,aLast,bLast,thirdEnd,thirdLast,aStart,bStart,thirdStart);
        }
        public YBoundary withEnds(double aEnd,double bEnd,double thirdEnd){
            return new YBoundary(aEnd,bEnd,aLast,bLast,thirdEnd,thirdLast,aStart,bStart,thirdStart);
        }
    }
    /** A diamond crossing runs through its junction instead of starting at it, so its steel is
     *  bounded by a station window per road. The window follows the native repeat cells: the mod
     *  draws exactly the cells the renderer hides and MTR keeps the rest, on both sides. */
    public record DiamondBoundary(double[][] windows) {
        public DiamondBoundary(double[] a,double[] b){this(new double[][]{a,b});}
        public double[] window(int road){return windows[Math.min(road,windows.length-1)];}
        public int roads(){return windows.length;}
        public static DiamondBoundary nominal(Junction j,PointSettings s){
            double limit=extent(j,s);
            return new DiamondBoundary(new double[]{j.sa()-limit,j.sa()+limit},new double[]{j.sb()-limit,j.sb()+limit});
        }
    }
    public static Mesh build(Junction j,PointSettings s,Profile raw,double position) {
        return build(j,s,raw,position,YBoundary.nominal(j,s));
    }
    /** A plain crossing hands over to the native rail on the edges of the repeat cells the renderer
     *  hides, so the mod's own window is passed in instead of the nominal radius. */
    public static Mesh build(Junction j,PointSettings s,Profile raw,double position,DiamondBoundary diamond) {
        if(j.kind()!=Junction.Kind.DIAMOND)return build(j,s,raw,position,YBoundary.nominal(j,s));
        Mesh mesh=new Mesh();if(!s.enabled())return mesh;Profile p=raw.tune(s);double extent=extent(j,s);
        DiamondGeometry.build(mesh,j,s,p,extent,diamond==null?DiamondBoundary.nominal(j,s):diamond);
        sleepers(mesh,j,s,p,extent,YBoundary.nominal(j,s));SleeperEdits.finish(mesh,j,s,p);return mesh;
    }
    /** A crossing point shared by several rails: one mesh draws every road that meets there and the
     *  flange channels of all of them cut it, so nothing is drawn twice and no channel keeps steel. */
    public static Mesh centre(Junction owner,List<Track> roads,DiamondBoundary windows,List<Track> channelRoads,PointSettings s,Profile raw,double position) {
        Mesh mesh=new Mesh();if(!s.enabled())return mesh;Profile p=raw.tune(s);double extent=extent(owner,s);
        DiamondGeometry.centre(mesh,owner,s,p,extent,roads,windows,channelRoads);
        sleepers(mesh,owner,s,p,extent,YBoundary.nominal(owner,s));SleeperEdits.finish(mesh,owner,s,p);return mesh;
    }
    public static Mesh build(Junction j,PointSettings s,Profile raw,double position,YBoundary boundary) {
        if(j.kind()==Junction.Kind.THREE)return ThreeWayMesh.build(j,s,raw,position,boundary);
        Mesh mesh=new Mesh();if(!s.enabled())return mesh;Profile p=raw.tune(s);
        double extent=extent(j,s),offset=p.centerOffset();
        if(j.kind()==Junction.Kind.DIAMOND){DiamondGeometry.build(mesh,j,s,p,extent,DiamondBoundary.nominal(j,s));sleepers(mesh,j,s,p,extent,boundary);SleeperEdits.finish(mesh,j,s,p);return mesh;}
        List<Running> runs=List.of(new Running(j.a(),-1,0),new Running(j.a(),1,0),new Running(j.b(),-1,1),new Running(j.b(),1,1));
        double frog=extent*.65;
        if(j.kind()==Junction.Kind.Y)for(double d=.25;d<=extent;d+=.1)if(j.a().at(d).distance(j.b().at(d))>2*offset){frog=d;break;}
        frog=Math.max(1,Math.min(extent-1,frog+s.frogShift()));
        FrogGeometry crossing=j.kind()==Junction.Kind.Y?new FrogGeometry(j,s,p,extent):null;
        if(crossing!=null)frog=(crossing.sa+crossing.sb)/2;
        double bladeStart=TurnoutFrame.start(j,extent);
        double blade=s.bladeLength()>0?s.bladeLength():Math.max(2,Math.min((frog-bladeStart)*.65,9));
        double secondStart=j.b().nearest(j.a().at(bladeStart));
        // Only the tip contact length is planed; the rest of the switch rail keeps its full section.
        double contact=TurnoutFrame.contact(blade,p,s);
        // Determine which branch lies to the positive lateral side in the common frame.
        double side=TurnoutFrame.side(j,extent);
        for(Running r:runs) {
            double c=r.branch==0?j.sa():j.sb(),start=j.kind()==Junction.Kind.Y?(r.branch==0?boundary.aStart():r.branch==1?boundary.bStart():boundary.thirdStart()):Math.max(0,c-extent),end=j.kind()==Junction.Kind.Y?(r.branch==0?boundary.aEnd():r.branch==1?boundary.bEnd():boundary.thirdEnd()):Math.min(r.track.length,c+extent);
            boolean inner=j.kind()==Junction.Kind.Y&&r.sign==(r.branch==0?side:-side);
            double localStart=r.branch==0?bladeStart:secondStart;
            double step=.24; int count=(int)Math.ceil((end-start)/step);
            for(int i=0;i<count;i++) {
                double d=start+(end-start)*i/count,e=start+(end-start)*(i+1)/count;
                if(r.branch==1&&e<=localStart)continue;
                if(inner&&crossing!=null){double toe=crossing.toe(r.branch),heel=crossing.heel(r.branch);if(d>=toe&&e<=heel)continue;if(d<toe&&e>toe)e=toe;else if(d<heel&&e>heel)d=heel;}
                V3 a=running(r,d,offset),b=running(r,e,offset);
                double ta=1,tb=1;
                boolean planed=inner&&e>localStart&&d<localStart+blade;
                if(planed) {
                    // The switch rail is the route rail on its own side, so the fixed stock rail is
                    // never drawn beside it: not here, not at the tip, not in any blade position.
                    // Drawing one was the "extra thin length of rail hugging the real one" defect.
                    double open=r.branch==0?position:1-position;
                    ta=TurnoutFrame.taper(d-localStart,contact);tb=TurnoutFrame.taper(e-localStart,contact);
                    V3 ba=switchRail(r,d,ta,offset,open,localStart,blade,p,s),bb=switchRail(r,e,tb,offset,open,localStart,blade,p,s);
                    mesh.rail(ba,bb,ta,tb,p,s,"blade");
                } else {
                    mesh.rail(a,b,ta,tb,p,s,"rail");
                }
            }
        }
        if(j.kind()==Junction.Kind.Y) {
            crossing.build(mesh,position);
            // A paired stretcher bar translates with the blades, entirely in the visual mesh.
            double at=bladeStart+Math.min(1,blade/3);V3 origin=j.a().at(at),forward=j.a().tangent(at);
            V3 a=TurnoutFrame.contact(j.a(),at,side,offset,position,bladeStart,blade,s,origin,forward);
            V3 b=TurnoutFrame.contact(j.b(),j.b().nearest(origin),-side,offset,1-position,secondStart,blade,s,origin,forward);
            mesh.beam(a,b,.09,.09,p.top()+s.verticalOffset()-.07,p.top()+s.verticalOffset()-.03,p.steel(),"stretcher",-1);
        }
        sleepers(mesh,j,s,p,extent,boundary);
        EndSleepers.finish(mesh,j,s,p,boundary);
        SleeperEdits.finish(mesh,j,s,p);
        return mesh;
    }
    private static V3 running(Running r,double s,double offset){return r.track.at(s).add(r.track.tangent(s).lateral().mul(r.sign*offset));}
    /** The switch rail runs with its gauge face flush against the stock rail's gauge face, so its
     *  centre stands half its own planed width off the running line, and a thrown point swings it
     *  into the gauge by the throw. */
    private static V3 switchRail(Running r,double station,double taper,double offset,double open,double start,double length,Profile p,PointSettings s){
        return TurnoutFrame.blade(r.track,station,r.sign,offset-p.headWidth()/2+taper*p.headWidth()/2,open,start,length,s);
    }
    public static V3 sleeperNormal(Junction j,PointSettings s,double distance,double start,double end){
        double t=Math.max(0,Math.min(1,(distance-start)/Math.max(.001,end-start)));
        V3 direction;
        if(s.sleeperPath()==0)direction=s.sleeperMode()==0?j.a().tangent(distance):j.a().tangent(start);
        else {Track reference=SleeperEdits.path(j.tracks(),s.sleeperPath());
            V3 sample=j.a().at(Math.max(0,Math.min(j.a().length,distance)));
            double at=reference.nearest(sample),begin=reference.nearest(j.a().at(Math.max(0,Math.min(j.a().length,start))));
            direction=s.sleeperMode()==0?reference.tangent(at):reference.tangent(begin);}
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
