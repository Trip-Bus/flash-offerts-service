-- US-058: máximo de cupos que una misma persona (userId) puede tomar en una oferta, sumando todos sus documentos.

ALTER TABLE offers ADD COLUMN max_por_usuario INTEGER NOT NULL DEFAULT 2;

-- Ofertas existentes con cupo 1: el máximo no puede superar el cupo.
UPDATE offers SET max_por_usuario = LEAST(max_por_usuario, cupo_max);

ALTER TABLE offers ADD CONSTRAINT ck_offers_max_por_usuario
    CHECK (max_por_usuario > 0 AND max_por_usuario <= cupo_max);

-- Conteo de ventas vivas por persona al reservar.
CREATE INDEX idx_offer_sales_usuario ON offer_sales (offer_id, user_id) WHERE estado IN ('RESERVADA', 'VENDIDA');
