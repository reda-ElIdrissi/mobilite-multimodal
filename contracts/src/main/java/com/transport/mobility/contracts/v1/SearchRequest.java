package com.transport.mobility.contracts.v1;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public record SearchRequest(String origin, String destination, LocalDate date, LocalDate dateFin,
                            Integer travelerCount, Set<Offer.Mode> modes, String category,
                            Integer maxSegments, List<String> via, Integer driverCount, Integer carDurationMinutes) {
    public boolean compositionRequested() {
        return maxSegments != null || via != null || driverCount != null || carDurationMinutes != null;
    }
}
