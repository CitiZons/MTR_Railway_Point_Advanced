package org.mtrpoint.client;

import java.util.*;
import org.mtrpoint.geometry.*;

/**
 * Every physical stock rail of a turnout is one continuous piece of steel between the places where
 * it is meant to hand over. This walks each road and each side - preview mesh and world mesh, both
 * blade positions - and lists the covered intervals per rail together with the first station where
 * the steel is missing, so a "large area of broken stock rail" can be named by rail, side and
 * station instead of being seen in a screenshot.
 */
public final class StockRailIntervalRegression{
    private static final PointSettings s=PointSettings.DEFAULT;
    /** Longer than this with no steel anywhere in the search window is a visible break. */
    private static final double BREAK=.35;
    /** The window a wheel-side search has to look at: the stock rail, or the switch rail that takes
     *  its place inside the planed run, can sit up to a blade throw away from the nominal line. */
    private static final double WINDOW=.25;
    private static final List<String> failures=new ArrayList<>();
    private static void check(boolean pass,String why){if(!pass){failures.add(why);System.out.println("FAIL: "+why);}}

    public static void run()throws Exception{
        Profile p=Profile.STANDARD.tune(s);
        double top=p.top()+s.verticalOffset(),offset=p.centerOffset();
        var junctions=Detector.find(tracks());
        check(junctions.size()==1&&junctions.get(0).kind()==Junction.Kind.Y,
            "the stock-rail fixture is one Y turnout (found "+junctions.size()+")");
        if(junctions.isEmpty())return;
        Junction j=junctions.get(0);
        double extent=PointMesh.extent(j,s);
        check(extent>10,"the stock-rail fixture lost its length: "+extent);
        int rails=0,covered=0;StringBuilder listing=new StringBuilder();
        for(double position:new double[]{0,1}){
            var views=PointRenderer.viewsForTest(junctions,s,Profile.STANDARD);
            Mesh world=PointRenderer.worldForTest(views);
            for(var view:views){
                if(view.junction.kind()!=Junction.Kind.Y)continue;
                for(boolean preview:new boolean[]{true,false}){
                    Mesh mesh=preview?view.mesh():world;
                    String where=preview?"preview":"world";
                    for(int branch=0;branch<2;branch++){
                        Track road=branch==0?j.a():j.b();
                        for(int sign:new int[]{-1,1}){
                            rails++;
                            double from=-1,last=-1,firstMissing=-1,gapFrom=-1,worst=0,worstFrom=0,worstTo=0,run=0;
                            var intervals=new ArrayList<String>();
                            for(double d=.05;d<=extent-.05;d+=.05){
                                boolean steel=steelNear(mesh,road,d,sign,offset,top,p);
                                if(steel){
                                    covered++;
                                    if(from<0){from=d;}
                                    last=d;
                                    if(run>worst){worst=run;worstFrom=gapFrom;worstTo=d-.05;}
                                    run=0;gapFrom=-1;
                                }else{
                                    if(run==0)gapFrom=d;
                                    run+=.05;
                                    if(firstMissing<0)firstMissing=d;
                                }
                            }
                            if(run>worst){worst=run;worstFrom=gapFrom;worstTo=last>0?extent-.05:.0;}
                            if(from>=0)intervals.add(round(from)+".."+round(last));
                            listing.append("     rail ").append(road.id).append(" side ").append(sign)
                                .append(" position ").append(position).append(' ').append(where)
                                .append(": steel ").append(intervals.isEmpty()?"none":intervals)
                                .append(", first missing station ").append(firstMissing<0?"none":round(firstMissing))
                                .append(", longest break ").append(round(worst)).append(" m")
                                .append(worst>0?" at "+round(worstFrom)+".."+round(worstTo):"").append('\n');
                            check(worst<=BREAK,"rail "+road.id+" side "+sign+" position "+position+' '+where
                                +" has a "+round(worst)+" m break with no steel in a "+WINDOW+" m window at station "
                                +round(worstFrom)+".."+round(worstTo)+", first missing station "+round(firstMissing));
                        }
                    }
                }
            }
        }
        check(rails>0&&covered>0,"the stock-rail walk sampled nothing, so it proves nothing");
        // End faces of plain join ends must carry texture coordinates: a section face without uv is
        // the untextured, hollow looking end the report is about. A section face of a beam is the
        // quad that is vertical (spans the rail height) and does not advance along the rail.
        int caps=0,noUv=0;String firstNoUv="";
        for(var view:PointRenderer.viewsForTest(junctions,s,Profile.STANDARD)){
            if(view.junction.kind()!=Junction.Kind.Y)continue;
            for(var q:view.mesh().quads){
                if(!q.part().equals("rail"))continue;
                double lo=Math.min(Math.min(q.a().y(),q.b().y()),Math.min(q.c().y(),q.d().y()));
                double hi=Math.max(Math.max(q.a().y(),q.b().y()),Math.max(q.c().y(),q.d().y()));
                double zlo=Math.min(Math.min(q.a().z(),q.b().z()),Math.min(q.c().z(),q.d().z()));
                double zhi=Math.max(Math.max(q.a().z(),q.b().z()),Math.max(q.c().z(),q.d().z()));
                if(hi-lo<.05||zhi-zlo>.05)continue;
                caps++;
                if(q.uv()==null){noUv++;if(firstNoUv.isEmpty())firstNoUv="("+round(q.center().x())+","+round(q.center().y())+","+round(q.center().z())+")";}
            }
        }
        check(caps>0,"the stock-rail walk found no section face at all, so the texture test proves nothing");
        check(noUv==0,"plain join end faces have no texture coordinates: "+noUv+" of "+caps+" section faces, first at "+firstNoUv);
        System.out.print(listing);
        scissorsCatalogue();
        System.out.println("PASS: "+rails+" physical stock-rail lines keep their steel in pieces no longer than "
            +BREAK+" m ("+covered+" steel samples, preview and world, both blade positions)");
    }

    /** Steel of this route near this station, searched across the window a wheel-side rail may sit in. */
    private static boolean steelNear(Mesh mesh,Track road,double d,int sign,double offset,double top,Profile p){
        V3 base=road.at(d),side=road.tangent(d).lateral();
        for(double lat=-WINDOW;lat<=WINDOW+.001;lat+=.05){
            if(ToeCoverageRegression.steelAt(mesh,base.add(side.mul(sign*offset+lat)),top,p))return true;
        }
        return false;
    }

    private static double round(double v){return Math.round(v*1000)/1000D;}

    /** Two diverging roads and the road that runs into them: a Y turnout whose toe is the node. */
    private static List<Track> tracks(){
        var a=new ArrayList<V3>();var b=new ArrayList<V3>();var incoming=new ArrayList<V3>();
        for(int i=0;i<=120;i++){double z=i*.25,x=.008*z*z;a.add(new V3(-x,0,z));b.add(new V3(x,0,z));}
        for(int i=-80;i<=0;i++)incoming.add(new V3(0,0,i*.25));
        return List.of(new Track("ta","0,0,0","-7,0,30",a),new Track("tb","0,0,0","7,0,30",b),
            new Track("tin","in","0,0,0",incoming));
    }

    /** The same walk on a full scissors: four turnouts sharing a diamond centre. Only gaps between
     *  the first and the last station that carry steel are counted, because a junction draws its own
     *  window and the rest of the road belongs to plain track (and, in game, to the native rails). */
    private static void scissorsCatalogue()throws Exception{
        for(boolean curved:new boolean[]{false,true}){
            var roads=org.mtrpoint.Regression.scissors(curved);
            var junctions=Detector.find(roads);
            var views=PointRenderer.viewsForTest(junctions,s,Profile.STANDARD);
            check(!views.isEmpty(),"the scissors fixture produced no view (curved="+curved+")");
            Mesh world=PointRenderer.worldForTest(views);
            Profile p=Profile.STANDARD.tune(s);
            double top=p.top()+s.verticalOffset(),offset=p.centerOffset();
            int lines=0;
            for(var view:views){
                if(view.junction.kind()==Junction.Kind.DIAMOND)continue;
                for(boolean preview:new boolean[]{true,false}){
                    Mesh mesh=preview?view.mesh():world;
                    for(Track road:view.junction.tracks()){
                        for(int sign:new int[]{-1,1}){
                            double first=-1,last=-1,gapFrom=-1,worst=0,worstFrom=0,worstTo=0,run=0;
                            for(double d=.05;d<=road.length-.05;d+=.05){
                                boolean steel=steelNear(mesh,road,d,sign,offset,top,p);
                                if(steel){
                                    if(first<0)first=d;
                                    last=d;
                                    if(run>worst){worst=run;worstFrom=gapFrom;worstTo=d-.05;}
                                    run=0;gapFrom=-1;
                                }else if(first>=0){if(run==0)gapFrom=d;run+=.05;}
                            }
                            if(first<0||last<=first)continue;
                            lines++;
                            check(worst<=BREAK,"scissors"+(curved?" curved":"")+" road "+road.id+" side "+sign
                                +" "+(preview?"preview":"world")+" has a "+round(worst)+" m interior break at station "
                                +round(worstFrom)+".."+round(worstTo)+" (steel "+round(first)+".."+round(last)+")");
                        }
                    }
                }
            }
            check(lines>0,"the scissors walk sampled no rail line (curved="+curved+")");
            System.out.println("PASS: scissors"+(curved?" curved":"")+" walk: "+lines
                +" road sides carry steel, no interior break longer than "+BREAK+" m (preview and world)");
        }
    }
}
