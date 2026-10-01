# Flash Offers Service

Ofertas relámpago: creación con cupo y horario, contador atómico de cupo, venta sin sobreventa y cupo en vivo.

**HU:** US-054, US-056, US-058 · **Puerto:** 16006 · **BD:** `tripbus_offers`

## API

| Método | Endpoint | Doc |
|---|---|---|
| POST | `/offers` `{itineraryId, seatNumbers, precioOferta, cupo, inicio, fin}` | CreateOffer (OPERADOR de la empresa del viaje o ADMIN) |
| GET | `/offers?itineraryId=` · `/offers/{id}` | GetOfferStatus |
| GET | `/offers/{id}/quota` | GetRemainingQuota + segundos restantes |
| POST | `/offers/{id}/reservations` `{docId}` | ReserveOfferSeat → retención del asiento (luego se compra con `POST /bookings`) |
| POST | `/offers/{id}/activate` · `/offers/{id}/close` | ActivateOffer / CloseOffer |

### Nunca vender más que el cupo

`offer:{id}:quota` en Redis: un script Lua hace `DECR`; si queda < 0 revierte con `INCR` y
rechaza (409 `OUT_OF_STOCK`), si no toma un asiento de la lista de la oferta. Idempotencia por
`(offerId, userId, docId)`. Si una retención de oferta expira, el cupo vuelve. Activación y cierre
automáticos por horario (un solo nodo).

**Publica:** `OfferCreated`, `OfferActivated`, `OfferClosed`, `OfferSoldOut`, `OfferQuotaUpdated`.
**Consume:** `SeatSold`, `SeatReleased`, `HoldExpired`, `ItineraryCancelled`.

Prueba US-058: `FlashOfferQuotaIT` — 1.000 solicitudes concurrentes, cupo 20 → exactamente 20 ventas.

## Ejecutar

Toda la plataforma: `cd ../API-gateway && docker compose up -d --build` (ver README de API-gateway).

Solo este servicio (con PostgreSQL y Redis del compose levantados):

```bash
mvn spring-boot:run          # puerto 16006
```

## Pruebas

```bash
mvn test                     # unitarias
TRIPBUS_IT=true mvn test     # + integración contra PostgreSQL/Redis reales (base tripbus_offers_test)
```

El pipeline `.github/workflows/ci-cd.yml` corre todas las pruebas y publica la imagen en GHCR.
