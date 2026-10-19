package org.mtrpoint.geometry;

import org.mtrpoint.Regression;
import java.util.*;

/** Compares incremental detector updates with the full detector oracle. */
public final class IncrementalDetectorRegression {
    private IncrementalDetectorRegression() {}

    public static void run() {
        var detector = new IncrementalDetector();
        List<Track> y = Regression.y();
        repeated(detector, "Y", y);

        List<Track> scissors = Regression.scissors(false);
        repeated(detector, "Scissors replacement", scissors);
        List<Track> curvedScissors = Regression.scissors(true);
        repeated(detector, "Curved scissors replacement", curvedScissors);

        Track crossA = Regression.line("cross-a", "aw", "ae", new V3(-20, 0, 0), new V3(20, 0, 0));
        Track crossB = Regression.line("cross-b", "bn", "bs", new V3(0, 0, -20), new V3(0, 0, 20));
        List<Track> crossing = List.of(crossA, crossB);
        repeated(detector, "Diamond added", crossing);

        Track raised = new Track("cross-b", crossB.startNode, crossB.endNode,
            crossB.points.stream().map(p -> p.add(0, 3, 0)).toList());
        equivalent("Grade-separated replacement", detector.find(List.of(crossA, raised)), Detector.find(List.of(crossA, raised)));
        equivalent("Diamond restored", detector.find(crossing), Detector.find(crossing));
        equivalent("Rail deleted", detector.find(List.of(crossA)), Detector.find(List.of(crossA)));

        Track left = Regression.line("joint-left", "left", "joint", new V3(-20, 0, 0), V3.ZERO);
        Track right = Regression.line("joint-right", "joint", "right", V3.ZERO, new V3(20, 0, 0));
        Track vertical = Regression.line("joint-cross", "north", "south", new V3(0, 0, -20), new V3(0, 0, 20));
        equivalent("Shared-node continuation", detector.find(List.of(left, right, vertical)), Detector.find(List.of(left, right, vertical)));
        equivalent("Continuation removed", detector.find(List.of(left, vertical)), Detector.find(List.of(left, vertical)));
        equivalent("Continuation restored", detector.find(List.of(left, right, vertical)), Detector.find(List.of(left, right, vertical)));

        var reversedPoints = new ArrayList<>(crossB.points);
        Collections.reverse(reversedPoints);
        Track reversed = new Track("cross-b", crossB.endNode, crossB.startNode, reversedPoints);
        equivalent("Reversed geometry replacement", detector.find(List.of(crossA, reversed)), Detector.find(List.of(crossA, reversed)));
        System.out.println("INCREMENTAL_DETECTOR: PASS unchanged reuse, additions, removals, replacement, reversal, endpoint joins, and grade separation");
    }

    private static void repeated(IncrementalDetector detector, String label, List<Track> tracks) {
        List<Junction> first = detector.find(tracks);
        equivalent(label + " initial", first, Detector.find(tracks));
        List<Junction> second = detector.find(tracks);
        equivalent(label + " unchanged", second, Detector.find(tracks));
        requireSameJunctionObjects(first, second, label + " unchanged snapshot rebuilt junctions");
    }

    private static void equivalent(String label, List<Junction> actual, List<Junction> expected) {
        List<String> a = signatures(actual), e = signatures(expected);
        if (!a.equals(e)) throw new AssertionError(label + " differs from Detector.find; incremental=" + a + ", full=" + e);
    }

    private static List<String> signatures(List<Junction> junctions) {
        var result = new ArrayList<String>();
        for (Junction j : junctions) result.add(j.kind() + "|" + j.id() + "|" + j.a().id + "|" + j.b().id + "|"
            + (j.third() == null ? "" : j.third().id) + "|" + bits(j.center().x()) + "," + bits(j.center().y()) + "," + bits(j.center().z())
            + "|" + bits(j.sa()) + "," + bits(j.sb()) + "|" + bits(j.extent()));
        Collections.sort(result);
        return result;
    }

    private static String bits(double value) { return Long.toHexString(Double.doubleToLongBits(value)); }

    private static void requireSameJunctionObjects(List<Junction> first, List<Junction> second, String message) {
        if (first.size() != second.size()) throw new AssertionError(message + ": size changed");
        var identities = Collections.newSetFromMap(new IdentityHashMap<Junction, Boolean>());
        identities.addAll(first);
        for (Junction junction : second) if (!identities.remove(junction)) throw new AssertionError(message + ": result identity changed");
        if (!identities.isEmpty()) throw new AssertionError(message + ": cached results disappeared");
    }
}
