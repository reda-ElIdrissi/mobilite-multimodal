package com.transport.rail;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;

public final class RailSearch {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: RailSearch <IOR file>");
        }
        try (var rail = new RailClient(Path.of(args[0]))) {
            var fixture = Arrays.stream(rail.search("Casablanca", "Rabat", LocalDate.of(2026, 11, 15)))
                    .filter(trip -> "RAIL-CAS-RAB-0730".equals(trip.tripId))
                    .findFirst().orElseThrow();
            if (fixture.priceMinor != 8000 || fixture.seatsAvailable != 120
                    || !"2026-11-15T08:25:00".equals(fixture.arrival)) {
                throw new IllegalStateException("Unexpected rail fixture");
            }
            System.out.println(fixture.tripId + " " + fixture.arrival + " " + fixture.priceMinor
                    + " MAD minor units, " + fixture.seatsAvailable + " seats");
        }
    }
}
