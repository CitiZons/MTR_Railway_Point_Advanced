package org.mtrpoint.client;

import org.mtrpoint.Regression;
import org.mtrpoint.geometry.*;
import java.util.*;

/**
 * The toe hand-over: over the stretch a view claims, every running rail of every road it owns has to
 * be steel in the mesh - both in the preview mesh the editing screen draws and in the final world
 * mesh. The native cells are hidden over exactly that window, so a stretch the mesh skips is a hole
 * in the world rather than a hand-over. The plain fixture, its left/right mirror and its reversed
 * direction are all sampled, because the two roads are not symmetric.
 */
public final class ToeCoverageRegression {
    private static final List<String> failures=new ArrayList<>();
    private static final PointSettings s=PointSettings.DEFAULT;
    private static void check(boolean pass,String why){if(!pass){failures.add(why);System.out.println("FAIL: "+why);}}
    private static final double TOE_LIMIT=7.5;

    public static void run()throws Exception{
        runModel();
        runThreeBuiltIn();
        Profile p=Profile.STANDARD.tune(s);
        double top=p.top()+s.verticalOffset(),offset=p.centerOffset();
        int samples=0,missing=0;
        for(var fixture:List.of("plain","mirrored","reversed")){
            var tracks=tracks(fixture);
            var junctions=Detector.find(tracks);
            check(!junctions.isEmpty(),"the "+fixture+" toe fixture produced no junction");
            if(junctions.isEmpty())continue;
            var views=PointRenderer.viewsForTest(junctions,s,Profile.STANDARD);
            check(!views.isEmpty(),"the "+fixture+" toe fixture produced no view");
            if(views.isEmpty())continue;
            Mesh world=PointRenderer.worldForTest(views);
            check(!world.quads.isEmpty(),"the "+fixture+" toe fixture produced no world mesh");
            for(var view:views){
                if(view.junction.kind()!=Junction.Kind.Y)continue;
                for(boolean preview:new boolean[]{true,false}){
                    Mesh mesh=preview?view.mesh():world;
                    String where="the "+fixture+" "+(preview?"preview":"world")+" mesh";
                    for(int road=0;road<2;road++){
                        Track track=road==0?view.junction.a():view.junction.b();
                        double end=Math.min(TOE_LIMIT,PointMesh.extent(view.junction,s));
                        for(int sign:new int[]{-1,1}){
                            double first=-1,last=-1,before=-1;
                            var gaps=new ArrayList<String>();
                            for(double d=.05;d<=end;d+=.05){
                                V3 point=routeRail(view.junction,track,road,sign,d,p,s,0);
                                samples++;
                                if(steelNear(mesh,view.junction,track,road,sign,d,top,p,s)){
                                    if(first<0)first=d;last=d;
                                    if(before>=0){gaps.add(round(before)+".."+round(d));before=-1;}
                                }else{missing++;if(before<0)before=d;}
                            }
                            if(before>=0)gaps.add(round(before)+".."+round(end));
                            System.out.println("     coverage "+fixture+" "+(preview?"preview":"world")
                                +" road "+(road==0?"a":"b")+" rail "+(sign<0?"-":"+")
                                +" stations 0.05.."+round(end)+": first "+round(first)+", last "+round(last)
                                +(gaps.isEmpty()?"":", gaps "+gaps));
                            check(gaps.isEmpty(),where+" leaves the "+(road==0?"through":"diverging")+" road's "
                                +(sign<0?"left":"right")+" stock rail open over "+gaps+" of its own toe window");
                        }
                    }
                }
                break;
            }
        }
        check(samples>0,"no toe station was sampled at all");
        check(missing==0,"the toe meshes are missing steel at "+missing+" of "+samples+" running-rail samples");
        if(!failures.isEmpty())throw new AssertionError(String.join("\n",failures));
        System.out.println("PASS: both roads of a turnout are solid steel over the whole toe window ("+samples
            +" running-rail samples, plain/mirrored/reversed, left and right rails) in the preview mesh and in the final world mesh");
    }

    /** Diagnostic only: the same whole-junction sweep on the three-way fixture with the built-in
     *  section, so the model-section hole can be told apart from a three-way window bug. */
    private static void runThreeBuiltIn()throws Exception{
        Profile profile=Profile.STANDARD,p=profile.tune(s);
        double top=p.top()+s.verticalOffset(),offset=p.centerOffset();
        var junctions=Detector.find(threeTracks(.008));
        if(junctions.isEmpty()){System.out.println("     three built-in: no junction detected");return;}
        var views=PointRenderer.viewsForTest(junctions,s,profile);
        Mesh world=PointRenderer.worldForTest(views);
        for(var view:views){
            if(view.junction.kind()==Junction.Kind.DIAMOND)continue;
            for(boolean preview:new boolean[]{true,false}){
                Mesh mesh=preview?view.mesh():world;
                double end=PointMesh.extent(view.junction,s);
                var threeRoads=view.junction.tracks();
                for(int road=0;road<threeRoads.size();road++)for(int sign:new int[]{-1,1}){
                    Track track=threeRoads.get(road);
                    double hole=0,maxHole=0,holeEnd=0,holeStart=0,bestStart=0;
                    for(double d=.05;d<=end;d+=.05){
                        V3 point=routeRail(view.junction,track,road,sign,d,p,s,0);
                        if(steelNear(mesh,view.junction,track,road,sign,d,top,p,s)){if(hole>maxHole){maxHole=hole;holeEnd=d;holeStart=bestStart;}hole=0;}
                        else{if(hole==0)bestStart=d;hole+=.05;}
                    }
                    if(hole>maxHole){maxHole=hole;holeEnd=end;holeStart=bestStart;}
                    check(maxHole<=.35,"the built-in three-way "+(preview?"preview":"world")+" mesh leaves a "+round(maxHole)
                        +" m hole in road "+track.id+"'s "+(sign<0?"left":"right")+" rail inside the junction");
                    System.out.println("     three built-in "+(preview?"preview":"world")+" road "+track.id
                        +" "+(sign<0?"left":"right")+" longest hole "+round(maxHole)+" m"
                        +(maxHole>.35?" at "+round(holeStart)+".."+round(holeEnd)+" (movableFrog="+s.movableFrog()
                            +" extent="+round(PointMesh.extent(view.junction,s))+")":""));
                    if(maxHole>.35)for(int x=0;x<3;x++)for(int y=x+1;y<3;y++){
                        if(x!=1&&y!=1)continue;
                        int local=x==1?0:1;
                        Junction pair=new Junction("p"+x+y,Junction.Kind.Y,view.junction.tracks().get(x),
                            view.junction.tracks().get(y),view.junction.center(),0,0,view.junction.extent());
                        var frog=new FrogGeometry(pair,s,p,PointMesh.extent(view.junction,s),false);
                        System.out.println("       pair "+x+y+" road tm toe/heel = "+round(frog.toe(local))+".."+round(frog.heel(local))
                            +" (sa="+round(frog.sa)+" sb="+round(frog.sb)+")");
                    }
                }
            }
            break;
        }
    }
    /** The same hand-over on a native model section: the drawn steel is model quads, so any cut path
     *  that pairs quad corners wrongly stops cutting exactly the rails the game really draws. */
    private static void runModel()throws Exception{
        Profile profile=ChannelCutRegression.modelled(),p=profile.tune(s);
        double top=p.top()+s.verticalOffset(),offset=p.centerOffset();
        int samples=0,missing=0;
        for(var fixture:List.of("plain","mirrored","reversed","three","threeSharp")){
            var junctions=Detector.find(tracks(fixture));
            if(junctions.isEmpty())continue;
            var views=PointRenderer.viewsForTest(junctions,s,profile);
            if(views.isEmpty())continue;
            Mesh world=PointRenderer.worldForTest(views);
            for(var view:views){
                if(view.junction.kind()==Junction.Kind.DIAMOND)continue;
                for(boolean preview:new boolean[]{true,false}){
                    Mesh mesh=preview?view.mesh():world;
                    var roads=view.junction.tracks();
                    for(int road=0;road<roads.size();road++){
                        Track track=roads.get(road);
                        // The whole junction, not just the toe: the frog, blade and wing cuts now really
                        // bite on model steel, so nothing may be missing except the flange ways, which
                        // are far shorter than this threshold.
                        double end=PointMesh.extent(view.junction,s);
                        for(int sign:new int[]{-1,1}){
                            double hole=0,maxHole=0;
                            for(double d=.05;d<=end;d+=.05){
                                V3 point=routeRail(view.junction,track,road,sign,d,p,s,0);
                                samples++;
                                if(steelNear(mesh,view.junction,track,road,sign,d,top,p,s)){maxHole=Math.max(maxHole,hole);hole=0;}
                                else hole+=.05;
                            }
                            maxHole=Math.max(maxHole,hole);
                            check(maxHole<=.35,"the model "+fixture+" "+(preview?"preview":"world")+" mesh leaves a "+round(maxHole)
                                +" m hole in road "+track.id+"'s "+(sign<0?"left":"right")
                                +" rail inside the junction, longer than any flange way");
                        }
                    }
                }
                break;
            }
        }
        check(samples>0,"no model toe station was sampled at all");
        check(missing==0,"the model toe meshes are missing steel at "+missing+" of "+samples+" running-rail samples");
        System.out.println("     model section coverage: "+samples+" samples, "+missing+" missing");
    }
    /** Where the route rail of this side really is: inside the planed run of the switch side the
     *  moving switch rail is the route rail and no fixed stock rail is drawn beside it; everywhere
     *  else the fixed stock rail is. This mirrors what PointMesh builds. */
    static V3 routeRail(Junction j,Track track,int branch,int sign,double d,Profile p,PointSettings s,double position){
        double extent=PointMesh.extent(j,s),offset=p.centerOffset();
        if(j.kind()!=Junction.Kind.Y||d<=0)return track.at(d).add(track.tangent(d).lateral().mul(sign*offset));
        double side=TurnoutFrame.side(j,extent),frog=extent*.65,bladeStart=TurnoutFrame.start(j,extent);
        double blade=s.bladeLength()>0?s.bladeLength():Math.max(2,Math.min((frog-bladeStart)*.65,9));
        double localStart=branch==0?bladeStart:j.b().nearest(j.a().at(bladeStart));
        boolean inner=sign==(branch==0?side:-side);
        if(!inner||d<=localStart||d>=localStart+blade)return track.at(d).add(track.tangent(d).lateral().mul(sign*offset));
        double open=branch==0?position:1-position;
        return TurnoutFrame.blade(track,d,sign,offset,open,localStart,blade,s);
    }
    /** Steel of the route rail near this station: on the switch side the moving switch rail may sit
     *  a blade throw away from the stock position, so the running line has to be searched across a
     *  narrow window - a switch rail is not a hole, but a stretch with no steel anywhere is. */
    private static boolean steelNear(Mesh mesh,Junction j,Track track,int branch,int sign,double d,double top,Profile p,PointSettings s){
        V3 base=track.at(d),side=track.tangent(d).lateral();double offset=p.centerOffset();
        for(double lat=-.20;lat<=.2001;lat+=.02){
            if(steelAt(mesh,base.add(side.mul(sign*offset+lat)),top,p))return true;
        }
        return false;
    }
    /** Steel of a running rail at this station: the head's top face covers the point. */
    static boolean steelAt(Mesh mesh,V3 centre,double top,Profile p){
        for(var q:mesh.quads){
            if(q.part().equals("sleeper")||q.part().equals("fastener"))continue;
            if(Math.abs(q.a().y()-top)>.006&&Math.abs(q.b().y()-top)>.006&&Math.abs(q.c().y()-top)>.006&&Math.abs(q.d().y()-top)>.006)continue;
            if(triangle(q.a(),q.b(),q.c(),centre)||triangle(q.a(),q.c(),q.d(),centre))return true;
        }
        return false;
    }
    /** Point-in-triangle on the ground plane: the sweep is vertical, so the top face decides. */
    private static boolean triangle(V3 a,V3 b,V3 c,V3 p){
        double area=(b.x()-a.x())*(c.z()-a.z())-(b.z()-a.z())*(c.x()-a.x());
        if(Math.abs(area)<1e-12)return false;
        double d1=((b.x()-a.x())*(p.z()-a.z())-(b.z()-a.z())*(p.x()-a.x()))/area;
        double d2=((c.x()-b.x())*(p.z()-b.z())-(c.z()-b.z())*(p.x()-b.x()))/area;
        double d3=((a.x()-c.x())*(p.z()-c.z())-(a.z()-c.z())*(p.x()-c.x()))/area;
        return d1>=-2e-3&&d2>=-2e-3&&d3>=-2e-3;
    }
    private static double round(double v){return Math.round(v*100)/100D;}

    /** The turnout fixture, its lateral mirror and its reversed direction: the two roads differ, so a
     *  one-sided skip only shows on one of them. */
    private static List<Track> tracks(String kind){
        if(kind.equals("three"))return threeTracks(.008);
        if(kind.equals("threeSharp"))return threeTracks(.012);
        if(kind.equals("reversed")){
            // A rail whose sampled points are stored the other way round: the detector canonicalises
            // it back to the node, so the mesh has to land on the same steel as the plain direction.
            var plain=plain();
            return List.of(plain.get(0).reverse(),plain.get(1).reverse(),plain.get(2).reverse());
        }
        if(!kind.equals("mirrored"))return plain();
        var a=new ArrayList<V3>();var b=new ArrayList<V3>();
        for(int i=0;i<=120;i++){double z=i*.25,x=.008*z*z;a.add(new V3(x,0,z));b.add(new V3(-x,0,z));}
        return List.of(new Track("a","0,0,0","7,0,30",a),new Track("b","0,0,0","-7,0,30",b),
            Regression.line("in","0,0,-20","0,0,0",new V3(0,0,-20),new V3(0,0,0)));
    }
    /** A three-way fan: two diverging roads and the middle road that shares the same node. */
    private static List<Track> threeTracks(double k){
        var a=new ArrayList<V3>();var b=new ArrayList<V3>();var m=new ArrayList<V3>();
        for(int i=0;i<=120;i++){
            double z=i*.25,x=k*z*z;
            a.add(new V3(-x,0,z));b.add(new V3(x,0,z));m.add(new V3(0,0,z));
        }
        return List.of(new Track("ta","0,0,0","-7,0,30",a),new Track("tb","0,0,0","7,0,30",b),
            new Track("tm","0,0,0","0,0,30",m));
    }
    private static List<Track> plain(){
        var a=new ArrayList<V3>();var b=new ArrayList<V3>();
        for(int i=0;i<=120;i++){double z=i*.25,x=.008*z*z;a.add(new V3(-x,0,z));b.add(new V3(x,0,z));}
        return List.of(new Track("a","0,0,0","-7,0,30",a),new Track("b","0,0,0","7,0,30",b),
            Regression.line("in","0,0,-20","0,0,0",new V3(0,0,-20),new V3(0,0,0)));
    }
}
