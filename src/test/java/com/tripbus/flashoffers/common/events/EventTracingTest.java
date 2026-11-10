package com.tripbus.flashoffers.common.events;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** HT-10: el contexto de traza viaja en los registros del stream y el consumidor continúa la misma traza. */
class EventTracingTest {

    private final SdkTracerProvider provider = SdkTracerProvider.builder().setSampler(Sampler.alwaysOn()).build();
    private final io.opentelemetry.api.trace.Tracer otel = provider.get("test");
    private final OtelTracer tracer = new OtelTracer(otel, new OtelCurrentTraceContext(), event -> { });
    private final OtelPropagator propagator =
            new OtelPropagator(ContextPropagators.create(W3CTraceContextPropagator.getInstance()), otel);

    @AfterEach
    void cerrar() {
        provider.close();
    }

    private static <T> ObjectProvider<T> provider(Class<T> type, T bean) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        if (bean != null) {
            factory.addBean(type.getSimpleName(), bean);
        }
        return factory.getBeanProvider(type);
    }

    private EventTracing tracing() {
        return new EventTracing(provider(Tracer.class, tracer), provider(Propagator.class, propagator));
    }

    @Test
    void publicarDentroDeUnSpanAgregaTraceparentYElConsumidorSigueLaMismaTraza() {
        EventTracing tracing = tracing();
        Map<String, String> fields = new LinkedHashMap<>();
        Span request = tracer.nextSpan().name("POST /offers/{id}/reservations").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(request)) {
            tracing.inject(fields);
        } finally {
            request.end();
        }
        assertThat(fields.get("traceparent")).contains(request.context().traceId());

        AtomicReference<String> traceDelConsumidor = new AtomicReference<>();
        AtomicReference<String> spanDelConsumidor = new AtomicReference<>();
        tracing.consume("SeatSold", fields, () -> {
            traceDelConsumidor.set(tracer.currentSpan().context().traceId());
            spanDelConsumidor.set(tracer.currentSpan().context().spanId());
        });
        assertThat(traceDelConsumidor.get()).isEqualTo(request.context().traceId());
        assertThat(spanDelConsumidor.get()).isNotEqualTo(request.context().spanId());
        assertThat(tracer.currentSpan()).as("el span del consumidor se cierra").isNull();
    }

    @Test
    void sinSpanActualNoAgregaNadaYUnEventoSinContextoAbreUnaTrazaNueva() {
        EventTracing tracing = tracing();
        Map<String, String> fields = new HashMap<>();
        tracing.inject(fields);
        assertThat(fields).isEmpty();

        AtomicReference<String> trace = new AtomicReference<>();
        tracing.consume("HoldExpired", fields, () -> trace.set(tracer.currentSpan().context().traceId()));
        assertThat(trace.get()).isNotBlank();
    }

    @Test
    void unErrorDelManejadorSePropagaYElSpanSeCierra() {
        EventTracing tracing = tracing();
        Map<String, String> fields = new HashMap<>();
        IllegalStateException error = new IllegalStateException("falló");
        assertThatThrownBy(() -> tracing.consume("SeatSold", fields, () -> {
            throw error;
        })).isSameAs(error);
        assertThat(tracer.currentSpan()).isNull();
    }

    @Test
    void sinTrazasConfiguradasSoloEjecutaElTrabajo() {
        EventTracing tracing = new EventTracing(provider(Tracer.class, null), provider(Propagator.class, null));
        Map<String, String> fields = new HashMap<>();
        tracing.inject(fields);
        assertThat(fields).isEmpty();
        AtomicReference<Boolean> corrio = new AtomicReference<>(false);
        tracing.consume("SeatSold", fields, () -> corrio.set(true));
        assertThat(corrio.get()).isTrue();
    }
}
