package com.transport.mobility.observability;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class Telemetry {
    private static final String NAME = "mobility";
    private static final String TRACEPARENT = "traceparent";

    private Telemetry() {}

    public static SpanBuilder span(String name) {
        return GlobalOpenTelemetry.getTracer(NAME).spanBuilder(name);
    }

    public static String traceparent() {
        var header = new AtomicReference<String>();
        W3CTraceContextPropagator.getInstance().inject(Context.current(), header, (carrier, key, value) -> {
            if (TRACEPARENT.equals(key)) {
                carrier.set(value);
            }
        });
        return header.get();
    }

    public static Context context(String traceparent) {
        return W3CTraceContextPropagator.getInstance().extract(Context.root(), traceparent, new TextMapGetter<String>() {
            @Override public Iterable<String> keys(String carrier) { return List.of(TRACEPARENT); }
            @Override public String get(String carrier, String key) { return TRACEPARENT.equals(key) ? carrier : null; }
        });
    }

    public static SpanBuilder resume(String name, String traceparent, boolean longRunning) {
        var origin = context(traceparent);
        var builder = span(name);
        if (longRunning) {
            return builder.setNoParent().addLink(Span.fromContext(origin).getSpanContext());
        }
        return builder.setParent(origin);
    }

    public static <T, E extends Exception> T legacy(String operator, String protocol, String operation,
            RemoteCall<T, E> call) throws E {
        var span = span("legacy." + protocol + "." + operation).setSpanKind(SpanKind.CLIENT)
                .setAttribute("mobility.operator", operator).setAttribute("rpc.system", protocol)
                .setAttribute("rpc.method", operation).startSpan();
        long start = System.nanoTime();
        String result = "success";
        try (var scope = span.makeCurrent()) {
            return call.execute();
        } catch (Exception failure) {
            result = "error";
            span.setStatus(StatusCode.ERROR);
            span.setAttribute("error.type", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            span.setAttribute("mobility.result", result);
            span.end();
            var tags = Attributes.builder().put("operator", operator).put("operation", operation).put("result", result).build();
            GlobalOpenTelemetry.getMeter(NAME).histogramBuilder("mobility.operator.duration").setUnit("ms")
                    .build().record((System.nanoTime() - start) / 1_000_000.0, tags);
        }
    }

    @FunctionalInterface
    public interface RemoteCall<T, E extends Exception> {
        T execute() throws E;
    }
}
