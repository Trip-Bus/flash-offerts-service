package com.tripbus.flashoffers.domain;

public enum OfferStatus {
    /** Recién creada, aún sin asientos reservados en Seat Inventory: no se lista, no se activa ni se reserva. */
    BORRADOR,
    PROGRAMADA,
    ACTIVA,
    AGOTADA,
    CERRADA
}
