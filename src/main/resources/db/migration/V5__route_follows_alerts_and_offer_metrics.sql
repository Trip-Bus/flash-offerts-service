ALTER TABLE offers ADD COLUMN agotada_en TIMESTAMP WITH TIME ZONE;

CREATE TABLE route_follows (
    id UUID PRIMARY KEY, user_id UUID NOT NULL, origen VARCHAR(120) NOT NULL, destino VARCHAR(120) NOT NULL,
    origen_norm VARCHAR(120) NOT NULL, destino_norm VARCHAR(120) NOT NULL,
    creado_en TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_route_follows_user_route UNIQUE (user_id, origen_norm, destino_norm)
);
CREATE INDEX idx_route_follows_route ON route_follows (origen_norm, destino_norm);

CREATE TABLE offer_alerts_sent (
    id UUID PRIMARY KEY, offer_id UUID NOT NULL REFERENCES offers(id), user_id UUID NOT NULL,
    sent_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_offer_alerts_sent_offer_user UNIQUE (offer_id, user_id)
);
CREATE INDEX idx_offer_alerts_sent_offer ON offer_alerts_sent (offer_id);
