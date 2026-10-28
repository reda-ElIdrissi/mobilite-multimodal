package com.transport.mobility.contracts.v1;

import java.util.List;

public record Itinerary(int travelerCount, List<String> participants, List<Segment> segments,
                        List<String> timingAssumptions) {
    public Itinerary {
        participants = List.copyOf(participants);
        segments = List.copyOf(segments);
        timingAssumptions = List.copyOf(timingAssumptions);
    }
}
