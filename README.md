# Flash Offers Service

## Sprints 5 y 6: observabilidad, sala de espera, antiabuso, automáticas y recurrentes

- HT-10: trazas OpenTelemetry (OTLP opcional), logs JSON ECS con `traceId`/`spanId`/`requestId` y métricas de
  reservas, activaciones y cupo restante. Ver [Observabilidad](#observabilidad).
- US-162 sala de espera virtual, US-168 antiabuso, US-167 ofertas automáticas por baja ocupación y US-169 ofertas
  recurrentes. Ver la [API](#api) y las secciones de reglas que siguen.

## Sprint 4: alertas y métricas

- `POST /offers/follows`, `GET /offers/follows` y `DELETE /offers/follows/{id}` permiten al pasajero seguir hasta diez rutas; la coincidencia ignora mayúsculas y tildes.
- Al activar una oferta se emite un `OfferAlert` privado por cada seguidor de la ruta. El evento contiene `userId`, `offerId`, `itineraryId`, origen, destino, precio de oferta, porcentaje de descuento y fin; su envío es asíncrono e idempotente por usuario/oferta.
- `GET /offers/{id}/metrics` está disponible para el operador propietario o ADMIN. `GET /operator/offers/metrics?desde=&hasta=` resume hasta 90 días de ofertas de la empresa.

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=Trip-Bus_flash-offerts-service&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=Trip-Bus_flash-offerts-service)

Ofertas relámpago: creación con cupo y horario, contador atómico de cupo, venta sin sobreventa y cupo en vivo.

**Stack:** Java 21 · Spring Boot 3.5.16 · PostgreSQL 16 · Redis 7

**HU:** US-054, US-056, US-058, US-160, US-161, US-162, US-164, US-165, US-166, US-167, US-168, US-169, US-192 · HT-10 · **Puerto:** 18006 · **BD:** `tripbus_offers` (puerto QA 18106)

> **Nombre "offerts" / "offers":** el repositorio GitHub, el proyecto de Sonar
> (`Trip-Bus_flash-offerts-service`) y la imagen (`ghcr.io/trip-bus/flash-offerts-service`) se
> escriben con **"offerts"**; la aplicación (`spring.application.name`, `artifactId`) y el servicio
> del compose (`flash-offers-service`, así lo llaman el gateway y los demás servicios) con **"offers"**.
> Es intencional: no se renombra nada para no romper el CI ni el gateway.

## API

| Método | Endpoint | Doc |
|---|---|---|
| POST | `/offers` `{itineraryId, seatNumbers, precioOferta, cupo, inicio, fin, maxPorUsuario?, permiteReprogramar?, salaEsperaHabilitada?}` | CreateOffer (OPERADOR de la empresa del viaje o ADMIN) |
| POST | `/offers/{id}/queue` | US-162: entra a la sala de espera (PASAJERO; desde 10 min antes del inicio; 201 al entrar, 200 si ya estaba) |
| GET | `/offers/{id}/queue/me` | US-162: estado y posición en la sala (autenticado) |
| GET · POST | `/operator/auto-offer-rules` | US-167: lista / crea reglas de ofertas automáticas (OPERADOR de la empresa o ADMIN) |
| GET · PUT · DELETE | `/operator/auto-offer-rules/{id}` | US-167: consulta / edita / borra una regla |
| GET · POST | `/operator/offer-templates` | US-169: lista / crea plantillas de ofertas recurrentes (OPERADOR de la empresa o ADMIN) |
| GET · PUT · DELETE | `/operator/offer-templates/{id}` | US-169: consulta / edita / borra una plantilla |
| GET | `/offers?itineraryId=` · `/offers/{id}` | GetOfferStatus (público) |
| GET | `/offers/{id}/quota` | GetRemainingQuota + segundos restantes (público) |
| POST | `/offers/{id}/reservations` `{docId}` | ReserveOfferSeat → retención del asiento (luego se compra con `POST /bookings`) |
| POST | `/offers/{id}/activate` · `/offers/{id}/close` | ActivateOffer / CloseOffer (OPERADOR o ADMIN) |
| GET | `/offers/showcase?origin=&destination=&page=&size=` | Vitrina pública en las próximas 48 h |
| GET | `/operator/offers?estado=&desde=&hasta=&page=&size=` | Gestión paginada por empresa (OPERADOR) o global (ADMIN) |
| PUT | `/offers/{id}` | Edita oferta PROGRAMADA antes del inicio; admite precio, cupo, límite, ventana, asientos, `permiteReprogramar` y `salaEsperaHabilitada` |
| DELETE | `/offers/{id}` | Cancela una oferta PROGRAMADA y libera sus asientos |
| POST | `/offers/{id}/duplicate` `{itineraryId,inicio,fin,seatNumbers?}` | Duplica descuento y cupos en otro viaje de la misma empresa |
| GET | `/internal/offers/{id}` | Precio base/oferta y regla de reprogramación (X-Internal-Token) |

Crear y editar validan descuento configurable entre `tripbus.offers.min-discount` (5 %) y
`tripbus.offers.max-discount` (70 %), inicio no pasado, ventana mínima de cinco minutos y ausencia
de ventanas cruzadas para un mismo viaje. La oferta conserva empresa, ruta y salida consultadas a
Catalog. Las ofertas antiguas se completan al arrancar; si Catalog no responde se reintenta al próximo
arranque. `ItineraryRescheduled` actualiza la salida y recorta el fin de la oferta cuando haga falta.
La vitrina normaliza tildes, ordena ACTIVA por fin y luego PROGRAMADA por inicio, y usa PostgreSQL
para calcular cupo si Redis no está disponible. `permiteReprogramar` es verdadero por defecto.

### Nunca vender más que el cupo

`offer:{id}:quota` en Redis: un script Lua hace `DECR`; si queda < 0 revierte con `INCR` y
rechaza (409 `OUT_OF_STOCK`), si no toma un asiento de la lista de la oferta. Idempotencia por
`(offerId, userId, docId)`. Si una retención de oferta expira, el cupo vuelve. Activación y cierre
automáticos por horario (un solo nodo).

**Publica:** `OfferCreated`, `OfferActivated`, `OfferClosed`, `OfferSoldOut`, `OfferUpdated`,
`OfferQuotaUpdated`, `OfferAlert`, `OfferQueueAdmitted` y `OfferQueueUpdated`. Los eventos de activación,
cierre, agotamiento y actualización incluyen `origen` y `destino` para la vitrina en tiempo real.
**Consume:** `SeatSold`, `SeatReleased`, `HoldExpired`, `ItineraryCancelled`, `ItineraryRescheduled`.
Cada registro del stream lleva además `traceparent` (contexto de traza W3C, ver [Observabilidad](#observabilidad)).

Prueba US-058: `FlashOfferQuotaIT` — 1.000 solicitudes concurrentes, cupo 20 → exactamente 20 ventas
y el contador nunca baja de 0. Es un **gate**: corre en cada CI.

### Sala de espera virtual (US-162)

- `salaEsperaHabilitada` (falso por defecto) se elige al crear y al editar; duplicar la conserva.
- Desde `tripbus.offers.queue.opens-before` (10 min) antes del inicio, `POST /offers/{id}/queue` (PASAJERO) entra a
  la sala, **una vez por usuario**. Antes de esa hora: 409 `QUEUE_NOT_OPEN` (con `abreEn`); sin sala: 409
  `QUEUE_NOT_ENABLED`; sala cerrada: 409 `QUEUE_CLOSED`.
- Todo vive en Redis (llaves `offer:{id}:queue*`, sorted set). Antes del inicio la posición no se muestra
  (`EN_SALA`): **al iniciar la oferta, el primer lote sortea al azar el orden de todos los que ya estaban** (Fisher-Yates
  atómico en Lua con semilla de `SecureRandom`); quien entra después va al final, por orden de llegada.
- Cada `tripbus.offers.queue.batch-interval` (10 s), en un solo nodo, se admite un lote de
  `tripbus.offers.queue.batch-size` (50). Cada admitido recibe un token de admisión de 5 min en Redis
  (`offer:{id}:admission:{userId}`).
- Con la sala habilitada, `POST /offers/{id}/reservations` exige un token vigente: sin él (o vencido) 403
  `QUEUE_TURN_REQUIRED`. La sala se cierra cuando la oferta se agota o termina; si una oferta agotada se reabre
  (vuelve un cupo), solo reservan quienes aún tienen token.
- `GET /offers/{id}/queue/me` → `{offerId, estado, posicion, enFila, admisionExpiraEn}` con `estado` en
  `NO_INSCRITO`, `EN_SALA`, `EN_FILA`, `ADMITIDO`, `TURNO_VENCIDO` o `CERRADA`.
- Eventos: `OfferQueueAdmitted {userId, offerId, expiraEn}` por cada admitido y, en cada lote (y al cerrar),
  `OfferQueueUpdated {offerId, enFila, admitidos}` (`admitidos` acumulado). Los difunde el Realtime Gateway.

### Antiabuso (US-168)

- Máximo `tripbus.offers.max-per-document-per-day` (2) reservas de oferta vivas (RESERVADA o VENDIDA) por
  **documento** al día (zona `tripbus.offers.zone`, America/Bogota), sumando todas las ofertas: 409
  `DOCUMENT_DAILY_LIMIT` (con `maxPorDocumentoAlDia`). Un lock asesor por documento evita que dos reservas
  simultáneas pasen el límite; el rechazo no gasta cupo.
- Máximo `tripbus.offers.reservation-attempts-per-minute` (10) intentos por usuario y minuto en
  `POST /offers/{id}/reservations`, con contador en Redis (`ratelimit:offer-reservations:{userId}:{minuto}`): 429
  `TOO_MANY_ATTEMPTS` con cabecera `Retry-After` (segundos hasta el siguiente minuto). Si Redis no responde no se
  bloquea la reserva.

### Ofertas automáticas por baja ocupación (US-167)

Tabla `auto_offer_rules` (`companyId`, `routeId` opcional, `horasAntes` 1-168, `ocupacionMaxima` %, `descuento` %,
`cupoMaximo`, `activa`). Cada `tripbus.jobs.auto-offers-ms` (15 min), en un solo nodo, para cada regla activa:

1. Catalog `GET /internal/itineraries?companyId=&routeId=&desde=&hasta=` (con `X-Internal-Token`) da los viajes que
   salen dentro de `horasAntes` → `[{itineraryId, companyId, routeId, precioBase, estado, fechaSalida}]`.
2. Seat Inventory `GET /internal/occupancy?itineraryId=` → `{itineraryId, capacidad, ocupados, asientosLibres}`.
3. Si la ocupación está por debajo de `ocupacionMaxima` y el viaje no tiene oferta (no cerrada) ni una automática
   previa, crea la oferta con el mismo flujo y las validaciones de crear: precio = base − descuento, asientos libres
   hasta `cupoMaximo`, de ahora + 1 min hasta 30 min antes de la salida, origen `AUTOMATICA`.

**Una sola oferta automática por viaje** (también con un índice único, aunque dos nodos coincidan). El descuento de la
regla debe estar en el rango permitido para ofertas (400 `INVALID_DISCOUNT`).

### Ofertas recurrentes (US-169)

Tabla `offer_templates` (`companyId`, `routeId`, `diasSemana` ISO 1 = lunes … 7 = domingo, `horaInicio` "HH:mm",
`duracionMinutos`, `descuento`, `cupo`, `maxPorUsuario`, `permiteReprogramar`, `salaEspera`, `horasAntesSalida`,
`activa`). Un job diario (`tripbus.jobs.offer-templates-cron`, 03:00 hora de la plataforma, un solo nodo) crea con
cada plantilla activa las ofertas de los **próximos 7 días** para los viajes de su ruta que salen en esos días: la
oferta empieza a la `horaInicio` del día que queda `horasAntesSalida` horas antes de la salida y dura
`duracionMinutos` (si ya pasó o termina después de la salida no se crea). Mismo flujo y validaciones de crear;
origen `RECURRENTE`. **Idempotente**: no duplica la oferta de una misma plantilla y viaje (índice único
`template_id, itinerary_id`).

Reglas y plantillas: el OPERADOR solo ve y gestiona las de su empresa (lo ajeno responde 404); el ADMIN ve todas y al
crear indica `companyId` (400 `COMPANY_REQUIRED` si falta).

## Flujo de ramas y entornos

```
rama de trabajo --PR--> develop (QA) --PR de release--> main (PROD)
```

- `main` sigue siendo la rama por defecto en GitHub, pero **los PR de trabajo van con base `develop`**.
- `develop` es QA y `main` es PROD: a `main` solo llega código por un PR de release desde `develop`.

| Evento | Pruebas + JaCoCo + Trivy | SonarQube Cloud | Imagen publicada en GHCR |
|---|---|---|---|
| PR con base `develop` | ✔ | ✔ (quality gate) | no |
| push a `develop` (merge de un PR) | ✔ | ✔ (quality gate) | `:qa`, `:develop`, `:sha-<corto>` |
| PR con base `main` (release) | ✔ | — | no |
| push a `main` (merge del release) | ✔ | — | `:prod`, `:latest`, `:sha-<corto>` |
| `workflow_dispatch` | ✔ | solo si se lanza sobre `develop` | no |

**Por qué Sonar solo corre en `develop`:** el plan Free de SonarQube Cloud analiza una única rama,
y la rama principal del proyecto en Sonar es `develop`. Lo que llega a `main` es exactamente el
código que ya pasó el quality gate en `develop`, así que en `main` el CI hace `mvn verify` sin Sonar
y lo indica con un aviso (`::notice::`). El control de cobertura de JaCoCo (`jacoco:check`) sí corre
en todas las ramas.

**Cómo hacer un release:**

1. Asegúrate de que el último CI de `develop` esté en verde (pruebas, cobertura, quality gate y
   Trivy) y de haber probado la imagen `:qa`.
2. Abre un PR con base `main` y rama de origen `develop` (título p. ej. `release: <resumen>`).
3. Cuando el CI del PR pase, haz merge (sin squash, para que `main` y `develop` compartan historial).
   El push a `main` publica la imagen `:prod` / `:latest`.

## Entornos (IDE / QA / PROD)

| Entorno | Cómo corre | Perfil Spring | Secretos |
|---|---|---|---|
| IDE | `mvn spring-boot:run` contra los contenedores de QA | (ninguno) | valores por defecto de QA en `application.yml` |
| QA | Docker Compose en tu computador | `qa` (+ `desplegado`) | `.env` (copia de `.env.example`) |
| PROD | Docker Compose en el servidor | `prod` (+ `desplegado`) | solo en el servidor |

Puertos de este repo (el contenedor escucha en el mismo puerto que publica):

| Servicio | QA (publicado) | Dentro de Docker | PROD |
|---|---|---|---|
| flash-offers-service | 18006 (antes 16006) | 18006 | no se publica (entra por api-gateway 18000) |
| PostgreSQL `tripbus_offers` / usuario `offers` (`offers-db`) | 18106 | 5432 | nunca se publica |
| Redis (compartido, con contraseña) | 18100 | 6379 | nunca se publica |

Otros servicios que usa: identity (JWKS, `IDENTITY_JWKS_URI`, por defecto `http://localhost:18001/.well-known/jwks.json`),
catalog (`CATALOG_URL`, por defecto `http://localhost:18002`, valida el itinerario y su precio base) y
seat-inventory (`SEAT_INVENTORY_URL`, por defecto `http://localhost:18003`, aparta y retiene los asientos).
En `qa`/`prod` no tienen valor por defecto y son obligatorias.

### Levantar QA

```bash
cp .env.example .env && docker compose up -d --build
docker compose ps                                   # esperar a "healthy"
curl http://localhost:18006/actuator/health
curl http://localhost:18006/offers
```

Con este compose solo funcionan las consultas públicas (`GET /offers`, `/offers/{id}`,
`/offers/{id}/quota`). Crear/activar/cerrar y reservar exigen un JWT de identity y llaman a catalog y
seat-inventory: pruébalos con el compose general de **API-gateway**. El servicio llega a identity
(18001), catalog (18002) y seat-inventory (18003) de tu PC por `host.docker.internal`.

No lo corras a la vez que el compose general de **API-gateway** (usan los mismos puertos).
`docker compose down` detiene conservando los datos; `down -v` los borra.

### Imágenes Docker (GHCR)

El CI publica `ghcr.io/trip-bus/flash-offerts-service` solo en push (nunca desde un PR):

| Etiqueta | Rama | Uso |
|---|---|---|
| `:qa` (y `:develop`) | `develop` | Última versión de QA. |
| `:prod` (y `:latest`) | `main` | Última versión de PROD; es la que se despliega en el servidor. |
| `:sha-<corto>` | ambas | Versión inmutable de un commit concreto (para fijar o hacer rollback). |

`docker compose up --build` en tu computador construye la imagen local `ghcr.io/trip-bus/flash-offerts-service:qa-local`;
para usar la de QA ya publicada: `docker pull ghcr.io/trip-bus/flash-offerts-service:qa`.

### Correr desde el IDE contra QA

Con el compose de QA arriba, `docker compose stop flash-offers-service` (libera el 18006) y luego
`mvn spring-boot:run` sin perfil: `application.yml` ya apunta a `localhost:18106` (BD) y
`localhost:18100` (Redis). Para Redis define `REDIS_PASSWORD=tripbus_redis_qa_pw` (el compose de QA
lo exige con contraseña).

### Variables

En `qa` y `prod` se activa el perfil `desplegado`: si falta alguna variable obligatoria el servicio
no arranca y dice cuáles faltan (`RequiredSecretsCheck`).

| Variable | Obligatoria en qa/prod | Por defecto (IDE) |
|---|---|---|
| `DB_PASSWORD` | ✔ | `offers_qa_pw` |
| `REDIS_PASSWORD` | ✔ | (vacía) |
| `INTERNAL_API_TOKEN` | ✔ | `tripbus_internal_qa_token` |
| `IDENTITY_JWKS_URI` | ✔ | `http://localhost:18001/.well-known/jwks.json` |
| `SEAT_INVENTORY_URL` | ✔ | `http://localhost:18003` |
| `CATALOG_URL` | ✔ | `http://localhost:18002` |

Opcionales con valor por defecto: `SERVER_PORT` (18006), `DB_HOST` (localhost), `DB_PORT` (18106),
`DB_NAME` (tripbus_offers), `DB_USERNAME` (offers), `DB_POOL_SIZE`, `REDIS_HOST` (localhost),
`REDIS_PORT` (18100). `DB_HOST` reemplaza a la antigua `DB_URL`.

Observabilidad y reglas (opcionales): `OTEL_EXPORTER_OTLP_ENDPOINT` (vacía = sin exportar trazas),
`TRACING_SAMPLING` (0.1), `LOG_FORMAT` (`json` = logs ECS; en `prod` es `json` por defecto), `OPENAPI_SERVER_URL`
(vacía), `OFFERS_MAX_PER_DOCUMENT_PER_DAY` (2), `OFFERS_RESERVATION_ATTEMPTS_PER_MINUTE` (10),
`OFFERS_QUEUE_BATCH_INTERVAL` (10s), `OFFERS_QUEUE_BATCH_SIZE` (50), `AUTO_OFFERS_MS` (900000) y
`OFFER_TEMPLATES_CRON` (`0 0 3 * * *`).

## Swagger

Solo en IDE y QA (en `prod` springdoc está apagado y responde 404):

- UI: http://localhost:18006/swagger-ui.html
- OpenAPI JSON: http://localhost:18006/v3/api-docs

Consultar ofertas es público. Para crear/activar/cerrar (OPERADOR o ADMIN) y reservar, haz login en
identity (`/auth/login`) y pega el `accessToken` en **Authorize**. Con `OPENAPI_SERVER_URL` (p. ej. la URL
pública del API Gateway) el documento declara ese servidor; vacía, usa la del propio servicio.

## Observabilidad

- `/actuator/health` (con `liveness` y `readiness`), `/actuator/prometheus`.
- `/actuator/info`: versión y fecha del build, y `app.commit` (el commit con el que se construyó la imagen).
- Cada petición lleva un `requestId`: se reutiliza el header `X-Request-Id` (lo manda el API Gateway)
  o se genera uno, y se devuelve en la respuesta. Flash Offers lo **propaga** en sus llamadas
  salientes a catalog y seat-inventory, así una petición se sigue entre servicios.
- **Trazas (HT-10):** Micrometer Tracing con OpenTelemetry (`micrometer-tracing-bridge-otel`,
  `opentelemetry-exporter-otlp`, versiones de Spring Boot). Se exportan por OTLP/HTTP a
  `OTEL_EXPORTER_OTLP_ENDPOINT` (p. ej. `http://otel-collector:4318/v1/traces`); vacía = no se exporta, pero las
  trazas igual se generan y se propagan. Muestreo `TRACING_SAMPLING` (0.1). El contexto (`traceparent` W3C) viaja en
  las llamadas a catalog y seat-inventory (`RestClient.Builder` de Spring Boot, con observación) y en cada registro de
  Redis Streams: al consumir un evento el manejador corre en un span hijo de la traza que lo publicó.
- **Logs:** cada línea lleva `[traceId,spanId,requestId]`. Con `LOG_FORMAT=json`
  (`logging.structured.format.console=ecs`) salen en JSON ECS con `traceId`, `spanId` y `requestId` (MDC); en `prod`
  es el formato por defecto.
- **Métricas** (Prometheus): `tripbus_reservas_oferta_total{resultado=ok|agotada|limite_usuario|sin_turno}`
  (`limite_usuario` incluye el límite por persona en la oferta y el diario por documento; `sin_turno`, las reservas
  sin token de la sala de espera), `tripbus_ofertas_activadas_total{origen=MANUAL|AUTOMATICA|RECURRENTE}` y el gauge
  `tripbus_ofertas_cupo_restante{offerId}` con el cupo restante de cada oferta activa (se refresca cada 15 s).

## Pruebas

```bash
mvn -B verify      # unitarias + integración con PostgreSQL y Redis reales (Testcontainers: Docker abierto)
```

- `*Test` / `*Tests`: unitarias (`RequiredSecretsCheck`, `RequestIdFilter`, `RequestIdPropagationInterceptor`).
- `FlashOfferQuotaIT`: levanta PostgreSQL 16 y Redis 7 con Testcontainers, corre las migraciones
  Flyway reales y el script Lua real del cupo (catalog y seat-inventory se simulan). Verifica US-058
  (1.000 reservas concurrentes con cupo 20 → exactamente 20 ventas, 980 rechazos y el contador nunca
  < 0), la idempotencia por documento, la devolución del cupo y las reglas de creación.
- Sprints 5 y 6 (comparten un PostgreSQL y un Redis de Testcontainers, `ContainersIT`): `ObservabilityIT`
  (`traceparent` en el RestClient y en el stream, métricas en `/actuator/prometheus`), `WaitingRoomIT` (entrar antes de
  tiempo, una vez por usuario, sorteo y orden, lotes y eventos, reserva sin token, token vencido, cierre al agotarse y
  al terminar), `AntiAbuseIT` (límite por documento y por intentos), `AutoOfferIT` (regla que crea y que no duplica) y
  `OfferTemplateIT` (plantilla idempotente). Catalog y seat-inventory se simulan.

Toda la plataforma: ver el README de **API-gateway**.

## Calidad

| Herramienta | Qué revisa | Dónde |
|---|---|---|
| JaCoCo | Cobertura de las pruebas y **mínimo obligatorio** (`jacoco:check`) | local: `target/site/jacoco/index.html` (tras `mvn verify`); CI: tabla en el resumen de cada ejecución y artifact `jacoco` |
| SonarQube Cloud | Bugs, code smells y cobertura; quality gate "Sonar way" (código nuevo ≥ 80 %) | proyecto `Trip-Bus_flash-offerts-service` de la organización `trip-bus` |
| Gitleaks | Secretos en todo el historial git | job `secretos` del CI |
| Trivy | Vulnerabilidades CRITICAL/HIGH con corrección disponible en la imagen Docker | job `docker` del CI |

### Cobertura mínima (JaCoCo)

`mvn verify` falla si la cobertura total baja de los mínimos del `pom.xml`, en **todas** las ramas
(también en `main`, donde Sonar no corre):

| Métrica | Medido (2026-11-05, sprints 5 y 6) | Mínimo |
|---|---|---|
| Líneas | 89,5 % | `jacoco.minimo.lineas` = 0.85 |
| Ramas | 84,0 % | `jacoco.minimo.ramas` = 0.80 |
| Líneas por clase nueva (sprints 5 y 6) | ≥ 80 % en todas | `jacoco.minimo.clase.nueva` = 0.80 |

**La cobertura nunca puede bajar: si agregas pruebas, sube el mínimo.** Corre `mvn verify`, mira
el total en `target/site/jacoco/index.html` (o la tabla del CI), redondéalo hacia abajo al 5 % y
actualiza la propiedad en el pom en el mismo PR. La segunda regla de `jacoco:check` (CLASS) exige 80 % de
líneas a cada clase nueva de los sprints 5 y 6; agrega ahí las clases nuevas de los próximos sprints. Las que aún
tienen menos cobertura son `GlobalExceptionHandler` y `EventConsumer` (arranque del consumidor).

Fuera de la cobertura (mismas en `sonar.coverage.exclusions` y en los `<excludes>` de JaCoCo) solo
está lo que no tiene lógica: `FlashOffersApplication`, los records de `web/dto` (`CreateOfferRequest`,
`OfferResponse`, `QuotaResponse`, `ReservationResponse`, `ReserveRequest`, `UpdateOfferRequest`,
`DuplicateOfferRequest`), `EventEnvelope`, la
entidad de solo getters `ProcessedEvent` y `config/OpenApiConfig`. `Offer`, `OfferSale`,
`ErrorResponse` y `EventProperties` tienen lógica y **sí** cuentan.

### Vulnerabilidades aceptadas (`.trivyignore.yaml`)

Lista **temporal** de CVE que Trivy no debe tratar como error. Solo entran las que **no tienen
arreglo en Spring Boot 3.x** (hoy CVE-2026-47884 y CVE-2026-47890 de `spring-webmvc`, que solo se
corrigen en Spring Framework 7; este servicio no usa `XsltView` ni expone SSE); cada una explica en
`statement` por qué se acepta. Todas **vencen el 2027-01-31** (`expired_at`): desde esa fecha Trivy
vuelve a fallar si no se migró a Spring Boot 4.1. Lo que sí tiene parche nunca se agrega aquí: se
corrige subiendo la versión (ver las propiedades `*.version` de parches de seguridad en `pom.xml`).

El análisis de Sonar solo corre en `develop` (push y PR con base `develop`, ver
[Flujo de ramas y entornos](#flujo-de-ramas-y-entornos)) y si el repo tiene el secreto `SONAR_TOKEN`;
en otro caso el CI hace `mvn verify` y deja un aviso.

### Secretos del CI (`SONAR_TOKEN`, `DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN`)

| Secreto | Valor |
|---|---|
| `SONAR_TOKEN` | Token de análisis de SonarQube Cloud (organización `trip-bus`) |
| `DOCKERHUB_USERNAME` | Usuario de Docker Hub |
| `DOCKERHUB_TOKEN` | Personal access token con permiso **Public Repo Read-only** (solo lectura; nunca la contraseña) |

Los runners de GitHub comparten IP y agotan el límite de descargas anónimas de Docker Hub
(`toomanyrequests: You have reached your unauthenticated pull rate limit`), lo que rompe
Testcontainers (`postgres:16-alpine`, Redis) y el build de la imagen (`maven`, `eclipse-temurin`).
Con los secretos de Docker Hub los jobs `secretos`, `build-test` y `docker` inician sesión antes de
descargar nada y usan el límite de una cuenta autenticada. Gitleaks se descarga desde GHCR
(`ghcr.io/gitleaks/gitleaks`), que no tiene ese límite. Son **opcionales pero recomendados**: si
faltan, el CI descarga sin autenticar, deja un aviso y puede fallar por el límite. Se crean en
Settings → Secrets and variables → Actions.

Escaneos locales con Docker:

```bash
docker run --rm -v "$PWD:/repo" ghcr.io/gitleaks/gitleaks:v8.30.1 git /repo --redact
docker build -t flash-offerts-service:local . && \
  docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "$PWD:/src" aquasec/trivy:0.70.0 \
  image --severity CRITICAL,HIGH --ignore-unfixed --ignorefile /src/.trivyignore.yaml flash-offerts-service:local
```
