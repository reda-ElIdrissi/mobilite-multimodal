package com.transport.air;

import java.time.LocalDate;

public final class AirSearch {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: AirSearch <SOAP endpoint>");
        }
        var fixture = new AirClient(args[0]).search("Rabat", "Paris", LocalDate.of(2026, 11, 15)).stream()
                .filter(flight -> "AIR-RBA-ORY-1200".equals(flight.getFlightId()))
                .findFirst().orElseThrow();
        if (fixture.getPriceMinor() != 180000 || fixture.getSeatsAvailable() != 40
                || !"2026-11-15T15:10:00".equals(fixture.getArrival())) {
            throw new IllegalStateException("Unexpected air fixture");
        }
        System.out.println(fixture.getFlightId() + " " + fixture.getArrival() + " "
                + fixture.getPriceMinor() + " MAD minor units, " + fixture.getSeatsAvailable() + " seats");
    }
}
