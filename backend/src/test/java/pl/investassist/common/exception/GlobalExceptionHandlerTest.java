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
import org.springframework.test.util.ReflectionTestUtils;
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

    GlobalExceptionHandler handler = createHandler(messageSource);

    AcceptHeaderLocaleResolver localeResolver = new AcceptHeaderLocaleResolver();
    localeResolver.setDefaultLocale(Locale.ENGLISH);

    mockMvc = MockMvcBuilders.standaloneSetup(new TestErrorController())
        .setControllerAdvice(handler)
        .setLocaleResolver(localeResolver)
        .build();
  }

  private GlobalExceptionHandler createHandler(MessageSource messageSource) {
    try {
      return GlobalExceptionHandler.class.getConstructor(MessageSource.class).newInstance(messageSource);
    } catch (NoSuchMethodException e) {
      try {
        GlobalExceptionHandler handler = GlobalExceptionHandler.class.getConstructor().newInstance();
        try {
          ReflectionTestUtils.setField(handler, "messageSource", messageSource);
        } catch (Exception ignored) {
        }
        return handler;
      } catch (Exception ex) {
        throw new RuntimeException(ex);
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void handleInsufficientPosition_whenAcceptLanguagePl_returnsLocalizedPolishMessage() throws Exception {
    MvcResult result = mockMvc.perform(get("/test/insufficient-position")
            .header(HttpHeaders.ACCEPT_LANGUAGE, "pl"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").exists())
        .andReturn();

    JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
    String message = json.get("message").asText();

    assertThat(message)
        .isNotBlank()
        .isNotEqualTo("error.insufficient_position");
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
}
