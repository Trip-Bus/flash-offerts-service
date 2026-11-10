package com.tripbus.flashoffers.web.dto;
import java.time.Instant; import java.util.UUID;
public record RouteFollowResponse(UUID id,String origen,String destino,Instant creadoEn) {}
