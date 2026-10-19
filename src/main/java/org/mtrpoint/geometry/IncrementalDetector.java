package org.mtrpoint.geometry;

import java.util.*;

/** Incremental equivalent of {@link Detector#find(List)} for immutable sampled tracks. */
public final class IncrementalDetector {
    private record Segment(Track track, int index) {}
    private record Cell(int x, int z) {}
    private record Pair(String first, String second) {
        static Pair of(String a, String b) { return a.compareTo(b) < 0 ? new Pair(a, b) : new Pair(b, a); }
    }
    private record NodeState(List<Track> oriented, List<Junction> junctions) {}
    private record Candidate(Segment current, Segment previous, int cellX, int cellZ) {}

    private Map<String, Track> tracks = Map.of();
    private Map<String, Integer> order = Map.of();
    private Map<String, NodeState> nodeStates = new HashMap<>();
    private final Map<Cell, List<Segment>> bins = new HashMap<>();
    private final Map<String, List<Segment>> segments = new HashMap<>();
    private final Map<Pair, List<Junction>> crossings = new HashMap<>();
    private long cellPlacements;
    private long pairChecks;
    private boolean overloaded;
    private List<Track> lastInput = List.of();
    private List<Junction> lastResult = List.of();

    /** Find all supported junctions while reusing unchanged endpoint groups and rail pairs. */
    public synchronized List<Junction> find(List<Track> input) {
        if (sameInput(input)) return lastResult;
        var next = new LinkedHashMap<String, Track>();
        var nextOrder = new HashMap<String, Integer>();
        boolean duplicateIds = false;
        for (int i = 0; i < input.size(); i++) {
            Track track = input.get(i);
            if (next.putIfAbsent(track.id, track) != null) duplicateIds = true;
            nextOrder.put(track.id, i);
        }
        // Duplicate IDs are not expected in the runtime rail map. Preserve Detector semantics
        // for synthetic callers that supply them, then reset incremental state for a clean start.
        if (duplicateIds) {
            clear();
            return Detector.find(input);
        }

        var changed = new HashSet<String>();
        for (var entry : tracks.entrySet()) if (next.get(entry.getKey()) != entry.getValue()) changed.add(entry.getKey());
        for (var entry : next.entrySet()) if (tracks.get(entry.getKey()) != entry.getValue()) changed.add(entry.getKey());
        if (overloaded) {
            if (changed.isEmpty()) return remember(input, List.of());
            tracks = Map.of(); order = Map.of(); bins.clear(); segments.clear(); crossings.clear();
            cellPlacements = 0; pairChecks = 0; overloaded = false;
            changed.clear(); changed.addAll(next.keySet());
        }
        if (!order.isEmpty() && order.size() == nextOrder.size() && !order.equals(nextOrder)) changed.addAll(next.keySet());

        Map<String, List<Track>> nodes = buildNodes(next.values());
        var affectedRails = new HashSet<>(changed);
        var changedNodes = new HashSet<String>();
        for (String id : changed) {
            Track old = tracks.get(id), current = next.get(id);
            if (old != null) { changedNodes.add(old.startNode); changedNodes.add(old.endNode); }
            if (current != null) { changedNodes.add(current.startNode); changedNodes.add(current.endNode); }
        }
        for (String node : changedNodes) for (Track incident : nodes.getOrDefault(node, List.of())) affectedRails.add(incident.id);
        var updatedNodes = new TreeMap<String, NodeState>();
        for (var entry : nodes.entrySet()) {
            NodeState old = nodeStates.get(entry.getKey());
            if (old != null && sameIdentityList(old.oriented, entry.getValue())) updatedNodes.put(entry.getKey(), old);
            else updatedNodes.put(entry.getKey(), new NodeState(List.copyOf(entry.getValue()), findNode(entry.getKey(), entry.getValue())));
        }
        nodeStates = updatedNodes;

        var affectedPairs = new HashSet<Pair>();
        for (Pair pair : crossings.keySet()) if (affectedRails.contains(pair.first) || affectedRails.contains(pair.second)) affectedPairs.add(pair);
        for (Pair pair : affectedPairs) crossings.remove(pair);
        for (String id : changed) removeTrack(id);
        tracks = Map.copyOf(next);
        order = Map.copyOf(nextOrder);
        for (String id : changed) {
            Track track = tracks.get(id);
            if (track != null && !addTrack(track)) return remember(input, List.of());
        }

        var candidatePairs = new HashSet<Pair>();
        for (String id : affectedRails) {
            Track track = tracks.get(id);
            if (track == null) continue;
            for (Segment segment : segments.getOrDefault(id, List.of())) {
                for (Cell cell : cells(segment)) for (Segment other : bins.getOrDefault(cell, List.of())) {
                    if (!other.track.id.equals(id)) candidatePairs.add(Pair.of(id, other.track.id));
                }
            }
        }
        for (Pair pair : candidatePairs) {
            Track a = tracks.get(pair.first), b = tracks.get(pair.second);
            if (a != null && b != null) crossings.put(pair, findPair(a, b, nodes));
        }

        if (overloaded || cellPlacements > 200_000 || pairChecks > 2_000_000) { overloaded = true; return remember(input, List.of()); }
        var result = new ArrayList<Junction>();
        for (NodeState state : nodeStates.values()) result.addAll(state.junctions);
        var orderedPairs = new ArrayList<>(crossings.entrySet());
        orderedPairs.sort(Comparator.comparingInt((Map.Entry<Pair, List<Junction>> e) -> Math.max(order.getOrDefault(e.getKey().first, 0), order.getOrDefault(e.getKey().second, 0)))
            .thenComparingInt(e -> Math.min(order.getOrDefault(e.getKey().first, 0), order.getOrDefault(e.getKey().second, 0))));
        for (var pair : orderedPairs) result.addAll(pair.getValue());
        return remember(input, Detector.deduplicate(result.stream().filter(GeometryEligibility::supported).toList()));
    }

    public synchronized void clear() {
        tracks = Map.of(); order = Map.of(); nodeStates.clear(); bins.clear(); segments.clear(); crossings.clear();
        cellPlacements = 0; pairChecks = 0; overloaded = false; lastInput = List.of(); lastResult = List.of();
    }

    private boolean sameInput(List<Track> input) {
        if (input.size() != lastInput.size()) return false;
        for (int i = 0; i < input.size(); i++) if (input.get(i) != lastInput.get(i)) return false;
        return true;
    }

    private List<Junction> remember(List<Track> input, List<Junction> result) {
        lastInput = List.copyOf(input);
        lastResult = result;
        return result;
    }

    private static boolean sameIdentityList(List<Track> a, List<Track> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (a.get(i) != b.get(i)) return false;
        return true;
    }

    private static Map<String, List<Track>> buildNodes(Collection<Track> tracks) {
        var nodes = new TreeMap<String, List<Track>>();
        for (Track track : tracks) {
            nodes.computeIfAbsent(track.startNode, ignored -> new ArrayList<>()).add(track);
            nodes.computeIfAbsent(track.endNode, ignored -> new ArrayList<>()).add(track.reverse());
        }
        for (List<Track> incident : nodes.values()) incident.sort(Comparator.comparing(t -> t.id));
        return nodes;
    }

    private static List<Junction> findNode(String node, List<Track> incident) {
        var result = new ArrayList<Junction>();
        var ts = incident;
        if (ts.size() < 2 || ts.size() > 16) return result;
        for (int i = 0; i < ts.size(); i++) for (int k = i + 1; k < ts.size(); k++) for (int l = k + 1; l < ts.size(); l++) {
            Track a = ts.get(i), b = ts.get(k), c = ts.get(l);
            if (a.tangent(0).dot(b.tangent(0)) < .5 || a.tangent(0).dot(c.tangent(0)) < .5 || b.tangent(0).dot(c.tangent(0)) < .5) continue;
            boolean fourth = false;
            for (int n = 0; n < ts.size(); n++) if (n != i && n != k && n != l && ts.get(n).tangent(0).dot(a.tangent(0)) > .5) fourth = true;
            if (fourth) continue;
            double limit = Math.min(80, Math.min(a.length, Math.min(b.length, c.length)));
            V3 normal = a.tangent(0).lateral(); var fan = new ArrayList<>(List.of(a, b, c)); double extent = 0;
            for (double d = 1; d < limit; d += .25) if (Math.min(a.at(d).distance(b.at(d)), Math.min(a.at(d).distance(c.at(d)), b.at(d).distance(c.at(d)))) >= 2.5) { extent = Math.min(limit, d + 2); break; }
            double orderAt = extent; fan.sort(Comparator.comparingDouble(t -> t.at(orderAt).dot(normal)));
            if (extent > 0) result.add(new Junction("t:" + node + ":" + a.id + ":" + b.id + ":" + c.id, Junction.Kind.THREE, fan.get(0), fan.get(2), a.at(0), 0, 0, extent, fan.get(1)));
        }
        for (int i = 0; i < ts.size(); i++) for (int j = i + 1; j < ts.size(); j++) {
            Track a = ts.get(i), b = ts.get(j);
            if (a.length < 5 || b.length < 5 || a.tangent(0).dot(b.tangent(0)) < .5) continue;
            boolean third = false;
            for (int k = 0; k < ts.size(); k++) if (k != i && k != j && ts.get(k).tangent(0).dot(a.tangent(0)) > .5) third = true;
            if (third) continue;
            double limit = Math.min(80, Math.min(a.length, b.length)), extent = 0;
            for (double s = 1; s < limit; s += .25) if (a.at(s).distance(b.at(s)) >= 2.5) { extent = Math.min(limit, s + 2); break; }
            if (extent > 0) result.add(new Junction("y:" + node + ":" + a.id + ":" + b.id, Junction.Kind.Y, a, b, a.at(0), 0, 0, extent));
        }
        return List.copyOf(result);
    }

    private boolean addTrack(Track track) {
        var refs = new ArrayList<Segment>();
        for (int i = 1; i < track.points.size(); i++) {
            Segment segment = new Segment(track, i); refs.add(segment);
            List<Cell> touched = cells(segment);
            if (touched.size() > 200_000 || cellPlacements + touched.size() > 200_000) { overloaded = true; return false; }
            for (Cell cell : touched) {
                List<Segment> entries = bins.computeIfAbsent(cell, ignored -> new ArrayList<>());
                pairChecks += entries.size();
                entries.add(segment); cellPlacements++;
                if (pairChecks > 2_000_000) { segments.put(track.id, List.copyOf(refs)); overloaded = true; return false; }
            }
        }
        segments.put(track.id, List.copyOf(refs));
        return true;
    }

    private void removeTrack(String id) {
        List<Segment> refs = segments.remove(id);
        if (refs == null) return;
        for (Segment segment : refs) for (Cell cell : cells(segment)) {
            List<Segment> entries = bins.get(cell); if (entries == null) continue;
            pairChecks -= entries.size() - 1L;
            if (entries.remove(segment)) cellPlacements--;
            if (entries.isEmpty()) bins.remove(cell);
        }
    }

    private static List<Cell> cells(Segment segment) {
        V3 a = segment.track.points.get(segment.index - 1), b = segment.track.points.get(segment.index);
        var cells = new ArrayList<Cell>();
        for (int x = (int) Math.floor(Math.min(a.x(), b.x()) / 8); x <= (int) Math.floor(Math.max(a.x(), b.x()) / 8); x++)
            for (int z = (int) Math.floor(Math.min(a.z(), b.z()) / 8); z <= (int) Math.floor(Math.max(a.z(), b.z()) / 8); z++) {
                if (cells.size() > 200_000) return cells;
                cells.add(new Cell(x, z));
            }
        return cells;
    }

    private List<Junction> findPair(Track a, Track b, Map<String, List<Track>> nodes) {
        var candidates = new HashMap<String, Candidate>();
        for (Segment sa : segments.getOrDefault(a.id, List.of())) for (Cell cell : cells(sa))
            for (Segment other : bins.getOrDefault(cell, List.of())) if (other.track.id.equals(b.id)) {
                Segment current = order.getOrDefault(a.id, 0) > order.getOrDefault(b.id, 0) ? sa : other;
                Segment previous = current == sa ? other : sa;
                String key = current.index + ":" + previous.index;
                candidates.putIfAbsent(key, new Candidate(current, previous, cell.x, cell.z));
            }
        var sorted = new ArrayList<>(candidates.values());
        sorted.sort(Comparator.comparingInt((Candidate c) -> c.current.index)
            .thenComparingInt(c -> c.cellX).thenComparingInt(c -> c.cellZ)
            .thenComparingInt(c -> c.previous.index));
        var result = new ArrayList<Junction>();
        for (Candidate candidate : sorted) {
            Segment current = candidate.current, other = candidate.previous;
            V3 p0 = current.track.points.get(current.index - 1), p1 = current.track.points.get(current.index);
            V3 q0 = other.track.points.get(other.index - 1), q1 = other.track.points.get(other.index);
            double[] uv = Detector.intersection(p0, p1, q0, q1); if (uv == null) continue;
            V3 p = p0.lerp(p1, uv[0]), q = q0.lerp(q1, uv[1]); if (Math.abs(p.y() - q.y()) > .08) continue;
            double sine = Math.abs(V3.crossXZ(p1.sub(p0).unit(), q1.sub(q0).unit())); if (sine < .025) continue;
            double sc = current.track.distance[current.index - 1] + uv[0] * (current.track.distance[current.index] - current.track.distance[current.index - 1]);
            double so = other.track.distance[other.index - 1] + uv[1] * (other.track.distance[other.index] - other.track.distance[other.index - 1]);
            double extent = Math.min(80, 2 / sine + 1);
            if (Math.min(sc, current.track.length - sc) < .5 && !joined(current.track, sc, nodes)) continue;
            if (Math.min(so, other.track.length - so) < .5 && !joined(other.track, so, nodes)) continue;
            Track first = current.track.id.compareTo(other.track.id) < 0 ? current.track : other.track;
            Track second = first == current.track ? other.track : current.track;
            double sa = first == current.track ? sc : so, sb = first == current.track ? so : sc;
            String id = "x:" + first.id + ":" + second.id + ":" + Math.round(p.x() * 4) + ":" + Math.round(p.z() * 4);
            if (result.stream().noneMatch(j -> j.kind() == Junction.Kind.DIAMOND && j.center().distance(p) < 1))
                result.add(new Junction(id, Junction.Kind.DIAMOND, first, second, p, sa, sb, extent));
        }
        return List.copyOf(result);
    }

    private static boolean joined(Track track, double station, Map<String, List<Track>> nodes) {
        boolean atStart = station < .5; String node = atStart ? track.startNode : track.endNode;
        V3 outgoing = atStart ? track.tangent(0) : track.tangent(track.length).mul(-1);
        for (Track other : nodes.getOrDefault(node, List.of())) if (!other.id.equals(track.id) && other.tangent(0).dot(outgoing) < -.995) return true;
        return false;
    }
}
