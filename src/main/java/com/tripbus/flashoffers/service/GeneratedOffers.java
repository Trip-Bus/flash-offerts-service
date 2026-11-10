package com.tripbus.flashoffers.service;

import com.tripbus.flashoffers.client.CatalogClient;
import com.tripbus.flashoffers.client.SeatInventoryClient;
import com.tripbus.flashoffers.common.error.ApiException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/** Cálculos comunes de las ofertas que genera el sistema (reglas automáticas y plantillas). */
final class GeneratedOffers {

    private GeneratedOffers() {
    }

    /** Precio de oferta: el base menos el descuento (%), a dos decimales. */
    static BigDecimal price(BigDecimal precioBase, BigDecimal descuento) {
        return precioBase.multiply(BigDecimal.valueOf(100).subtract(descuento))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    /** Los primeros {@code max} asientos libres del viaje. */
    static List<String> seats(SeatInventoryClient.Occupancy occupancy, int max) {
        List<String> libres = occupancy != null ? occupancy.libres() : List.of();
        return libres.subList(0, Math.min(max, libres.size()));
    }

    /** Un viaje que aún se puede ofertar: programado, con precio y salida futura. */
    static boolean available(CatalogClient.Trip trip, Instant now) {
        return trip != null && "PROGRAMADO".equals(trip.estado()) && trip.precioBase() != null
                && trip.fechaSalida() != null && trip.fechaSalida().isAfter(now);
    }

    /** Valida que el descuento de una regla o plantilla esté en el rango permitido para ofertas. */
    static void validateDiscount(BigDecimal descuento, BigDecimal min, BigDecimal max) {
        if (descuento.compareTo(min) < 0 || descuento.compareTo(max) > 0) {
            throw ApiException.badRequest("INVALID_DISCOUNT",
                    "El descuento debe estar entre " + min + "% y " + max + "%");
        }
    }
}
