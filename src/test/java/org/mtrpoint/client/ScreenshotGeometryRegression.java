package org.mtrpoint.client;

import java.util.*;
import org.mtrpoint.geometry.*;

/** Dense-layout and exposed-section reproductions from the September 26 screenshots. */
public final class ScreenshotGeometryRegression {
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    public static void main(String[] args){run();}
    public static void run(){handover();handedOverChannels();translatedCaps();bladeCrossing();guardMouths();nativeEnds();}
    private static void handedOverChannels(){
        var p=Profile.STANDARD;var s=PointSettings.DEFAULT;
        Track a=new Track("a","as","ae",List.of(new V3(0,-60,-10),new V3(0,-60,10)));
        Track b=new Track("b","bs","be",List.of(new V3(-10,-60,0),new V3(10,-60,0)));
        Junction j=Detector.find(List.of(a,b)).get(0);Mesh mesh=new Mesh();
        // A turnout owns the first half of b. Its flange channels must still cut a.
        DiamondGeometry.centre(mesh,j,s,p,5,List.of(a,b),new PointMesh.DiamondBoundary(new double[][]{{5,15},{11.5,15}}),List.of());
        double offset=p.centerOffset()-p.headWidth()/2-s.flangeway()/2;int blocked=0;
        for(int side:new int[]{-1,1})for(double x=-1;x<=1;x+=.025)
            if(ReviewFixRegression.at(mesh,x,side*offset,-60+p.top()))blocked++;
        require(blocked==0,"Handing a road over to a turnout truncated its flange channels: "+blocked);
        System.out.println("SCREENSHOT_HANDOVER_CHANNELS: both flange channels remain clear across the neighbouring owner's rail");
    }
    private static void guardMouths(){
        var p=Profile.STANDARD;var s=PointSettings.DEFAULT;
        Track road=new Track("guard","gs","ge",List.of(new V3(0,0,-3),new V3(0,0,3)));
        var run=new GuardRails.Run(road,0,6,.55,true,true,p,s);
        var joined=GuardRails.exposeEnds(GuardRails.merge(List.of(
            new GuardRails.Run(road,0,4,.55,false,false,p,s,"manual-old"),
            new GuardRails.Run(road,2,6,.55,false,false,p,s,"manual-old"))));
        require(joined.size()==1&&joined.get(0).flareStart()&&joined.get(0).flareEnd(),"Legacy manual merge loses its exposed mouths");
        Mesh rail=new Mesh();rail.rail(new V3(-2,0,1),new V3(2,0,1),1,1,p,s,"rail");
        Track cross=new Track("cross","xs","xe",List.of(new V3(-2,0,1),new V3(2,0,1)));
        var steel=List.of(new DiamondGeometry.Steel(rail,p.top(),p,s,List.of(cross)));
        var pieces=DiamondGeometry.finishedGuards(List.of(run),steel);
        require(pieces.size()==1&&pieces.get(0).end()-pieces.get(0).start()>5.9,"Crossing split a full-length guard into separate stubs");
        for(var piece:pieces)for(double d:new double[]{piece.start(),piece.end()}){
            double inward=piece.point(d).sub(piece.center(d)).dot(piece.road().tangent(d).lateral())*Math.signum(piece.offset());
            require(inward<-.09,"Exposed guard end has no inward mouth");
        }
        Track acute=new Track("acute","acs","ace",List.of(new V3(-3,0,-2),new V3(3,0,2)));
        var channels=List.of(new DiamondGeometry.Steel(new Mesh(),p.top(),p,s,List.of(acute)));
        var mouths=DiamondGeometry.finishedGuards(List.of(run),channels);
        Mesh swept=DiamondGeometry.guards(List.of(run),channels);
        require(mouths.size()==1,"Wheel slots must not create new guard mouths");
        for(var piece:mouths)for(double d:new double[]{piece.start()+.04,piece.start()+.2,piece.end()-.2,piece.end()-.04}){
            if(d<=piece.start()||d>=piece.end())continue;V3 q=piece.point(d);
            require(ReviewFixRegression.at(swept,q.x(),q.z(),p.top()),"A crossing channel amputated a completed inward mouth");
        }
        for(double d=1;d<5;d+=.025){
            V3 q=run.center(d);
            require(ReviewFixRegression.at(swept,q.x()+.05,q.z(),p.top()-p.railHeight()+.025),"Wheel slot deleted the continuous guard foot");
        }
        var auto=GuardRails.exposeEnds(GuardRails.merge(List.of(
            new GuardRails.Run(road,0,4,.55,true,true,p,s),
            new GuardRails.Run(road.reverse(),0,4,-.55,true,true,p,s))));
        require(auto.size()==1&&auto.get(0).end()-auto.get(0).start()>5.9,"Unedited opposite-direction guards did not merge");
        var y=org.mtrpoint.Regression.y();var junction=Detector.find(y).get(0);
        require(GuardRails.assembled(junction,s,p,null,null).size()==4,"Unedited turnout wings bypass the shared interval pool");
        System.out.println("SCREENSHOT_GUARDS: full crossing spans retain lower steel, only outer terminals flare, unedited wings enter automatic merging");
    }
    private static void nativeEnds(){
        Profile p=ChannelCutRegression.modelled();var s=PointSettings.DEFAULT;
        Track road=new Track("guard","gs","ge",List.of(new V3(0,0,0),new V3(0,0,5)));
        Mesh mesh=new GuardRails.Run(road,1,4,.55,true,true,p,s).mesh();
        long caps=mesh.quads.stream().filter(q->q.c().equals(q.d())&&Math.abs(q.a().y()-q.c().y())>.01).count();
        require(caps>0,"Actual native guard mesh has no exposed end sections");
        Mesh cap=new Mesh();cap.railCap(new V3(0,0,0),new V3(0,0,1),1,p,s,"guard",true);
        require(CapFaceRegression.section(cap.quads,new V3(1,0,0),new V3(0,0,0)),"Native end cap does not follow its section");
        require(cap.quads.stream().anyMatch(q->new HashSet<>(q.uv()).size()>1),"Section texture still samples a single atlas pixel");
        require(cap.quads.stream().allMatch(q->q.surface().equals(Profile.END_STEEL)),"End sections still reuse the side atlas instead of cut steel");
        System.out.println("SCREENSHOT_NATIVE_ENDS: physical caps preserve head/web/foot and mapped UVs");
    }
    private static void bladeCrossing(){
        var tracks=new ArrayList<>(org.mtrpoint.Regression.y());
        tracks.add(new Track("early","es","ee",List.of(new V3(-10,0,4),new V3(10,0,4))));
        var views=PointRenderer.viewsForTest(Detector.find(tracks),PointSettings.DEFAULT,Profile.STANDARD);
        PointClient.refreshCrossings(views,tracks);int blocked=0;
        double channel=Profile.STANDARD.centerOffset()-Profile.STANDARD.headWidth()/2-PointSettings.DEFAULT.flangeway()/2;
        for(double pose:new double[]{0,.25,.5,.75,1}){
            for(var v:views)v.position=pose;
            Mesh world=PointRenderer.worldForTest(views);
            for(int sign:new int[]{-1,1})for(double x=-1.5;x<1.5;x+=.01)
                if(ReviewFixRegression.at(world,x,4+sign*channel,Profile.STANDARD.top()))blocked++;
        }
        System.out.println("SCREENSHOT_EARLY_CROSSING: blocked="+blocked);
        require(blocked==0,"A crossing through switch blades leaves uncut steel");
    }
    private static void handover(){
        var tracks=new ArrayList<>(org.mtrpoint.Regression.y());
        tracks.add(new Track("cross","cs","ce",List.of(new V3(-20,0,15),new V3(20,0,15))));
        var views=PointRenderer.viewsForTest(Detector.find(tracks),PointSettings.DEFAULT,Profile.STANDARD);
        PointClient.refreshCrossings(views,tracks);
        var turnout=views.stream().filter(v->v.junction.kind()==Junction.Kind.Y).findFirst().orElseThrow();
        var natural=RailSampler.yBoundary(turnout.junction,turnout.settings);
        Mesh world=PointRenderer.worldForTest(views);int missing=0;double first=-1,last=-1;
        Track road=turnout.junction.a();double side=-TurnoutFrame.side(turnout.junction,PointMesh.extent(turnout.junction,turnout.settings));
        for(double d=1;d<natural.aEnd()-.1;d+=.025){
            V3 p=road.at(d).add(road.tangent(d).lateral().mul(side*Profile.STANDARD.centerOffset()));
            if(p.z()>13)continue;
            if(!ReviewFixRegression.at(world,p.x(),p.z(),Profile.STANDARD.top())){if(first<0)first=d;last=d;missing++;}
        }
        System.out.println("SCREENSHOT_HANDOVER: missing="+missing+" stations="+first+".."+last+" natural="+natural.aEnd()+" current="+turnout.boundary.aEnd());
        require(missing==0,"Turnout-to-diamond handover removed an uncut stock rail");
    }
    private static void translatedCaps(){
        Profile p=ChannelCutRegression.modelled();var s=PointSettings.DEFAULT;
        Mesh a=crossingAt(0,p,s),b=crossingAt(137,p,s);
        require(a.quads.size()==b.quads.size(),"Translated crossing changed geometry: "+a.quads.size()+" vs "+b.quads.size());
        for(int i=0;i<a.quads.size();i++){var x=a.quads.get(i);var y=b.quads.get(i);
            require(x.a().add(137,0,137).distance(y.a())<1e-6&&x.c().add(137,0,137).distance(y.c())<1e-6,"Cut cap moved away from its translated rail at face "+i);
        }
        System.out.println("SCREENSHOT_CAPS: translated rail sections remain on the actual cut");
    }
    private static Mesh crossingAt(double shift,Profile p,PointSettings s){
        V3 c=new V3(shift,0,shift),u=new V3(0,0,1),v=new V3(.5,0,Math.sqrt(.75));
        Track a=new Track("a","as","ae",List.of(c.sub(u.mul(12)),c.add(u.mul(12))));
        Track b=new Track("b","bs","be",List.of(c.sub(v.mul(12)),c.add(v.mul(12))));
        return PointMesh.build(Detector.find(List.of(a,b)).get(0),s,p,0);
    }
}
