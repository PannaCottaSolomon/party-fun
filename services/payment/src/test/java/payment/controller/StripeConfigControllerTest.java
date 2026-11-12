package payment.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import payment.config.StripeProperties;
import payment.exception.GlobalExceptionHandler;
import payment.exception.ServiceNotConfiguredException;
import payment.service.PaymentService;
import payment.service.RefundService;

@WebMvcTest({StripeConfigController.class, PaymentController.class})
@Import(StripeConfigControllerTest.Properties.class)
@TestPropertySource(properties = "stripe.publishable-key=pk_test_frontend123")
class StripeConfigControllerTest {

    @TestConfiguration
    @EnableConfigurationProperties(StripeProperties.class)
    static class Properties {
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PaymentService paymentService;
    @MockitoBean
    private RefundService refundService;

    @Test
    void servesThePublishableKeyAndIsNotMistakenForAPaymentId() throws Exception {
        mvc.perform(get("/payments/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stripePublishableKey").value("pk_test_frontend123"));
    }

    @Test
    void unsetKeyIsServiceUnavailable() {
        StripeConfigController controller = new StripeConfigController(new StripeProperties("sk_test_x", "w", ""));

        assertThatThrownBy(controller::config)
                .isInstanceOf(ServiceNotConfiguredException.class)
                .hasMessage("Stripe card payments are not configured: stripe_publishable_key is not set");
    }

    @Test
    void unsetKeyMapsToA503ErrorBody() throws Exception {
        MockMvc unconfigured = MockMvcBuilders
                .standaloneSetup(new StripeConfigController(new StripeProperties("sk_test_x", "w", "")))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        unconfigured.perform(get("/payments/config"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("Service Unavailable"))
                .andExpect(jsonPath("$.message")
                        .value("Stripe card payments are not configured: stripe_publishable_key is not set"))
                .andExpect(jsonPath("$.path").value("/payments/config"));
    }
}
