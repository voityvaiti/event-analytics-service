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
 * The parts of the OpenAPI document that cannot be read off a controller: what the API is called,
 * how a caller authenticates, and the field naming its schemas use.
 *
 * <p>Everything else is generated — paths and verbs from the request mappings, schemas from the
 * request and response records, and each field's {@code required} flag and bounds from its Bean
 * Validation constraints. So a new endpoint appears in the document without anyone remembering to
 * add it, which is the property a hand-written reference cannot have.
 *
 * <p>The security requirement is declared once, globally, because every {@code /api/v1} endpoint is
 * behind the same bearer token — the document says so per operation only because that is the only
 * place OpenAPI can say it.
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
   * Makes the generated schemas carry the field names the API actually accepts.
   *
   * <p>swagger-core introspects with a Jackson 2 mapper of its own, so it never sees {@code
   * spring.jackson.property-naming-strategy} — which Boot applies to the Jackson 3 mapper the
   * service serializes with. Left alone it documents {@code eventId} for a field the API takes as
   * {@code event_id}, which is worse than documenting nothing: a caller who follows it sends a
   * request that fails validation. The naming strategy is therefore stated a second time here, and
   * an integration test holds the two together.
   *
   * <p>Replacing the resolver means re-stating the document version it resolves for: a 3.1 resolver
   * writes each schema's type into a member a 3.0 one does not, so a resolver left in the default
   * mode silently drops {@code type: object} from every schema of a 3.1 document.
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
