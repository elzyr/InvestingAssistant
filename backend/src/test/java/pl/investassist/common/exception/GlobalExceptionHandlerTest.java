package pl.investassist.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

class GlobalExceptionHandlerTest {

  private MockMvc mockMvc;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @RestController
  static class TestErrorController {
    @GetMapping("/test/insufficient-position")
    void throwInsufficientPosition() {
      throw new InsufficientPositionException("error.insufficient_position");
    }

    @GetMapping("/test/resource-not-found")
    void throwResourceNotFound() {
      throw new ResourceNotFoundException("error.resource_not_found");
    }
  }

  @BeforeEach
  void setUp() {
    ReloadableResourceBundleMessageSource messageSource = new ReloadableResourceBundleMessageSource();
    messageSource.setBasename("classpath:messages");
    messageSource.setDefaultEncoding("UTF-8");
    messageSource.setDefaultLocale(Locale.ENGLISH);

    GlobalExceptionHandler handler = new GlobalExceptionHandler(messageSource);

    AcceptHeaderLocaleResolver localeResolver = new AcceptHeaderLocaleResolver();
    localeResolver.setDefaultLocale(Locale.ENGLISH);

    mockMvc = MockMvcBuilders.standaloneSetup(new TestErrorController())
        .setControllerAdvice(handler)
        .setLocaleResolver(localeResolver)
        .build();
  }

  @Test
  void handleInsufficientPosition_localizesAccordingToRequestedLanguage() throws Exception {
    MvcResult plResult = mockMvc.perform(get("/test/insufficient-position")
            .header(HttpHeaders.ACCEPT_LANGUAGE, "pl"))
        .andExpect(status().isBadRequest())
        .andReturn();

    MvcResult enResult = mockMvc.perform(get("/test/insufficient-position")
            .header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
        .andExpect(status().isBadRequest())
        .andReturn();

    String plMessage = objectMapper.readTree(plResult.getResponse().getContentAsString()).get("message").asText();
    String enMessage = objectMapper.readTree(enResult.getResponse().getContentAsString()).get("message").asText();

    assertThat(plMessage).isNotEqualTo("error.insufficient_position");
    assertThat(enMessage).isNotEqualTo("error.insufficient_position");
    assertThat(plMessage).isNotEqualTo(enMessage);
  }

  @Test
  void handleResourceNotFound_localizesAccordingToRequestedLanguage() throws Exception {
    MvcResult plResult = mockMvc.perform(get("/test/resource-not-found")
            .header(HttpHeaders.ACCEPT_LANGUAGE, "pl"))
        .andExpect(status().isNotFound())
        .andReturn();

    MvcResult enResult = mockMvc.perform(get("/test/resource-not-found")
            .header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
        .andExpect(status().isNotFound())
        .andReturn();

    String plMessage = objectMapper.readTree(plResult.getResponse().getContentAsString()).get("message").asText();
    String enMessage = objectMapper.readTree(enResult.getResponse().getContentAsString()).get("message").asText();

    assertThat(plMessage).isNotEqualTo("error.resource_not_found");
    assertThat(enMessage).isNotEqualTo("error.resource_not_found");
    assertThat(plMessage).isNotEqualTo(enMessage);
  }

  @Test
  void handleIllegalArgument_preservesDynamicMessageWithoutKeyOverwrite() {
    var messageSource = new org.springframework.context.support.StaticMessageSource();
    messageSource.addMessage("error.bad_request", java.util.Locale.ENGLISH, "Invalid request");
    var handler = new GlobalExceptionHandler(messageSource);

    var response = handler.handleIllegalArgument(
        new IllegalArgumentException("Commission cannot exceed gross amount on SELL"));

    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().message())
        .isEqualTo("Commission cannot exceed gross amount on SELL");
  }

  @Test
  void globalExceptionHandler_localizedValidationAndMalformedJson() throws Exception {
    var rb = new org.springframework.context.support.ResourceBundleMessageSource();
    rb.setBasename("messages");
    rb.setDefaultEncoding("UTF-8");
    rb.setFallbackToSystemLocale(false);

    var localeResolver = new org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver();
    localeResolver.setDefaultLocale(java.util.Locale.ENGLISH);

    var mvc = MockMvcBuilders.standaloneSetup(new ValidationDummyController())
        .setControllerAdvice(new GlobalExceptionHandler(rb))
        .setLocaleResolver(localeResolver)
        .build();

    MvcResult malformedEn = mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/test/validate")
                .header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{bad-json"))
        .andExpect(status().isBadRequest())
        .andReturn();
    MvcResult malformedPl = mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/test/validate")
                .header(HttpHeaders.ACCEPT_LANGUAGE, "pl")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{bad-json"))
        .andExpect(status().isBadRequest())
        .andReturn();

    String malformedEnMsg = objectMapper.readTree(malformedEn.getResponse().getContentAsString()).get("message").asText();
    String malformedPlMsg = objectMapper.readTree(malformedPl.getResponse().getContentAsString()).get("message").asText();
    assertThat(malformedEnMsg).isEqualTo("Malformed JSON request");
    assertThat(malformedPlMsg).isNotEqualTo("error.malformed_json");
    assertThat(malformedEnMsg).isNotEqualTo(malformedPlMsg);

    MvcResult validationEn = mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/test/validate")
                .header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andReturn();
    MvcResult validationPl = mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/test/validate")
                .header(HttpHeaders.ACCEPT_LANGUAGE, "pl")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andReturn();

    String validationEnMsg = objectMapper.readTree(validationEn.getResponse().getContentAsString()).get("message").asText();
    String validationPlMsg = objectMapper.readTree(validationPl.getResponse().getContentAsString()).get("message").asText();
    assertThat(validationEnMsg).startsWith("Validation failed:");
    assertThat(validationPlMsg).startsWith("Błąd walidacji:");
  }

  @org.springframework.web.bind.annotation.RestController
  static class ValidationDummyController {
    record Payload(@jakarta.validation.constraints.NotBlank String name) {}

    @org.springframework.web.bind.annotation.PostMapping("/test/validate")
    String validate(@jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody Payload payload) {
      return payload.name();
    }
  }
}
