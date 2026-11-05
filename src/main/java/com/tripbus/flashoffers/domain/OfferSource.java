package com.tripbus.flashoffers.domain;

/** Cómo se creó la oferta (etiqueta {@code origen} de las métricas). */
public enum OfferSource {
    /** La creó un operador o un administrador (POST /offers o duplicar). */
    MANUAL,
    /** US-167: la creó una regla de baja ocupación. */
    AUTOMATICA,
    /** US-169: la creó una plantilla recurrente. */
    RECURRENTE
}
