package payment.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import payment.TestTokens;
import payment.dto.AdminRetryResponse;
import payment.dto.DlqCountResponse;
import payment.dto.FailedRefundsResponse;
import payment.dto.RefundResultResponse.Outcome;
import payment.entity.PaymentStatus;
import payment.exception.InvalidStateException;
import payment.service.AdminRefundService;

/** The Admin dashboard endpoints: only a verified ADMIN reaches them. */
@WebMvcTest(AdminRefundController.class)
@TestPropertySource(properties = {"auth.jwt.secret=" + TestTokens.SECRET, "payment.admin-api-key=",
        "payment.service-api-key=" + AdminRefundControllerTest.SERVICE_KEY})
class AdminRefundControllerTest {

    static final String SERVICE_KEY = "test-service-key";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AdminRefundService adminRefundService;

    @Test
    void dlqCountForAnAdmin() throws Exception {
        when(adminRefundService.deadLetterCount())
                .thenReturn(new DlqCountResponse("payments.refunds.dlq", 2, 0L, 2, "management-api"));

        mvc.perform(get("/admin/refunds/dlq/count").header("Authorization", TestTokens.bearer("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queue").value("payments.refunds.dlq"))
                .andExpect(jsonPath("$.ready").value(2))
                .andExpect(jsonPath("$.unacknowledged").value(0))
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void failedRefundsForAnAdmin() throws Exception {
        when(adminRefundService.failedRefunds()).thenReturn(new FailedRefundsResponse(0,
                new DlqCountResponse("payments.refunds.dlq", 0, 0L, 0, "management-api"), List.of(), List.of()));

        mvc.perform(get("/admin/refunds/failed").header("Authorization", TestTokens.bearer("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.dlq.total").value(0));
    }

    @Test
    void retryReturnsWhatTheRecoveryDid() throws Exception {
        when(adminRefundService.retry(101)).thenReturn(new AdminRetryResponse(101, Outcome.REFUNDED,
                PaymentStatus.REFUNDED, "re_1", 1, 1, 1, "Refunded"));

        mvc.perform(post("/admin/refunds/101/retry").header("Authorization", TestTokens.bearer("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("REFUNDED"))
                .andExpect(jsonPath("$.dlqMessagesRemoved").value(1))
                .andExpect(jsonPath("$.remainingInDlq").value(1));
        // The deprecated alias does the same.
        mvc.perform(post("/payments/101/retry-refund").header("Authorization", TestTokens.bearer("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("REFUNDED"));
    }

    @Test
    void recoveryAlreadyInProgressIs409() throws Exception {
        when(adminRefundService.retry(101)).thenThrow(new InvalidStateException("Payment 101 is REFUND_PENDING: a"
                + " refund of it is already in progress (or another Admin is recovering it)"));

        mvc.perform(post("/admin/refunds/101/retry").header("Authorization", TestTokens.bearer("ADMIN")))
                .andExpect(status().isConflict());
    }

    @Test
    void nonAdminsAreRefused() throws Exception {
        for (String path : List.of("/admin/refunds/dlq/count", "/admin/refunds/failed")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization", "Bearer not-a-token")).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization", TestTokens.bearer("PARTICIPANT")))
                    .andExpect(status().isForbidden());
            mvc.perform(get(path).header("Authorization", TestTokens.bearer("ORGANISER")))
                    .andExpect(status().isForbidden());
            mvc.perform(get(path).header("X-Service-Api-Key", SERVICE_KEY)).andExpect(status().isForbidden());
        }
        mvc.perform(post("/admin/refunds/101/retry").header("Authorization", TestTokens.bearer("PARTICIPANT")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(adminRefundService);
    }
}
