package org.mtrpoint.geometry;

import java.util.*;

/** Longitudinal check-rail intervals, independent of turnout ownership and simulation state. */
public final class GuardRails {
    /** `part` keeps the source of a pooled interval: ordinary guards are "guard", check-side
     *  wings are "wing", and everything downstream keeps that label on the swept faces. */
    public record Run(Track road,double start,double end,double offset,boolean flareStart,boolean flareEnd,Profile profile,PointSettings settings,String mergeGroup,String part,boolean attachedStart,boolean attachedEnd){
        public Run(Track road,double start,double end,double offset,boolean flareStart,boolean flareEnd,Profile profile,PointSettings settings,String mergeGroup,String part){this(road,start,end,offset,flareStart,flareEnd,profile,settings,mergeGroup,part,false,false);}
        public Run(Track road,double start,double end,double offset,boolean flareStart,boolean flareEnd,Profile profile,PointSettings settings){this(road,start,end,offset,flareStart,flareEnd,profile,settings,"","guard");}
        public Run(Track road,double start,double end,double offset,boolean flareStart,boolean flareEnd,Profile profile,PointSettings settings,String mergeGroup){this(road,start,end,offset,flareStart,flareEnd,profile,settings,mergeGroup,"guard");}
        public Run canonical(){return road.startNode.compareTo(road.endNode)<=0?this:new Run(road.reverse(),road.length-end,road.length-start,-offset,flareEnd,flareStart,profile,settings,mergeGroup,part,attachedEnd,attachedStart);}
        public V3 center(double d){return road.at(d).add(road.tangent(d).lateral().mul(offset));}
        public V3 point(double d){
            double margin=Math.min(flareStart?d-start:Double.MAX_VALUE,flareEnd?end-d:Double.MAX_VALUE);
            double flare=Math.max(0,1-margin/.4)*.10;
            return road.at(d).add(road.tangent(d).lateral().mul(offset-Math.signum(offset)*flare));
        }
        public Mesh mesh(){
            Mesh out=new Mesh();int count=Math.max(2,(int)Math.ceil((end-start)/.3));
            for(int i=0;i<count;i++)out.rail(point(start+(end-start)*i/count),point(start+(end-start)*(i+1)/count),1,1,profile,settings,part);
            if(!attachedStart)out.railCap(point(start),point(start+.01).sub(point(start)),1,profile,settings,part,true);
            if(!attachedEnd)out.railCap(point(end),point(end).sub(point(end-.01)),1,profile,settings,part,false);
            return out;
        }
        public Run clip(V3 origin,V3 normal){
            double a=point(start).sub(origin).dot(normal),b=point(end).sub(origin).dot(normal);
            if(a<=0&&b<=0)return this;if(a>0&&b>0)return null;
            double lo=start,hi=end;
            for(int i=0;i<32;i++){double mid=(lo+hi)/2,v=point(mid).sub(origin).dot(normal);if((v>0)==(a>0))lo=mid;else hi=mid;}
            double cut=(lo+hi)/2;
            return a>0?new Run(road,cut,end,offset,false,flareEnd,profile,settings,mergeGroup,part,false,attachedEnd):new Run(road,start,cut,offset,flareStart,false,profile,settings,mergeGroup,part,attachedStart,false);
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
            // A wing's knee belongs to the incoming closure rail. A saved guard edit cannot
            // detach that connection or turn it into another free mouth.
            if(r.attachedStart)start=r.start;if(r.attachedEnd)end=r.end;
            if(end>start+1e-5)result.set(i,new Run(r.road,start,end,r.offset,!r.attachedStart&&edit.flareStart(),!r.attachedEnd&&edit.flareEnd(),r.profile,r.settings,edit.mergeGroup(),r.part(),r.attachedStart,r.attachedEnd));
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
    /** Every check enters the common interval pool before its terminal bends are drawn. */
    public static List<Run> assembled(Junction j,PointSettings s,Profile raw,ScissorsLayout group,PointMesh.YBoundary boundary){
        var all=selectable(j,s,raw,group,boundary);
        // The central three-way road already has closure wings. A second guard
        // in that same corridor is redundant. Keep editor indices stable above.
        if(j.kind()==Junction.Kind.THREE)return all.stream().filter(r->!r.part().equals("guard")||!r.road().id.equals(j.third().id)).toList();
        // The through-road guards of a scissors come from its turnouts, not from an
        // invented long diamond guard. Explicit edits of the central view still apply.
        if(j.kind()!=Junction.Kind.DIAMOND||group==null)return all;
        var result=new ArrayList<Run>();
        for(int i=0;i<all.size();i++){
            Run r=all.get(i);
            if(r.road.id.equals(j.a().id)||r.road.id.equals(j.b().id)||s.guardEdits().containsKey(i))result.add(r);
        }
        return List.copyOf(result);
    }
    public static List<Run> exposeEnds(List<Run> runs){
        var result=new ArrayList<Run>();
        for(var run:runs){
            boolean legacy=!run.mergeGroup().isBlank();
            boolean start=!run.attachedStart()&&(run.flareStart()||legacy),end=!run.attachedEnd()&&(run.flareEnd()||legacy);
            for(var other:runs)if(other!=run&&other.profile().equals(run.profile())&&other.settings().verticalOffset()==run.settings().verticalOffset()){
                start&=!continues(run,run.start(),other);end&=!continues(run,run.end(),other);
            }
            result.add(new Run(run.road(),run.start(),run.end(),run.offset(),start,end,run.profile(),run.settings(),run.mergeGroup(),run.part(),run.attachedStart(),run.attachedEnd()));
        }
        return List.copyOf(result);
    }
    private static boolean continues(Run run,double d,Run other){
        V3 point=run.center(d);double at=TurnoutFrame.nearestOffset(other.road(),point,other.offset());
        if(!overlapsAt(run,d,other,at))return false;
        // A separately owned road can begin exactly where this one ends. Only suppress
        // a mouth if the other run extends outwards; coincident outer ends must still flare.
        double direction=run.road().tangent(d).dot(other.road().tangent(at))*(d==run.start()?-1:1);
        return (direction>0?other.end()-at:at-other.start())>.01;
    }
    private static boolean overlapsAt(Run run,double d,Run other,double at){
        V3 point=run.center(d),target=other.center(at);
        return at>=other.start()-1e-6&&at<=other.end()+1e-6
            &&Math.abs(point.y()-target.y())<.01
            &&Math.abs(run.road().tangent(d).dot(other.road().tangent(at)))>.995
            &&point.distance(target)<run.profile().headWidth()-1e-6;
    }
    /** The world union can join curves over just their shared portion. Editor eligibility
     * must test that connected component, not demand one global station coordinate system. */
    public static boolean canMerge(List<Run> input){
        var runs=merge(input);if(runs.isEmpty())return false;
        boolean[] seen=new boolean[runs.size()];seen[0]=true;var pending=new ArrayDeque<Integer>();pending.add(0);int count=1;
        while(!pending.isEmpty()){
            Run a=runs.get(pending.remove());
            for(int i=0;i<runs.size();i++)if(!seen[i]&&connected(a,runs.get(i))){seen[i]=true;pending.add(i);count++;}
        }
        return count==runs.size();
    }
    private static boolean connected(Run a,Run b){
        if(!a.profile().equals(b.profile())||a.settings().verticalOffset()!=b.settings().verticalOffset())return false;
        if(continues(a,a.start(),b)||continues(a,a.end(),b)||continues(b,b.start(),a)||continues(b,b.end(),a))return true;
        // Interior-only overlap may also join runs whose two distant tails diverge.
        Run sample=a.end()-a.start()<b.end()-b.start()?a:b,other=sample==a?b:a;
        int steps=Math.max(2,(int)Math.ceil((sample.end()-sample.start())/.1)),hits=0;
        for(int i=0;i<=steps;i++){
            double d=sample.start()+(sample.end()-sample.start())*i/steps;
            double at=TurnoutFrame.nearestOffset(other.road(),sample.center(d),other.offset());
            hits=overlapsAt(sample,d,other,at)?hits+1:0;if(hits>=3)return true;
        }
        return false;
    }
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
                    // A knee inside a longer overlapping check is still a connection, not an
                    // exposed end. Keep its run so the pool cannot erase that attachment.
                    if(r.attachedStart&&r.start>p.start+1e-6||r.attachedEnd&&r.end<p.end-1e-6
                        ||p.attachedEnd&&r.end>p.end+1e-6)continue;
                    if(p.road.id.equals(r.road.id)&&p.end>=r.start-gap&&Math.abs(p.offset-r.offset)<offsetTolerance&&p.profile.equals(r.profile)&&p.settings.verticalOffset()==r.settings.verticalOffset()){found=i;break;}}
                if(found<0){result.add(r);owners.add(new LinkedHashSet<>(provenance.get(index)));}
                else {Run p=result.get(found);
                    boolean attachedStart=p.attachedStart||Math.abs(p.start-r.start)<1e-6&&r.attachedStart;
                    boolean attachedEnd=r.end>p.end?r.attachedEnd:p.attachedEnd||Math.abs(p.end-r.end)<1e-6&&r.attachedEnd;
                    boolean extendsStart=r.start<p.start-1e-6, extendsEnd=r.end>p.end+1e-6;
                    boolean flareStart=extendsStart?r.flareStart:(Math.abs(r.start-p.start)<1e-6?p.flareStart||r.flareStart:p.flareStart);
                    boolean flareEnd=extendsEnd?r.flareEnd:(Math.abs(r.end-p.end)<1e-6?p.flareEnd||r.flareEnd:p.flareEnd);
                    result.set(found,new Run(p.road,Math.min(p.start,r.start),Math.max(p.end,r.end),p.offset,!attachedStart&&flareStart,!attachedEnd&&flareEnd,p.profile,p.settings,p.mergeGroup.isBlank()?r.mergeGroup:p.mergeGroup,p.part.equals("wing")||r.part.equals("wing")?"wing":"guard",attachedStart,attachedEnd));owners.get(found).addAll(provenance.get(index));}
            }
        }
        if(sources!=null){sources.clear();for(var owner:owners)sources.add(Set.copyOf(owner));}
        return List.copyOf(result);
    }
    private static Run rebase(Run r,Run target){
        if(r.road.id.equals(target.road.id)||!r.profile.equals(target.profile)||r.settings.verticalOffset()!=target.settings.verticalOffset())return null;
        boolean manual=!r.mergeGroup.isBlank()&&r.mergeGroup.equals(target.mergeGroup);
        double tolerance=manual?Math.max(.18,r.profile.gauge()*.18):.012;
        double first=0,last=0;
        for(int i=0;i<=8;i++){
            double d=r.start+(r.end-r.start)*i/8;V3 center=r.road.at(d),point=center.add(r.road.tangent(d).lateral().mul(r.offset));
            double at=target.road.nearest(center);V3 tangent=target.road.tangent(at);
            // Manual UI merges use the same practical visual tolerance as the world union. A
            // curved rail can change tangent slightly between adjacent MTR samples while still
            // being one continuous check rail; the old .999 threshold rejected those runs.
            V3 targetPoint=target.road.at(at).add(tangent.lateral().mul(target.offset));
            if(Math.abs(point.y()-targetPoint.y())>.01||Math.abs(tangent.dot(r.road.tangent(d)))<(manual?.995:.999)||point.distance(targetPoint)>tolerance)return null;
            if(i==0)first=at;if(i==8)last=at;
        }
        if(Math.max(first,last)<target.start-(manual?.25:0)||Math.min(first,last)>target.end+(manual?.25:0))return null;
        return new Run(target.road,Math.min(first,last),Math.max(first,last),target.offset,first<last?r.flareStart:r.flareEnd,first<last?r.flareEnd:r.flareStart,r.profile,r.settings,r.mergeGroup,r.part.equals("wing")||target.part.equals("wing")?"wing":"guard",first<last?r.attachedStart:r.attachedEnd,first<last?r.attachedEnd:r.attachedStart);
    }
}
