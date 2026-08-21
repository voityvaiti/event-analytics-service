package dev.rymarovych.event_analytics.web;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.rymarovych.event_analytics.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Pins the generated API document to the API it claims to describe.
 *
 * <p>A generated document cannot go out of date, but it can quietly be wrong about what it
 * generated from: swagger-core introspects with a Jackson mapper of its own, so field naming,
 * schema naming, and the document version are each something it can settle correctly for itself and
 * incorrectly for this service. Every assertion below is a way that has already happened once.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OpenApiDocumentIntegrationTest {

  @Autowired private MockMvc mockMvc;

  /**
   * The document and the UI reading it are the one part of the API a caller reaches before holding
   * a token. This is the test that fails if that stops being true.
   */
  @Test
  void documentAndUserInterfaceNeedNoToken() throws Exception {
    apiDocument();
    mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
  }

  @Test
  void everyEndpointIsDocumented() throws Exception {
    apiDocument()
        .andExpect(jsonPath("$.paths['/api/v1/events'].post").exists())
        .andExpect(jsonPath("$.paths['/api/v1/events/batch'].post").exists())
        .andExpect(jsonPath("$.paths['/api/v1/stats/event-counts'].get").exists())
        .andExpect(jsonPath("$.paths['/api/v1/stats/active-users'].get").exists())
        .andExpect(jsonPath("$.paths['/api/v1/stats/top-pages'].get").exists());
  }

  /**
   * The failure this guards is the worst one a document can have: names a caller can follow into a
   * request the API rejects. swagger-core never sees {@code
   * spring.jackson.property-naming-strategy} on its own, so every one of these would read {@code
   * eventId} instead.
   */
  @Test
  void schemaFieldsCarryTheNamesTheApiAcceptsAndReturns() throws Exception {
    apiDocument()
        .andExpect(jsonPath("$.components.schemas.EventRequest.properties.event_id").exists())
        .andExpect(jsonPath("$.components.schemas.EventRequest.properties.user_id").exists())
        .andExpect(jsonPath("$.components.schemas.EventRequest.properties.event_type").exists())
        .andExpect(jsonPath("$.components.schemas.EventRequest.properties.timestamp").exists())
        .andExpect(jsonPath("$.components.schemas.TopPagesResponse.properties.has_more").exists())
        .andExpect(
            jsonPath("$.components.schemas.EventCountsResponse.properties.group_by").exists());
  }

  /**
   * OpenAPI has one flat schema namespace, so the two response envelopes that both nest a {@code
   * Bucket} would publish one shape under both endpoints — and the one that lost would be
   * documented with a field it never returns.
   */
  @Test
  void theTwoBucketShapesAreDocumentedApart() throws Exception {
    apiDocument()
        .andExpect(jsonPath("$.components.schemas.EventCountBucket.properties.count").exists())
        .andExpect(
            jsonPath("$.components.schemas.ActiveUsersBucket.properties.active_users").exists());
  }

  /**
   * A schema resolver left in its default mode writes the type into the member a 3.0 document uses,
   * which a 3.1 document drops on the way out — leaving every schema typeless. Two are enough to
   * catch it, because it is all of them or none.
   */
  @Test
  void schemasDeclareTheirType() throws Exception {
    apiDocument()
        .andExpect(jsonPath("$.components.schemas.EventRequest.type").value("object"))
        .andExpect(jsonPath("$.components.schemas.EventCountsResponse.type").value("object"));
  }

  /** Ingest answers 202, and a document inferring 200 from the signature would say otherwise. */
  @Test
  void ingestIsDocumentedAsAcceptedAndNothingElse() throws Exception {
    apiDocument()
        .andExpect(jsonPath("$.paths['/api/v1/events'].post.responses.202").exists())
        .andExpect(jsonPath("$.paths['/api/v1/events'].post.responses.200").doesNotExist())
        .andExpect(jsonPath("$.paths['/api/v1/events/batch'].post.responses.202").exists())
        .andExpect(jsonPath("$.paths['/api/v1/events/batch'].post.responses.200").doesNotExist());
  }

  /**
   * The constraints are the contract's bounds, and they are declared once — on the request records
   * — rather than restated for the document.
   */
  @Test
  void beanValidationReachesTheDocument() throws Exception {
    apiDocument()
        .andExpect(
            jsonPath("$.components.schemas.EventRequest.required")
                .value(containsInAnyOrder("event_id", "user_id", "event_type", "timestamp")))
        .andExpect(
            jsonPath("$.components.schemas.EventBatchRequest.properties.events.maxItems")
                .value(1000))
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/stats/top-pages'].get.parameters[?(@.name == 'limit')].schema.maximum")
                .value(contains(100)));
  }

  /**
   * {@code properties} takes any JSON, and the document says exactly that. Generated from the
   * declared type instead, it would publish two dozen accessor flags of the node class as if they
   * were fields a caller may send.
   */
  @Test
  void arbitraryPropertiesAreDocumentedAsArbitrary() throws Exception {
    apiDocument()
        .andExpect(jsonPath("$.components.schemas.JsonNode").doesNotExist())
        .andExpect(
            jsonPath("$.components.schemas.EventRequest.properties.properties['$ref']")
                .doesNotExist());
  }

  /** Every operation is behind the same token, so the document says so once, at its root. */
  @Test
  void theApiIsDocumentedAsBearerAuthenticated() throws Exception {
    apiDocument()
        .andExpect(jsonPath("$.components.securitySchemes['bearer-token'].scheme").value("bearer"))
        .andExpect(
            jsonPath("$.components.securitySchemes['bearer-token'].bearerFormat").value("JWT"))
        .andExpect(jsonPath("$.security[0]['bearer-token']").exists());
  }

  private ResultActions apiDocument() throws Exception {
    return mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
  }
}
