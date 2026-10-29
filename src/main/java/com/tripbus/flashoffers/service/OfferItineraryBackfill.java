package com.tripbus.flashoffers.service;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class OfferItineraryBackfill implements ApplicationRunner {
 private final OfferService service;
 public OfferItineraryBackfill(OfferService service){this.service=service;}
 @Override public void run(ApplicationArguments args){service.backfillItineraryDetails();}
}
