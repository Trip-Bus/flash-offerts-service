ALTER TABLE offers ADD COLUMN company_id UUID;
ALTER TABLE offers ADD COLUMN origen VARCHAR(120);
ALTER TABLE offers ADD COLUMN destino VARCHAR(120);
ALTER TABLE offers ADD COLUMN fecha_salida TIMESTAMP WITH TIME ZONE;
ALTER TABLE offers ADD COLUMN precio_base NUMERIC(12, 2);
ALTER TABLE offers ADD COLUMN empresa_nombre VARCHAR(160);
ALTER TABLE offers ADD COLUMN permite_reprogramar BOOLEAN NOT NULL DEFAULT TRUE;
CREATE INDEX idx_offers_company ON offers (company_id, estado, inicio);
CREATE INDEX idx_offers_showcase ON offers (estado, inicio, fin);
