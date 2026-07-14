package org.example.weather.api;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

class Helpers {
    static final int PAGE_SIZE = 25;
    static final String TOTAL_COUNT = "total_count";

    /**
     * The fixed 6-hour bucket grid for the past-week detail view (requirements
     * iteration 5): boundaries at 02:00/08:00/14:00/20:00 UTC, oldest first.
     * Returns the 25 bucket start times from window_start (the current bucket
     * minus 6 days) up to and including the current, partial bucket that
     * contains {@code now}.
     *
     * <p>Pure and clock-free (takes {@code now}) so the boundary maths --
     * alignment, the overnight bucket crossing midnight, the 6-day window start
     * -- can be unit-tested without a database.
     *
     * <p>Past week means "touches 7 calendar days" incl. today. That's why minus 6 days.
     *
     * @param now the query time; any offset is read as the same instant in UTC
     * @return 25 bucket start times, ascending, exactly 6 hours apart
     */
    public static List<OffsetDateTime> bucketStarts(OffsetDateTime now) {
        OffsetDateTime nowUtc = now.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS);
        // Boundaries sit at hour == 2 (mod 6); step back to the current one.
        int hoursIntoBucket = Math.floorMod(nowUtc.getHour() - 2, 6);
        OffsetDateTime currentBucketStart = nowUtc.minusHours(hoursIntoBucket);
        OffsetDateTime windowStart = currentBucketStart.minusDays(6);

        List<OffsetDateTime> starts = new ArrayList<>();
        for (OffsetDateTime t = windowStart; !t.isAfter(currentBucketStart); t = t.plusHours(6)) {
            starts.add(t);
        }
        return starts;
    }

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
