package com.tripbus.flashoffers.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OfferItineraryBackfillTest {
    @Test
    void delegatesTheStartupBackfill() throws Exception {
        OfferService service=mock(OfferService.class);
        new OfferItineraryBackfill(service).run(mock(ApplicationArguments.class));
        verify(service).backfillItineraryDetails();
    }
}
