package payment.service;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import payment.TestData;
import payment.entity.PaymentStatus;
import payment.exception.InvalidStateException;
import payment.repository.PaymentRepository;

/** Refunds abandoned mid-way (e.g. the service stopped) are resumed, with the same idempotency key. */
@ExtendWith(MockitoExtension.class)
class RefundRecoveryJobTest {

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private RefundService refundService;

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-23T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void resumesRefundsAbandonedForLongerThanTheThreshold() {
        when(paymentRepository.findAbandonedRefunds(PaymentStatus.REFUND_PENDING, LocalDateTime.of(2026, 10, 23, 9, 50)))
                .thenReturn(List.of(7, 8));
        org.mockito.Mockito.lenient().when(refundService.refundAndReport(eq(8), eq(RefundTrigger.RECOVERY), eq("recovery-8")))
                .thenThrow(new InvalidStateException("Payment 8 is REFUNDED"));

        new RefundRecoveryJob(paymentRepository, refundService, clock, TestData.properties()).resumeAbandonedRefunds();

        verify(refundService).refundAndReport(7, RefundTrigger.RECOVERY, "recovery-7");
        verify(refundService).refundAndReport(8, RefundTrigger.RECOVERY, "recovery-8"); // one failure doesn't stop it
    }

    @Test
    void nothingToDoWhenNoRefundIsStuck() {
        when(paymentRepository.findAbandonedRefunds(eq(PaymentStatus.REFUND_PENDING), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());

        new RefundRecoveryJob(paymentRepository, refundService, clock, TestData.properties()).resumeAbandonedRefunds();

        verifyNoInteractions(refundService);
    }
}
