package com.tripbus.flashoffers.web.dto;
import jakarta.validation.constraints.NotBlank;
public record RouteFollowRequest(@NotBlank String origen,@NotBlank String destino) {}
