package org.mtrpoint.geometry;

import java.util.*;

/** Shared crossing gaps applied after neighboring turnout meshes are assembled. */
public final class RailCuts {
    public record Cut(Track road,double start,double end,double offset,double radius,Profile profile,PointSettings settings) {}
    /** A fixed running-rail interval. These are unioned before meshing, so neighboring
     * turnout/crossing views cannot leave duplicate stubs or clip the same quad twice. */
    public record Segment(Track road,double start,double end,double offset,Profile profile,PointSettings settings) {
        public Segment canonical(){return road.startNode.compareTo(road.endNode)<=0?this:new Segment(road.reverse(),road.length-end,road.length-start,-offset,profile,settings);}
    }
    private RailCuts(){}

    public static List<Cut> forJunction(Junction junction,PointSettings settings,Profile raw){
        if(!settings.enabled()||junction.kind()==Junction.Kind.THREE)return List.of();
        Profile profile=raw.tune(settings);double extent=PointMesh.extent(junction,settings);
        var roads=junction.tracks();var result=new ArrayList<Cut>();
        if(junction.kind()==Junction.Kind.DIAMOND){
            double radius=radius(profile);
            for(int road=0;road<2;road++){
                Track track=roads.get(road);double center=road==0?junction.sa():junction.sb();
                for(int sign:new int[]{-1,1})result.add(new Cut(track,Math.max(0,center-extent),Math.min(track.length,center+extent),sign*profile.centerOffset(),radius,profile,settings));
            }
            return List.copyOf(result);
        }
        for(int a=0;a<roads.size();a++)for(int b=a+1;b<roads.size();b++){
            Junction pair=new Junction(junction.id()+":"+a+b,Junction.Kind.Y,roads.get(a),roads.get(b),junction.center(),0,0,junction.extent());
            FrogGeometry frog=new FrogGeometry(pair,settings,profile,extent);double side=TurnoutFrame.side(pair,extent);
            double radius=radius(profile);
            result.add(new Cut(roads.get(a),frog.toe(0),frog.heel(0),side*profile.centerOffset(),radius,profile,settings));
            result.add(new Cut(roads.get(b),frog.toe(1),frog.heel(1),-side*profile.centerOffset(),radius,profile,settings));
        }
        return List.copyOf(result);
    }

    private static double radius(Profile profile){
        double radius=Math.max(profile.headWidth(),profile.footWidth())*.75+.02;
        if(profile.detail()!=null&&!profile.detail().rails().isEmpty())for(var quad:profile.detail().rails())for(V3 vertex:List.of(quad.a(),quad.b(),quad.c(),quad.d()))
            radius=Math.max(radius,Math.abs(vertex.x()-profile.detail().railCenter())*profile.headWidth()/profile.detail().headWidth()+.02);
        return radius;
    }

    public static Mesh apply(Mesh source,List<Cut> cuts){
        Mesh current=source;
        for(Cut cut:merge(cuts)){
            Mesh next=new Mesh();boolean removed=false,before=false,after=false,tagged=false;
            Mesh.RailTag startTag=new Mesh.RailTag(cut.road,cut.start,cut.start,cut.offset),endTag=new Mesh.RailTag(cut.road,cut.end,cut.end,cut.offset);
            // Ownership comes from the tagged source intervals, which name the rail a face was swept
            // from, not from the faces belongs() selects: a cut lands on the boundary between two
            // swept cells, so only one of them reaches into the crossing while both carry the same
            // ownership tag. Reading the tags directly is the only way to tell that the rail really
            // continues before or after the cut and its end face is owed to a removed neighbor.
            for(var quad:current.quads){
                if(quad.rail()==null)continue;
                Mesh.RailTag tag=quad.rail().canonical();
                if(!tag.road().id.equals(cut.road.id)||Math.abs(tag.offset()-cut.offset)>=1e-5)continue;
                tagged=true;before|=tag.start()<cut.start-1e-7;after|=tag.end()>cut.end+1e-7;
            }
            for(var quad:current.quads){
                if(!belongs(quad,cut)){next.quad(quad);continue;}
                removed=true;
                Mesh.clip(next,quad,railPoint(startTag,cut.start),horizontal(cut.road.tangent(cut.start)));
                Mesh.clip(next,quad,railPoint(endTag,cut.end),horizontal(cut.road.tangent(cut.end)).mul(-1));
            }
            if(removed){
                if(!tagged||before)next.railCutCap(railPoint(startTag,cut.start),horizontal(cut.road.tangent(cut.start)),cut.profile,cut.settings,false,startTag);
                if(!tagged||after)next.railCutCap(railPoint(endTag,cut.end),horizontal(cut.road.tangent(cut.end)),cut.profile,cut.settings,true,endTag);
            }
            current=next;
        }
        return current;
    }

    /** Build stationary rails from their exact tagged source intervals. */
    public static Mesh assemble(List<Segment> input,Mesh untagged,List<Cut> inputCuts){
        record Style(String road,double offset,Profile profile,double height) {}
        var groups=new LinkedHashMap<Style,List<Segment>>();
        for(Segment raw:input){Segment s=raw.canonical();if(s.end<=s.start+1e-7)continue;
            groups.computeIfAbsent(new Style(s.road.id,s.offset,s.profile,s.settings.verticalOffset()),k->new ArrayList<>()).add(s);
        }
        var cuts=merge(inputCuts);Mesh out=apply(untagged,inputCuts);
        for(var entries:groups.values()){
            entries.sort(Comparator.comparingDouble(Segment::start));var union=new ArrayList<Segment>();
            for(Segment s:entries){if(union.isEmpty()||union.get(union.size()-1).end<s.start-1e-7)union.add(s);
                else {Segment p=union.get(union.size()-1);union.set(union.size()-1,new Segment(p.road,p.start,Math.max(p.end,s.end),p.offset,p.profile,p.settings));}}
            for(Segment whole:union){var holes=new ArrayList<double[]>();
                for(Cut cut:cuts){
                    if(!cut.road.id.equals(whole.road.id)||Math.abs(cut.offset-whole.offset)>=1e-5||!cut.profile.equals(whole.profile)||cut.settings.verticalOffset()!=whole.settings.verticalOffset())continue;
                    double a=Math.max(whole.start,cut.start),b=Math.min(whole.end,cut.end);if(b>a+1e-7)holes.add(new double[]{a,b});
                }
                holes.sort(Comparator.comparingDouble(v->v[0]));var mergedHoles=new ArrayList<double[]>();
                for(double[] hole:holes){if(mergedHoles.isEmpty()||mergedHoles.get(mergedHoles.size()-1)[1]<hole[0]-1e-7)mergedHoles.add(hole);
                    else mergedHoles.get(mergedHoles.size()-1)[1]=Math.max(mergedHoles.get(mergedHoles.size()-1)[1],hole[1]);}
                double cursor=whole.start;
                for(double[] hole:mergedHoles){if(hole[0]>cursor+1e-7)rail(out,whole,cursor,hole[0]);cursor=Math.max(cursor,hole[1]);}
                if(cursor<whole.end-1e-7)rail(out,whole,cursor,whole.end);
            }
        }
        return out;
    }

    /** One swept cell per .24 m. A hole boundary is closed with the profile section, so an exact
     * fixed-rail interval keeps the same complete end face a cut through assembled rail gets. A
     * joint between adjacent intervals was unioned above and stays continuous, so it receives no
     * cap and no extra wall. */
    private static void rail(Mesh out,Segment segment,double start,double end){
        int count=Math.max(1,(int)Math.ceil((end-start)/.24));double step=(end-start)/count;
        for(int i=0;i<count;i++){double a=start+step*i,b=a+step;
            V3 x=railPoint(segment,a),y=railPoint(segment,b);out.rail(x,y,1,1,segment.profile,segment.settings,"rail",new Mesh.RailTag(segment.road,a,b,segment.offset));
            if(i==0&&start>segment.start+1e-7)railCutCap(out,segment,start,x,railPoint(segment,start+step));
            if(i==count-1&&end<segment.end-1e-7)railCutCap(out,segment,end,y,railPoint(segment,end-step));
        }
    }

    /** Close one cut boundary with the offset curve's own cross-section, oriented along the
     * band that ends there. The exact boundary station names the boundary the cap faces, so the
     * cap carries a zero-length ownership tag matching the swept cells it closes. */
    private static void railCutCap(Mesh out,Segment segment,double station,V3 center,V3 inside){
        V3 tangent=horizontal(inside.sub(center));
        Mesh.RailTag tag=new Mesh.RailTag(segment.road,station,station,segment.offset);
        if(segment.profile.detail()!=null&&!segment.profile.detail().rails().isEmpty()){
            int first=out.quads.size();
            out.railCap(center,tangent,1,segment.profile,segment.settings,"rail",true);
            for(int i=first;i<out.quads.size();i++)out.quads.set(i,out.quads.get(i).withRail(tag));
        }
        else out.railCutCap(center,tangent,segment.profile,segment.settings,true,tag);
    }

    /** Collapse neighboring crossing pockets before clipping so internal caps cannot survive. */
    private static List<Cut> merge(List<Cut> input){
        var sorted=input.stream().map(RailCuts::canonical).sorted(Comparator.comparing((Cut c)->c.road.id).thenComparingDouble(Cut::offset).thenComparingDouble(Cut::start)).toList();
        var result=new ArrayList<Cut>();
        for(Cut cut:sorted){int match=-1;
            for(int i=result.size()-1;i>=0;i--){Cut old=result.get(i);if(!old.road.id.equals(cut.road.id))break;
                if(old.profile.equals(cut.profile)&&old.settings.verticalOffset()==cut.settings.verticalOffset()&&Math.abs(old.offset-cut.offset)<1e-5&&old.end>=cut.start-1e-7){match=i;break;}
            }
            if(match<0)result.add(cut);else{Cut old=result.get(match);result.set(match,new Cut(old.road,old.start,Math.max(old.end,cut.end),old.offset,Math.max(old.radius,cut.radius),old.profile,old.settings));}
        }
        return result;
    }

    private static Cut canonical(Cut cut){
        if(cut.road.startNode.compareTo(cut.road.endNode)<=0)return cut;
        return new Cut(cut.road.reverse(),cut.road.length-cut.end,cut.road.length-cut.start,-cut.offset,cut.radius,cut.profile,cut.settings);
    }

    private static boolean belongs(Mesh.Quad quad,Cut cut){
        if(quad.rail()!=null){
            Mesh.RailTag tag=quad.rail().canonical();
            return tag.road().id.equals(cut.road.id)&&Math.abs(tag.offset()-cut.offset)<1e-5&&tag.start()<cut.end-1e-7&&tag.end()>cut.start()+1e-7;
        }
        var vertices=List.of(quad.a(),quad.b(),quad.c(),quad.d());double lo=Double.MAX_VALUE,hi=-Double.MAX_VALUE;
        for(V3 vertex:vertices){double at=cut.road.nearest(vertex);lo=Math.min(lo,at);hi=Math.max(hi,at);}
        if(hi<cut.start-1e-7||lo>cut.end+1e-7)return false;
        var probes=new ArrayList<V3>(9);probes.addAll(vertices);probes.add(quad.center());
        for(int i=0;i<4;i++)probes.add(vertices.get(i).lerp(vertices.get((i+1)%4),.5));
        for(V3 probe:probes){double at=cut.road.nearest(probe);V3 tangent=horizontal(cut.road.tangent(at));
            V3 expected=cut.road.at(at).add(tangent.lateral().mul(cut.offset));double dx=probe.x()-expected.x(),dz=probe.z()-expected.z();
            if(dx*dx+dz*dz<=cut.radius*cut.radius)return true;
        }
        return false;
    }

    private static V3 horizontal(V3 value){return new V3(value.x(),0,value.z()).unit();}
    private static V3 railPoint(Mesh.RailTag tag,double station){return tag.road().at(station).add(horizontal(tag.road().tangent(station)).lateral().mul(tag.offset()));}
    private static V3 railPoint(Segment segment,double station){return segment.road.at(station).add(horizontal(segment.road.tangent(station)).lateral().mul(segment.offset));}
}
