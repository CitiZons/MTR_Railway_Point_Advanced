package org.mtrpoint.geometry;

/** Common crossing derived from the two inner rail centre lines, entirely client geometry. */
public final class FrogGeometry {
    private final Junction j;private final PointSettings s;private final Profile p;private final boolean editableCheckWings;
    public final double sa,sb;private final double side,extent,tail;
    public FrogGeometry(Junction j,PointSettings s,Profile p,double extent){this(j,s,p,extent,true);}
    public FrogGeometry(Junction j,PointSettings s,Profile p,double extent,boolean editableCheckWings){
        this.j=j;this.s=s;this.p=p;this.extent=extent;this.editableCheckWings=editableCheckWings;
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
    public double knee(int branch){return wing(branch,Math.max(.02,s.flangeway()+s.wingGapDelta())).kneeIncoming;}
    public double crossingStation(int branch){return at(branch);}
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
            V3 kneeNormal=track(road).tangent(w.kneeIncoming).lateral().add(track(1-road).tangent(w.kneeOther).lateral()).unit();
            sweep(m,t->inner(road,toe(road)+(w.kneeIncoming-toe(road))*t),track(road).tangent(toe(road)).lateral(),kneeNormal);
            // Retain preview geometry, but mark the check-side wing for replacement by the
            // world interval pool. Its incoming closure and knee extension stay connected.
            if(!editableCheckWings||!s.guardEdits().containsKey(2+road)){
                int first=m.quads.size();
                V3 endNormal=wingPoint(1-road,w.endAt+.01,gap,0).sub(wingPoint(1-road,w.endAt-.01,gap,0)).lateral();
                sweep(m,t->wingPoint(1-road,w.kneeOther+(w.endAt-w.kneeOther)*t,gap,0),kneeNormal,endNormal);
                sweep(m,t->wingPoint(1-road,w.endAt+(w.flareAt-w.endAt)*t,gap,.075*t),endNormal,null);
                V3 mouth=wingPoint(1-road,w.flareAt,gap,.075),before=wingPoint(1-road,w.flareAt-.01,gap,.075);
                m.railCap(mouth,mouth.sub(before),1,p,s,"wing",false);
                for(int i=first;i<m.quads.size();i++){
                    var q=m.quads.get(i);m.quads.set(i,new Mesh.Quad(q.a(),q.b(),q.c(),q.d(),q.surface(),q.part(),-2,q.uv()));
                }
            }
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
    private void sweep(Mesh m,java.util.function.DoubleFunction<V3> path){
        sweep(m,path,null,null);
    }
    private void sweep(Mesh m,java.util.function.DoubleFunction<V3> path,V3 startNormal,V3 endNormal){
        for(int i=0;i<12;i++){
            double a=i/12D,b=(i+1)/12D;
            V3 na=i==0&&startNormal!=null?startNormal:path.apply(a+.0001).sub(path.apply(a-.0001)).lateral();
            V3 nb=i==11&&endNormal!=null?endNormal:path.apply(b+.0001).sub(path.apply(b-.0001)).lateral();
            m.rail(path.apply(a),path.apply(b),na,nb,1,1,p,s,"wing");
        }
    }
    private void fixedHeart(Mesh m,V3 center,V3 forward,double sine){
        // Keep both original rail sections at full width. Trim their overlap at the common
        // bisector: only after the inner head edges meet does the union form a solid V nose.
        // This also preserves the native web/foot widths instead of scaling an entire rail.
        V3 plane=forward.lateral();
        double reach=Math.max(p.footWidth(),p.headWidth())/sine+1;
        for(int branch=0;branch<2;branch++){
            // The heart owns the heel side of the bisector. The incoming wing is clipped
            // to the other side, so preserve the whole heart through its actual heel.
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
    /** The same full-section V nose used by an ordinary turnout. */
    public Mesh fixedHeart(){
        Mesh mesh=new Mesh();V3 center=inner(0,sa).lerp(inner(1,sb),.5);
        V3 forward=j.a().tangent(sa).add(j.b().tangent(sb)).unit();
        double sine=Math.max(.04,Math.abs(V3.crossXZ(j.a().tangent(sa),j.b().tangent(sb))));
        fixedHeart(mesh,center,forward,sine);return mesh;
    }
    private V3 fittingTip;
    public V3 fittingTip(){
        if(fittingTip==null){V3 forward=fittingForward();
            fittingTip=fixedHeart().quads.stream().flatMap(q->java.util.stream.Stream.of(q.a(),q.b(),q.c(),q.d()))
                .filter(v->Math.abs(v.y()-p.top()-s.verticalOffset()-(inner(0,sa).y()+inner(1,sb).y())/2)<.02)
                .min(java.util.Comparator.comparingDouble(v->v.dot(forward))).orElse(center().add(0,p.top()+s.verticalOffset(),0));
        }return fittingTip;
    }
    public V3 fittingForward(){return j.a().tangent(sa).add(j.b().tangent(sb)).unit();}
    public double fittingClearLength(){double sine=Math.max(.04,Math.abs(V3.crossXZ(j.a().tangent(sa),j.b().tangent(sb))));return Math.min(1.2,Math.max(.4,p.footWidth()/sine));}
    public record FittingEdge(V3 origin,V3 outward) {}
    /** The two outside foot edges enclose only the interior of the V heart. */
    public FittingEdge fittingEdge(int branch){
        V3 origin=inner(branch,at(branch)),outward=track(branch).tangent(at(branch)).lateral();
        V3 inside=inner(0,heel(0)).lerp(inner(1,heel(1)),.5);
        if(inside.sub(origin).dot(outward)>0)outward=outward.mul(-1);
        return new FittingEdge(origin.add(outward.mul(p.footWidth()/2)),outward);
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
    /** Check-side wing of the crossing, indexed after the two ordinary guards. */
    public GuardRails.Run checkWing(int branch){
        int other=1-branch;double gap=Math.max(.02,s.flangeway()+s.wingGapDelta());Wing w=wing(branch,gap);
        double offset=sign(other)*(p.centerOffset()-p.headWidth()-gap);
        return new GuardRails.Run(track(other),w.kneeOther,w.flareAt,offset,false,true,p,s,"","wing",true,false);
    }
    private void segment(Mesh m,V3 a,V3 b,double wa,double wb,String part){
        int n=12; // Fixed topology while the heart moves between its two endpoints.
        for(int i=0;i<n;i++){double u=(double)i/n,v=(double)(i+1)/n;m.rail(a.lerp(b,u),a.lerp(b,v),wa+(wb-wa)*u,wa+(wb-wa)*v,p,s,part);}
    }
    private static double clamp(double v,double a,double b){return Math.max(a,Math.min(b,v));}
}
