package org.mtrpoint.geometry;

import java.util.*;

/** Longitudinal check-rail intervals, independent of turnout ownership and simulation state. */
public final class GuardRails {
    public record Run(Track road,double start,double end,double offset,boolean flareStart,boolean flareEnd,Profile profile,PointSettings settings,String mergeGroup){
        public Run(Track road,double start,double end,double offset,boolean flareStart,boolean flareEnd,Profile profile,PointSettings settings){this(road,start,end,offset,flareStart,flareEnd,profile,settings,"");}
        public Run canonical(){return road.startNode.compareTo(road.endNode)<=0?this:new Run(road.reverse(),road.length-end,road.length-start,-offset,flareEnd,flareStart,profile,settings,mergeGroup);}
        public V3 center(double d){return road.at(d).add(road.tangent(d).lateral().mul(offset));}
        public V3 point(double d){
            double margin=Math.min(flareStart?d-start:Double.MAX_VALUE,flareEnd?end-d:Double.MAX_VALUE);
            double flare=Math.max(0,1-margin/.4)*.10;
            return center(d).add(road.tangent(d).lateral().mul(-Math.signum(offset)*flare));
        }
        public Mesh mesh(){
            Mesh out=new Mesh();int count=Math.max(2,(int)Math.ceil((end-start)/.3));
            for(int i=0;i<count;i++)out.rail(point(start+(end-start)*i/count),point(start+(end-start)*(i+1)/count),1,1,profile,settings,"guard");
            out.railCap(point(start),road.tangent(start),1,profile,settings,"guard",true);out.railCap(point(end),road.tangent(end),1,profile,settings,"guard",false);
            return out;
        }
        public Run clip(V3 origin,V3 normal){
            double a=point(start).sub(origin).dot(normal),b=point(end).sub(origin).dot(normal);
            if(a<=0&&b<=0)return this;if(a>0&&b>0)return null;
            double lo=start,hi=end;
            for(int i=0;i<32;i++){double mid=(lo+hi)/2,v=point(mid).sub(origin).dot(normal);if((v>0)==(a>0))lo=mid;else hi=mid;}
            double cut=(lo+hi)/2;
            return a>0?new Run(road,cut,end,offset,false,flareEnd,profile,settings,mergeGroup):new Run(road,start,cut,offset,flareStart,false,profile,settings,mergeGroup);
        }
    }
    public static List<Run> forJunction(Junction j,PointSettings s,Profile raw){
        if(!s.enabled()||j.kind()==Junction.Kind.DIAMOND)return List.of();
        Profile p=raw.tune(s);double extent=PointMesh.extent(j,s);var result=new ArrayList<Run>();
        var roads=j.tracks();
        for(int a=0;a<roads.size();a++)for(int b=a+1;b<roads.size();b++){
            Junction pair=new Junction(j.id(),Junction.Kind.Y,roads.get(a),roads.get(b),j.center(),0,0,j.extent());
            FrogGeometry frog=new FrogGeometry(pair,s,p,extent);result.add(frog.guard(0));result.add(frog.guard(1));
        }
        result.sort(Comparator.comparing((Run r)->r.canonical().road.id).thenComparingDouble(r->r.canonical().start).thenComparingDouble(Run::offset));
        for(int i=0;i<result.size();i++){
            Run r=result.get(i);PointSettings.GuardEdit edit=s.guardEdits().get(i);if(edit==null)continue;
            double start=Math.max(0,Math.min(r.road.length,edit.start())),end=Math.max(0,Math.min(r.road.length,edit.end()));
            if(end>start+1e-5)result.set(i,new Run(r.road,start,end,r.offset,edit.flareStart(),edit.flareEnd(),r.profile,r.settings,edit.mergeGroup()));
        }
        return List.copyOf(result);
    }
    public static List<Run> merge(List<Run> input){
        var rebased=new ArrayList<Run>();
        for(Run raw:input){Run r=raw.canonical();
            for(Run target:rebased){Run match=rebase(r,target);if(match!=null){r=match;break;}}
            rebased.add(r);
        }
        var groups=new LinkedHashMap<String,List<Run>>();
        for(Run r:rebased)groups.computeIfAbsent(r.road.id,k->new ArrayList<>()).add(r);
        var result=new ArrayList<Run>();
        for(var runs:groups.values()){
            runs.sort(Comparator.comparingDouble(Run::start));
            for(Run r:runs){int found=-1;
                for(int i=result.size()-1;i>=0;i--){Run p=result.get(i);if(p.road.id.equals(r.road.id)&&p.end>=r.start-1e-7&&compatible(p,r)){found=i;break;}}
                if(found<0)result.add(r);
                else {Run p=result.get(found);result.set(found,new Run(p.road,p.start,Math.max(p.end,r.end),p.offset,p.flareStart,r.end>p.end?r.flareEnd:p.flareEnd,p.profile,p.settings,p.mergeGroup.isBlank()?r.mergeGroup:p.mergeGroup));}
            }
        }
        return List.copyOf(result);
    }
    private static boolean compatible(Run a,Run b){
        boolean manual=!a.mergeGroup.isBlank()&&a.mergeGroup.equals(b.mergeGroup);
        return a.profile.equals(b.profile)&&a.settings.verticalOffset()==b.settings.verticalOffset()&&(manual||Math.abs(a.offset-b.offset)<=1e-5);
    }
    private static Run rebase(Run r,Run target){
        if(r.road.id.equals(target.road.id)||!r.profile.equals(target.profile)||r.settings.verticalOffset()!=target.settings.verticalOffset())return null;
        boolean manual=!r.mergeGroup.isBlank()&&r.mergeGroup.equals(target.mergeGroup);
        double tolerance=manual?Math.max(.12,Math.min(r.profile.gauge(),target.profile.gauge())*.12):Math.max(.0125,Math.min(r.profile.headWidth(),target.profile.headWidth())*.25);
        int samples=Math.max(8,(int)Math.ceil((r.end-r.start)/.12));double first=0,last=0;
        for(int i=0;i<=samples;i++){
            double d=r.start+(r.end-r.start)*i/samples,at=target.road.nearest(r.center(d));
            V3 tangent=target.road.tangent(at);
            if(Math.abs(tangent.dot(r.road.tangent(d)))<.999||r.center(d).distance(target.center(at))>tolerance)return null;
            if(i==0)first=at;if(i==samples)last=at;
        }
        double lo=Math.min(first,last),hi=Math.max(first,last);
        if(!manual&&(hi<=target.start+1e-7||lo>=target.end-1e-7))return null;
        if(manual&&(hi<target.start-.25||lo>target.end+.25))return null;
        return new Run(target.road,lo,hi,target.offset,first<last?r.flareStart:r.flareEnd,first<last?r.flareEnd:r.flareStart,r.profile,r.settings,r.mergeGroup);
    }
}
