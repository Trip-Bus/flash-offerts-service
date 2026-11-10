package com.tripbus.flashoffers.web;

import com.tripbus.flashoffers.common.error.ApiException;
import com.tripbus.flashoffers.common.error.ErrorResponse;
import com.tripbus.flashoffers.common.error.GlobalExceptionHandler;
import com.tripbus.flashoffers.common.error.TooManyRequestsException;
import com.tripbus.flashoffers.common.security.OperatorScope;
import com.tripbus.flashoffers.service.AutoOfferRuleService;
import com.tripbus.flashoffers.service.OfferService;
import com.tripbus.flashoffers.service.OfferTemplateService;
import com.tripbus.flashoffers.service.ReservationRateLimiter;
import com.tripbus.flashoffers.service.WaitingRoomService;
import com.tripbus.flashoffers.web.dto.AutoOfferRuleRequest;
import com.tripbus.flashoffers.web.dto.AutoOfferRuleResponse;
import com.tripbus.flashoffers.web.dto.OfferTemplateRequest;
import com.tripbus.flashoffers.web.dto.OfferTemplateResponse;
import com.tripbus.flashoffers.web.dto.ReservationResponse;
import com.tripbus.flashoffers.web.dto.ReserveRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Controladores de la sala de espera, reglas automáticas, plantillas y el 429 con Retry-After. */
class Sprint6ControllersTest {

    private final UUID user = UUID.randomUUID();
    private final UUID company = UUID.randomUUID();

    private JwtAuthenticationToken auth(String role, UUID companyId) {
        Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "none").subject(user.toString()).claim("role", role);
        if (companyId != null) {
            jwt.claim("companyId", companyId.toString());
        }
        return new JwtAuthenticationToken(jwt.build(), List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Test
    void entrarALaSalaDevuelve201LaPrimeraVezY200Despues() {
        WaitingRoomService room = mock(WaitingRoomService.class);
        UUID offer = UUID.randomUUID();
        var status = new WaitingRoomService.QueueStatus(offer, WaitingRoomService.QueueState.EN_SALA, null, 1, null);
        when(room.join(offer, user)).thenReturn(new WaitingRoomService.JoinResult(status, true),
                new WaitingRoomService.JoinResult(status, false));
        when(room.status(offer, user)).thenReturn(status);
        OfferQueueController controller = new OfferQueueController(room);
        var auth = auth("PASAJERO", null);
        assertThat(controller.join(offer, auth).getStatusCode().value()).isEqualTo(201);
        assertThat(controller.join(offer, auth).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.me(offer, auth)).isSameAs(status);
    }

    @Test
    void reservarCuentaElIntentoAntesDeReservar() {
        OfferService service = mock(OfferService.class);
        ReservationRateLimiter limiter = mock(ReservationRateLimiter.class);
        UUID offer = UUID.randomUUID();
        var reservation = new ReservationResponse(UUID.randomUUID(), offer, UUID.randomUUID(), UUID.randomUUID(), user,
                "1", BigDecimal.ONE, "RESERVADA");
        when(service.reserve(offer, user, "CC1234")).thenReturn(new OfferService.ReserveResult(reservation, true));
        OfferController controller = new OfferController(service, null, limiter);
        assertThat(controller.reserve(offer, new ReserveRequest("CC1234"), auth("PASAJERO", null))
                .getStatusCode().value()).isEqualTo(201);
        verify(limiter).check(user);

        doThrow(new TooManyRequestsException("TOO_MANY_ATTEMPTS", "Demasiados", 12)).when(limiter).check(user);
        var auth = auth("PASAJERO", null);
        ReserveRequest request = new ReserveRequest("CC9999");
        assertThatThrownBy(() -> controller.reserve(offer, request, auth)).isInstanceOf(TooManyRequestsException.class);
        verify(service, never()).reserve(offer, user, "CC9999");
    }

    @Test
    void el429LlevaRetryAfterYLosDemasErroresNo() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        ResponseEntity<ErrorResponse> tooMany = handler.handleApi(
                new TooManyRequestsException("TOO_MANY_ATTEMPTS", "Demasiados", 12));
        assertThat(tooMany.getStatusCode().value()).isEqualTo(429);
        assertThat(tooMany.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("12");
        assertThat(tooMany.getBody().error().details()).containsEntry("retryAfterSeconds", 12L);
        ResponseEntity<ErrorResponse> forbidden = handler.handleApi(ApiException.forbidden("QUEUE_TURN_REQUIRED", "x"));
        assertThat(forbidden.getStatusCode().value()).isEqualTo(403);
        assertThat(forbidden.getHeaders().containsKey(HttpHeaders.RETRY_AFTER)).isFalse();
    }

    @Test
    void reglasAutomaticasUsanElAlcanceDelOperador() {
        AutoOfferRuleService service = mock(AutoOfferRuleService.class);
        AutoOfferRuleController controller = new AutoOfferRuleController(service);
        var auth = auth("OPERADOR", company);
        OperatorScope scope = new OperatorScope(company, false);
        UUID id = UUID.randomUUID();
        var request = new AutoOfferRuleRequest(null, null, 24, BigDecimal.TEN, BigDecimal.TEN, 3, true);
        var response = new AutoOfferRuleResponse(id, company, null, 24, BigDecimal.TEN, BigDecimal.TEN, 3, true,
                Instant.now());
        when(service.list(scope)).thenReturn(List.of(response));
        when(service.get(id, scope)).thenReturn(response);
        when(service.create(request, scope)).thenReturn(response);
        when(service.update(id, request, scope)).thenReturn(response);
        assertThat(controller.list(auth)).containsExactly(response);
        assertThat(controller.get(id, auth)).isSameAs(response);
        assertThat(controller.create(request, auth).getStatusCode().value()).isEqualTo(201);
        assertThat(controller.update(id, request, auth)).isSameAs(response);
        assertThat(controller.delete(id, auth).getStatusCode().value()).isEqualTo(204);
        verify(service).delete(id, scope);
    }

    @Test
    void plantillasUsanElAlcanceDelAdministrador() {
        OfferTemplateService service = mock(OfferTemplateService.class);
        OfferTemplateController controller = new OfferTemplateController(service);
        var auth = auth("ADMIN", null);
        OperatorScope scope = new OperatorScope(null, true);
        UUID id = UUID.randomUUID();
        var request = new OfferTemplateRequest(company, UUID.randomUUID(), List.of(1), LocalTime.NOON, 30,
                BigDecimal.TEN, 2, null, null, null, 0, null);
        var response = new OfferTemplateResponse(id, company, request.routeId(), List.of(1), LocalTime.NOON, 30,
                BigDecimal.TEN, 2, 2, true, false, 0, true, Instant.now());
        when(service.list(scope)).thenReturn(List.of(response));
        when(service.get(id, scope)).thenReturn(response);
        when(service.create(request, scope)).thenReturn(response);
        when(service.update(eq(id), eq(request), any())).thenReturn(response);
        assertThat(controller.list(auth)).containsExactly(response);
        assertThat(controller.get(id, auth)).isSameAs(response);
        assertThat(controller.create(request, auth).getBody()).isSameAs(response);
        assertThat(controller.update(id, request, auth)).isSameAs(response);
        assertThat(controller.delete(id, auth).getStatusCode().value()).isEqualTo(204);
        verify(service).delete(id, scope);
    }

    @Test
    void alcanceDeEmpresa() {
        OperatorScope operador = new OperatorScope(company, false);
        assertThat(operador.companyFor(UUID.randomUUID())).as("ignora la empresa del cuerpo").isEqualTo(company);
        operador.check(company, "X", "no existe");
        UUID otra = UUID.randomUUID();
        assertThatThrownBy(() -> operador.check(otra, "X", "no existe")).extracting("code").isEqualTo("X");
        OperatorScope sinEmpresa = new OperatorScope(null, false);
        assertThatThrownBy(() -> sinEmpresa.companyFor(null)).extracting("code").isEqualTo("COMPANY_REQUIRED");
        OperatorScope admin = OperatorScope.of(auth("ADMIN", null));
        assertThat(admin.admin()).isTrue();
        admin.check(otra, "X", "no existe");
        assertThat(OperatorScope.of(auth("OPERADOR", company))).isEqualTo(operador);
    }
}
