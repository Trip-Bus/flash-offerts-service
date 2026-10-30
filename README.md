# Flash Offers Service

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=Trip-Bus_flash-offerts-service&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=Trip-Bus_flash-offerts-service)

Ofertas relámpago: creación con cupo y horario, contador atómico de cupo, venta sin sobreventa y cupo en vivo.

**Stack:** Java 21 · Spring Boot 3.5.16 · PostgreSQL 16 · Redis 7

**HU:** US-054, US-056, US-058 · **Puerto:** 18006 · **BD:** `tripbus_offers` (puerto QA 18106)

> **Nombre "offerts" / "offers":** el repositorio GitHub, el proyecto de Sonar
> (`Trip-Bus_flash-offerts-service`) y la imagen (`ghcr.io/trip-bus/flash-offerts-service`) se
> escriben con **"offerts"**; la aplicación (`spring.application.name`, `artifactId`) y el servicio
> del compose (`flash-offers-service`, así lo llaman el gateway y los demás servicios) con **"offers"**.
> Es intencional: no se renombra nada para no romper el CI ni el gateway.

## API

| Método | Endpoint | Doc |
|---|---|---|
| POST | `/offers` `{itineraryId, seatNumbers, precioOferta, cupo, inicio, fin}` | CreateOffer (OPERADOR de la empresa del viaje o ADMIN) |
| GET | `/offers?itineraryId=` · `/offers/{id}` | GetOfferStatus (público) |
| GET | `/offers/{id}/quota` | GetRemainingQuota + segundos restantes (público) |
| POST | `/offers/{id}/reservations` `{docId}` | ReserveOfferSeat → retención del asiento (luego se compra con `POST /bookings`) |
| POST | `/offers/{id}/activate` · `/offers/{id}/close` | ActivateOffer / CloseOffer (OPERADOR o ADMIN) |
| GET | `/offers/showcase?origin=&destination=&page=&size=` | Vitrina pública en las próximas 48 h |
| GET | `/operator/offers?estado=&desde=&hasta=&page=&size=` | Gestión paginada por empresa (OPERADOR) o global (ADMIN) |
| PUT | `/offers/{id}` | Edita oferta PROGRAMADA antes del inicio; admite precio, cupo, límite, ventana, asientos y `permiteReprogramar` |
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
`OfferQuotaUpdated`. Los eventos de activación, cierre, agotamiento y actualización incluyen `origen`
y `destino` para la vitrina en tiempo real.
**Consume:** `SeatSold`, `SeatReleased`, `HoldExpired`, `ItineraryCancelled`, `ItineraryRescheduled`.

Prueba US-058: `FlashOfferQuotaIT` — 1.000 solicitudes concurrentes, cupo 20 → exactamente 20 ventas
y el contador nunca baja de 0. Es un **gate**: corre en cada CI.

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

## Swagger

Solo en IDE y QA (en `prod` springdoc está apagado y responde 404):

- UI: http://localhost:18006/swagger-ui.html
- OpenAPI JSON: http://localhost:18006/v3/api-docs

Consultar ofertas es público. Para crear/activar/cerrar (OPERADOR o ADMIN) y reservar, haz login en
identity (`/auth/login`) y pega el `accessToken` en **Authorize**.

## Observabilidad

- `/actuator/health` (con `liveness` y `readiness`), `/actuator/prometheus`.
- `/actuator/info`: versión y fecha del build, y `app.commit` (el commit con el que se construyó la imagen).
- Cada petición lleva un `requestId`: se reutiliza el header `X-Request-Id` (lo manda el API Gateway)
  o se genera uno, y se devuelve en la respuesta. Flash Offers lo **propaga** en sus llamadas
  salientes a catalog y seat-inventory, así una petición se sigue entre servicios. Logs legibles con
  `[requestId]` en IDE/QA y JSON (con `requestId` y `"service":"flash-offers-service"`) en PROD.

## Pruebas

```bash
mvn -B verify      # unitarias + integración con PostgreSQL y Redis reales (Testcontainers: Docker abierto)
```

- `*Test` / `*Tests`: unitarias (`RequiredSecretsCheck`, `RequestIdFilter`, `RequestIdPropagationInterceptor`).
- `FlashOfferQuotaIT`: levanta PostgreSQL 16 y Redis 7 con Testcontainers, corre las migraciones
  Flyway reales y el script Lua real del cupo (catalog y seat-inventory se simulan). Verifica US-058
  (1.000 reservas concurrentes con cupo 20 → exactamente 20 ventas, 980 rechazos y el contador nunca
  < 0), la idempotencia por documento, la devolución del cupo y las reglas de creación.

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

| Métrica | Medido (2026-10-09) | Mínimo |
|---|---|---|
| Líneas | 54-57 % (CI 54 %, local 55,4-56,8 %: varía según lo que alcance a ejecutar `OfferScheduler` en segundo plano) | `jacoco.minimo.lineas` = 0.50 |
| Ramas | 43,5 % | `jacoco.minimo.ramas` = 0.40 |

**La cobertura nunca puede bajar: si agregas pruebas, sube el mínimo.** Corre `mvn verify`, mira
el total en `target/site/jacoco/index.html` (o la tabla del CI), redondéalo hacia abajo al 5 % y
actualiza la propiedad en el pom en el mismo PR. **Ambos mínimos están por debajo del 60 % de
referencia**: hoy solo hay pruebas de configuración y la IT del cupo. Las mejores candidatas para
subirlos son `OfferController`, `SeatInventoryClient`, `CatalogClient`, `EventConsumer`,
`OfferEventsHandler`, `CurrentUser` y `GlobalExceptionHandler`.

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
