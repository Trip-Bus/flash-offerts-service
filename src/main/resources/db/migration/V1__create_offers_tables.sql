-- Ofertas relámpago (esquema propio del servicio).

CREATE TABLE offers (
    id              UUID PRIMARY KEY,
    itinerary_id    UUID NOT NULL,
    precio_oferta   NUMERIC(12, 2) NOT NULL,
    cupo_max        INTEGER NOT NULL CHECK (cupo_max > 0),
    inicio          TIMESTAMP WITH TIME ZONE NOT NULL,
    fin             TIMESTAMP WITH TIME ZONE NOT NULL,
    estado          VARCHAR(20) NOT NULL,
    motivo_cierre   VARCHAR(60),
    creado_por      UUID,
    creado_en       TIMESTAMP WITH TIME ZONE NOT NULL,
    version         BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_offers_ventana CHECK (fin > inicio),
    CONSTRAINT ck_offers_estado CHECK (estado IN ('PROGRAMADA', 'ACTIVA', 'AGOTADA', 'CERRADA'))
);

CREATE INDEX idx_offers_estado ON offers (estado, inicio, fin);
CREATE INDEX idx_offers_itinerary ON offers (itinerary_id);

CREATE TABLE offer_seats (
    offer_id     UUID NOT NULL REFERENCES offers (id),
    seat_number  VARCHAR(10) NOT NULL,
    PRIMARY KEY (offer_id, seat_number)
);

CREATE TABLE offer_sales (
    id              UUID PRIMARY KEY,
    offer_id        UUID NOT NULL REFERENCES offers (id),
    user_id         UUID NOT NULL,
    doc_id          VARCHAR(30) NOT NULL,
    seat_number     VARCHAR(10) NOT NULL,
    hold_id         UUID,
    estado          VARCHAR(20) NOT NULL,
    creado_en       TIMESTAMP WITH TIME ZONE NOT NULL,
    actualizado_en  TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Idempotencia por (offerId, userId, docId): nunca doble reserva para la misma persona.
    CONSTRAINT uq_offer_sales_persona UNIQUE (offer_id, user_id, doc_id),
    CONSTRAINT ck_offer_sales_estado CHECK (estado IN ('RESERVADA', 'VENDIDA', 'LIBERADA'))
);

-- Un asiento de la oferta solo puede estar en una venta viva.
CREATE UNIQUE INDEX uq_offer_sales_asiento_vivo ON offer_sales (offer_id, seat_number)
    WHERE estado IN ('RESERVADA', 'VENDIDA');
CREATE INDEX idx_offer_sales_hold ON offer_sales (hold_id);

CREATE TABLE processed_events (
    event_id      VARCHAR(64) PRIMARY KEY,
    event_type    VARCHAR(80) NOT NULL,
    processed_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
