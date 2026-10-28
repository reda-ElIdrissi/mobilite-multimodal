package com.transport.mobility.contracts.v1;

import java.util.UUID;

public record ConfirmationReceipt(String bookingRef, UUID operationId) {}
