package org.mtrpoint.geometry;

import java.util.*;

/** Longitudinal check-rail intervals, independent of turnout ownership and simulation state. */
public final class GuardRails {
    /** Longest seam a manual merge group may close, and the widest centre-line mismatch still
     * treated as one physical check rail. Both cover MTR sampling error between adjacent rails. */
    private static final double MANUAL_GAP=.25;
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
        return applyEdits(removeBladeSteel(result),s);
    }
    /** The check rails a diamond crossing draws on both roads and both sides. Extracted from the
     * assembly so the editor and the world bake enumerate byte-for-byte the same runs, and a
     * manual edit therefore lands on the steel the union later reconstructs.
     * {@code tuned} is the profile the bake uses; it is already tuned by the caller. */
    public static List<Run> crossing(Junction j,PointSettings s,Profile tuned){
        if(!s.enabled())return List.of();
        double extent=PointMesh.extent(j,s);var result=new ArrayList<Run>();
        double gap=Math.max(.02,s.flangeway()+s.wingGapDelta());
        double sine=Math.max(.025,Math.abs(V3.crossXZ(j.a().tangent(j.sa()),j.b().tangent(j.sb()))));
        double cosine=Math.abs(j.a().tangent(j.sa()).dot(j.b().tangent(j.sb())));
        double reach=tuned.centerOffset()*(1+cosine)/sine+tuned.headWidth()/sine+.9;
        for(Track t:j.tracks()){
            double c=t.nearest(j.center());
            double start=Math.max(0,c-extent),end=Math.min(t.length,c+extent);
            double guardStart=Math.max(start+.1,c-reach-s.guardLengthDelta()/2+s.guardShift());
            double guardEnd=Math.min(end-.1,c+reach+s.guardLengthDelta()/2+s.guardShift());
            for(int sign:new int[]{-1,1}){
                double checkOffset=sign*(tuned.centerOffset()-tuned.headWidth()-gap-Math.max(0,s.guardGapDelta()));
                if(guardEnd>guardStart)result.add(new Run(t,guardStart,guardEnd,checkOffset,true,true,tuned,s));
            }
        }
        return applyEdits(removeBladeSteel(result),s);
    }
    /** Every physically selectable check rail of one view. A diamond crossing and a three-way fan
     * own their check steel inside their shared assembly rather than through the frog guards, so
     * the editor must ask the same generator the renderer bakes from. A scissors member's centre
     * is drawn from its own turnouts, so the crossing view contributes nothing to avoid claiming
     * steel another view owns. */
    public static List<Run> selectable(Junction j,PointSettings s,Profile raw,ScissorsLayout group,PointMesh.YBoundary boundary){
        if(!s.enabled())return List.of();
        if(j.kind()==Junction.Kind.DIAMOND)return group==null?crossing(j,s,raw.tune(s)):List.of();
        if(j.kind()==Junction.Kind.THREE)return ThreeWayMesh.checkRuns(j,s,raw,boundary);
        return forJunction(j,s,raw);
    }
    /** Apply each view's absolute manual stations by index, the index space both the editor and
     * the bake agree on. A diamond or three-way list is built once and edited here, so callers
     * that enumerate the same kind see exactly the steel the world will draw. */
    private static List<Run> applyEdits(List<Run> input,PointSettings s){
        var result=new ArrayList<Run>(input);
        for(int i=0;i<result.size();i++){
            Run r=result.get(i);PointSettings.GuardEdit edit=s.guardEdits().get(i);if(edit==null)continue;
            double start=Math.max(0,Math.min(r.road.length,edit.start())),end=Math.max(0,Math.min(r.road.length,edit.end()));
            if(end>start+1e-5)result.set(i,new Run(r.road,start,end,r.offset,edit.flareStart(),edit.flareEnd(),r.profile,r.settings,edit.mergeGroup()));
        }
        return List.copyOf(result);
    }
    /** Only steel strictly inside the two running rails is a check rail. A band that sits on a
     * running-rail line is the stock rail immediately beside the switch blade: it is frog/track
     * steel, never a mergeable guard, so a selection must not claim it. */
    public static boolean isBladeAdjacent(Run run){
        return Math.abs(Math.abs(run.offset())-run.profile().centerOffset())<=run.profile().headWidth()*.5+1e-9;
    }
    private static List<Run> removeBladeSteel(List<Run> runs){
        var result=new ArrayList<Run>(runs.size());
        for(Run run:runs)if(!isBladeAdjacent(run))result.add(run);
        return result;
    }
    /** A generator's raw list brought to the state the world bakes: steel beside a switch blade
     * removed, then this view's manual stations applied by index. Every enumerator an editor can
     * select from returns this, so both sides agree on the index space and on the drawn steel. */
    static List<Run> edited(List<Run> runs,PointSettings s){return applyEdits(removeBladeSteel(runs),s);}
    public static List<Run> merge(List<Run> input){return merge(input,null);}
    /** The same merge, additionally reporting which input runs each returned rail was built from.
     * The editor needs that identity: at a shallow crossing the centre lines of two differently
     * oriented check rails pass within the merge tolerance, so matching a selection back by
     * geometry alone lit up rails the user never clicked. {@code sources} is filled with one index
     * set per returned run, in the returned order. */
    public static List<Run> merge(List<Run> input,List<Set<Integer>> sources){
        var keys=new ArrayList<Set<Integer>>();
        var rebased=new ArrayList<Run>();
        for(int index=0;index<input.size();index++){
            Run r=input.get(index).canonical();var key=new LinkedHashSet<Integer>();key.add(index);
            for(int i=0;i<rebased.size();i++){Run match=rebase(r,rebased.get(i));if(match!=null){r=match;key.addAll(keys.get(i));break;}}
            rebased.add(r);keys.add(key);
        }
        var order=new ArrayList<Integer>();var byRoad=new LinkedHashMap<String,List<Integer>>();
        for(int i=0;i<rebased.size();i++)byRoad.computeIfAbsent(rebased.get(i).road.id,k->new ArrayList<>()).add(i);
        for(var ids:byRoad.values()){ids.sort(Comparator.comparingDouble(i->rebased.get(i).start()));order.addAll(ids);}
        var result=new ArrayList<Run>();var owners=new ArrayList<Set<Integer>>();
        for(int index:order){Run r=rebased.get(index);int found=-1;
            for(int i=result.size()-1;i>=0;i--){Run p=result.get(i);
                if(!p.road.id.equals(r.road.id)||!compatible(p,r))continue;
                // Automatic runs must really overlap; one manual group is one rail the user
                // drew as one, so a seam inside the sampling error may be bridged.
                double gap=Math.max(0,Math.max(p.start-r.end,r.start-p.end));
                if(gap<=(p.mergeGroup.isBlank()||!p.mergeGroup.equals(r.mergeGroup)?1e-7:MANUAL_GAP)){found=i;break;}
            }
            if(found<0){result.add(r);owners.add(new LinkedHashSet<>(keys.get(index)));}
            else {Run p=result.get(found);
                // A mouth belongs to the extreme end of the union: the run that reaches
                // further owns it, so an interior seam never keeps a flare and an outer
                // extension never loses one.
                Run merged=new Run(p.road,Math.min(p.start,r.start),Math.max(p.end,r.end),p.offset,
                        r.start<p.start?r.flareStart:p.flareStart,
                        r.end>p.end?r.flareEnd:p.flareEnd,
                        p.profile,p.settings,p.mergeGroup.isBlank()?r.mergeGroup:p.mergeGroup);
                result.set(found,merged);owners.get(found).addAll(keys.get(index));}
        }
        if(sources!=null){sources.clear();for(Set<Integer> owner:owners)sources.add(Set.copyOf(owner));}
        return List.copyOf(result);
    }
    /** The mismatch a manual merge group is allowed to absorb. Kept in one place so the editor
     * decides whether a selection can be merged with exactly the rule that later merges it. */
    public static double mergeTolerance(Profile a,Profile b){return Math.max(.12,Math.min(a.gauge(),b.gauge())*.12);}
    /** True when two runs describe one physical check rail: same profile and height, the same side
     * of one road, or co-directional centre lines within tolerance on two different roads. The
     * editor refuses a merge group that fails this, because no geometry could follow it. */
    public static boolean mergeable(Run a,Run b){
        if(!a.profile.equals(b.profile)||a.settings.verticalOffset()!=b.settings.verticalOffset())return false;
        if(a.road.id.equals(b.road.id))return Math.abs(a.offset-b.offset)<=1e-5;
        Run first=a.end-a.start<=b.end-b.start?a:b,second=first==a?b:a;
        double tolerance=mergeTolerance(a.profile,b.profile);
        int samples=Math.max(8,(int)Math.ceil((first.end-first.start)/.12));
        for(int i=0;i<=samples;i++){
            double d=first.start+(first.end-first.start)*i/samples,at=second.road.nearest(first.center(d));
            if(Math.abs(second.road.tangent(at).dot(first.road.tangent(d)))<.999||first.center(d).distance(second.center(at))>tolerance)return false;
        }
        // The projection must also land on the other run, so a manual group never claims two
        // collinear rails that are metres apart: rebase() refuses exactly the same seams.
        double firstAt=second.road.nearest(first.center(first.start)),secondAt=second.road.nearest(first.center(first.end));
        double lo=Math.min(firstAt,secondAt),hi=Math.max(firstAt,secondAt);
        return Math.max(0,Math.max(second.start-hi,lo-second.end))<=MANUAL_GAP;
    }
    private static boolean compatible(Run a,Run b){
        if(!a.profile.equals(b.profile)||a.settings.verticalOffset()!=b.settings.verticalOffset())return false;
        // A group is one drawn rail, so its runs must still sit on the same side of the track:
        // tolerating opposite offsets would silently delete the far check rail of the pair.
        if(!a.mergeGroup.isBlank()&&a.mergeGroup.equals(b.mergeGroup))return Math.abs(a.offset-b.offset)<=mergeTolerance(a.profile,b.profile);
        return Math.abs(a.offset-b.offset)<=1e-5;
    }
    private static Run rebase(Run r,Run target){return rebase(r,target,!r.mergeGroup.isBlank()&&r.mergeGroup.equals(target.mergeGroup));}
    private static Run rebase(Run r,Run target,boolean manual){
        if(r.road.id.equals(target.road.id)||!r.profile.equals(target.profile)||r.settings.verticalOffset()!=target.settings.verticalOffset())return null;
        double tolerance=manual?mergeTolerance(r.profile,target.profile):Math.max(.0125,Math.min(r.profile.headWidth(),target.profile.headWidth())*.25);
        int samples=Math.max(8,(int)Math.ceil((r.end-r.start)/.12));double first=0,last=0;
        for(int i=0;i<=samples;i++){
            double d=r.start+(r.end-r.start)*i/samples,at=target.road.nearest(r.center(d));
            V3 tangent=target.road.tangent(at);
            if(Math.abs(tangent.dot(r.road.tangent(d)))<.999||r.center(d).distance(target.center(at))>tolerance)return null;
            if(i==0)first=at;if(i==samples)last=at;
        }
        double lo=Math.min(first,last),hi=Math.max(first,last);
        if(!manual&&(hi<=target.start+1e-7||lo>=target.end-1e-7))return null;
        // A manual group closes a real seam between two rails of one track, not a run metres away.
        if(manual&&Math.max(0,Math.max(target.start-hi,lo-target.end))>MANUAL_GAP)return null;
        return new Run(target.road,lo,hi,target.offset,first<last?r.flareStart:r.flareEnd,first<last?r.flareEnd:r.flareStart,r.profile,r.settings,r.mergeGroup);
    }
    /** Re-expose the ends a merge closed: the flare belongs to the extreme end of the union, not
     * to the interior seam where two coincident runs were stitched together. */
    public static List<Run> exposeEnds(List<Run> runs){
        var result=new ArrayList<Run>(runs.size());
        for(Run run:runs){
            boolean start=run.flareStart(),end=run.flareEnd();
            for(Run other:runs)if(other!=run){
                if(coveredEnd(run,run.start(),other))start=false;
                if(coveredEnd(run,run.end(),other))end=false;
            }
            result.add(start==run.flareStart()&&end==run.flareEnd()?run:new Run(run.road(),run.start(),run.end(),run.offset(),start,end,run.profile(),run.settings(),run.mergeGroup()));
        }
        return List.copyOf(result);
    }
    private static boolean coveredEnd(Run run,double d,Run other){
        // Test the working centre lines, before either exposed mouth is flared inward.
        // Otherwise the 10 cm flare itself makes physically overlapping runs look separate.
        V3 point=run.center(d);double near=other.road().nearest(point);
        if(near<other.start()-.12||near>other.end()+.12)return false;
        // Collinearity only: two runs may describe one physical rail from roads whose node order is
        // opposite, and an anti-parallel pair is still the same steel.
        if(Math.abs(run.road().tangent(d).dot(other.road().tangent(near)))<.98)return false;
        // Slightly different MTR centre lines may still describe one overlapping rail.
        // Use the full section width, otherwise both internal flared mouths survive.
        return point.distance(other.center(near))<(run.profile().footWidth()+other.profile().footWidth())*.5;
    }
}
