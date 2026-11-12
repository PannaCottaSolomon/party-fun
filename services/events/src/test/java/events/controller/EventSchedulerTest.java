package events.controller;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import events.messaging.Outbox;
import events.service.EventService;
import events.service.PaymentRecoveryService;

/**
 * Unit test for {@link EventScheduler}: each scheduled run hands over to the job it schedules (the jobs themselves
 * are tested in EventServiceTest, OutboxTest and PaymentRecoveryServiceTest, and against the real stack in the
 * integration and chaos tests).
 */
@ExtendWith(MockitoExtension.class)
class EventSchedulerTest {

    @Mock
    private EventService eventService;
    @Mock
    private Outbox outbox;
    @Mock
    private PaymentRecoveryService paymentRecovery;

    private EventScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new EventScheduler(eventService, outbox, paymentRecovery);
    }

    @Test
    void eachRunProcessesTheExpiredEvents() {
        scheduler.checkExpiredEvents();

        verify(eventService).processExpiredEvents();
    }

    @Test
    void eachRelayRunPublishesTheOutbox() {
        scheduler.relayOutbox();

        verify(outbox).relay();
    }

    @Test
    void eachRecoveryRunRecoversPayments() {
        scheduler.recoverPayments();

        verify(paymentRecovery).recover();
    }
}
