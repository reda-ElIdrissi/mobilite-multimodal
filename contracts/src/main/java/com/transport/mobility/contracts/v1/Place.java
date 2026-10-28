package com.transport.mobility.contracts.v1;

import java.time.ZoneId;

public record Place(String cityCode, String displayName, ZoneId zoneId) {}
