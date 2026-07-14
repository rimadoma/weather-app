package org.example.weather.api;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HelpersTest {

    @Test
    void gridHas25BucketsAlignedTo6HourBoundaries() {
        List<OffsetDateTime> starts = Helpers.bucketStarts(OffsetDateTime.of(2026, 7, 14, 19, 30, 0, 0, ZoneOffset.UTC));

        assertThat(starts).hasSize(25);
        assertThat(starts).allSatisfy(s -> {
            assertThat(s.getOffset()).isEqualTo(ZoneOffset.UTC);
            assertThat(s.getMinute()).isZero();
            assertThat(s.getSecond()).isZero();
            assertThat(s.getHour()).isIn(2, 8, 14, 20);
        });
        for (int i = 1; i < starts.size(); i++) {
            assertThat(starts.get(i)).isEqualTo(starts.get(i - 1).plusHours(6));
        }
    }

    @Test
    void windowStartsSixDaysBeforeTheCurrentBucket() {
        OffsetDateTime now = OffsetDateTime.of(2026, 7, 14, 19, 30, 0, 0, ZoneOffset.UTC);

        List<OffsetDateTime> starts = Helpers.bucketStarts(now);
        OffsetDateTime current = starts.getLast();

        // now 19:30 falls in the [14:00, 20:00) bucket.
        assertThat(current).isEqualTo(OffsetDateTime.of(2026, 7, 14, 14, 0, 0, 0, ZoneOffset.UTC));
        assertThat(starts.getFirst()).isEqualTo(current.minusDays(6));
        // The last bucket is the current, partial one containing now.
        assertThat(current).isBeforeOrEqualTo(now);
        assertThat(current.plusHours(6)).isAfter(now);
    }

    @Test
    void overnightBucketCrossesMidnight() {
        // 00:30 belongs to the previous day's [20:00, 02:00) overnight bucket.
        OffsetDateTime now = OffsetDateTime.of(2026, 7, 14, 0, 30, 0, 0, ZoneOffset.UTC);

        List<OffsetDateTime> starts = Helpers.bucketStarts(now);
        OffsetDateTime current = starts.getLast();

        assertThat(current).isEqualTo(OffsetDateTime.of(2026, 7, 13, 20, 0, 0, 0, ZoneOffset.UTC));
        assertThat(current).isBeforeOrEqualTo(now);
        assertThat(current.plusHours(6)).isAfter(now);
        assertThat(starts).hasSize(25);
    }

    @Test
    void readsNonUtcNowAsTheSameInstant() {
        // 01:30 at +02:00 == 23:30 UTC the day before, which is the [20:00, 02:00) bucket.
        OffsetDateTime now = OffsetDateTime.of(2026, 7, 14, 1, 30, 0, 0, ZoneOffset.ofHours(2));

        OffsetDateTime current = Helpers.bucketStarts(now).getLast();

        assertThat(current).isEqualTo(OffsetDateTime.of(2026, 7, 13, 20, 0, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void boundaryInstantStartsANewBucket() {
        // Exactly 08:00 is the start of its own bucket, not the tail of the previous.
        OffsetDateTime now = OffsetDateTime.of(2026, 7, 14, 8, 0, 0, 0, ZoneOffset.UTC);

        assertThat(Helpers.bucketStarts(now).getLast())
                .isEqualTo(OffsetDateTime.of(2026, 7, 14, 8, 0, 0, 0, ZoneOffset.UTC));
    }
}
