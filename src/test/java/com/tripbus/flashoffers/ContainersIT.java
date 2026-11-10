package com.tripbus.flashoffers;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * PostgreSQL 16 y Redis 7 reales (Testcontainers) compartidos por las pruebas de integración de los sprints 5 y 6:
 * se levantan una sola vez por ejecución (patrón singleton) y Spring reutiliza el contexto entre las clases que
 * tienen la misma configuración. Cada prueba usa ids aleatorios, así no se pisan los datos.
 */
abstract class ContainersIT {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }
}
