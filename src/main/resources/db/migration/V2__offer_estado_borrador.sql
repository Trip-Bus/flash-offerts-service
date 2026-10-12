-- US-054: la oferta se guarda como BORRADOR hasta que Seat Inventory reserva sus asientos.

ALTER TABLE offers DROP CONSTRAINT ck_offers_estado;
ALTER TABLE offers ADD CONSTRAINT ck_offers_estado
    CHECK (estado IN ('BORRADOR', 'PROGRAMADA', 'ACTIVA', 'AGOTADA', 'CERRADA'));

-- Limpieza periódica de borradores abandonados (OfferScheduler).
CREATE INDEX idx_offers_borrador ON offers (creado_en) WHERE estado = 'BORRADOR';
