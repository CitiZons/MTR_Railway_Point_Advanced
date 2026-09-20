package org.mtrpoint.geometry;

import java.util.*;

/** One six-rail assembly: four blades and three common crossings, with a shared bearer family. */
public final class ThreeWayMesh {
    private record Crossing(int a,int b,FrogGeometry frog,double sign) {}
    public static Mesh build(Junction j,PointSettings s,Profile raw,double position,PointMesh.YBoundary boundary){
        Mesh m=new Mesh();if(!s.enabled())return m;Profile p=raw.tune(s);double extent=PointMesh.extent(j,s);
        List<Track> roads=j.tracks();var crossings=new ArrayList<Crossing>();
        for(int a=0;a<3;a++)for(int b=a+1;b<3;b++){
            Track x=roads.get(a),y=roads.get(b);Junction pair=new Junction(j.id()+":"+a+b,Junction.Kind.Y,x,y,j.center(),0,0,j.extent());
            double sign=TurnoutFrame.side(pair,extent);
            crossings.add(new Crossing(a,b,new FrogGeometry(pair,s,p,extent),sign));
        }
        double first=crossings.stream().mapToDouble(c->Math.min(c.frog.sa,c.frog.sb)).min().orElse(extent*.5);
        double bladeStart=TurnoutFrame.start(j,extent);
        double blade=s.bladeLength()>0?s.bladeLength():Math.max(2,Math.min(9,(first-bladeStart)*.65));
        double[] ends={boundary.aEnd(),boundary.thirdEnd(),boundary.bEnd()};
        double seam=Math.max(bladeStart+blade+.25,crossings.stream().mapToDouble(c->Math.min(c.frog.toe(0),c.frog.toe(1))).min().orElse(first));
        V3 seamOrigin=roads.get(1).at(seam),seamNormal=roads.get(1).tangent(seam);
        double[] starts=new double[3];
        for(int i=0;i<3;i++){
            Track road=roads.get(i);double d=road.nearest(seamOrigin);
            for(int k=0;k<12;k++){double den=road.tangent(d).dot(seamNormal);if(Math.abs(den)<.1)break;d=Math.max(0,Math.min(ends[i],d-road.at(d).sub(seamOrigin).dot(seamNormal)/den));}
            starts[i]=d;
        }
        for(int branch=0;branch<3;branch++)for(int sign:new int[]{-1,1}){
            Track road=roads.get(branch);double end=ends[branch];var cuts=new ArrayList<double[]>();
            for(var c:crossings){int local=c.a==branch?0:c.b==branch?1:-1;if(local>=0&&sign==(local==0?c.sign:-c.sign))cuts.add(new double[]{c.frog.toe(local),c.frog.heel(local)});}
            var distances=new TreeSet<Double>();distances.add(0D);distances.add(end);
            for(double d=.24;d<end;d+=.24)distances.add(d);
            distances.add(Math.max(0,Math.min(end,bladeStart)));distances.add(Math.max(0,Math.min(end,bladeStart+blade)));
            for(var cut:cuts){distances.add(Math.max(0,Math.min(end,cut[0])));distances.add(Math.max(0,Math.min(end,cut[1])));}
            boolean moving=branch==1||(branch==0?sign==1:sign==-1);
            var ds=new ArrayList<>(distances);
            for(int i=1;i<ds.size();i++){
                double a=ds.get(i-1),b=ds.get(i),mid=(a+b)/2;if(cuts.stream().anyMatch(c->mid>c[0]&&mid<c[1]))continue;
                if(branch>0&&moving&&b<=bladeStart)continue;
                V3 x=rail(road,a,sign,p),y=rail(road,b,sign,p);
                if(moving&&b>bladeStart&&a<bladeStart+blade){
                    double open=Math.min(1,Math.abs(position-branch*.5)*2);
                    Track stock=roads.get(branch+(sign>0?1:-1));
                    var bladeA=TurnoutFrame.blade(road,stock,a,sign,p.centerOffset(),p.headWidth(),open,bladeStart,blade,s);
                    var bladeB=TurnoutFrame.blade(road,stock,b,sign,p.centerOffset(),p.headWidth(),open,bladeStart,blade,s);
                    m.blade(bladeA.point(),bladeB.point(),bladeA.cut(),bladeB.cut(),p,s);continue;
                }
                m.rail(x,y,1,1,p,s,"rail",new Mesh.RailTag(road,a,b,sign*p.centerOffset()));
            }
        }
        for(var c:crossings)c.frog.build(m,Math.max(0,Math.min(1,(position-c.a*.5)/((c.b-c.a)*.5))));
        {
            // One shared crossing assembly replaces the three overlapping pairs.
            // Keep moving blades outside the clip so their animation topology is stable.
            Mesh before=new Mesh();
            for(var q:m.quads){if(q.part().equals("guard")||s.movableFrog()&&q.part().equals("frog"))continue;if(q.part().equals("blade"))before.quad(q);else Mesh.clip(before,q,seamOrigin,seamNormal);}
            double[] extended=starts.clone();for(int i=0;i<3;i++)extended[i]=Math.max(0,extended[i]-1);
            Mesh fixed=new Mesh();DiamondGeometry.three(fixed,j,s,p,extended,ends);
            if(s.movableFrog()){
                for(var c:crossings)fixed=DiamondGeometry.pocket(fixed,nose(c,crossings,0),nose(c,crossings,1),p.top()+s.verticalOffset()+j.center().y());
            }
            for(var q:fixed.quads)Mesh.clip(before,q,seamOrigin,seamNormal.mul(-1));m=before;
            if(s.movableFrog())for(var c:crossings)m.quads.addAll(nose(c,crossings,Math.max(0,Math.min(1,(position-c.a*.5)/((c.b-c.a)*.5)))).quads);
        }
        for(int pair=0;pair<2;pair++){
            int a=pair,b=pair+1;double at=bladeStart+Math.min(1.1+pair*.35,blade/3);V3 origin=roads.get(a).at(at),forward=roads.get(a).tangent(at);
            V3 x=TurnoutFrame.contact(roads.get(a),roads.get(b),at,1,p.centerOffset(),p.headWidth(),Math.min(1,Math.abs(position-a*.5)*2),bladeStart,blade,s,origin,forward);
            V3 y=TurnoutFrame.contact(roads.get(b),roads.get(a),roads.get(b).nearest(origin),-1,p.centerOffset(),p.headWidth(),Math.min(1,Math.abs(position-b*.5)*2),bladeStart,blade,s,origin,forward);
            m.beam(x,y,.09,.09,p.top()+s.verticalOffset()-.07,p.top()+s.verticalOffset()-.03,p.steel(),"stretcher",-1);
        }
        double[] lasts={boundary.aLast(),boundary.thirdLast(),boundary.bLast()};int index=0;
        for(double d:PointMesh.sleeperDistances(lasts[1],s.sleeperSpacing())){
            double blend=Math.max(0,Math.min(1,(d-lasts[1]+2*s.sleeperSpacing())/(2*s.sleeperSpacing())));
            var centers=new ArrayList<V3>();var normals=new ArrayList<V3>();var distances=new ArrayList<Double>();
            for(int branch=0;branch<3;branch++){
                double at=d+(lasts[branch]-lasts[1])*blend+s.sleeperShifts().getOrDefault(index,0D);distances.add(at);centers.add(roads.get(branch).at(at));
                V3 n=s.sleeperMode()==4?roads.get(branch).tangent(at).lateral():PointMesh.sleeperNormal(j,s,at,0,extent);
                if(s.sleeperMode()==4){double angle=Math.toRadians(s.sleeperAngle()+(s.sleeperEndAngle()-s.sleeperAngle())*at/extent);n=new V3(n.x()*Math.cos(angle)-n.z()*Math.sin(angle),0,n.x()*Math.sin(angle)+n.z()*Math.cos(angle));}
                normals.add(n);
            }
            bearers(m,roads,centers,normals,distances,p,s,index++);
        }
        EndSleepers.finish(m,j,s,p,boundary);
        SleeperEdits.finish(m,j,s,p);
        return m;
    }
    private static V3 rail(Track road,double d,double sign,Profile p){return road.at(d).add(road.tangent(d).lateral().mul(sign*p.centerOffset()));}
    private static Mesh nose(Crossing crossing,List<Crossing> crossings,double position){
        Mesh mesh=crossing.frog.movableNose(position);V3 center=crossing.frog.center();
        for(var other:crossings)if(other!=crossing){V3 delta=other.frog.center().sub(center);delta=new V3(delta.x(),0,delta.z());if(delta.length()>1e-6)mesh=Mesh.clipAnimated(mesh,center.lerp(other.frog.center(),.5),delta.unit());}
        return mesh;
    }
    private static void bearers(Mesh out,List<Track> roads,List<V3> centers,List<V3> normals,List<Double> distances,Profile p,PointSettings s,int index){
        V3[] joints=new V3[2];
        for(int outer:new int[]{0,2}){
            V3 middle=centers.get(1),n=normals.get(1);
            // Each adjacent pair stops sharing bearers when its own two ordinary
            // sleeper envelopes separate. An asymmetric fan has two distinct ends.
            V3 transverse=n.add(normals.get(outer)).unit();
            if(Math.abs(centers.get(outer).sub(middle).dot(transverse))>2*(p.centerOffset()+s.sleeperOverhang()))continue;
            V3 joint=middle.add(n.mul(centers.get(outer).sub(middle).dot(n)/2));joints[outer/2]=joint;
            if(s.sleeperMode()==4){
                double at=distances.get(outer),angle=Math.atan2(V3.crossXZ(roads.get(outer).tangent(at).lateral(),normals.get(outer)),roads.get(outer).tangent(at).lateral().dot(normals.get(outer)));
                at=VSleepers.station(roads.get(outer),joint,at,angle);distances.set(outer,at);centers.set(outer,roads.get(outer).at(at));
                V3 lateral=roads.get(outer).tangent(at).lateral();normals.set(outer,new V3(lateral.x()*Math.cos(angle)-lateral.z()*Math.sin(angle),0,lateral.x()*Math.sin(angle)+lateral.z()*Math.cos(angle)));
            }
        }
        var seats=new ArrayList<V3>();
        for(int branch=0;branch<3;branch++){
            V3 c=centers.get(branch),n=normals.get(branch);double half=p.centerOffset()+s.sleeperOverhang();Mesh arm=new Mesh();
            double lo=-half,hi=half;
            if(!SleeperEdits.split(s,index))for(int adjacent:new int[]{branch-1,branch+1})if(adjacent>=0&&adjacent<3&&joints[Math.min(branch,adjacent)]!=null){double join=joints[Math.min(branch,adjacent)].sub(c).dot(n);lo=Math.min(lo,join-.4);hi=Math.max(hi,join+.4);}
            if(p.detail()!=null){if(!p.detail().siding())p.detail().bearer(arm,c,n,lo,hi,s,p,index);}
            else {double top=p.top()-p.railHeight()+s.verticalOffset();arm.beam(c.add(n.mul(lo)),c.add(n.mul(hi)),s.sleeperWidth(),s.sleeperWidth(),top-s.sleeperHeight(),top,p.sleeper(),"sleeper",index);}
            if(!SleeperEdits.split(s,index)&&!SleeperEdits.full(s,index))for(int adjacent:new int[]{branch-1,branch+1})if(adjacent>=0&&adjacent<3&&joints[Math.min(branch,adjacent)]!=null){
                V3 other=centers.get(adjacent),on=normals.get(adjacent),joint=joints[Math.min(branch,adjacent)];
                V3 cut=n.add(on).unit();if(cut.dot(other.sub(c))<0)cut=cut.mul(-1);Mesh clipped=new Mesh();for(var q:arm.quads)Mesh.clip(clipped,q,joint,cut);arm=clipped;
            }
            out.quads.addAll(arm.quads);
            if(p.detail()!=null)for(int sign:new int[]{-1,1}){
                Track road=roads.get(branch);double d=distances.get(branch);V3 forward=new V3(n.z(),0,-n.x());
                for(int k=0;k<5;k++){V3 at=rail(road,d,sign,p);double den=road.tangent(d).dot(forward);if(Math.abs(den)<.1)break;d=Math.max(0,Math.min(road.length,d-at.sub(c).dot(forward)/den));}
                V3 seat=rail(road,d,sign,p);if(seats.stream().noneMatch(v->v.distance(seat)<.18)){seats.add(seat);p.detail().fitting(out,seat,n,s,p,index);}
            }
        }
    }
}
