package com.tripbus.flashoffers.common.security;

import com.tripbus.flashoffers.common.error.ApiException;
import org.springframework.security.core.Authentication;

import java.util.Objects;
import java.util.UUID;

/**
 * Alcance de un operador (su empresa) o de un administrador (todas) sobre recursos de empresa: reglas automáticas y
 * plantillas. Lo ajeno responde 404, igual que las ofertas de otra empresa.
 */
public record OperatorScope(UUID companyId, boolean admin) {

    public static OperatorScope of(Authentication authentication) {
        return new OperatorScope(CurrentUser.companyId(authentication), CurrentUser.hasRole(authentication, "ADMIN"));
    }

    /** Empresa del recurso nuevo: la del operador; un administrador debe indicarla. */
    public UUID companyFor(UUID requested) {
        if (!admin) {
            if (companyId == null) {
                throw ApiException.forbidden("COMPANY_REQUIRED", "El operador no tiene empresa asociada");
            }
            return companyId;
        }
        if (requested == null) {
            throw ApiException.badRequest("COMPANY_REQUIRED", "Indique la empresa (companyId)");
        }
        return requested;
    }

    /** Lanza 404 si el recurso es de otra empresa. */
    public void check(UUID owner, String notFoundCode, String message) {
        if (!admin && !Objects.equals(companyId, owner)) {
            throw ApiException.notFound(notFoundCode, message);
        }
    }
}
