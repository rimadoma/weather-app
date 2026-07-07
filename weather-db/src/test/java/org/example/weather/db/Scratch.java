package org.example.weather.db;

import org.jooq.CloseableDSLContext;
import org.jooq.impl.DSL;

import static org.example.weather.db.generated.Tables.REGIONS;

/**
 * Throwaway Phase 0 checkpoint: proves migrate -> codegen -> query works end
 * to end against the compose database. Run the main method from the IDE;
 * delete this class once slice 1 starts.
 */
public class Scratch {

    static void main() {
        try (CloseableDSLContext db =
                DSL.using("jdbc:postgresql://localhost:5432/weather", "weather", "weather")) {

            db.insertInto(REGIONS, REGIONS.NAME)
                    .values("Northumberland")
                    .onConflictDoNothing()
                    .execute();

            db.selectFrom(REGIONS)
                    .fetch()
                    .forEach(r -> System.out.printf("%d: %s%n", r.getId(), r.getName()));

            int deleted = db.deleteFrom(REGIONS)
                    .where(REGIONS.NAME.eq("Northumberland"))
                    .execute();
            System.out.println("cleaned up " + deleted + " row(s)");
        }
    }
}
