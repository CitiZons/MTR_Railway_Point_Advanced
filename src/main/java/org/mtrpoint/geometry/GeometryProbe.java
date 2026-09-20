package org.mtrpoint.geometry;

import java.util.List;
import java.util.Locale;

/** Property-gated cache-build diagnostics for point geometry probes. */
public final class GeometryProbe {
    private static final boolean ENABLED = Boolean.getBoolean("pointProbePartColors");

    private GeometryProbe() {
    }

    public static void wingRuns(int styleGroup, double height, List<FrogGeometry.WingRun> raw, List<FrogGeometry.WingRun> merged) {
        if (!ENABLED) return;
        System.out.printf(Locale.ROOT, "POINT_GEOMETRY_DIAG fixedYWings styleGroup=%d height=%.4f rawCount=%d mergedCount=%d%n",
            styleGroup, height, raw.size(), merged.size());
        printWings("raw", raw);
        printWings("merged", merged);
    }

    public static void guardRuns(List<GuardRails.Run> raw, List<GuardRails.Run> merged) {
        if (!ENABLED) return;
        System.out.printf(Locale.ROOT, "POINT_GEOMETRY_DIAG guardRails rawCount=%d mergedCount=%d%n", raw.size(), merged.size());
        printGuards("raw", raw);
        printGuards("merged", merged);
    }

    /** Dump every pooled check span with the exact rail interval it was built from. */
    public static void spanBins(int styleGroup, List<String> rows) {
        if (!Boolean.getBoolean("pointProbeBins")) return;
        for (String row : rows) System.out.printf(Locale.ROOT, "POINT_GEOMETRY_DIAG span styleGroup=%d %s%n", styleGroup, row);
    }

    /** A seam between two check rails must not leave a doubled flared mouth: report every station
     * where another run's own steel physically reaches this run's centre line, whether or not the
     * two runs share a Track ID. Two Tracks that cross at a mouth are the same defect as two
     * Tracks joined end to end. */
    public static void guardSeams(List<GuardRails.Run> runs) {
        if (!ENABLED) return;
        for (int i = 0; i < runs.size(); i++) {
            GuardRails.Run run = runs.get(i);
            for (int k = 0; k < runs.size(); k++) {
                if (k == i) continue;
                GuardRails.Run other = runs.get(k);
                double best = Double.MAX_VALUE, at = Double.NaN;
                for (double d = other.start(); d <= other.end(); d += .02) {
                    V3 point = other.point(d);
                    double here = run.road().nearest(point);
                    if (here < run.start() - .12 || here > run.end() + .12) continue;
                    double distance = point.sub(run.center(here)).length();
                    if (distance < best) {best = distance; at = here;}
                }
                if (best > .03) continue;
                System.out.printf(Locale.ROOT,
                    "POINT_GEOMETRY_DIAG checkSeam road=%s nodes=%s->%s station=%.4f coveredBy=%s nodes=%s->%s offset=%.5f distance=%.5f%n",
                    run.road().id, run.road().startNode, run.road().endNode, at,
                    other.road().id, other.road().startNode, other.road().endNode, other.offset(), best);
            }
        }
    }

    private static void printWings(String kind, List<FrogGeometry.WingRun> runs) {
        for (int i = 0; i < runs.size(); i++) {
            FrogGeometry.WingRun run = runs.get(i).canonical();
            Track road = run.road();
            V3 start = center(road, run.start(), run.startOffset());
            V3 end = center(road, run.flareAt(), run.endOffset());
            System.out.printf(Locale.ROOT,
                "POINT_GEOMETRY_DIAG fixedYWing %s[%d] road=%s nodes=%s->%s start=%.4f startFlareAt=%.4f endAt=%.4f flareAt=%.4f baseOffset=%.5f startOffset=%.5f endOffset=%.5f caps=%s/%s centerStart=%s centerEnd=%s%n",
                kind, i, road.id, road.startNode, road.endNode, run.start(), run.startFlareAt(), run.endAt(), run.flareAt(),
                run.offset(), run.startOffset(), run.endOffset(), run.capStart(), run.capEnd(), point(start), point(end));
        }
    }

    private static void printGuards(String kind, List<GuardRails.Run> runs) {
        for (int i = 0; i < runs.size(); i++) {
            GuardRails.Run run = runs.get(i).canonical();
            Track road = run.road();
            System.out.printf(Locale.ROOT,
                "POINT_GEOMETRY_DIAG guardRail %s[%d] road=%s nodes=%s->%s start=%.4f end=%.4f offset=%.5f flares=%s/%s centerStart=%s centerEnd=%s%n",
                kind, i, road.id, road.startNode, road.endNode, run.start(), run.end(), run.offset(), run.flareStart(), run.flareEnd(),
                point(run.center(run.start())), point(run.center(run.end())));
        }
    }

    private static V3 center(Track road, double at, double offset) {
        return road.at(at).add(road.tangent(at).lateral().mul(offset));
    }

    private static String point(V3 point) {
        return String.format(Locale.ROOT, "(%.3f,%.3f,%.3f)", point.x(), point.y(), point.z());
    }
}
