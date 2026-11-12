package payment.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MockMvc;

import payment.TestTokens;
import payment.dto.BookingRefundResponse;
import payment.dto.PaymentResponse;
import payment.dto.RefundResultResponse;
import payment.dto.RefundResultResponse.Outcome;
import payment.entity.PaymentStatus;
import payment.exception.InvalidStateException;
import payment.exception.InvalidWebhookException;
import payment.exception.NotFoundException;
import payment.exception.PaymentGatewayException;
import payment.exception.RefundFailedException;
import payment.service.PaymentService;
import payment.service.RefundService;
import payment.service.WebhookService;

@WebMvcTest({PaymentController.class, StripeWebhookController.class})
@TestPropertySource(properties = {"payment.admin-api-key=" + PaymentControllerTest.ADMIN_KEY,
        "payment.service-api-key=" + PaymentControllerTest.SERVICE_KEY, "auth.jwt.secret=" + TestTokens.SECRET})
class PaymentControllerTest {

    static final String ADMIN_KEY = "test-admin-key";
    static final String SERVICE_KEY = "test-service-key";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PaymentService paymentService;
    @MockitoBean
    private RefundService refundService;
    @MockitoBean
    private WebhookService webhookService;

    private static PaymentResponse payment(int id, PaymentStatus status) {
        return new PaymentResponse(id, 7, null, 1, 2, new BigDecimal("40.00"), "SGD", status, "pi_" + id, null,
                "card", null, null, null, null, null, null);
    }

    @Test
    void createPaymentReturns201() throws Exception {
        when(paymentService.create(any())).thenReturn(payment(10, PaymentStatus.PAID));

        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingId\":7,\"paymentMethodId\":\"pm_card_visa\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentId").value(10))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.currency").value("SGD"));
    }

    @Test
    void createPaymentWithoutBookingIdIs400() throws Exception {
        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.bookingId").exists());
        verifyNoInteractions(paymentService);
    }

    @Test
    void stripeFailureIs502() throws Exception {
        when(paymentService.create(any()))
                .thenThrow(new PaymentGatewayException("Stripe payment request failed (code=null, status=500)"));

        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content("{\"bookingId\":7}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502));
    }

    @Test
    void brokerDownIs503() throws Exception {
        when(refundService.refundPaymentManually(1)).thenThrow(new AmqpConnectException(new java.net.ConnectException()));

        mvc.perform(admin(post("/payments/1/refund")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503));
    }

    @Test
    void unexpectedErrorIs500AndLeaksNothing() throws Exception {
        when(paymentService.get(1)).thenThrow(new IllegalStateException("db password=g4t2 at jdbc:mysql://x"));

        mvc.perform(get("/payments/1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(content().string(not(containsString("g4t2"))))
                .andExpect(content().string(not(containsString("jdbc"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    @Test
    void getPaymentAndLookups() throws Exception {
        when(paymentService.get(1)).thenReturn(payment(1, PaymentStatus.PAID));
        when(paymentService.listByBooking(7)).thenReturn(List.of(payment(1, PaymentStatus.PAID)));
        when(paymentService.listByUser(1)).thenReturn(List.of(payment(1, PaymentStatus.PAID),
                payment(2, PaymentStatus.REFUNDED)));

        mvc.perform(get("/payments/1")).andExpect(status().isOk()).andExpect(jsonPath("$.paymentId").value(1));
        mvc.perform(get("/payments/booking/7")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/payments/user/1")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void listAllPayments() throws Exception {
        when(paymentService.listAll()).thenReturn(List.of(payment(2, PaymentStatus.REFUND_FAILED),
                payment(1, PaymentStatus.PAID)));

        mvc.perform(get("/payments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].status").value("REFUND_FAILED"));
    }

    @Test
    void unknownPaymentIs404() throws Exception {
        when(paymentService.get(99)).thenThrow(new NotFoundException("Payment 99 not found"));

        mvc.perform(get("/payments/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Payment 99 not found"));
    }

    @Test
    void nonNumericIdIs400() throws Exception {
        mvc.perform(get("/payments/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for 'paymentId'"));
    }

    @Test
    void refundPayment() throws Exception {
        when(refundService.refundPaymentManually(1)).thenReturn(
                new RefundResultResponse(1, Outcome.REFUNDED, PaymentStatus.REFUNDED, "re_1", 1, "Refunded"));

        mvc.perform(admin(post("/payments/1/refund")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("REFUNDED"))
                .andExpect(jsonPath("$.stripeRefundId").value("re_1"));
    }

    @Test
    void refundInWrongStateIs409() throws Exception {
        when(refundService.refundPaymentManually(1))
                .thenThrow(new InvalidStateException("Payment 1 cannot change from PENDING to REFUND_PENDING"));

        mvc.perform(admin(post("/payments/1/refund")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void refundExhaustedIs502() throws Exception {
        when(refundService.refundPaymentManually(1)).thenThrow(new RefundFailedException("failed after 3 attempts"));

        mvc.perform(admin(post("/payments/1/refund")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("failed after 3 attempts"));
    }

    @Test
    void refundBookingReturnsPerPaymentResults() throws Exception {
        when(refundService.refundBooking(7)).thenReturn(new BookingRefundResponse(7, List.of(
                new RefundResultResponse(1, Outcome.SKIPPED, PaymentStatus.FAILED, null, 0, "Payment was never paid"),
                new RefundResultResponse(2, Outcome.REFUNDED, PaymentStatus.REFUNDED, "re_2", 1, "Refunded"))));

        mvc.perform(admin(post("/payments/booking/7/refund")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(7))
                .andExpect(jsonPath("$.results[1].outcome").value("REFUNDED"));
    }

    @Test
    void genericMutationEndpointsDoNotExist() throws Exception {
        mvc.perform(put("/payments/1").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"REFUNDED\"}"))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(patch("/payments/1").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PAID\"}"))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(delete("/payments/1"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405));
        verifyNoInteractions(paymentService, refundService);
    }

    @Test
    void webhookIsAcknowledged() throws Exception {
        when(webhookService.handle(anyString(), any()))
                .thenReturn(new WebhookService.Result("evt_1", "payment_intent.succeeded",
                        WebhookService.Action.PROCESSED));

        mvc.perform(post("/payments/webhook/stripe").contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", "t=1,v1=abc").content("{\"id\":\"evt_1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("PROCESSED"));
    }

    @Test
    void webhookWithBadSignatureIs400() throws Exception {
        when(webhookService.handle(anyString(), any()))
                .thenThrow(new InvalidWebhookException("Invalid Stripe webhook signature"));

        mvc.perform(post("/payments/webhook/stripe").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid Stripe webhook signature"));
    }

    private static MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
        return request.header("X-Admin-Api-Key", ADMIN_KEY);
    }

    // ---------------------------------------------------------------- Admin / service API keys

    @Test
    void adminOnlyEndpointsRejectMissingOrWrongCredentials() throws Exception {
        String path = "/payments/booking/7/refund";
        mvc.perform(post(path))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message")
                        .value("Missing or invalid credentials: send an Admin token (Authorization: Bearer ...)"));
        mvc.perform(post(path).header("X-Admin-Api-Key", "wrong")).andExpect(status().isUnauthorized());
        mvc.perform(post(path).header("Authorization", "Bearer forged")).andExpect(status().isUnauthorized());
        mvc.perform(post(path).header("Authorization", TestTokens.bearer("ORGANISER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This endpoint requires the ADMIN role"));
        verifyNoInteractions(refundService);
    }

    @Test
    void adminJwtCanUseTheRefundEndpoints() throws Exception {
        when(refundService.refundPaymentManually(1)).thenReturn(
                new RefundResultResponse(1, Outcome.REFUNDED, PaymentStatus.REFUNDED, "re_1", 1, "Refunded"));

        mvc.perform(post("/payments/1/refund").header("Authorization", TestTokens.bearer("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("REFUNDED"));
    }

    @Test
    void singleRefundRejectsMissingOrWrongKeys() throws Exception {
        mvc.perform(post("/payments/1/refund"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(
                        "Missing or invalid credentials: send an Admin token (Authorization: Bearer ...) or"
                                + " X-Service-Api-Key"));
        mvc.perform(post("/payments/1/refund").header("X-Service-Api-Key", "wrong"))
                .andExpect(status().isUnauthorized());
        // The keys are not interchangeable between the two headers.
        mvc.perform(post("/payments/1/refund").header("X-Service-Api-Key", ADMIN_KEY))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/payments/1/refund").header("X-Admin-Api-Key", SERVICE_KEY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(refundService);
    }

    @Test
    void serviceKeyMayRefundOnePayment() throws Exception {
        when(refundService.refundPaymentManually(1)).thenReturn(
                new RefundResultResponse(1, Outcome.REFUNDED, PaymentStatus.REFUNDED, "re_1", 1, "Refunded"));

        mvc.perform(post("/payments/1/refund").header("X-Service-Api-Key", SERVICE_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("REFUNDED"));
    }

    @Test
    void serviceKeyIsForbiddenOnAdminOnlyEndpoints() throws Exception {
        mvc.perform(post("/payments/booking/7/refund").header("X-Service-Api-Key", SERVICE_KEY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This is an Admin endpoint: X-Service-Api-Key is not"
                        + " accepted; send an Admin token (Authorization: Bearer ...)"));
        verifyNoInteractions(refundService);
    }

    @Test
    void nonAdminEndpointsNeedNoApiKey() throws Exception {
        when(paymentService.get(1)).thenReturn(payment(1, PaymentStatus.PAID));

        mvc.perform(get("/payments/1")).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- checkout (Scenario 1)

    private static final String CHECKOUT = """
            {"eventBookingId":"evb-1","eventId":2,"userId":1,"quantity":3,"ticketPrice":12.50,
             "paymentMethodId":"pm_card_visa","participantEmail":"p@smu.edu.sg","participantName":"Pat",
             "eventTitle":"Neon Party"}""";

    @Test
    void checkoutCreatesAPaymentWith201() throws Exception {
        when(paymentService.checkout(any())).thenReturn(
                new PaymentService.CheckoutResult(payment(10, PaymentStatus.PAID), true));

        mvc.perform(post("/payments/checkout").contentType(MediaType.APPLICATION_JSON).content(CHECKOUT))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentId").value(10))
                .andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    void repeatedCheckoutReturnsTheExistingPaymentWith200() throws Exception {
        when(paymentService.checkout(any())).thenReturn(
                new PaymentService.CheckoutResult(payment(10, PaymentStatus.PAID), false));

        mvc.perform(post("/payments/checkout").contentType(MediaType.APPLICATION_JSON).content(CHECKOUT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(10));
    }

    @Test
    void invalidCheckoutIs400() throws Exception {
        mvc.perform(post("/payments/checkout").contentType(MediaType.APPLICATION_JSON).content("""
                        {"eventId":2,"userId":1,"quantity":0,"ticketPrice":-1,"participantEmail":"not-an-email"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.eventBookingId").exists())
                .andExpect(jsonPath("$.fieldErrors.quantity").exists())
                .andExpect(jsonPath("$.fieldErrors.ticketPrice").exists())
                .andExpect(jsonPath("$.fieldErrors.participantEmail").exists());
        mvc.perform(post("/payments/checkout").contentType(MediaType.APPLICATION_JSON).content("""
                        {"eventBookingId":"evb-1","eventId":2,"userId":1,"quantity":1,"ticketPrice":10.005}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.ticketPrice").exists());
        verifyNoInteractions(paymentService);
    }
}
