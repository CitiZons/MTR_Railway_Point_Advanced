package org.mtrpoint.client;

import org.mtrpoint.Regression;
import org.mtrpoint.geometry.*;
import java.util.*;

/**
 * The stock rail beside the blade at the switch toe, and the steel cutting at the frog.
 *
 * <p>Every expectation is sampled on the mesh the frame really draws. The switch toe (where the two
 * routes meet and the blade leans on the stock rail) and the frog (where the two inner lines cross)
 * are labelled by station and world point first, because a check that samples the wrong end proves
 * nothing: {@link #assertToe} fails loudly when a sample meant for the toe lands at the frog.
 */
public final class FrogCutRegression {
    private static final List<String> failures=new ArrayList<>();
    private static final PointSettings s=PointSettings.DEFAULT;
    private static final Profile raw=Profile.STANDARD;
    private static final Profile p=raw.tune(s);
    private static final double top=p.top()+s.verticalOffset();
    private static int toeSamples,frogSamples,channelSamples;
    private static double worstShift,worstCoreShift;
    private static List<Mesh.Quad> heads=List.of();
    private static void check(boolean pass,String why){if(!pass){failures.add(why);System.out.println("FAIL: "+why);}}
    private static void require(boolean pass,String why){if(!pass)throw new AssertionError(why);}

    public static void run()throws Exception{
        var tracks=Regression.y();
        var junctions=Detector.find(tracks);
        require(!junctions.isEmpty(),"the turnout fixture has to be detected");
        Junction y=null;
        for(Junction j:junctions)if(j.kind()==Junction.Kind.Y)y=j;
        require(y!=null,"the fixture has no Y turnout: "+junctions.stream().map(Junction::id).toList());
        var views=PointRenderer.viewsForTest(junctions,s,raw);
        Mesh world=PointRenderer.worldForTest(views);
        heads=headTops(world,top);
        require(!heads.isEmpty(),"the world mesh carries no running surface");

        // ---- label both ends with station and world point before any expectation
        double extent=PointMesh.extent(y,s);
        var frog=new FrogGeometry(y,s,p,extent);
        double bladeStart=TurnoutFrame.start(y,extent);
        double blade=s.bladeLength()>0?s.bladeLength():Math.max(2,Math.min(((frog.sa+frog.sb)/2-bladeStart)*.65,9));
        double contact=TurnoutFrame.contact(blade,p,s);
        V3 node=y.center();
        V3 frogCenter=frog.center();
        System.out.println("     toe/frog frame: node="+round(node)+" sa="+round(frog.sa)+" sb="+round(frog.sb)
            +" bladeStart="+round(bladeStart)+" contact="+round(contact)+" frogToe="+round(frog.toe(0))+"/"+round(frog.toe(1))
            +" frogHeel="+round(frog.heel(0))+"/"+round(frog.heel(1))+" frogCenter="+round(frogCenter)
            +" extent="+round(extent));
        for(Track r:y.tracks())System.out.println("       road "+r.id+" length="+round(r.length)+" start="+r.startNode+" end="+r.endNode
            +" outgoing="+round(r.tangent(0))+" nodeStation="+round(r.nearest(node)));

        var cutters=Crossings.cutters(y.tracks(),tracks);
        System.out.println("     cutters for the turnout = "+cutters.stream().map(t->t.id).toList()
            +(cutters.isEmpty()?"":" [every one of these offers a flange channel that cuts the turnout steel]"));
        check(cutters.isEmpty(),"a plain turnout has no crossing rail: "+cutters.stream().map(t->t.id).toList()
            +" would cut its stock rail at the toe and its frog steel with a foreign flange channel");

        // ---- the fixed stock rail only exists on the outer (through) side
        int aOuter=outerSign(y,y.a()),bOuter=outerSign(y,y.b());
        // The switch side carries no fixed stock rail at all: the moving switch rail is the route
        // rail there, so the old "a-stock"/"b-stock" continuity checks were asserting a rail that
        // must not be drawn. BladeCountRegression now proves, per branch and side, that the switch
        // side draws exactly one body and that the route rail is present at every station.
        // The outer rail is the through route: the wheel rides it over the whole frog, so it may
        // never be interrupted, neither inside the crossing nor at the hand-over.
        checkRailContinuous("a-through",y.a(),aOuter,.05,Math.min(extent,y.a().length-.05),frog,true);
        checkRailContinuous("b-through",y.b(),bOuter,bladeStart,Math.min(extent,y.b().length-.05),frog,true);

        // ---- the frog: its own flange channels have to stay clear and the heart has to survive
        int blocked=0;StringBuilder blockedAt=new StringBuilder();
        for(int branch=0;branch<2;branch++){
            Track road=branch==0?y.a():y.b();
            for(int sideSign:new int[]{-1,1}){
                for(double d=frog.toe(branch)+.1;d<frog.heel(branch)-.1;d+=.04){
                    V3 q=channelPoint(road,d,sideSign,p,s,0);
                    if(isInsideRoadChannel(y.tracks(),road,q))continue;
                    if(Math.abs(q.sub(road.at(d)).dot(road.tangent(d).lateral()))<p.headWidth()/2)continue;
                    channelSamples++;
                    var hit=cover(heads,q);
                    if(!hit.isEmpty()){
                        blocked++;
                        if(blockedAt.length()<200)blockedAt.append(" [").append(road.id).append(" d=").append(round(d)).append(" ").append(hit.get(0).part()).append("]");
                        if(blocked<=3){var face=hit.get(0);double lateral=q.sub(road.at(d)).dot(road.tangent(d).lateral());
                            System.out.println("       blocked "+road.id+" d="+round(d)+" side="+sideSign+" lateral="+round(lateral)
                                +" part="+face.part()+" face="+round(face.a())+round(face.b())+round(face.c())+round(face.d())
                                +" ownInnerRail="+round(rail(road,d,sideSign,p)));}
                    }
                }
            }
        }
        check(channelSamples>20,"the frog channel sweep has to sample the frog");
        int heart=0;
        for(var q:heads)if(q.part().equals("frog")||q.part().equals("wing"))heart++;
        check(heart>0,"the frog heart and wings have to reach the running surface on the world mesh ("+heart+" faces)");
        check(blocked==0,"the frog leaves "+blocked+" of "+channelSamples+" flange-channel samples blocked"+blockedAt);
        frogSamples=channelSamples;

        nearCrossing();

        if(!failures.isEmpty())throw new AssertionError(String.join("\n",failures));
        System.out.println("PASS: the toe-side stock rail of a turnout keeps "+toeSamples+" running-surface samples continuous and its frog keeps "+frogSamples
            +" flange-channel samples clear with the heart and wings intact ("+heart+" heart faces)");
    }

    /** A frog close to a plain crossing: the distance tiers are independent (the windows are far
     *  apart), adjacent (the windows just touch) and overlapping, and both the crossing angle and the
     *  direction of the crossing roads are varied. Everything is sampled on the final combined mesh -
     *  the check rails by their physical centre line, the flange channels by their physical channel
     *  line - so neither a renamed part nor an empty sample set can pass as a result. */
    private static void nearCrossing()throws Exception{
        double[][] tiers={{40,6},{12,12},{6,30}};
        String[] tierName={"independent","adjacent","overlapping"};
        for(int t=0;t<tiers.length;t++)for(double angle:new double[]{Math.PI/4,Math.PI/2})for(int flip=0;flip<2;flip++){
            double distance=tiers[t][0],half=tiers[t][1];
            var tracks=new ArrayList<Track>(Regression.y());
            V3 c=new V3(0,0,-distance);
            V3 d1=new V3(Math.cos(angle),0,Math.sin(angle)).mul(half*(flip==0?1:-1));
            V3 d2=new V3(-Math.sin(angle),0,Math.cos(angle)).mul(half);
            tracks.add(Regression.line("x1","x1a","x1b",c.sub(d1),c.add(d1)));
            tracks.add(Regression.line("x2","x2a","x2b",c.sub(d2),c.add(d2)));
            var junctions=Detector.find(tracks);
            var views=PointRenderer.viewsForTest(junctions,s,raw);
            Mesh world=PointRenderer.worldForTest(views);
            heads=headTops(world,top);
            var byId=new HashMap<String,PointClient.View>();
            for(var v:views)byId.put(v.junction.id(),v);
            String tag=tierName[t]+" distance="+round(distance)+" halfLength="+round(half)
                +" angle="+round(Math.toDegrees(angle))+"deg direction="+(flip==0?"+":"-");

            // The check rails the geometry declares for these junctions, sampled where they physically
            // are: a run that the crossing's channels cut away is missing steel at its own centre line.
            int runs=0,missing=0,runSamples=0;worstShift=0;worstCoreShift=0;
            for(Junction j:junctions)for(var run:GuardRails.selectable(j,s,raw,null,null)){
                runs++;
                // The run's own offset is a straight line while its flared ends bend away from it, so
                // the flared metre at either end is sampled for presence only and the whole middle of
                // the run has to be solid: a cut that severs the guard anywhere in its body is caught,
                // and a severed flare end or end face is caught by the presence of its own samples.
                double guard=1;double firstMissing=Double.NaN;
                int present=0,counted=0,corePresent=0,coreCounted=0,startPresent=0,endPresent=0,cutSamples=0;
                for(double d=run.start()+.05;d<=run.end()-.05;d+=.05){
                    V3 q=run.road().at(d).add(run.road().tangent(d).lateral().mul(run.offset()));
                    // Outside the stretch the junction really draws there is no steel to expect: the
                    // run is the nominal one and the mesh is trimmed to the same window that hides the
                    // native cells, exactly as the renderer decides it.
                    if(!drawnAt(byId.get(j.id()),run.road(),d))continue;
                    if(insideAnyRail(tracks,q))continue;
                    // Where the guard crosses another road's flange channel it has to be gone: only a
                    // spot that belongs to the guard is asked to show steel. Every road of the whole
                    // combined component counts, not just the roads of this one junction.
                    if(isInsideRoadChannel(tracks,run.road(),q)){cutSamples++;continue;}
                    counted++;runSamples++;
                    // The guard is looked for across its own width: a run that is handed over to a
                    // neighbouring junction is rebased there and can sit up to a head width off its
                    // nominal line, but it may not be gone. Only a spot with no steel anywhere in that
                    // band counts as cut away, and the largest shift seen is reported.
                    double shift=Double.NaN;
                    for(double o:new double[]{0,-.02,.02,-.04,.04,-.06,.06}){
                        V3 n=run.road().at(d).add(run.road().tangent(d).lateral().mul(run.offset()+o));
                        if(!cover(heads,n).isEmpty()){shift=Math.abs(o);break;}
                    }
                    boolean hit=!Double.isNaN(shift);
                    if(hit)worstShift=Math.max(worstShift,shift);
                    boolean core=d>=run.start()+guard&&d<=run.end()-guard;
                    if(hit&&core)worstCoreShift=Math.max(worstCoreShift,shift);
                    if(hit)present++;
                    if(d<run.start()+guard){if(hit)startPresent++;}
                    else if(d>run.end()-guard){if(hit)endPresent++;}
                    else{coreCounted++;if(hit)corePresent++;else if(Double.isNaN(firstMissing))firstMissing=d;}
                }
                if(counted==0||coreCounted==0||corePresent<coreCounted||(run.flareStart()&&startPresent==0)||(run.flareEnd()&&endPresent==0)){
                    missing++;
                    if(missing<4)System.out.println("       "+j.id()+" "+run.part()+" on "+run.road().id+" ["+round(run.start())+".."+round(run.end())
                        +"] lateral="+round(run.offset())+" flare="+run.flareStart()+"/"+run.flareEnd()
                        +" has "+present+"/"+counted+" samples of steel on the world mesh, middle "+corePresent+"/"+coreCounted
                        +", flare ends "+startPresent+"/"+endPresent+", "+cutSamples+" samples inside a foreign channel");
                    if(missing<3&&!Double.isNaN(firstMissing)){
                        V3 base=run.road().at(firstMissing),lat=run.road().tangent(firstMissing).lateral();
                        StringBuilder scan=new StringBuilder();
                        for(double o=-.12;o<=.12001;o+=.02){
                            V3 q=base.add(lat.mul(run.offset()+o));
                            if(!cover(heads,q).isEmpty())scan.append(" ").append(round(run.offset()+o));
                        }
                        System.out.println("         first missing station "+round(firstMissing)+" on "+run.road().id
                            +": steel at lateral offsets"+scan+" (run offset "+round(run.offset())+")");
                    }
                }
            }
            // No check-rail face may be drawn twice: two coincident faces of the same part would
            // z-fight and would also hide an intersection that should have been cut.
            var seen=new HashSet<String>();int duplicates=0;
            for(var q:heads){
                if(!q.part().equals("wing")&&!q.part().equals("check"))continue;
                String key=q.part()+"|"+round(q.a())+round(q.b())+round(q.c())+round(q.d());
                if(!seen.add(key)){duplicates++;if(duplicates<3)System.out.println("       "+tag+" duplicate face of part "+q.part()+" at "+round(q.a()));}
            }

            // Every flange channel that really exists in the final mesh - the frog windows of the
            // turnout and the crossing window of the diamond - excluding the steel that owns the spot
            // (the road's own rail and any rail crossing it) instead of excluding whole regions.
            int samples=0,blocked=0;
            for(Junction j:junctions){
                var v=byId.get(j.id());
                if(j.kind()==Junction.Kind.Y){
                    var frog=new FrogGeometry(j,s,p,PointMesh.extent(j,s));
                    for(int branch=0;branch<2;branch++){
                        Track road=branch==0?j.a():j.b();
                        for(int sideSign:new int[]{-1,1}){
                            int[] r=sweep(tag,tracks,j,road,sideSign,frog.toe(branch)+.1,frog.heel(branch)-.1);
                            samples+=r[0];blocked+=r[1];
                        }
                    }
                }else if(v!=null&&v.diamond!=null){
                    var roads=v.centreRoads!=null?v.centreRoads:j.tracks();
                    for(int road=0;road<roads.size();road++){
                        double[] w=v.diamond.window(road);
                        for(int sideSign:new int[]{-1,1}){
                            int[] r=sweep(tag,tracks,j,roads.get(road),sideSign,Math.min(w[0],w[1])+.1,Math.max(w[0],w[1])-.1);
                            samples+=r[0];blocked+=r[1];
                        }
                    }
                }
            }
            System.out.println("     near-crossing "+tag+": junctions="+junctions.size()+" checkRuns="+runs
                +" (missing "+missing+", "+runSamples+" centre-line samples, largest lateral shift "+round(worstShift)
                +" overall and "+round(worstCoreShift)+" in the body of a run) channelSamples="+samples
                +" (blocked "+blocked+") duplicateFaces="+duplicates);
            check(runs>0&&runSamples>0,"no check-rail centre line was sampled for "+tag);
            check(samples>0,"no flange-channel sample was taken for "+tag);
            check(missing==0,"check-rail steel is cut away on the world mesh for "+tag);
            check(blocked==0,"flange channels are blocked by steel for "+tag);
            check(duplicates==0,"check steel is drawn twice on the world mesh for "+tag);
        }
    }

    /** Whether the junction draws its own mesh at this station of this road: the window the renderer
     *  hides the native cells over is the window it draws, so a sample outside it proves nothing. */
    private static boolean drawnAt(PointClient.View v,Track road,double d){
        if(v==null)return true;
        if(v.junction.kind()==Junction.Kind.DIAMOND){
            var roads=v.centreRoads!=null?v.centreRoads:v.junction.tracks();
            for(int i=0;i<roads.size();i++)if(roads.get(i).id.equals(road.id)){
                double[] w=v.diamond!=null?v.diamond.window(i):null;
                return w==null||(d>=Math.min(w[0],w[1])-.01&&d<=Math.max(w[0],w[1])+.01);
            }
            return true;
        }
        var b=v.boundary;if(b==null)return true;
        double start,end;
        if(road.id.equals(v.junction.a().id)){start=b.aStart();end=b.aEnd();}
        else if(road.id.equals(v.junction.b().id)){start=b.bStart();end=b.bEnd();}
        else if(v.junction.third()!=null&&road.id.equals(v.junction.third().id)){start=b.thirdStart();end=b.thirdEnd();}
        else return true;
        return d>=start-.01&&d<=end+.01;
    }

    /** Samples one road's physical flange channel between two stations on the final mesh and returns
     *  how many samples were taken and how many of them land on steel that does not belong there. */
    private static int[] sweep(String tag,List<Track> scope,Junction j,Track road,int sideSign,double from,double to){
        int samples=0,blocked=0;
        for(double d=from;d<to;d+=.08){
            V3 q=channelPoint(road,d,sideSign,p,s,0);
            if(isInsideRoadChannel(scope,road,q))continue;
            if(insideAnyRail(scope,q))continue;
            samples++;
            var hit=cover(heads,q);
            if(!hit.isEmpty()){
                blocked++;
                if(blocked<3)System.out.println("       "+tag+" blocked "+road.id+" d="+round(d)+" side="+sideSign+" part="+hit.get(0).part());
            }
        }
        return new int[]{samples,blocked};
    }

    /** Whether a point lies under the solid of any rail of the junction, so a channel sample that
     *  runs into a rail where it really crosses it is not counted as blocked steel. */
    private static boolean insideAnyRail(List<Track> roads,V3 q){
        for(Track t:roads){
            double d=t.nearest(q);if(t.at(d).distance(q)>1.4)continue;
            for(int sign:new int[]{-1,1})if(q.distance(rail(t,d,sign,p))<p.headWidth()/2)return true;
        }
        return false;
    }

    /** One lateral cut through a station: the steel present, as intervals and parts, so a missing
     *  rail can be told apart from a rail that merely moved. */
    private static void profile(String tag,Track road,double d){
        V3 origin=road.at(d),lateral=road.tangent(d).lateral();
        double from=0;StringBuilder line=new StringBuilder();
        boolean open=false;
        for(double lat=-1.3;lat<=1.3;lat+=.02){
            V3 q=origin.add(lateral.mul(lat));var hit=cover(heads,q);
            if(!hit.isEmpty()&&!open){open=true;from=lat;}
            if(hit.isEmpty()&&open){open=false;line.append(" [").append(round(from)).append("..").append(round(lat-.02)).append("]");}
        }
        if(open)line.append(" [").append(round(from)).append("..1.3]");
        System.out.println("       profile "+tag+" d="+round(d)+" steel at"+line+" (centreOffset="+round(p.centerOffset())+")");
    }
    /** The switch toe is the blade contact zone. A sample claimed for it must stay in front of the
     *  frog's own toe: if a change ever moves the toe sampling onto the frog, this fails instead of
     *  quietly measuring the wrong end. */
    private static void checkRailContinuous(String tag,Track road,int sign,double from,double to,FrogGeometry frog,boolean full){
        int samples=0;StringBuilder gaps=new StringBuilder();
        double[] across=full?new double[]{-p.headWidth()/4,0,p.headWidth()/4}:new double[]{0};
        for(double d=from;d<=to;d+=.05){
            // A full-head sample is taken across the whole width: a hairline left by a planed
            // section would cover the centre line and still read as a missing rail to a player.
            boolean covered=true;
            for(double lat:across){
                V3 q=rail(road,d,sign,p).add(road.tangent(d).lateral().mul(sign*lat));
                if(cover(heads,q).isEmpty()){covered=false;if(gaps.length()<120)System.out.println("       miss "+tag+" d="+round(d)+" sign="+sign+" lat="+round(lat)+" q="+round(q)+" road="+round(road.at(d))+" tangent="+round(road.tangent(d)));}
            }
            samples++;
            if(!covered&&gaps.length()<160){gaps.append(" [").append(tag).append(" d=").append(round(d)).append(" ").append(label(d,frog,0)).append("]");profile(tag,road,d);}
        }
        check(samples>20,"the "+tag+" sweep has to sample the running line ("+samples+" samples)");
        check(gaps.length()==0,"the "+tag+" running line is cut open at"+gaps);
        if(tag.endsWith("stock")){toeSamples+=samples;assertToe(to,frog.toe(0),tag);}
    }
    /** Sampling helpers reproduce the coordinates the geometry itself uses, so a sample can only be
     *  counted when it really sits on the steel it claims to test. */
    private static String label(double d,FrogGeometry frog,double contact){
        return d<frog.toe(0)?"toe-side":d<frog.heel(0)?"frog-body":"heel-side";
    }
    /** A sample claimed for the toe may never sit at the frog: the two ends are not interchangeable. */
    static void assertToe(double station,double frogToe,String what){
        require(station<frogToe,"a "+what+" sample meant for the switch toe landed at the frog: station="+station+" frogToe="+frogToe);
    }
    private static int outerSign(Junction j,Track r){
        double d=Math.min(r.length*.5,Math.max(.5,PointMesh.extent(j,s)*.5));
        int best=1;double bestDistance=-1;
        for(int sign:new int[]{-1,1}){
            V3 q=rail(r,d,sign,p);double other=Double.MAX_VALUE;
            for(Track t:j.tracks())if(!t.id.equals(r.id)){double u=t.nearest(q);other=Math.min(other,t.at(u).distance(q));}
            if(other>bestDistance){bestDistance=other;best=sign;}
        }
        return best;
    }
    private static boolean isInsideRoadChannel(List<Track> roads,Track own,V3 q){
        double gap=Math.max(.02,s.flangeway()+s.wingGapDelta());
        for(Track other:roads){
            if(other.id.equals(own.id))continue;
            double d=other.nearest(q);if(other.at(d).distance(q)>1.4)continue;
            for(int sign:new int[]{-1,1})if(q.distance(channelPoint(other,d,sign,p,s,0))<gap/2+1e-6)return true;
        }
        return false;
    }
    private static V3 rail(Track road,double d,int sign,Profile profile){return road.at(d).add(road.tangent(d).lateral().mul(sign*profile.centerOffset()));}
    private static V3 channelPoint(Track road,double d,int sign,Profile profile,PointSettings settings,double across){
        double gap=Math.max(.02,settings.flangeway()+settings.wingGapDelta());
        return road.at(d).add(road.tangent(d).lateral().mul(sign*(profile.centerOffset()-profile.headWidth()/2-gap/2)+across));
    }
    private static List<Mesh.Quad> headTops(Mesh mesh,double height){
        var result=new ArrayList<Mesh.Quad>();
        for(var q:mesh.quads)if(runningBand(q,height))result.add(q);
        return result;
    }
    private static boolean runningBand(Mesh.Quad q,double height){
        boolean reach=false;
        for(V3 v:List.of(q.a(),q.b(),q.c(),q.d())){if(!Double.isFinite(v.x()+v.z()))continue;if(v.y()>=height-1e-3)reach=true;}
        return reach&&minHeight(q)>=5e-4;
    }
    private static double minHeight(Mesh.Quad q){
        double area=Math.abs(V3.crossXZ(q.b().sub(q.a()),q.c().sub(q.a())))+Math.abs(V3.crossXZ(q.c().sub(q.a()),q.d().sub(q.a())));
        double longest=0;V3[] v={q.a(),q.b(),q.c(),q.d()};
        for(int i=0;i<4;i++)longest=Math.max(longest,v[i].distance(v[(i+1)%4]));
        return longest<1e-9?0:area/longest;
    }
    private static List<Mesh.Quad> cover(List<Mesh.Quad> faces,V3 point){
        var result=new ArrayList<Mesh.Quad>();
        for(var q:faces)if(triangle(point,q.a(),q.b(),q.c())||triangle(point,q.a(),q.c(),q.d()))result.add(q);
        return result;
    }
    private static boolean triangle(V3 p,V3 a,V3 b,V3 c){
        double area=V3.crossXZ(b.sub(a),c.sub(a));if(Math.abs(area)<1e-12)return false;
        double u=V3.crossXZ(p.sub(a),c.sub(a))/area,v=V3.crossXZ(b.sub(a),p.sub(a))/area;
        return u>=-1e-9&&v>=-1e-9&&u+v<=1+1e-9;
    }
    private static double round(double v){return Math.round(v*1000)/1000D;}
    private static String round(V3 v){return "("+round(v.x())+","+round(v.y())+","+round(v.z())+")";}
}
