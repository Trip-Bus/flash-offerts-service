package com.tripbus.flashoffers.web.dto;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public record DuplicateOfferRequest(@NotNull UUID itineraryId, @NotNull Instant inicio, @NotNull Instant fin,
                                    List<String> seatNumbers) { }
