package com.transport.autopartage.vehiculeRoute;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class HoldExpiry {
    private final CarParticipant participant;

    HoldExpiry(CarParticipant participant) {
        this.participant = participant;
    }

    @Scheduled(fixedDelayString = "${mobility.car.expiry-scan-ms:30000}")
    void expire() {
        participant.expireDue();
    }
}
