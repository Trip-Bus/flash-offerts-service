package com.tripbus.flashoffers.common.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Manejo de un registro del stream: idempotencia, reintentos y ack, dentro del span del evento. */
class EventConsumerTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final StreamOperations<String, Object, Object> stream = mock(StreamOperations.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final ProcessedEventRepository processed = mock(ProcessedEventRepository.class);
    private final DomainEventHandler handler = mock(DomainEventHandler.class);
    private final EventProperties properties = new EventProperties(true, "tripbus:events", 0, "g", true);
    private EventConsumer consumer;

    @BeforeEach
    void preparar() {
        when(redis.opsForStream()).thenReturn(stream);
        when(handler.eventTypes()).thenReturn(Set.of("SeatSold"));
        doAnswer(inv -> {
            Consumer<?> c = inv.getArgument(0);
            c.accept(null);
            return null;
        }).when(tx).executeWithoutResult(any());
        StaticListableBeanFactory empty = new StaticListableBeanFactory();
        EventTracing tracing = new EventTracing(empty.getBeanProvider(io.micrometer.tracing.Tracer.class),
                empty.getBeanProvider(io.micrometer.tracing.propagation.Propagator.class));
        consumer = new EventConsumer(mock(RedisConnectionFactory.class), redis, new ObjectMapper(), properties, tx,
                processed, List.of(handler), tracing);
    }

    private static MapRecord<String, String, String> record(String type) {
        return StreamRecords.newRecord().in("tripbus:events").withId(RecordId.of("1-0"))
                .ofMap(Map.of("eventId", "e1", "type", type, "source", "seat-inventory",
                        "occurredAt", "2026-11-05T12:00:00Z", "payload", "{\"holdId\":\"x\"}",
                        "traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"));
    }

    @Test
    void procesaElEventoLoMarcaComoProcesadoYConfirma() {
        consumer.onMessage(record("SeatSold"));
        verify(handler).handle(any());
        verify(processed).save(any(ProcessedEvent.class));
        verify(stream).acknowledge("tripbus:events", "g", RecordId.of("1-0"));
    }

    @Test
    void unEventoYaProcesadoNoSeRepite() {
        when(processed.existsById("e1")).thenReturn(true);
        consumer.onMessage(record("SeatSold"));
        verify(handler, never()).handle(any());
        verify(stream).acknowledge(eq("tripbus:events"), eq("g"), any(RecordId.class));
    }

    @Test
    void reintentaTresVecesYDespuesConfirmaParaNoBloquearElStream() {
        doThrow(new IllegalStateException("bd caída")).when(handler).handle(any());
        consumer.onMessage(record("SeatSold"));
        verify(handler, times(3)).handle(any());
        verify(stream).acknowledge(eq("tripbus:events"), eq("g"), any(RecordId.class));
    }

    @Test
    void unTipoSinManejadorSoloSeConfirma() {
        consumer.onMessage(record("Otro"));
        verify(tx, never()).execute(any(TransactionCallback.class));
        verify(handler, never()).handle(any());
        verify(stream).acknowledge(eq("tripbus:events"), eq("g"), any(RecordId.class));
    }
}
