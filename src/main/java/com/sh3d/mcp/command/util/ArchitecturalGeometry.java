package com.sh3d.mcp.command.util;

import java.awt.geom.Area;
import java.awt.geom.Path2D;

/** Geometry helpers used by the architectural analysis tools. */
public final class ArchitecturalGeometry {

    private ArchitecturalGeometry() {
    }

    public static boolean polygonsIntersect(float[][] first, float[][] second) {
        if (!valid(first) || !valid(second)) return false;
        Area intersection = new Area(toPath(first));
        intersection.intersect(new Area(toPath(second)));
        return !intersection.isEmpty();
    }

    public static double polygonDistance(float[][] first, float[][] second) {
        if (!valid(first) || !valid(second)) return Double.POSITIVE_INFINITY;
        if (polygonsIntersect(first, second)) return 0d;
        double min = Double.POSITIVE_INFINITY;
        for (int i = 0; i < first.length; i++) {
            float[] a1 = first[i];
            float[] a2 = first[(i + 1) % first.length];
            for (int j = 0; j < second.length; j++) {
                float[] b1 = second[j];
                float[] b2 = second[(j + 1) % second.length];
                min = Math.min(min, segmentDistance(a1[0], a1[1], a2[0], a2[1],
                        b1[0], b1[1], b2[0], b2[1]));
            }
        }
        return min;
    }

    public static double segmentDistance(double ax, double ay, double bx, double by,
                                          double cx, double cy, double dx, double dy) {
        if (segmentsIntersect(ax, ay, bx, by, cx, cy, dx, dy)) return 0d;
        return Math.min(Math.min(pointSegmentDistance(ax, ay, cx, cy, dx, dy),
                        pointSegmentDistance(bx, by, cx, cy, dx, dy)),
                Math.min(pointSegmentDistance(cx, cy, ax, ay, bx, by),
                        pointSegmentDistance(dx, dy, ax, ay, bx, by)));
    }

    public static double polygonArea(float[][] points) {
        if (!valid(points)) return 0d;
        double sum = 0d;
        for (int i = 0; i < points.length; i++) {
            float[] current = points[i];
            float[] next = points[(i + 1) % points.length];
            sum += current[0] * next[1] - next[0] * current[1];
        }
        return Math.abs(sum) / 2d;
    }

    public static double length(double x1, double y1, double x2, double y2) {
        return Math.hypot(x2 - x1, y2 - y1);
    }

    private static boolean valid(float[][] points) {
        return points != null && points.length >= 3;
    }

    private static Path2D toPath(float[][] points) {
        Path2D.Float path = new Path2D.Float(Path2D.WIND_NON_ZERO);
        path.moveTo(points[0][0], points[0][1]);
        for (int i = 1; i < points.length; i++) {
            path.lineTo(points[i][0], points[i][1]);
        }
        path.closePath();
        return path;
    }

    private static double pointSegmentDistance(double px, double py, double ax, double ay,
                                                double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        if (dx == 0d && dy == 0d) return Math.hypot(px - ax, py - ay);
        double t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy);
        t = Math.max(0d, Math.min(1d, t));
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    private static boolean segmentsIntersect(double ax, double ay, double bx, double by,
                                             double cx, double cy, double dx, double dy) {
        double d1 = direction(cx, cy, dx, dy, ax, ay);
        double d2 = direction(cx, cy, dx, dy, bx, by);
        double d3 = direction(ax, ay, bx, by, cx, cy);
        double d4 = direction(ax, ay, bx, by, dx, dy);
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0))
                && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0));
    }

    private static double direction(double ax, double ay, double bx, double by,
                                    double cx, double cy) {
        return (cx - ax) * (by - ay) - (cy - ay) * (bx - ax);
    }
}
