package com.transport.autopartage.vehiculeRoute;

import io.opentelemetry.api.GlobalOpenTelemetry;
import org.springframework.stereotype.Component;

@Component
class CarHoldMetrics {
    private final LocationRepository locations;

    CarHoldMetrics(LocationRepository locations) {
        this.locations = locations;
        GlobalOpenTelemetry.getMeter("mobility").counterBuilder("mobility.car.holds.expired")
                .setDescription("Number of car holds recorded as expired")
                .buildWithCallback(counter -> counter.record(expiredHolds()));
    }

    long expiredHolds() {
        return locations.countByStatusAndHoldOperationIdIsNotNull(Status.EXPIRED);
    }
}
