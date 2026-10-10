package com.tripbus.flashoffers.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Documentación OpenAPI / Swagger UI (http://localhost:18006/swagger-ui.html). Solo en IDE y QA:
 * application-prod.yml apaga springdoc. Consultar ofertas es público; para crear/activar/cerrar
 * (OPERADOR o ADMIN) y reservar, el botón "Authorize" acepta el accessToken que emite identity en /auth/login.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearer-jwt";

    @Bean
    public OpenAPI flashOffersOpenApi(ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties build = buildProperties.getIfAvailable();
        String version = build != null ? build.getVersion() : "dev";
        return new OpenAPI()
                .info(new Info()
                        .title("TripBus · Flash Offers")
                        .description("Ofertas relámpago: creación con cupo y horario, cupo en vivo y reserva sin sobreventa.")
                        .version(version))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
