package invalid.lena.scrcpy;

/** Screen bounds for the floating menu, including cutouts. Android-free. */
final class EdgeMenuPosition {
    final int minX, maxX, minY, maxY;

    EdgeMenuPosition(int width, int height, int handleWidth, int handleHeight,
            int left, int top, int right, int bottom) {
        int lastX = Math.max(0, width - handleWidth);
        int lastY = Math.max(0, height - handleHeight);
        minX = clamp(left, 0, lastX);
        maxX = clamp(lastX - right, minX, lastX);
        minY = clamp(top, 0, lastY);
        maxY = clamp(lastY - bottom, minY, lastY);
    }

    int x(boolean left) { return left ? minX : maxX; }
    int y(int permille) {
        return minY + Math.round((maxY - minY) * clamp(permille, 0, 1000) / 1000f);
    }
    int permille(int top) {
        return maxY == minY ? 500
                : Math.round((clamp(top, minY, maxY) - minY) * 1000f / (maxY - minY));
    }
    boolean nearestLeft(int x) { return x - minX < maxX - x; }
    static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
