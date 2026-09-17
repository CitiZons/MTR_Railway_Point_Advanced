package org.mtrpoint.geometry;

import java.util.*;

/** Visual grouping only: four two-branch nodes linked by two roads and two crossing diagonals. */
public record ScissorsLayout(Junction crossing,List<Junction> turnouts,List<Track> through,List<Track> tracks,V3 axis,double lo,double hi) {
    public static List<ScissorsLayout> find(List<Junction> junctions){
        var result=new ArrayList<ScissorsLayout>();var byNode=new HashMap<String,List<Junction>>();
        for(Junction j:junctions)if(j.kind()==Junction.Kind.Y)byNode.computeIfAbsent(j.a().startNode,k->new ArrayList<>()).add(j);
        for(Junction cross:junctions)if(cross.kind()==Junction.Kind.DIAMOND){
            var nodes=new HashSet<>(List.of(cross.a().startNode,cross.a().endNode,cross.b().startNode,cross.b().endNode));if(nodes.size()!=4)continue;
            var ys=new ArrayList<Junction>();var main=new LinkedHashMap<String,Track>();
            for(String node:nodes){
                Junction found=null;
                for(Junction j:byNode.getOrDefault(node,List.of())){
                    Track diagonal=j.a().id.equals(cross.a().id)||j.a().id.equals(cross.b().id)?j.a():j.b().id.equals(cross.a().id)||j.b().id.equals(cross.b().id)?j.b():null;
                    if(diagonal==null)continue;Track other=diagonal==j.a()?j.b():j.a();
                    if(nodes.contains(other.endNode)&&!other.id.equals(cross.a().id)&&!other.id.equals(cross.b().id)){found=j;main.putIfAbsent(other.id,other);break;}
                }
                if(found!=null)ys.add(found);
            }
            if(ys.size()!=4||main.size()!=2)continue;
            ys.sort(Comparator.comparing(Junction::id));var roads=new ArrayList<>(main.values());roads.sort(Comparator.comparing(t->t.id));
            V3 axis=roads.get(0).tangent(roads.get(0).nearest(cross.center()));V3 other=roads.get(1).tangent(roads.get(1).nearest(cross.center()));if(other.dot(axis)<0)other=other.mul(-1);axis=axis.add(other).unit();
            double lo=-Double.MAX_VALUE,hi=Double.MAX_VALUE;
            for(var y:ys){
                var frog=new FrogGeometry(y,PointSettings.DEFAULT,Profile.STANDARD,y.extent());
                // The central diamond's outer noses lie well beyond its centre. Halving
                // the turnout heel projection cuts those noses off and leaves raw X rails.
                // Put each seam just past the turnout's complete wing/check assembly.
                Mesh ends=new Mesh();frog.build(ends,0);
                double node=y.center().sub(cross.center()).dot(axis),limit=node<0?-Double.MAX_VALUE:Double.MAX_VALUE;
                for(var q:ends.quads)for(V3 v:List.of(q.a(),q.b(),q.c(),q.d())){
                    double d=v.sub(cross.center()).dot(axis);limit=node<0?Math.max(limit,d):Math.min(limit,d);
                }
                if(node<0)lo=Math.max(lo,Math.min(-.4,limit+.12));else hi=Math.min(hi,Math.max(.4,limit-.12));
            }
            // The central assembly owns every actual rail-head intersection, even when
            // the four turnout frogs have unequal lengths or their original extents overlap.
            for(int signA:new int[]{-1,1})for(int signB:new int[]{-1,1}){
                double a=cross.sa(),b=cross.sb(),offset=Profile.STANDARD.centerOffset();
                for(int k=0;k<24;k++){
                    V3 pa=cross.a().at(a).add(cross.a().tangent(a).lateral().mul(signA*offset));
                    V3 pb=cross.b().at(b).add(cross.b().tangent(b).lateral().mul(signB*offset));
                    V3 u=cross.a().tangent(a),v=cross.b().tangent(b);double den=V3.crossXZ(u,v);if(Math.abs(den)<1e-6)break;
                    a=Math.max(0,Math.min(cross.a().length,a+V3.crossXZ(pb.sub(pa),v)/den));
                    b=Math.max(0,Math.min(cross.b().length,b+V3.crossXZ(pb.sub(pa),u)/den));
                }
                double d=cross.a().at(a).add(cross.a().tangent(a).lateral().mul(signA*offset)).sub(cross.center()).dot(axis);
                lo=Math.min(lo,d-.9);hi=Math.max(hi,d+.9);
            }
            if(!Double.isFinite(lo+hi)||lo>=hi)continue;
            var all=new ArrayList<>(roads);all.add(cross.a());all.add(cross.b());
            result.add(new ScissorsLayout(cross,List.copyOf(ys),List.copyOf(roads),List.copyOf(all),axis,lo,hi));
        }
        return List.copyOf(result);
    }
    public boolean central(V3 p){double d=p.sub(crossing.center()).dot(axis);return d>=lo&&d<=hi;}
    public boolean owns(Junction j,String rail,V3 point){
        if(j.kind()==Junction.Kind.DIAMOND)return tracks.stream().anyMatch(t->t.id.equals(rail))&&central(point);
        Track road=j.a().id.equals(rail)?j.a():j.b().id.equals(rail)?j.b():null;
        if(road==null)return false;double end=end(j,road);return road.nearest(point)<=end+1e-6;
    }
    public double end(Junction j,Track road){return intersection(road,j.center().sub(crossing.center()).dot(axis)<0?lo:hi);}
    public double intersection(Track road,double plane){
        for(int i=1;i<road.points.size();i++){
            double a=road.points.get(i-1).sub(crossing.center()).dot(axis)-plane,b=road.points.get(i).sub(crossing.center()).dot(axis)-plane;
            if(a*b<=0&&Math.abs(a-b)>1e-9)return road.distance[i-1]+(road.distance[i]-road.distance[i-1])*a/(a-b);
        }
        return road.nearest(crossing.center().add(axis.mul(plane)));
    }
    public PointMesh.YBoundary boundary(Junction j,PointSettings s){
        // Sweep beyond the common plane before clipping: an offset running rail crosses
        // that plane at a different distance from the track centre on a curved road.
        double a=end(j,j.a()),b=end(j,j.b());return new PointMesh.YBoundary(Math.min(j.a().length,a+1),Math.min(j.b().length,b+1),Math.max(0,a-s.sleeperSpacing()/2),Math.max(0,b-s.sleeperSpacing()/2));
    }
    public Mesh clip(Mesh source,Junction owner){
        Mesh result=new Mesh();
        if(owner.kind()!=Junction.Kind.DIAMOND){
            boolean before=owner.center().sub(crossing.center()).dot(axis)<0;V3 at=crossing.center().add(axis.mul(before?lo:hi)),normal=before?axis:axis.mul(-1);
            for(var q:source.quads)Mesh.clip(result,q,at,normal);
        }else{
            Mesh first=new Mesh();for(var q:source.quads)Mesh.clip(first,q,crossing.center().add(axis.mul(hi)),axis);
            for(var q:first.quads)Mesh.clip(result,q,crossing.center().add(axis.mul(lo)),axis.mul(-1));
        }
        return result;
    }
    public Mesh centerMesh(PointSettings settings,Profile raw){
        Profile p=raw.tune(settings);Mesh mesh=new Mesh();if(!settings.enabled())return mesh;
        double reach=0;for(Track road:crossing.tracks())for(double plane:new double[]{lo,hi})reach=Math.max(reach,Math.abs(intersection(road,plane)-road.nearest(crossing.center())));
        Junction expanded=new Junction(crossing.id(),Junction.Kind.DIAMOND,crossing.a(),crossing.b(),crossing.center(),crossing.sa(),crossing.sb(),reach+1);
        DiamondGeometry.buildScissors(mesh,this,settings,p);
        int index=0;double length=hi-lo;
        for(double local:PointMesh.sleeperDistances(Math.max(0,length-settings.sleeperSpacing()/2),settings.sleeperSpacing())){
            double along=lo+local+settings.sleeperShifts().getOrDefault(index,0D);V3 target=crossing.center().add(axis.mul(along));
            Track a=through.get(0),b=through.get(1);double da=a.nearest(target),db=b.nearest(target);V3 center=a.at(da).lerp(b.at(db),.5);
            V3 forward=a.tangent(da),other=b.tangent(db);if(forward.dot(other)<0)other=other.mul(-1);forward=forward.add(other).unit();if(forward.dot(axis)<0)forward=forward.mul(-1);
            double angle=Math.toRadians(settings.sleeperAngle()+(settings.sleeperEndAngle()-settings.sleeperAngle())*local/Math.max(.001,length));
            V3 n=forward.lateral();n=new V3(n.x()*Math.cos(angle)-n.z()*Math.sin(angle),0,n.x()*Math.sin(angle)+n.z()*Math.cos(angle));forward=new V3(n.z(),0,-n.x());
            var seats=new ArrayList<V3>();double min=Double.MAX_VALUE,max=-Double.MAX_VALUE;
            for(Track road:tracks)for(int sign:new int[]{-1,1}){
                double d=road.nearest(center);
                for(int k=0;k<6;k++){V3 seat=road.at(d).add(road.tangent(d).lateral().mul(sign*p.centerOffset()));double den=road.tangent(d).dot(forward);if(Math.abs(den)<.1)break;d=Math.max(0,Math.min(road.length,d-seat.sub(center).dot(forward)/den));}
                V3 seat=road.at(d).add(road.tangent(d).lateral().mul(sign*p.centerOffset()));if(seats.stream().noneMatch(v->v.distance(seat)<.18))seats.add(seat);
                double side=seat.sub(center).dot(n);min=Math.min(min,side-settings.sleeperOverhang());max=Math.max(max,side+settings.sleeperOverhang());
            }
            if(settings.sleeperMode()==4){
                VSleepers.across(mesh,tracks,center,axis,angle,settings,p,index);
            }
            else if(p.detail()!=null){if(!p.detail().siding())p.detail().bearer(mesh,center,n,min,max,settings,p,index);for(V3 seat:seats)p.detail().fitting(mesh,seat,n,settings,p,index);}
            else {double top=p.top()-p.railHeight()+settings.verticalOffset();mesh.beam(center.add(n.mul(min)),center.add(n.mul(max)),settings.sleeperWidth(),settings.sleeperWidth(),top-settings.sleeperHeight(),top,p.sleeper(),"sleeper",index);}
            index++;
        }
        return clip(mesh,crossing);
    }
}
