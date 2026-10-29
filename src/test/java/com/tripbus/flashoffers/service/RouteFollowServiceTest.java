package com.tripbus.flashoffers.service;

import com.tripbus.flashoffers.common.error.ApiException;
import com.tripbus.flashoffers.domain.RouteFollow;
import com.tripbus.flashoffers.repository.RouteFollowRepository;
import com.tripbus.flashoffers.web.dto.RouteFollowRequest;
import org.junit.jupiter.api.Test;
import java.time.*; import java.util.*;
import static org.assertj.core.api.Assertions.*; import static org.mockito.Mockito.*;

class RouteFollowServiceTest {
 private final RouteFollowRepository repo=mock(RouteFollowRepository.class);
 private final UUID user=UUID.randomUUID(); private final Clock clock=Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
 private final RouteFollowService service=new RouteFollowService(repo,clock);
 @Test void normalizesAndReturnsExistingFollow(){RouteFollow f=new RouteFollow(user,"Bogotá","Cali","bogota","cali",clock.instant());when(repo.findByUserIdAndOrigenNormAndDestinoNorm(user,"bogota","cali")).thenReturn(Optional.of(f));var response=service.follow(user,new RouteFollowRequest(" BOGOTA ","CALI"));assertThat(response.id()).isEqualTo(f.getId());verify(repo,never()).saveAndFlush(any());}
 @Test void createsAndHonorsLimit(){when(repo.findByUserIdAndOrigenNormAndDestinoNorm(any(),any(),any())).thenReturn(Optional.empty());when(repo.countByUserId(user)).thenReturn(9L);when(repo.saveAndFlush(any())).thenAnswer(i->i.getArgument(0));assertThat(service.follow(user,new RouteFollowRequest("Bogotá","Cali")).origen()).isEqualTo("Bogotá");when(repo.countByUserId(user)).thenReturn(10L);assertThatThrownBy(()->service.follow(user,new RouteFollowRequest("Medellín","Pasto"))).isInstanceOf(ApiException.class).extracting(e->((ApiException)e).getCode()).isEqualTo("FOLLOW_LIMIT");}
 @Test void onlyDeletesOwnFollow(){UUID id=UUID.randomUUID();when(repo.findById(id)).thenReturn(Optional.of(new RouteFollow(UUID.randomUUID(),"A","B","a","b",clock.instant())));assertThatThrownBy(()->service.delete(user,id)).isInstanceOf(ApiException.class).extracting(e->((ApiException)e).getCode()).isEqualTo("FOLLOW_NOT_FOUND");}
}
