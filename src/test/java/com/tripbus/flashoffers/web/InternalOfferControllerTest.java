package com.tripbus.flashoffers.web;
import com.tripbus.flashoffers.service.OfferService;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;
class InternalOfferControllerTest {
 @Test void exposesOfferPricingAndRebookingRule(){OfferService service=mock(OfferService.class);UUID id=UUID.randomUUID();var expected=Map.<String,Object>of("offerId",id);when(service.internalDetails(id)).thenReturn(expected);assertThat(new InternalOfferController(service).details(id)).isSameAs(expected);}
}
