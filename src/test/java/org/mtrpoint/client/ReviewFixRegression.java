package org.mtrpoint.client;

import org.mtrpoint.geometry.*;
import java.util.*;

/** Reproductions of the 2026-09-26 review findings, including the world assembly path. */
public final class ReviewFixRegression {
    private static void require(boolean pass,String message){if(!pass)throw new AssertionError(message);}
    private static Track line(String id,V3 a,V3 b){return new Track(id,id+"s",id+"e",List.of(a,b));}
    public static void main(String[] args)throws Exception{
        run();
        if(args.length>0){ChannelCutRegression.run();CheckSteelCutRegression.run();CapFaceRegression.run();}
    }
    public static void run(){
        AppearanceSaveRegression.run();crossings();windows();fragments();heights();cuttingViews();
        System.out.println("PASS: off-grid intersections, edited crossing windows, complete animation fragments and world-height flange cuts");
    }
    private static void crossings(){
        for(int i=0;i<32;i++){
            double x=50.5+i*.0137;
            Track a=line("a",new V3(0,0,0),new V3(100,0,0)),b=line("b",new V3(x,0,-10),new V3(x,0,10));
            for(Track aa:List.of(a,a.reverse()))for(Track bb:List.of(b,b.reverse())){
                require(Crossings.meets(aa,bb)&&Crossings.meets(bb,aa),"Off-grid crossing must be symmetric at "+x);
                require(Crossings.cutters(List.of(aa),List.of(aa,bb)).size()==1,"Crossing enters cutter set");
            }
            require(!Crossings.meets(a,line("high",new V3(x,.3,-10),new V3(x,.3,10))),"Overpass excluded");
        }
        Track a=line("a",new V3(-5,0,0),new V3(5,0,0));
        require(!Crossings.meets(a,line("parallel",new V3(-5,0,.01),new V3(5,0,.01))),"Parallel neighbour excluded");
        require(!Crossings.meets(a,line("tangent",new V3(-5,0,-.001),new V3(5,0,.001))),"Near tangent excluded");
        Track curved=new Track("curve","cs","ce",List.of(new V3(-4,0,-3),new V3(-1,0,2),new V3(4,0,3)));
        require(Crossings.meets(a,curved)&&Crossings.meets(curved.reverse(),a),"Polyline interior crossing");
        Track graded=line("grade",new V3(0,-1,-10),new V3(0,1,10));
        require(Crossings.meets(a,graded),"Use interpolated crossing height, not endpoints");
    }
    private static void windows(){
        Track a=line("a",new V3(-20,0,0),new V3(20,0,0)),b=line("b",new V3(0,0,-20),new V3(0,0,20));
        var j=Detector.find(List.of(a,b)).get(0);var v=new PointClient.View(j,PointSettings.DEFAULT,Profile.STANDARD,Set.of("default"));
        PointClient.refreshCrossings(List.of(v));double[] old=v.diamond.window(0).clone();Mesh oldMesh=v.mesh();
        PointClient.refreshCrossings(List.of(v));require(v.mesh()==oldMesh,"Unchanged window reuses mesh");
        v.settings=v.settings.with(0,1.8);PointClient.refreshCrossings(List.of(v));
        require(v.diamond.window(0)[0]<old[0]-2&&v.diamond.window(0)[1]>old[1]+2,"Length edit expands replacement window");
        double[] edited=v.diamond.window(0).clone();
        var reloaded=new PointClient.View(j,v.settings,Profile.STANDARD,Set.of("default"));PointClient.refreshCrossings(List.of(reloaded));
        require(Arrays.equals(edited,reloaded.diamond.window(0)),"Saved settings restore the same window");
        double d=old[1]+.5;
        require(PointClient.suppress(List.of(v),a.id,"default",a.at(d),0),"Expanded mesh also hides native cells");
        require(!PointClient.suppress(List.of(v),a.id,"default",a.at(edited[1]+.5),0),"Cells outside the new boundary remain native");
        v.settings=v.settings.flags(false,false);PointClient.refreshCrossings(List.of(v));
        require(v.centreRoads==null&&!v.centreHidden&&v.diamond==null,"Disabling clears old region membership");
        require(!PointClient.suppress(List.of(v),a.id,"default",a.at(20),0),"Disabled crossing keeps native track");
    }
    private static void fragments(){
        PointSettings s=PointSettings.DEFAULT.with(3,.025);Profile p=Profile.STANDARD.tune(s);
        double channel=p.centerOffset()-p.headWidth()/2-s.flangeway()/2;
        var roads=new ArrayList<Track>();for(int i=0;i<4;i++){double z=-channel-.09+i*.06;roads.add(line("cut"+i,new V3(-8,0,z),new V3(8,0,z)));}
        var poses=new ArrayList<Mesh>();
        for(double shift:new double[]{0,.004,.008}){Mesh m=new Mesh();m.rail(new V3(p.centerOffset(),0,-.12+shift),new V3(p.centerOffset(),0,.12+shift),1,1,p,s,"frog");poses.add(m);}
        var frames=DiamondGeometry.cutChannelFrames(poses,roads,p,s);
        for(int i=0;i<frames.size();i++){
            require(frames.get(i).quads.size()==frames.get(0).quads.size(),"Fragment slots agree across poses");
            Mesh reference=DiamondGeometry.cutChannels(poses.get(i),roads,p,s,p.top());
            for(double z=-.119;z<.128;z+=.001)
                require(at(reference,p.centerOffset(),z,p.top())==at(frames.get(i),p.centerOffset(),z,p.top()),"No surface lost when aligning animation at "+i+"/"+z);
        }
        for(double blend:new double[]{.1,.3,.5,.7,.9}){
            Mesh mesh=new Mesh();for(int i=0;i<frames.get(0).quads.size();i++){
                var a=frames.get(0).quads.get(i);var b=frames.get(2).quads.get(i);
                mesh.quad(new Mesh.Quad(a.a().lerp(b.a(),blend),a.b().lerp(b.b(),blend),a.c().lerp(b.c(),blend),a.d().lerp(b.d(),blend),a.surface(),a.part(),a.index(),a.uv()));
            }
            for(int i=0;i<4;i++)require(!at(mesh,p.centerOffset(),-.09+i*.06,p.top()),"Intermediate pose fills a flange channel");
        }
        Mesh oldBoolean=DiamondGeometry.cutChannels(poses.get(0),roads,p,s,p.top(),true);
        Mesh complete=DiamondGeometry.cutChannels(poses.get(0),roads,p,s,p.top(),false);
        require(oldBoolean.quads.equals(complete.quads),"Single-pose overload never truncates output");
    }
    private static void heights(){
        var s=PointSettings.DEFAULT;var p=Profile.STANDARD.tune(s);double channel=p.centerOffset()-p.headWidth()/2-s.flangeway()/2;
        for(double slope:new double[]{0,.03})for(double height:new double[]{-40,0,64}){
            Mesh source=new Mesh();source.rail(new V3(p.centerOffset(),height-2*slope,-2),new V3(p.centerOffset(),height+2*slope,2),1,1,p,s,"rail");
            Track cut=line("height",new V3(-8,height+slope*channel,channel),new V3(8,height+slope*channel,channel));
            // The positive channel of this crossing lies at z=2*channel; the negative at z=0.
            Mesh out=DiamondGeometry.cutChannels(source,List.of(cut),p,s,p.top());
            require(!at(out,p.centerOffset(),0,height+p.top()),"Crossing channel blocked at height="+height+" slope="+slope);
            require(at(out,p.centerOffset(),-1,height-slope+p.top()),"Steel away from channel disappeared");
            require(at(out,p.centerOffset(),0,height+p.top()-p.railHeight()+.025),"Flange cut removed the foot");
        }
        for(double height:new double[]{0,64,-40}){
            var a=new ArrayList<V3>();var b=new ArrayList<V3>();
            for(int i=0;i<=120;i++){double d=i*.25;a.add(new V3(-.008*d*d,height,d));b.add(new V3(.008*d*d,height,d));}
            Track ar=new Track("ya","shared","left",a),br=new Track("yb","shared","right",b);
            var j=Detector.find(List.of(ar,br)).get(0);Mesh world=PointRenderer.worldForTest(PointRenderer.viewsForTest(List.of(j),s,p));
            FrogGeometry frog=new FrogGeometry(j,s,p,PointMesh.extent(j,s));int count=0;
            for(int k=0;k<2;k++){Track road=k==0?ar:br;double center=k==0?frog.sa:frog.sb;
                for(int sign:new int[]{-1,1})for(double station=center-.8;station<=center+.8;station+=.01){
                    V3 q=road.at(station).add(road.tangent(station).lateral().mul(sign*channel));
                    require(!at(world,q.x(),q.z(),height+p.top()),"World Y flange blocked at height="+height+" station="+station);count++;
                }
            }
            require(count>600,"World Y coverage");
        }
    }
    private static void cuttingViews(){
        var s=PointSettings.DEFAULT;var p=Profile.STANDARD;
        var roads=new ArrayList<Track>(org.mtrpoint.Regression.y());
        roads.add(line("external",new V3(-10,0,11.173),new V3(10,0,11.173)));
        var junctions=Detector.find(roads);
        var views=PointRenderer.viewsForTest(junctions,s,p);
        PointClient.refreshCrossings(views,roads);
        var turnout=views.stream().filter(v->v.junction.kind()==Junction.Kind.Y).findFirst().orElseThrow();
        require(turnout.channels.stream().anyMatch(t->t.id.equals("external")),"Real sampled-road refresh selects external cutter");
        int size=-1;
        for(double position:new double[]{0,.25,.5,.75,1}){
            turnout.position=position;Mesh mesh=turnout.mesh();
            if(size<0)size=mesh.quads.size();require(size==mesh.quads.size(),"Live animation with external cutter preserves slots");
            double channel=p.centerOffset()-p.headWidth()/2-s.flangeway()/2;
            for(int side:new int[]{-1,1}){
                double z=11.173+side*channel;
                for(double x=-2;x<2;x+=.025)require(!at(mesh,x,z,p.top()),"External crossing channel remains clear during animation");
            }
        }
        Mesh first=turnout.mesh();long builds=turnout.builds;
        PointClient.refreshCrossings(views,roads);require(turnout.mesh()==first&&turnout.builds==builds,"Unchanged crossing refresh keeps compiled geometry");
    }
    static boolean at(Mesh mesh,double x,double z,double y){
        for(var q:mesh.quads)if(hit(q.a(),q.b(),q.c(),x,z,y)||hit(q.a(),q.c(),q.d(),x,z,y))return true;return false;
    }
    private static boolean hit(V3 a,V3 b,V3 c,double x,double z,double y){
        double area=V3.crossXZ(b.sub(a),c.sub(a));if(Math.abs(area)<1e-10)return false;
        V3 q=new V3(x,0,z);double u=V3.crossXZ(q.sub(a),c.sub(a))/area,v=V3.crossXZ(b.sub(a),q.sub(a))/area;
        return u>=-1e-7&&v>=-1e-7&&u+v<=1+1e-7&&Math.abs(a.y()+(b.y()-a.y())*u+(c.y()-a.y())*v-y)<1e-6;
    }
}
