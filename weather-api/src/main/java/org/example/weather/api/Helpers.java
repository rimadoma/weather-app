package org.example.weather.api;

class Helpers {
    static final int PAGE_SIZE = 25;
    static final String TOTAL_COUNT = "total_count";

    /**
     * Converts wind vector components into a bearing as per meteorological convention
     *
     * @param x vector's x component
     * @param y vector's y component
     * @return wind bearing in whole degrees
     */
    public static short bearingFromComponents(double x, double y) {
        long degrees = Math.round(Math.toDegrees(Math.atan2(y, x)));
        return (short) Math.floorMod(degrees, 360);
    }
}
