package org.mtrpoint.geometry;

import java.util.*;

/** Common crossing derived from the two inner rail centre lines, entirely client geometry. */
public final class FrogGeometry {
    private final Junction j;private final PointSettings s;private final Profile p;
    public final double sa,sb;private final double side,extent,tail;
    /** A direction-safe fixed wing interval with optional exterior start/end blends. */
    public record WingRun(Track road,double start,double startFlareAt,double endAt,double flareAt,
                          double startOffset,double offset,double endOffset,boolean capStart,boolean capEnd) {
        /** Compatibility constructor for a constant run with an optional terminal flare. */
        public WingRun(Track road,double start,double endAt,double flareAt,double offset,double endOffset,boolean capStart,boolean capEnd){
            this(road,start,start,endAt,flareAt,offset,offset,endOffset,capStart,capEnd);
        }
        public double end(){return flareAt;}
        public double baseStart(){return startFlareAt;}
        public double baseEnd(){return endAt;}
        public WingRun canonical(){
            if(road.startNode.compareTo(road.endNode)<=0)return this;
            return new WingRun(road.reverse(),road.length-flareAt,road.length-endAt,road.length-startFlareAt,road.length-start,
                -endOffset,-offset,-startOffset,capEnd,capStart);
        }
    }
    public FrogGeometry(Junction j,PointSettings s,Profile p,double extent){
        this.j=j;this.s=s;this.p=p;this.extent=extent;
        side=TurnoutFrame.side(j,extent);
        double a=extent*.6,b=a,best=Double.MAX_VALUE;
        // Seed from the closest actual offset-rail pair, including a long common approach.
        for(double d=.25;d<extent;d+=.25){V3 point=inner(0,d);double e=j.b().nearest(point);double error=point.distance(inner(1,e));if(error<best){best=error;a=d;b=e;}}
        for(int i=0;i<18;i++){
            V3 delta=inner(1,b).sub(inner(0,a)),u=j.a().tangent(a),v=j.b().tangent(b);
            double den=V3.crossXZ(u,v);if(Math.abs(den)<1e-6)break;
            a=clamp(a+V3.crossXZ(delta,v)/den,.5,extent-.5);
            b=clamp(b+V3.crossXZ(delta,u)/den,.5,extent-.5);
        }
        sa=clamp(a+s.frogShift(),.5,extent-.5);sb=clamp(b+s.frogShift(),.5,extent-.5);
        double sine=Math.abs(V3.crossXZ(j.a().tangent(sa),j.b().tangent(sb)));
        tail=clamp(1.1+p.headWidth()/Math.max(.04,sine)+s.noseLengthDelta(),.65,4.5);
    }
    public double toe(int branch){return Math.max(.1,at(branch)-1.45-s.wingLengthDelta()*.4+s.wingShift());}
    public double heel(int branch){return Math.min(extent,at(branch)+tail);}
    private double at(int branch){return branch==0?sa:sb;}
    private Track track(int branch){return branch==0?j.a():j.b();}
    private double sign(int branch){return branch==0?side:-side;}
    private V3 inner(int branch,double d){Track t=track(branch);return t.at(d).add(t.tangent(d).lateral().mul(sign(branch)*p.centerOffset()));}
    public V3 center(){return inner(0,sa).lerp(inner(1,sb),.5);}
    /** Moving insert only; the adjoining full-width heart rails belong to the shared assembly. */
    public Mesh movableNose(double position){
        Mesh complete=new Mesh();build(complete,position);Mesh nose=new Mesh();
        int count=12*(p.detail()!=null&&!p.detail().rails().isEmpty()?p.detail().rails().size():18);
        for(var q:complete.quads)if(q.part().equals("frog")&&nose.quads.size()<count)nose.quad(q);
        return nose;
    }
    public void build(Mesh m,double position){
        V3 center=inner(0,sa).lerp(inner(1,sb),.5);
        V3 forward=j.a().tangent(sa).add(j.b().tangent(sb)).unit(),normal=forward.lateral();
        double sine=Math.max(.04,Math.abs(V3.crossXZ(j.a().tangent(sa),j.b().tangent(sb))));
        double setback=Math.min(tail*.55,p.headWidth()/sine*.65);
        double gap=Math.max(.02,s.flangeway()+s.wingGapDelta());
        V3 tip=center.add(forward.mul(setback));
        if(s.movableFrog()){
            tip=tip.add(forward.mul(-.35));
            V3[] contacts=new V3[2];
            for(int branch=0;branch<2;branch++){
                Wing w=wing(branch,gap);double d=(w.kneeOther+w.endAt)/2;
                for(int k=0;k<8;k++){
                    V3 point=wingPoint(1-branch,d,gap,0),u=wingPoint(1-branch,d+.01,gap,0).sub(wingPoint(1-branch,d-.01,gap,0)).mul(50);
                    double den=u.dot(forward);if(Math.abs(den)<.1)break;
                    d=clamp(d-point.sub(tip).dot(forward)/den,w.kneeOther,w.endAt);
                }
                double step=(w.endAt-w.kneeOther)/12,cell=clamp(Math.floor((d-w.kneeOther)/step),0,11);
                V3 a=wingPoint(1-branch,w.kneeOther+step*cell,gap,0),b=wingPoint(1-branch,w.kneeOther+step*(cell+1),gap,0);
                V3 tangent=b.sub(a).unit(),outward=tangent.lateral().mul(-sign(1-branch));
                contacts[branch]=a.lerp(b,clamp((d-w.kneeOther-step*cell)/step,0,1)).sub(outward.mul(p.headWidth()*.505));
            }
            tip=contacts[0].lerp(contacts[1],position);
        }
        // Solid tapered crossing point, then two heart rails continuing to their actual heels.
        // A single nose avoids the hollow V left by overlaying two narrow tapered rails.
        double neck=Math.min(tail*.7,Math.max(setback+.18,p.footWidth()/sine));
        V3 rootA=inner(0,Math.min(heel(0),sa+neck)),rootB=inner(1,Math.min(heel(1),sb+neck));
        V3 root=rootA.lerp(rootB,.5);
        if(!s.movableFrog())fixedHeart(m,center,forward,sine);
        else segment(m,tip,root,.015,(rootA.distance(rootB)+p.headWidth())/p.headWidth(),"frog");
        for(int branch=0;branch<2;branch++){
            V3 end=inner(branch,heel(branch));
            if(s.movableFrog())segment(m,branch==0?rootA:rootB,end,1,1,"frog");
            int road=branch;Wing w=wing(road,gap);
            sweep(m,t->inner(road,toe(road)+(w.kneeIncoming-toe(road))*t),false);
            sweep(m,t->wingPoint(1-road,w.kneeOther+(w.endAt-w.kneeOther)*t,gap,0),false);
            sweep(m,t->wingPoint(1-road,w.endAt+(w.flareAt-w.endAt)*t,gap,.075*t),true);
            guards(m,branch);
        }
    }
    private record Wing(double kneeIncoming,double kneeOther,double endAt,double flareAt) {}
    private V3 wingPoint(int other,double d,double gap,double flare){
        return inner(other,d).add(track(other).tangent(d).lateral().mul(-sign(other)*(p.headWidth()+gap+flare)));
    }
    private Wing wing(int branch,double gap){
        int other=1-branch;double a=at(branch),b=at(other);
        // Intersect the actual incoming rail and the opposite rail's offset curve.
        // A tangent-line intersection is not on either curve for an asymmetric turnout.
        for(int i=0;i<20;i++){
            V3 delta=wingPoint(other,b,gap,0).sub(inner(branch,a));
            V3 u=inner(branch,a+.01).sub(inner(branch,a-.01)).mul(50);
            V3 v=wingPoint(other,b+.01,gap,0).sub(wingPoint(other,b-.01,gap,0)).mul(50);
            double den=V3.crossXZ(u,v);if(Math.abs(den)<1e-7)break;
            a=clamp(a+V3.crossXZ(delta,v)/den,toe(branch),extent-.3);
            b=clamp(b+V3.crossXZ(delta,u)/den,0,extent-.3);
            if(delta.length()<1e-8)break;
        }
        double endAt=clamp(at(other)+tail+.35+s.wingLengthDelta()+s.wingShift(),b+.05,extent-.25);
        return new Wing(a,b,endAt,Math.min(extent,endAt+.3));
    }
    private void sweep(Mesh m,java.util.function.DoubleFunction<V3> path,boolean capEnd){
        for(int i=0;i<12;i++)m.rail(path.apply(i/12D),path.apply((i+1)/12D),1,1,p,s,"wing");
        if(capEnd){V3 end=path.apply(1),before=path.apply(11/12D);m.railCap(end,end.sub(before),1,p,s,"wing",false);}
    }
    private void fixedHeart(Mesh m,V3 center,V3 forward,double sine){
        // Keep both original rail sections at full width. Trim their overlap at the common
        // bisector: only after the inner head edges meet does the union form a solid V nose.
        // This also preserves the native web/foot widths instead of scaling an entire rail.
        V3 plane=forward.lateral();
        double reach=Math.max(p.footWidth(),p.headWidth())/sine+1;
        for(int branch=0;branch<2;branch++){
            double end=heel(branch),start=Math.max(0,at(branch)-reach);
            V3 keep=inner(branch,end).sub(center).dot(plane)>0?plane.mul(-1):plane;
            int count=Math.max(1,(int)Math.ceil((end-start)/.16));
            for(int i=0;i<count;i++){
                double a=start+(end-start)*i/count,b=start+(end-start)*(i+1)/count;
                Mesh section=new Mesh();section.rail(inner(branch,a),inner(branch,b),1,1,p,s,"frog");
                for(var q:section.quads)Mesh.clip(m,q,center,keep);
            }
        }
    }
    private void guards(Mesh m,int branch){
        m.quads.addAll(guard(branch).mesh().quads);
    }
    public GuardRails.Run guard(int branch){
        Track t=track(branch);double length=Math.max(.5,2.8+s.guardLengthDelta());
        double start=Math.max(0,at(branch)+s.guardShift()-length/2),end=Math.min(extent,start+length);
        // Check rails lie inside the OUTER stock rail. Both ends flare farther into the track.
        double offset=p.centerOffset()-p.headWidth()-Math.max(.02,s.flangeway()+s.guardGapDelta());
        return new GuardRails.Run(t,start,end,-sign(branch)*offset,true,true,p,s);
    }
    public static List<WingRun> mergeWings(List<WingRun> input){
        var sorted=input.stream().map(WingRun::canonical)
            .sorted(Comparator.comparing((WingRun w)->w.road().id).thenComparingDouble(WingRun::offset).thenComparingDouble(WingRun::start)).toList();
        var result=new ArrayList<WingRun>();
        for(var wing:sorted){
            int match=-1;
            for(int i=result.size()-1;i>=0;i--){
                WingRun prior=result.get(i);
                if(!prior.road().id.equals(wing.road().id)||Math.abs(prior.offset()-wing.offset())>1e-5)continue;
                if(prior.flareAt()>=wing.start()-1e-7&&wing.flareAt()>=prior.start()-1e-7){match=i;break;}
                if(prior.start()<wing.start()-1e-7)break;
            }
            if(match<0){result.add(wing);continue;}
            WingRun prior=result.get(match),early=prior.start()<=wing.start()?prior:wing,late=prior.flareAt()>=wing.flareAt()?prior:wing;
            result.set(match,new WingRun(prior.road(),early.start(),early.startFlareAt(),late.endAt(),late.flareAt(),
                early.startOffset(),prior.offset(),late.endOffset(),early.capStart(),late.capEnd()));
        }
        return List.copyOf(result);
    }
    public java.util.List<WingRun> fixedWings(){
        double gap=Math.max(.02,s.flangeway()+s.wingGapDelta());var result=new java.util.ArrayList<WingRun>();
        for(int branch=0;branch<2;branch++){
            int other=1-branch;Wing wing=wing(branch,gap);double incomingOffset=sign(branch)*p.centerOffset();
            result.add(new WingRun(track(branch),toe(branch),wing.kneeIncoming,wing.kneeIncoming,incomingOffset,incomingOffset,false,false));
            double offset=sign(other)*(p.centerOffset()-p.headWidth()-gap);
            result.add(new WingRun(track(other),wing.kneeOther,wing.endAt,wing.flareAt,offset,offset-sign(other)*.075,false,true));
        }
        return java.util.List.copyOf(result);
    }
    private void segment(Mesh m,V3 a,V3 b,double wa,double wb,String part){
        int n=12; // Fixed topology while the heart moves between its two endpoints.
        for(int i=0;i<n;i++){double u=(double)i/n,v=(double)(i+1)/n;m.rail(a.lerp(b,u),a.lerp(b,v),wa+(wb-wa)*u,wa+(wb-wa)*v,p,s,part);}
    }
    private static double clamp(double v,double a,double b){return Math.max(a,Math.min(b,v));}
}
