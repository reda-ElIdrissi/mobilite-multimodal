package com.transport.mobility.contracts.v1;

import java.time.Instant;
import java.util.UUID;

public record HoldReceipt(String holdId, Instant expiresAt, UUID operationId) {}
