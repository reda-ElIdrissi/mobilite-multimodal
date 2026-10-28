package com.transport.mobility.contracts.v1;

import java.net.URI;

public record ProblemDetails(URI type, String title, int status, String detail, String code, String traceId) {}
