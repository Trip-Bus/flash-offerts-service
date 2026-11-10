package com.tripbus.flashoffers.repository;
import com.tripbus.flashoffers.domain.RouteFollow;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface RouteFollowRepository extends JpaRepository<RouteFollow,UUID>{
 Optional<RouteFollow> findByUserIdAndOrigenNormAndDestinoNorm(UUID userId,String origenNorm,String destinoNorm);
 List<RouteFollow> findByOrigenNormAndDestinoNorm(String origenNorm,String destinoNorm);
 List<RouteFollow> findByUserIdOrderByCreadoEnDesc(UUID userId);
 long countByUserId(UUID userId);
}
