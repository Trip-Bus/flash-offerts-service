package com.tripbus.flashoffers.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityEnvironmentTest {

    private final ObservabilityEnvironment processor = new ObservabilityEnvironment();

    private MockEnvironment process(MockEnvironment env) {
        processor.postProcessEnvironment(env, new SpringApplication());
        return env;
    }

    @Test
    void sinVariablesNoExportaNiCambiaElFormatoDeLogs() {
        MockEnvironment env = process(new MockEnvironment());
        assertThat(env.containsProperty(ObservabilityEnvironment.OTLP_ENDPOINT)).isFalse();
        assertThat(env.containsProperty(ObservabilityEnvironment.CONSOLE_FORMAT)).isFalse();
        assertThat(env.getPropertySources().contains(ObservabilityEnvironment.SOURCE)).isFalse();
    }

    @Test
    void unEndpointVacioDejaLaExportacionApagada() {
        MockEnvironment env = process(new MockEnvironment().withProperty("OTEL_EXPORTER_OTLP_ENDPOINT", "  "));
        assertThat(env.containsProperty(ObservabilityEnvironment.OTLP_ENDPOINT)).isFalse();
    }

    @Test
    void conEndpointEnciendeLaExportacionOtlp() {
        MockEnvironment env = process(new MockEnvironment()
                .withProperty("OTEL_EXPORTER_OTLP_ENDPOINT", " http://otel:4318/v1/traces "));
        assertThat(env.getProperty(ObservabilityEnvironment.OTLP_ENDPOINT)).isEqualTo("http://otel:4318/v1/traces");
    }

    @Test
    void unEndpointExplicitoGanaSobreLaVariable() {
        MockEnvironment env = process(new MockEnvironment()
                .withProperty("OTEL_EXPORTER_OTLP_ENDPOINT", "http://otel:4318/v1/traces")
                .withProperty(ObservabilityEnvironment.OTLP_ENDPOINT, "http://otro:4318/v1/traces"));
        assertThat(env.getProperty(ObservabilityEnvironment.OTLP_ENDPOINT)).isEqualTo("http://otro:4318/v1/traces");
    }

    @Test
    void logFormatJsonActivaLogsEcs() {
        MockEnvironment env = process(new MockEnvironment().withProperty("LOG_FORMAT", "JSON"));
        assertThat(env.getProperty(ObservabilityEnvironment.CONSOLE_FORMAT)).isEqualTo("ecs");
    }

    @Test
    void elFormatoDelPerfilTieneMasPrioridadQueLaVariable() {
        MockEnvironment env = process(new MockEnvironment()
                .withProperty("LOG_FORMAT", "json").withProperty("tripbus.logging.format", "plain"));
        assertThat(env.containsProperty(ObservabilityEnvironment.CONSOLE_FORMAT)).isFalse();
        MockEnvironment prod = process(new MockEnvironment().withProperty("tripbus.logging.format", "json"));
        assertThat(prod.getProperty(ObservabilityEnvironment.CONSOLE_FORMAT)).isEqualTo("ecs");
    }

    @Test
    void unFormatoExplicitoGana() {
        MockEnvironment env = process(new MockEnvironment().withProperty("LOG_FORMAT", "json")
                .withProperty(ObservabilityEnvironment.CONSOLE_FORMAT, "logstash"));
        assertThat(env.getProperty(ObservabilityEnvironment.CONSOLE_FORMAT)).isEqualTo("logstash");
    }
}
