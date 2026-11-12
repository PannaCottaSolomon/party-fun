package events.controller;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import events.messaging.Outbox;
import events.service.EventService;
import events.service.PaymentRecoveryService;

@Component
public class EventScheduler {

    private final EventService eventService;
    private final Outbox outbox;
    private final PaymentRecoveryService paymentRecovery;

    public EventScheduler(EventService eventService, Outbox outbox, PaymentRecoveryService paymentRecovery) {
        this.eventService = eventService;
        this.outbox = outbox;
        this.paymentRecovery = paymentRecovery;
    }

    // Runs according to the configurable event-check schedule
    @Scheduled(cron = "${app.scheduling.event-check-cron}")
    public void checkExpiredEvents() {
        eventService.processExpiredEvents();
    }

    // Publishes event.cancelled messages a broker outage held back (events.outbox.relay-cron)
    @Scheduled(cron = "${events.outbox.relay-cron}")
    public void relayOutbox() {
        outbox.relay();
    }

    // Retries owed refunds and confirms payments whose answer was lost (events.payment.recovery-cron)
    @Scheduled(cron = "${events.payment.recovery-cron}")
    public void recoverPayments() {
        paymentRecovery.recover();
    }
}
