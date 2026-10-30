package com.tripbus.flashoffers.service;
import com.tripbus.flashoffers.common.error.ApiException;
import com.tripbus.flashoffers.domain.RouteFollow;
import com.tripbus.flashoffers.repository.RouteFollowRepository;
import com.tripbus.flashoffers.web.dto.*;
import org.springframework.dao.DataIntegrityViolationException; import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional;
import java.text.Normalizer; import java.time.Clock; import java.util.*;
@Service public class RouteFollowService {
 private final RouteFollowRepository repo; private final Clock clock; public RouteFollowService(RouteFollowRepository repo,Clock clock){this.repo=repo;this.clock=clock;}
 public static String normalize(String value){return Normalizer.normalize(value.trim(),Normalizer.Form.NFD).replaceAll("\\p{M}+","").toLowerCase(Locale.ROOT);}
 @Transactional public RouteFollowResponse follow(UUID user,RouteFollowRequest request){String o=normalize(request.origen()),d=normalize(request.destino()); var found=repo.findByUserIdAndOrigenNormAndDestinoNorm(user,o,d); if(found.isPresent())return response(found.get()); if(repo.countByUserId(user)>=10)throw ApiException.conflict("FOLLOW_LIMIT","Solo puede seguir hasta 10 rutas"); try{return response(repo.saveAndFlush(new RouteFollow(user,request.origen().trim(),request.destino().trim(),o,d,clock.instant())));}catch(DataIntegrityViolationException e){return response(repo.findByUserIdAndOrigenNormAndDestinoNorm(user,o,d).orElseThrow(()->e));}}
 @Transactional(readOnly=true) public List<RouteFollowResponse> list(UUID user){return repo.findByUserIdOrderByCreadoEnDesc(user).stream().map(RouteFollowService::response).toList();}
 @Transactional public void delete(UUID user,UUID id){RouteFollow follow=repo.findById(id).filter(f->f.getUserId().equals(user)).orElseThrow(()->ApiException.notFound("FOLLOW_NOT_FOUND","La ruta seguida no existe"));repo.delete(follow);}
 private static RouteFollowResponse response(RouteFollow f){return new RouteFollowResponse(f.getId(),f.getOrigen(),f.getDestino(),f.getCreadoEn());}
}
