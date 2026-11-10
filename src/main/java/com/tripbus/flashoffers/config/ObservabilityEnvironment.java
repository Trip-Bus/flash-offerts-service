package com.tripbus.flashoffers.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * HT-10: traduce las variables de observabilidad a propiedades de Spring Boot antes de crear los beans y de
 * configurar los logs.
 *
 * <ul>
 *   <li>{@code OTEL_EXPORTER_OTLP_ENDPOINT}: si trae valor se usa como {@code management.otlp.tracing.endpoint}.
 *   Vacío = exportación apagada. No basta con {@code ${OTEL_EXPORTER_OTLP_ENDPOINT:}} en el yml: la
 *   autoconfiguración de OTLP se activa con la propiedad presente aunque esté vacía y falla al arrancar.</li>
 *   <li>{@code LOG_FORMAT=json} (o {@code tripbus.logging.format=json}): logs de consola en JSON con el formato
 *   ECS nativo de Spring Boot ({@code logging.structured.format.console=ecs}), con el MDC (traceId, spanId,
 *   requestId) en cada línea.</li>
 * </ul>
 *
 * Las propiedades se agregan con la prioridad más baja: lo que se configure explícitamente gana.
 * Se registra en {@code META-INF/spring.factories}.
 */
public class ObservabilityEnvironment implements EnvironmentPostProcessor {

    static final String SOURCE = "tripbusObservability";
    static final String OTLP_ENDPOINT = "management.otlp.tracing.endpoint";
    static final String CONSOLE_FORMAT = "logging.structured.format.console";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> props = new HashMap<>();
        String endpoint = environment.getProperty("OTEL_EXPORTER_OTLP_ENDPOINT");
        if (StringUtils.hasText(endpoint) && !environment.containsProperty(OTLP_ENDPOINT)) {
            props.put(OTLP_ENDPOINT, endpoint.trim());
        }
        String format = environment.getProperty("tripbus.logging.format", environment.getProperty("LOG_FORMAT", ""));
        if ("json".equalsIgnoreCase(format.trim()) && !environment.containsProperty(CONSOLE_FORMAT)) {
            props.put(CONSOLE_FORMAT, "ecs");
        }
        if (!props.isEmpty()) {
            environment.getPropertySources().addLast(new MapPropertySource(SOURCE, props));
        }
    }
}
