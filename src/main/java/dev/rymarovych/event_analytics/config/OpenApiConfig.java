package dev.rymarovych.event_analytics.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springdoc.core.providers.ObjectMapperProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configure API metadata, bearer authentication, and schema field naming. Paths, record schemas,
 * and validation bounds are generated. The global security requirement applies to every API
 * operation.
 */
@Configuration
@OpenAPIDefinition(
    info =
        @Info(
            title = "Event Analytics Service",
            version = "v1",
            description =
                """
                Ingest raw events and query aggregates over them.

                Every request is scoped to the tenant named in the bearer token: no endpoint takes \
                a tenant parameter, and a tenant in a request body carries no authority.\
                """),
    security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
@SecurityScheme(
    name = OpenApiConfig.BEARER_SCHEME,
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT",
    description =
        "An RS256 JWT carrying a `tenant` claim. Mint one for a local run with"
            + " `scripts/actions/mint-token <tenant>`.")
class OpenApiConfig {

  static final String BEARER_SCHEME = "bearer-token";

  /**
   * Match schema naming to runtime JSON. swagger-core's Jackson 2 mapper does not inherit Boot's
   * Jackson 3 naming strategy; without this, event_id is documented as eventId.
   *
   * <p>Also configure the replacement resolver for OpenAPI 3.1, or object type metadata is lost.
   * Integration tests cover both settings.
   */
  @Bean
  ModelResolver snakeCaseModelResolver(ObjectMapperProvider objectMapperProvider) {
    var introspector =
        objectMapperProvider
            .jsonMapper()
            .copy()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    return new ModelResolver(introspector).openapi31(objectMapperProvider.isOpenapi31());
  }
}
