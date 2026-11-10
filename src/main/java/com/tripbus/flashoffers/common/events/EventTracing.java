package com.tripbus.flashoffers.common.events;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * HT-10: propaga el contexto de traza por Redis Streams. Al publicar se agregan al registro los campos de
 * propagación (W3C {@code traceparent}/{@code tracestate}) del span actual; al consumir, el manejador corre en un
 * span CONSUMER hijo de ese contexto, así una reserva y lo que provoca en otros servicios quedan en la misma traza.
 * Si no hay trazas (p. ej. en pruebas con el tracing apagado) no hace nada.
 */
@Component
public class EventTracing {

    private final ObjectProvider<Tracer> tracer;
    private final ObjectProvider<Propagator> propagator;

    public EventTracing(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /** Agrega a {@code fields} el contexto del span actual, si lo hay. */
    public void inject(Map<String, String> fields) {
        Tracer t = tracer.getIfAvailable();
        Propagator p = propagator.getIfAvailable();
        if (t == null || p == null) {
            return;
        }
        Span current = t.currentSpan();
        if (current != null) {
            p.inject(current.context(), fields, Map::put);
        }
    }

    /** Corre {@code work} dentro de un span hijo del contexto que trae el evento (o de una traza nueva). */
    public void consume(String eventType, Map<String, String> fields, Runnable work) {
        Tracer t = tracer.getIfAvailable();
        Propagator p = propagator.getIfAvailable();
        if (t == null || p == null) {
            work.run();
            return;
        }
        Span span = p.extract(fields, Map::get).name("event " + eventType).kind(Span.Kind.CONSUMER)
                .tag("messaging.system", "redis-streams").start();
        try (Tracer.SpanInScope ignored = t.withSpan(span)) {
            work.run();
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
