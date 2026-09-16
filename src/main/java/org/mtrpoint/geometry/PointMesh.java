package org.mtrpoint.geometry;

import java.util.*;

/** Cross sections are swept along the actual sampled MTR curves. No simulation geometry is edited. */
public final class PointMesh {
    private record Running(Track track,double sign,int branch) {}
    public static double extent(Junction j,PointSettings s){return Math.min(Math.min(j.a().length,j.b().length),j.extent()*s.lengthScale());}
    public static Mesh build(Junction j,PointSettings s,Profile raw,double position) {
        Mesh mesh=new Mesh();if(!s.enabled())return mesh;Profile p=raw.tune(s);
        double extent=extent(j,s),offset=p.centerOffset();
        List<Running> runs=List.of(new Running(j.a(),-1,0),new Running(j.a(),1,0),new Running(j.b(),-1,1),new Running(j.b(),1,1));
        double frog=extent*.65;
        if(j.kind()==Junction.Kind.Y)for(double d=.25;d<=extent;d+=.1)if(j.a().at(d).distance(j.b().at(d))>2*offset){frog=d;break;}
        frog=Math.max(1,Math.min(extent-1,frog+s.frogShift()));
        double blade=s.bladeLength()>0?s.bladeLength():Math.max(2,Math.min(frog*.65,9));
        // Determine which branch lies to the positive lateral side in the common frame.
        double side=j.b().at(Math.min(3,extent)).sub(j.a().at(Math.min(3,extent))).dot(j.a().tangent(0).lateral())>=0?1:-1;
        for(Running r:runs) {
            double c=r.branch==0?j.sa():j.sb(),start=j.kind()==Junction.Kind.Y?0:Math.max(0,c-extent),end=j.kind()==Junction.Kind.Y?extent:Math.min(r.track.length,c+extent);
            boolean inner=j.kind()==Junction.Kind.Y&&r.sign==(r.branch==0?side:-side);
            double step=.12; int count=(int)Math.ceil((end-start)/step);
            for(int i=0;i<count;i++) {
                double d=start+(end-start)*i/count,e=start+(end-start)*(i+1)/count,mid=(d+e)/2;
                V3 a=running(r,d,offset),b=running(r,e,offset);
                // Flangeways are real gaps through the rail profile, not dark painted overlays.
                boolean gap=false;
                for(Running other:runs)if(other.branch!=r.branch) {
                    double near=other.track.nearest(a.lerp(b,.5));
                    double distance=running(other,near,offset).distance(a.lerp(b,.5));
                    if(distance<(p.headWidth()+s.flangeway())*.55 && (j.kind()==Junction.Kind.DIAMOND||mid>blade)) {
                        boolean closeFrog=s.movableFrog()&&j.kind()==Junction.Kind.Y&&inner&&Math.abs(mid-frog)<2&& (r.branch==0?position<.35:position>.65);
                        if(!closeFrog)gap=true;
                    }
                }
                if(gap)continue;
                if(inner&&s.movableFrog()&&Math.abs(mid-frog)<.75)continue;
                double ta=1,tb=1;
                if(inner&&d<blade) {
                    ta=Math.max(.025,Math.min(1,d/blade));tb=Math.max(.025,Math.min(1,e/blade));
                    double open=r.branch==0?position:1-position;
                    a=a.add(r.track.tangent(d).lateral().mul(-r.sign*s.throwDistance()*open*Math.pow(Math.max(0,1-d/blade),2)));
                    b=b.add(r.track.tangent(e).lateral().mul(-r.sign*s.throwDistance()*open*Math.pow(Math.max(0,1-e/blade),2)));
                }
                mesh.rail(a,b,ta,tb,p,s,inner&&d<blade?"blade":"rail");
            }
        }
        if(j.kind()==Junction.Kind.Y) {
            // Tapered common-crossing nose, with a continuous swing for the movable version.
            V3 noseA=j.a().at(frog).add(j.a().tangent(frog).lateral().mul(side*offset));
            V3 noseB=j.b().at(frog).add(j.b().tangent(frog).lateral().mul(-side*offset));
            V3 nose=noseA.lerp(noseB,.5),forward=j.a().tangent(frog).add(j.b().tangent(frog)).unit();
            if(s.movableFrog()){
                V3 backA=j.a().at(frog+.8).add(j.a().tangent(frog+.8).lateral().mul(side*offset));
                V3 backB=j.b().at(frog+.8).add(j.b().tangent(frog+.8).lateral().mul(-side*offset));
                V3 tip=nose.add(forward.mul(-.65)).add(forward.lateral().mul((position-.5)*s.throwDistance()));
                mNose(mesh,tip,backA.lerp(backB,position),p,s);
            }else mNose(mesh,nose.add(forward.mul(.07)),nose.add(forward.mul(.6)),p,s);
            // Wing and check rails bracket the common crossing; bent lead-ins guide wheel flanges.
            for(int branch=0;branch<2;branch++) {
                Track t=branch==0?j.a():j.b();double innerSign=branch==0?side:-side;
                guard(mesh,t,Math.max(blade,frog-1.4),Math.min(extent,frog+1.5),-innerSign,offset-s.flangeway()-p.headWidth(),p,s,runs);
                guard(mesh,t,Math.max(blade,frog-1.4),Math.max(blade+.1,frog-.18),innerSign,offset+s.flangeway()+p.headWidth(),p,s,runs);
            }
            // A paired stretcher bar translates with the blades, entirely in the visual mesh.
            V3 a=j.a().at(Math.min(1,blade/3)),n=j.a().tangent(1).lateral();
            V3 shift=n.mul((position-.5)*s.throwDistance());
            mesh.beam(a.add(n.mul(-offset)).add(shift),a.add(n.mul(offset)).add(shift),.07,.07,p.top()-.09,p.top()-.055,p.steel(),"stretcher",-1);
        } else for(Running r:runs) {
            double c=r.branch==0?j.sa():j.sb();guard(mesh,r.track,c-extent*.7,c+extent*.7,r.sign,offset-s.flangeway()-p.headWidth(),p,s,runs);
        }
        sleepers(mesh,j,s,p,extent);
        return mesh;
    }
    private static void mNose(Mesh mesh,V3 a,V3 b,Profile p,PointSettings s){mesh.rail(a,b,.035,1,p,s,"frog");}
    private static V3 running(Running r,double s,double offset){return r.track.at(s).add(r.track.tangent(s).lateral().mul(r.sign*offset));}
    private static void guard(Mesh m,Track t,double start,double end,double side,double offset,Profile p,PointSettings s,List<Running> roads) {
        for(double d=Math.max(0,start);d<Math.min(t.length,end);d+=.16) {
            double e=Math.min(end,d+.16),flareA=Math.max(0,.25-Math.min(d-start,end-d))*.3,flareB=Math.max(0,.25-Math.min(e-start,end-e))*.3;
            V3 a=t.at(d).add(t.tangent(d).lateral().mul(side*(offset-flareA))),b=t.at(e).add(t.tangent(e).lateral().mul(side*(offset-flareB)));
            V3 mid=a.lerp(b,.5);boolean cut=false;
            for(Running road:roads)if(!road.track.id.equals(t.id)){double near=road.track.nearest(mid);if(running(road,near,p.centerOffset()).distance(mid)<p.headWidth()+s.flangeway())cut=true;}
            if(cut)continue;
            m.rail(a,b,.8,.8,p,s,"guard");
        }
    }
    private static void sleepers(Mesh m,Junction j,PointSettings s,Profile p,double extent) {
        Track base=j.a();double start=j.kind()==Junction.Kind.Y?0:j.sa()-extent,end=j.kind()==Junction.Kind.Y?extent:j.sa()+extent;
        int index=0;
        for(double d=start+s.sleeperSpacing()/2;d<end;d+=s.sleeperSpacing()) {
            double shifted=d+s.sleeperShifts().getOrDefault(index,0D); V3 c=base.at(shifted),n=base.tangent(shifted).lateral();
            double lo=-p.centerOffset()-s.sleeperOverhang(),hi=-lo;
            if(j.kind()==Junction.Kind.Y) {
                V3 other=j.b().at(Math.min(j.b().length,shifted)); double lateral=other.sub(c).dot(n);
                lo=Math.min(lo,lateral-p.centerOffset()-s.sleeperOverhang());hi=Math.max(hi,lateral+p.centerOffset()+s.sleeperOverhang());
                c=c.add(0,(other.y()-c.y())/2,0);
            } else {
                // Slice the second track's footprint in this bearer's plane. One unified family
                // carries both roads, including perpendicular diamonds, with no crossed ties.
                V3 forward=base.tangent(shifted);double width=p.centerOffset()+s.sleeperOverhang();
                V3 b0=j.b().at(j.sb()-extent),b1=j.b().at(j.sb()+extent),n0=j.b().tangent(j.sb()-extent).lateral(),n1=j.b().tangent(j.sb()+extent).lateral();
                V3[] corners={b0.add(n0.mul(-width)),b1.add(n1.mul(-width)),b1.add(n1.mul(width)),b0.add(n0.mul(width))};
                for(int k=0;k<4;k++){V3 u=corners[k].sub(c),v=corners[(k+1)%4].sub(c);double du=u.dot(forward),dv=v.dot(forward);if(du*dv<=0&&Math.abs(du-dv)>1e-8){double lateral=u.lerp(v,du/(du-dv)).dot(n);lo=Math.min(lo,lateral);hi=Math.max(hi,lateral);}}
            }
            double top=Math.max(.03,p.top()-p.railHeight())+s.verticalOffset();
            m.beam(c.add(n.mul(lo)),c.add(n.mul(hi)),s.sleeperWidth(),s.sleeperWidth(),top-s.sleeperHeight(),top,p.sleeper(),"sleeper",index++);
        }
    }
}
