package org.mtrpoint.geometry;

import java.util.*;

/** Longitudinal check-rail intervals, independent of turnout ownership and simulation state. */
public final class GuardRails {
    /** `part` keeps the source of a pooled interval: ordinary guards are "guard", check-side
     *  wings are "wing", and everything downstream keeps that label on the swept faces. */
    public record Run(Track road,double start,double end,double offset,boolean flareStart,boolean flareEnd,Profile profile,PointSettings settings,String mergeGroup,String part){
        public Run(Track road,double start,double end,double offset,boolean flareStart,boolean flareEnd,Profile profile,PointSettings settings){this(road,start,end,offset,flareStart,flareEnd,profile,settings,"","guard");}
        public Run(Track road,double start,double end,double offset,boolean flareStart,boolean flareEnd,Profile profile,PointSettings settings,String mergeGroup){this(road,start,end,offset,flareStart,flareEnd,profile,settings,mergeGroup,"guard");}
        public Run canonical(){return road.startNode.compareTo(road.endNode)<=0?this:new Run(road.reverse(),road.length-end,road.length-start,-offset,flareEnd,flareStart,profile,settings,mergeGroup,part);}
        public V3 center(double d){return road.at(d).add(road.tangent(d).lateral().mul(offset));}
        public V3 point(double d){
            double margin=Math.min(flareStart?d-start:Double.MAX_VALUE,flareEnd?end-d:Double.MAX_VALUE);
            double flare=Math.max(0,1-margin/.4)*.10;
            return road.at(d).add(road.tangent(d).lateral().mul(offset-Math.signum(offset)*flare));
        }
        public Mesh mesh(){
            Mesh out=new Mesh();int count=Math.max(2,(int)Math.ceil((end-start)/.3));
            for(int i=0;i<count;i++)out.rail(point(start+(end-start)*i/count),point(start+(end-start)*(i+1)/count),1,1,profile,settings,part);
            return out;
        }
        public Run clip(V3 origin,V3 normal){
            double a=point(start).sub(origin).dot(normal),b=point(end).sub(origin).dot(normal);
            if(a<=0&&b<=0)return this;if(a>0&&b>0)return null;
            double lo=start,hi=end;
            for(int i=0;i<32;i++){double mid=(lo+hi)/2,v=point(mid).sub(origin).dot(normal);if((v>0)==(a>0))lo=mid;else hi=mid;}
            double cut=(lo+hi)/2;
            return a>0?new Run(road,cut,end,offset,false,flareEnd,profile,settings,mergeGroup,part):new Run(road,start,cut,offset,flareStart,false,profile,settings,mergeGroup,part);
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
        return applyEdits(result,s);
    }
    static List<Run> applyEdits(List<Run> input,PointSettings s){
        var result=new ArrayList<Run>(input);
        for(int i=0;i<result.size();i++){
            PointSettings.GuardEdit edit=s.guardEdits().get(i);if(edit==null)continue;Run r=result.get(i);
            double start=Math.max(0,Math.min(r.road.length,edit.start())),end=Math.max(0,Math.min(r.road.length,edit.end()));
            if(end>start+1e-5)result.set(i,new Run(r.road,start,end,r.offset,edit.flareStart(),edit.flareEnd(),r.profile,r.settings,edit.mergeGroup(),r.part()));
        }
        return List.copyOf(result);
    }
    /** Editor identity for the old geometry: source-order indices remain stable in saved data. */
    public static List<Run> selectable(Junction j,PointSettings s,Profile raw,ScissorsLayout group,PointMesh.YBoundary boundary){
        if(!s.enabled())return List.of();
        if(j.kind()==Junction.Kind.Y){
            var result=new ArrayList<>(forJunction(j,s,raw));
            FrogGeometry frog=new FrogGeometry(j,s,raw.tune(s),PointMesh.extent(j,s));
            result.add(frog.checkWing(0));result.add(frog.checkWing(1));
            return applyEdits(result,s);
        }
        if(j.kind()==Junction.Kind.THREE)return ThreeWayMesh.checkRuns(j,s,raw,boundary==null?PointMesh.YBoundary.nominal(j,s):boundary);
        if(j.kind()!=Junction.Kind.DIAMOND)return forJunction(j,s,raw);
        Profile p=raw.tune(s);var roads=group==null?j.tracks():group.tracks();double extent=PointMesh.extent(j,s);
        double gap=Math.max(.02,s.flangeway()+s.wingGapDelta());double sine=Math.max(.025,Math.abs(V3.crossXZ(j.a().tangent(j.sa()),j.b().tangent(j.sb()))));
        double cosine=Math.abs(j.a().tangent(j.sa()).dot(j.b().tangent(j.sb())));
        double reach=p.centerOffset()*(1+cosine)/sine+p.headWidth()/sine+.9;var result=new ArrayList<Run>();
        for(Track t:roads){double c=t.nearest(j.center()),start=Math.max(0,c-extent),end=Math.min(t.length,c+extent);
            if(group!=null){double a=group.intersection(t,group.lo()),b=group.intersection(t,group.hi());start=Math.max(0,Math.min(a,b)-1);end=Math.min(t.length,Math.max(a,b)+1);}
            double first=Math.max(start+.1,c-reach-s.guardLengthDelta()/2+s.guardShift()),last=Math.min(end-.1,c+reach+s.guardLengthDelta()/2+s.guardShift());
            for(int sign:new int[]{-1,1})if(last>first)result.add(new Run(t,first,last,sign*(p.centerOffset()-p.headWidth()-gap-Math.max(0,s.guardGapDelta())),true,true,p,s,"","wing"));
        }
        return applyEdits(result,s);
    }
    /** The base mesh already contains unedited wings. Only edited wings enter the assembly. */
    public static List<Run> assembled(Junction j,PointSettings s,Profile raw,ScissorsLayout group,PointMesh.YBoundary boundary){
        if((j.kind()==Junction.Kind.DIAMOND||j.kind()==Junction.Kind.THREE)&&s.guardEdits().isEmpty())return List.of();
        if(j.kind()==Junction.Kind.DIAMOND){
            var all=selectable(j,s,raw,group,boundary);var result=new ArrayList<Run>();
            for(int i=0;i<all.size();i++)if(s.guardEdits().containsKey(i))result.add(all.get(i));
            return result;
        }
        if(j.kind()==Junction.Kind.THREE){
            var all=selectable(j,s,raw,group,boundary);var result=new ArrayList<Run>();
            for(int i=0;i<all.size();i++)if(s.guardEdits().containsKey(i))result.add(all.get(i));
            return result;
        }
        if(j.kind()!=Junction.Kind.Y)return forJunction(j,s,raw);
        var all=selectable(j,s,raw,group,boundary);var result=new ArrayList<Run>();
        for(int i=0;i<all.size();i++)if(i<2||s.guardEdits().containsKey(i))result.add(all.get(i));
        return result;
    }
    public static List<Run> exposeEnds(List<Run> runs){return runs;}
    public static List<Run> merge(List<Run> input){return merge(input,null);}
    public static List<Run> merge(List<Run> input,List<Set<Integer>> sources){
        // Rebase geometrically coincident intervals before the inexpensive interval union.
        var rebased=new ArrayList<Run>();var provenance=new ArrayList<Set<Integer>>();
        for(int index=0;index<input.size();index++){Run r=input.get(index).canonical();
            for(Run target:rebased){Run match=rebase(r,target);if(match!=null){r=match;break;}}
            rebased.add(r);provenance.add(new LinkedHashSet<>(Set.of(index)));
        }
        var groups=new LinkedHashMap<String,List<Integer>>();
        for(int i=0;i<rebased.size();i++)groups.computeIfAbsent(rebased.get(i).road.id,k->new ArrayList<>()).add(i);
        var result=new ArrayList<Run>();var owners=new ArrayList<Set<Integer>>();
        for(var indices:groups.values()){
            indices.sort(Comparator.comparingDouble(i->rebased.get(i).start()));
            for(int index:indices){Run r=rebased.get(index);int found=-1;
                for(int i=result.size()-1;i>=0;i--){Run p=result.get(i);
                    boolean manual=!p.mergeGroup.isBlank()&&p.mergeGroup.equals(r.mergeGroup);
                    double gap=manual?.25:1e-7,offsetTolerance=manual?Math.max(.12,p.profile.gauge()*.12):1e-6;
                    if(p.road.id.equals(r.road.id)&&p.end>=r.start-gap&&Math.abs(p.offset-r.offset)<offsetTolerance&&p.profile.equals(r.profile)&&p.settings.verticalOffset()==r.settings.verticalOffset()){found=i;break;}}
                if(found<0){result.add(r);owners.add(new LinkedHashSet<>(provenance.get(index)));}
                else {Run p=result.get(found);result.set(found,new Run(p.road,p.start,Math.max(p.end,r.end),p.offset,p.flareStart,r.end>p.end?r.flareEnd:p.flareEnd,p.profile,p.settings,p.mergeGroup.isBlank()?r.mergeGroup:p.mergeGroup,p.part.equals("wing")||r.part.equals("wing")?"wing":"guard"));owners.get(found).addAll(provenance.get(index));}
            }
        }
        if(sources!=null){sources.clear();for(var owner:owners)sources.add(Set.copyOf(owner));}
        return List.copyOf(result);
    }
    private static Run rebase(Run r,Run target){
        if(r.road.id.equals(target.road.id)||!r.profile.equals(target.profile)||r.settings.verticalOffset()!=target.settings.verticalOffset())return null;
        boolean manual=!r.mergeGroup.isBlank()&&r.mergeGroup.equals(target.mergeGroup);
        double tolerance=manual?Math.max(.12,r.profile.gauge()*.12):.012;
        double first=0,last=0;
        for(int i=0;i<=8;i++){
            double d=r.start+(r.end-r.start)*i/8;V3 center=r.road.at(d),point=center.add(r.road.tangent(d).lateral().mul(r.offset));
            double at=target.road.nearest(center);V3 tangent=target.road.tangent(at);
            if(Math.abs(tangent.dot(r.road.tangent(d)))<.999||point.distance(target.road.at(at).add(tangent.lateral().mul(target.offset)))>tolerance)return null;
            if(i==0)first=at;if(i==8)last=at;
        }
        if(Math.max(first,last)<target.start-(manual?.25:0)||Math.min(first,last)>target.end+(manual?.25:0))return null;
        return new Run(target.road,Math.min(first,last),Math.max(first,last),target.offset,first<last?r.flareStart:r.flareEnd,first<last?r.flareEnd:r.flareStart,r.profile,r.settings,r.mergeGroup,r.part.equals("wing")||target.part.equals("wing")?"wing":"guard");
    }
}
