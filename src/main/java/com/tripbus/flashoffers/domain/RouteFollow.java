package com.tripbus.flashoffers.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "route_follows", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "origen_norm", "destino_norm"}))
public class RouteFollow {
 @Id private UUID id; @Column(name="user_id",nullable=false) private UUID userId;
 @Column(nullable=false) private String origen; @Column(nullable=false) private String destino;
 @Column(name="origen_norm",nullable=false) private String origenNorm; @Column(name="destino_norm",nullable=false) private String destinoNorm;
 @Column(name="creado_en",nullable=false) private Instant creadoEn;
 protected RouteFollow() {}
 public RouteFollow(UUID userId,String origen,String destino,String origenNorm,String destinoNorm,Instant now){id=UUID.randomUUID();this.userId=userId;this.origen=origen;this.destino=destino;this.origenNorm=origenNorm;this.destinoNorm=destinoNorm;creadoEn=now;}
 public UUID getId(){return id;} public UUID getUserId(){return userId;} public String getOrigen(){return origen;} public String getDestino(){return destino;} public Instant getCreadoEn(){return creadoEn;}
}
