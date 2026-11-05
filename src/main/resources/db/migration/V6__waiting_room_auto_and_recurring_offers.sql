-- Sprint 6: sala de espera (US-162), ofertas automáticas (US-167), antiabuso (US-168) y recurrentes (US-169).

-- US-169: plantillas de ofertas recurrentes.
CREATE TABLE offer_templates (
    id                  UUID PRIMARY KEY,
    company_id          UUID NOT NULL,
    route_id            UUID NOT NULL,
    -- Días ISO de salida del viaje, separados por coma (1 = lunes ... 7 = domingo).
    dias_semana         VARCHAR(20) NOT NULL,
    hora_inicio         TIME NOT NULL,
    duracion_minutos    INTEGER NOT NULL CHECK (duracion_minutos >= 5),
    descuento           NUMERIC(5, 2) NOT NULL CHECK (descuento > 0 AND descuento < 100),
    cupo                INTEGER NOT NULL CHECK (cupo > 0),
    max_por_usuario     INTEGER NOT NULL,
    permite_reprogramar BOOLEAN NOT NULL DEFAULT TRUE,
    sala_espera         BOOLEAN NOT NULL DEFAULT FALSE,
    horas_antes_salida  INTEGER NOT NULL CHECK (horas_antes_salida >= 0),
    activa              BOOLEAN NOT NULL DEFAULT TRUE,
    creado_en           TIMESTAMP WITH TIME ZONE NOT NULL,
    actualizado_en      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_offer_templates_max_por_usuario CHECK (max_por_usuario > 0 AND max_por_usuario <= cupo)
);
CREATE INDEX idx_offer_templates_company ON offer_templates (company_id);

-- US-167: reglas de ofertas automáticas por baja ocupación (route_id nulo = todas las rutas de la empresa).
CREATE TABLE auto_offer_rules (
    id               UUID PRIMARY KEY,
    company_id       UUID NOT NULL,
    route_id         UUID,
    horas_antes      INTEGER NOT NULL CHECK (horas_antes BETWEEN 1 AND 168),
    ocupacion_maxima NUMERIC(5, 2) NOT NULL CHECK (ocupacion_maxima > 0 AND ocupacion_maxima <= 100),
    descuento        NUMERIC(5, 2) NOT NULL CHECK (descuento > 0 AND descuento < 100),
    cupo_maximo      INTEGER NOT NULL CHECK (cupo_maximo > 0),
    activa           BOOLEAN NOT NULL DEFAULT TRUE,
    creado_en        TIMESTAMP WITH TIME ZONE NOT NULL,
    actualizado_en   TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_auto_offer_rules_company ON auto_offer_rules (company_id);

-- US-162: sala de espera por oferta (apagada por defecto).
ALTER TABLE offers ADD COLUMN sala_espera_habilitada BOOLEAN NOT NULL DEFAULT FALSE;

-- US-167/US-169: cómo se creó la oferta (etiqueta "origen" de las métricas) y la plantilla que la generó.
ALTER TABLE offers ADD COLUMN origen_creacion VARCHAR(20) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE offers ADD CONSTRAINT ck_offers_origen_creacion
    CHECK (origen_creacion IN ('MANUAL', 'AUTOMATICA', 'RECURRENTE'));
ALTER TABLE offers ADD COLUMN template_id UUID REFERENCES offer_templates (id) ON DELETE SET NULL;

-- Una sola oferta automática por viaje, y una sola oferta por plantilla y viaje (idempotencia del job diario),
-- aunque dos nodos o dos pasadas lleguen a la vez.
CREATE UNIQUE INDEX uq_offers_automatica_por_viaje ON offers (itinerary_id) WHERE origen_creacion = 'AUTOMATICA';
CREATE UNIQUE INDEX uq_offers_plantilla_viaje ON offers (template_id, itinerary_id) WHERE template_id IS NOT NULL;
CREATE INDEX idx_offers_sala_espera ON offers (estado) WHERE sala_espera_habilitada;

-- US-168: reservas vivas de un documento en el día (en todas las ofertas).
CREATE INDEX idx_offer_sales_documento ON offer_sales (doc_id, creado_en) WHERE estado IN ('RESERVADA', 'VENDIDA');
