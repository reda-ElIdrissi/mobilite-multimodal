package com.transport.mobility.search;

import com.transport.mobility.contracts.v1.Place;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class PlaceDirectory {
    private static final String MOROCCO = "Africa/Casablanca";
    private static final Map<String, Place> PLACES = List.of(
            place("CAS", "Casablanca", MOROCCO),
            place("RAB", "Rabat", MOROCCO),
            place("RAK", "Marrakech", MOROCCO),
            place("TNG", "Tanger", MOROCCO),
            place("FES", "Fès", MOROCCO),
            place("PAR", "Paris", "Europe/Paris"),
            place("VERSAILLES", "Versailles", "Europe/Paris"),
            place("MAD", "Madrid", "Europe/Madrid"),
            place("DAK", "Dakar", "Africa/Dakar"))
            .stream().collect(Collectors.toUnmodifiableMap(Place::cityCode, Function.identity()));

    private PlaceDirectory() {}

    public static Place require(String code) {
        Place place = code == null ? null : PLACES.get(code);
        if (place == null) {
            throw new IllegalArgumentException("Unknown city code");
        }
        return place;
    }

    public static java.util.Optional<ZoneId> zoneForName(String name) {
        return PLACES.values().stream().filter(place -> place.displayName().equals(name))
                .map(Place::zoneId).findFirst();
    }

    public static List<String> cityCodes() {
        return PLACES.keySet().stream().sorted().toList();
    }

    private static Place place(String code, String name, String zone) {
        return new Place(code, name, ZoneId.of(zone));
    }
}
