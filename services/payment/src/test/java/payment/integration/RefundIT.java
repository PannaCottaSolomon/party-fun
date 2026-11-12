package payment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static payment.integration.IntegrationClient.OBSERVER_QUEUE;
import static payment.integration.IntegrationClient.forPayment;
import static payment.integration.IntegrationClient.randomId;
import static payment.integration.IntegrationClient.routedAs;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import payment.integration.IntegrationClient.Message;
import payment.integration.IntegrationClient.Response;

/**
 * Black-box tests of the REST refund endpoints, with WireMock standing in for Stripe, including Stripe refusing a
 * refund, the refund DLQ ({@code payments.refunds.dlq}), a refund that Stripe reverses afterwards, and the Admin's
 * recovery through {@code /admin/refunds/...} (JWT with role ADMIN), which removes a DLQ message only after the
 * refund succeeded.
 *
 * <p>Stripe's side of each case is set up in WireMock: a PaymentIntent already refunded at Stripe
 * ({@link IntegrationClient#refundAtStripe}) makes the service's own refund get {@code charge_already_refunded}, a
 * permanent error; {@link IntegrationClient#stripeFailsRefundsTransiently} makes Stripe answer 500 a few times, which
 * the service retries with the same idempotency key.
 */
class RefundIT {

    private static IntegrationClient api;

    @BeforeAll
    static void connect() {
        api = IntegrationClient.get();
    }

    @Test
    void manualRefundIsIdempotent() {
        List<Map<String, Object>> paid = api.paidBooking(randomId(), randomId(), "pm_card_visa");
        Object bookingId = paid.get(0).get("bookingId");
        Object paymentId = paid.get(1).get("paymentId");

        Response first = api.adminPost("/payments/" + paymentId + "/refund");
        Response second = api.adminPost("/payments/" + paymentId + "/refund");

        assertThat(first.status()).isEqualTo(200);
        assertThat(first.field("outcome")).isEqualTo("REFUNDED");
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.field("outcome")).isEqualTo("ALREADY_REFUNDED");
        assertThat(second.field("stripeRefundId")).isEqualTo(first.field("stripeRefundId"));
        Map<String, Object> current = api.get("/payments/" + paymentId).map();
        assertThat(current.get("status")).isEqualTo("REFUNDED");
        assertThat(current.get("refundedAt")).isNotNull();
        assertThat(api.bookingStatus(bookingId)).isEqualTo("REFUNDED");

        // Payment emails the participant itself: notify.refund on the notifications exchange, exactly once.
        Predicate<Message> refunded = routedAs("notify.refund").and(forPayment(paymentId));
        api.waitFor(OBSERVER_QUEUE, refunded);
        IntegrationClient.sleep(1000);
        List<Message> emails = api.messages(OBSERVER_QUEUE, refunded);
        assertThat(emails).as("notify.refund must be published exactly once").hasSize(1);
        assertThat(emails.get(0).exchange()).isEqualTo("notifications");
        assertThat(emails.get(0).map())
                .containsEntry("notification_id", "refund-payment-" + paymentId)
                .containsEntry("user_id", paid.get(0).get("userId"))
                .containsEntry("event_title", "IT event " + paid.get(0).get("eventId"))
                .containsEntry("amount", 4000)
                .containsEntry("currency", "SGD")
                .containsEntry("registration_id", paid.get(1).get("eventBookingId"))
                .containsEntry("refund_id", first.field("stripeRefundId"));
    }

    @Test
    void unpaidPaymentCannotBeRefunded() {
        Map<String, Object> booking = api.createBooking(randomId(), randomId());
        Object paymentId = api.pay(booking.get("bookingId"), null).get("paymentId");

        Response response = api.adminPost("/payments/" + paymentId + "/refund");

        assertThat(response.status()).isEqualTo(409);
        assertThat(api.paymentStatus(paymentId)).isEqualTo("PENDING");
    }

    /** A paid payment whose charge was already refunded directly at Stripe: the service's refund will fail. */
    private Object paymentStripeWillRefuseToRefund() {
        Map<String, Object> payment = api.paidBooking(randomId(), randomId(), "pm_card_visa").get(1);
        api.refundAtStripe((String) payment.get("stripePaymentIntentId"));
        return payment.get("paymentId");
    }

    @Test
    void refundStripeRefusesIsNotRetriedAndGoesToTheDlq() {
        Object paymentId = paymentStripeWillRefuseToRefund();

        Response response = api.adminPost("/payments/" + paymentId + "/refund");

        assertThat(response.status()).isEqualTo(502);
        // charge_already_refunded is permanent: one attempt, no pointless retries.
        assertThat((String) response.field("message")).contains("after 1 attempts");
        assertThat(api.paymentStatus(paymentId)).isEqualTo("REFUND_FAILED");

        List<Message> dlq = api.waitForDlq(forPayment(paymentId));
        assertThat(dlq).hasSize(1);
        Map<String, Object> message = dlq.get(0).map();
        assertThat(message).containsEntry("trigger", "MANUAL").containsEntry("refundAmountCents", 4000)
                .containsEntry("retryCount", 1).containsEntry("errorCode", "charge_already_refunded");
        // Everything needed for Admin recovery.
        assertThat(message).extractingByKeys("paymentId", "bookingId", "eventId", "userId", "refundAmountCents",
                "stripePaymentIntentId", "failureReason", "failedAt").doesNotContainNull();
        // Application-managed routing: Payment published it to the refund dead-letter exchange itself.
        assertThat(dlq.get(0).exchange()).isEqualTo("payments.refund.dlx");
        assertThat(dlq.get(0).routingKey()).isEqualTo("payments.refunds.dlq");

        List<Message> alerts = api.waitFor(OBSERVER_QUEUE, routedAs("notify.admin_alert").and(forPayment(paymentId)));
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).map()).containsEntry("source", "payment-service");
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) alerts.get(0).map().get("details");
        assertThat(details).containsEntry("retryable", false).containsEntry("retries", 1)
                .containsEntry("stripe_error_code", "charge_already_refunded");
    }

    @Test
    void transientStripeErrorsAreRetriedWithTheSameIdempotencyKeyUntilTheRefundSucceeds() {
        Map<String, Object> payment = api.paidBooking(randomId(), randomId(), "pm_card_visa").get(1);
        String paymentIntentId = (String) payment.get("stripePaymentIntentId");
        api.stripeFailsRefundsTransiently(paymentIntentId, 2);

        Response response = api.adminPost("/payments/" + payment.get("paymentId") + "/refund");

        assertThat(response.status()).as(response.text()).isEqualTo(200);
        assertThat(response.field("outcome")).isEqualTo("REFUNDED");
        assertThat(api.paymentStatus(payment.get("paymentId"))).isEqualTo("REFUNDED");
        // 2 failed attempts + 1 that succeeded, all with the same idempotency key: one refund at Stripe.
        List<Map<String, Object>> requests = api.refundRequests(paymentIntentId);
        assertThat(requests).extracting(r -> ((Number) r.get("status")).intValue()).containsExactly(500, 500, 200);
        assertThat(requests).extracting(r -> r.get("idempotencyKey")).containsOnly(requests.get(0).get("idempotencyKey"));
        assertThat(api.stripeRefundCount(paymentIntentId)).isEqualTo(1);
    }

    @Test
    void bookingRefundEndpoint() {
        Map<String, Object> booking = api.createBooking(randomId(), randomId());
        Object bookingId = booking.get("bookingId");
        Object declinedId = api.pay(bookingId, "pm_card_visa_chargeDeclined").get("paymentId");
        Object paidId = api.pay(bookingId, "pm_card_visa").get("paymentId");

        Response response = api.adminPost("/payments/booking/" + bookingId + "/refund");

        assertThat(response.status()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) response.field("results");
        assertThat(results).extracting(r -> r.get("paymentId"), r -> r.get("outcome")).containsExactlyInAnyOrder(
                tuple(declinedId, "SKIPPED"),
                tuple(paidId, "REFUNDED"));
        assertThat(api.bookingStatus(bookingId)).isEqualTo("REFUNDED");

        Object unpaid = api.createBooking(randomId(), randomId()).get("bookingId");
        assertThat(api.adminPost("/payments/booking/" + unpaid + "/refund").status()).isEqualTo(409);
        assertThat(api.adminPost("/payments/booking/2147483000/refund").status()).isEqualTo(404);
    }

    // ---------------------------------------------------------------- Admin recovery of failed refunds

    /** A REFUND_FAILED payment with its message in the DLQ, because Stripe had already refunded it directly. */
    private Object failedRefundStripeAlreadyMade() {
        Object paymentId = paymentStripeWillRefuseToRefund();
        assertThat(api.adminPost("/payments/" + paymentId + "/refund").status()).isEqualTo(502);
        assertThat(api.waitForDlq(forPayment(paymentId))).hasSize(1);
        return paymentId;
    }

    @Test
    void adminSeesFailedRefundsAndTheDlqCount() {
        Object paymentId = failedRefundStripeAlreadyMade();

        Response count = api.adminGet("/admin/refunds/dlq/count");
        assertThat(count.status()).as(count.text()).isEqualTo(200);
        assertThat(count.map()).containsEntry("queue", "payments.refunds.dlq").containsEntry("source", "management-api");
        assertThat(((Number) count.field("total")).longValue()).isGreaterThanOrEqualTo(1);

        Response failed = api.adminGet("/admin/refunds/failed");
        assertThat(failed.status()).as(failed.text()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) failed.field("failedRefunds");
        Map<String, Object> row = rows.stream().filter(r -> paymentId.equals(r.get("paymentId"))).findFirst()
                .orElseThrow();
        assertThat(row).containsEntry("status", "REFUND_FAILED").containsEntry("amountCents", 4000)
                .containsEntry("refundAttempts", 1).containsEntry("dlqMessages", 1);
        assertThat((String) row.get("failureReason")).contains("charge_already_refunded");
        assertThat(row.get("failedAt")).isNotNull();
        // Listing does not remove anything.
        assertThat(api.dlq(forPayment(paymentId))).hasSize(1);
    }

    @Test
    void adminRecoveryFindsTheRefundStripeAlreadyMadeAndRemovesOnlyThatDlqMessage() {
        Object paymentId = failedRefundStripeAlreadyMade();
        Object otherId = failedRefundStripeAlreadyMade();

        Response recovered = api.adminPost("/admin/refunds/" + paymentId + "/retry");

        assertThat(recovered.status()).as(recovered.text()).isEqualTo(200);
        // Stripe already shows a succeeded refund: it is recorded, nothing is refunded twice.
        assertThat(recovered.map()).containsEntry("outcome", "REFUNDED").containsEntry("paymentStatus", "REFUNDED")
                .containsEntry("dlqMessagesFound", 1).containsEntry("dlqMessagesRemoved", 1);
        assertThat(api.paymentStatus(paymentId)).isEqualTo("REFUNDED");
        assertThat(api.dlq(forPayment(paymentId))).isEmpty();
        // Another payment's DLQ message is untouched.
        assertThat(api.dlq(forPayment(otherId))).hasSize(1);
        assertThat(api.paymentStatus(otherId)).isEqualTo("REFUND_FAILED");
        // The participant gets the refund email now.
        assertThat(api.waitFor(OBSERVER_QUEUE, routedAs("notify.refund").and(forPayment(paymentId)))).hasSize(1);

        // Recovering it again: already refunded, nothing sent to Stripe, nothing left to remove.
        Response again = api.adminPost("/admin/refunds/" + paymentId + "/retry");
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.map()).containsEntry("outcome", "ALREADY_REFUNDED").containsEntry("dlqMessagesRemoved", 0);
    }

    @Test
    void adminRecoveryChecksStripesActualStateAndNeverRefundsTwice() {
        // A refund.failed webhook marks the payment REFUND_FAILED (and puts it in the DLQ), but at Stripe the refund
        // actually succeeded: recovery must see that and record it, not refund a second time.
        Map<String, Object> payment = api.paidBooking(randomId(), randomId(), "pm_card_visa").get(1);
        Object paymentId = payment.get("paymentId");
        Response refund = api.adminPost("/payments/" + paymentId + "/refund");
        String refundId = (String) refund.field("stripeRefundId");
        api.webhook(IntegrationClient.stripeEvent("refund.failed", Map.of("id", refundId, "object", "refund",
                "status", "failed", "payment_intent", payment.get("stripePaymentIntentId"))));
        assertThat(api.paymentStatus(paymentId)).isEqualTo("REFUND_FAILED");
        assertThat(api.waitForDlq(forPayment(paymentId))).hasSize(1);
        api.stripeShowsRefund((String) payment.get("stripePaymentIntentId"), refundId, "succeeded");

        Response recovered = api.adminPost("/admin/refunds/" + paymentId + "/retry");

        assertThat(recovered.status()).as(recovered.text()).isEqualTo(200);
        assertThat(recovered.map()).containsEntry("outcome", "REFUNDED").containsEntry("stripeRefundId", refundId)
                .containsEntry("dlqMessagesRemoved", 1);
        assertThat(api.paymentStatus(paymentId)).isEqualTo("REFUNDED");
        assertThat(api.dlq(forPayment(paymentId))).isEmpty();
        assertThat(api.stripeRefundCount((String) payment.get("stripePaymentIntentId"))).isEqualTo(1);
    }

    @Test
    void adminRecoveryOnlyAppliesToRefundFailedPayments() {
        Object paidId = api.paidBooking(randomId(), randomId(), "pm_card_visa").get(1).get("paymentId");

        assertThat(api.adminPost("/admin/refunds/" + paidId + "/retry").status()).isEqualTo(409);
        assertThat(api.adminPost("/admin/refunds/2147483000/retry").status()).isEqualTo(404);
        assertThat(api.paymentStatus(paidId)).isEqualTo("PAID");
    }

    @Test
    void onlyAnAdminCanUseTheAdminEndpoints() {
        for (String path : List.of("/admin/refunds/dlq/count", "/admin/refunds/failed")) {
            assertThat(api.get(path).status()).as(path).isEqualTo(401);
            assertThat(api.getWithRole("PARTICIPANT", path).status()).as(path).isEqualTo(403);
            assertThat(api.getWithRole("ORGANISER", path).status()).as(path).isEqualTo(403);
        }
        assertThat(api.servicePost("/admin/refunds/1/retry").status()).isEqualTo(403);
        // The operator key is the fallback while the JWT issuer can't issue ADMIN tokens.
        assertThat(api.operatorKeyPost("/admin/refunds/2147483000/retry").status()).isEqualTo(404);
    }

    @Test
    void paymentsRefundedIsNoLongerPublishedAndItsQueueIsNotDeclared() {
        // The target architecture has no payments.refunded round trip through the Event Service.
        assertThat(api.queue("event.payments.refunded").status()).isEqualTo(404);
        assertThat(api.publishAndReportRouted("party.events", "payments.refunded", java.util.Map.of("x", 1))).isFalse();
        // Payment declares exactly its two queues, and none of the removed processing queues.
        assertThat(api.queue("payments.refunds.requested").status()).isEqualTo(200);
        assertThat(api.queue("payments.refunds.dlq").status()).isEqualTo(200);
        for (String removed : List.of("payments.refunds.process", "payments.refunds.reprocess",
                "payment.refunds.process", "payment.refunds.reprocess", "payment.refunds.requested",
                "payment.refunds.dlq")) {
            assertThat(api.queue(removed).status()).as(removed).isEqualTo(404);
        }
        assertThat(api.publishAndReportRouted("party.events", "refund.reprocess.requested", Map.of("x", 1)))
                .isFalse();
    }

    @Test
    void refundOfABookingWithoutTheCheckoutSnapshotSendsNoRefundEmail() {
        Map<String, Object> booking = api.createBooking(randomId(), randomId());
        Object paymentId = api.pay(booking.get("bookingId"), "pm_card_visa").get("paymentId");

        assertThat(api.adminPost("/payments/" + paymentId + "/refund").status()).isEqualTo(200);

        // No email/event title known: an invalid notify.refund is never published (the refund still succeeds).
        IntegrationClient.sleep(2000);
        assertThat(api.messages(OBSERVER_QUEUE, routedAs("notify.refund").and(forPayment(paymentId)))).isEmpty();
        assertThat(api.paymentStatus(paymentId)).isEqualTo("REFUNDED");
    }

    @Test
    void refundThatStripeReversesLaterGoesFromRefundedToTheDlq() {
        // Like Stripe test card pm_card_refundFail: the refund succeeds, later Stripe sends refund.failed for it.
        Map<String, Object> payment = api.paidBooking(randomId(), randomId(), "pm_card_visa").get(1);
        Object paymentId = payment.get("paymentId");
        Response refund = api.adminPost("/payments/" + paymentId + "/refund");
        assertThat(refund.field("outcome")).isEqualTo("REFUNDED");
        String refundId = (String) refund.field("stripeRefundId");
        assertThat(refundId).startsWith("re_");

        Map<String, Object> failed = IntegrationClient.stripeEvent("refund.failed", Map.of(
                "id", refundId, "object", "refund", "status", "failed",
                "payment_intent", payment.get("stripePaymentIntentId")));
        Response webhook = api.webhook(failed);
        Response replay = api.webhook(failed);

        assertThat(webhook.field("action")).isEqualTo("PROCESSED");
        assertThat(replay.field("action")).isEqualTo("ALREADY_PROCESSED");
        Map<String, Object> current = api.get("/payments/" + paymentId).map();
        assertThat(current.get("status")).isEqualTo("REFUND_FAILED");
        assertThat(current.get("refundedAt")).isNull();
        List<Message> dlq = api.waitForDlq(forPayment(paymentId));
        assertThat(dlq).hasSize(1);
        assertThat(dlq.get(0).exchange()).isEqualTo("payments.refund.dlx");
        assertThat(dlq.get(0).map()).containsEntry("trigger", "WEBHOOK").containsEntry("stripeRefundId", refundId);
        assertThat(api.waitFor(OBSERVER_QUEUE, routedAs("notify.admin_alert").and(forPayment(paymentId)))).hasSize(1);

        // An outdated "succeeded" event for the refund that failed must not flip it back.
        Response stale = api.webhook(IntegrationClient.stripeEvent("refund.created", Map.of(
                "id", refundId, "object", "refund", "status", "succeeded",
                "payment_intent", payment.get("stripePaymentIntentId"))));
        assertThat(stale.field("action")).isEqualTo("IGNORED");
        assertThat(api.paymentStatus(paymentId)).isEqualTo("REFUND_FAILED");
    }

    @Test
    void refundEndpointsRequireAnApiKey() {
        Object paymentId = api.paidBooking(randomId(), randomId(), "pm_card_visa").get(1).get("paymentId");

        assertThat(api.post("/payments/" + paymentId + "/refund", null).status()).isEqualTo(401);
        assertThat(api.post("/payments/" + paymentId + "/retry-refund", null).status()).isEqualTo(401);
        assertThat(api.post("/payments/booking/1/refund", null).status()).isEqualTo(401);
        assertThat(api.paymentStatus(paymentId)).isEqualTo("PAID");
    }

    @Test
    void serviceKeyRefundsOnePaymentButCannotUseAdminEndpoints() {
        List<Map<String, Object>> paid = api.paidBooking(randomId(), randomId(), "pm_card_visa");
        Object bookingId = paid.get(0).get("bookingId");
        Object paymentId = paid.get(1).get("paymentId");

        // Admin-only: the service key is recognised but not allowed.
        assertThat(api.servicePost("/payments/booking/" + bookingId + "/refund").status()).isEqualTo(403);
        assertThat(api.servicePost("/payments/" + paymentId + "/retry-refund").status()).isEqualTo(403);
        assertThat(api.paymentStatus(paymentId)).isEqualTo("PAID");

        // The Event Service's compensation refund.
        Response refund = api.servicePost("/payments/" + paymentId + "/refund");
        assertThat(refund.status()).as(refund.text()).isEqualTo(200);
        assertThat(refund.map()).containsEntry("outcome", "REFUNDED");
        assertThat(api.paymentStatus(paymentId)).isEqualTo("REFUNDED");
    }

    @Test
    void twoStepBookingWithTheSnapshotAlsoGetsTheRefundEmail() {
        // The Event Service's current flow (POST /bookings, then POST /payments) plus the optional snapshot fields.
        Response booking = api.post("/bookings", Map.of("userId", 1, "eventId", randomId(), "quantity", 1,
                "totalAmount", 15.00, "participantEmail", "bob@example.com", "eventTitle", "Two-step Party"));
        assertThat(booking.status()).as(booking.text()).isEqualTo(201);
        Object bookingId = booking.map().get("bookingId");
        Response payment = api.post("/payments", Map.of("bookingId", bookingId, "paymentMethodId", "pm_card_visa"));
        assertThat(payment.status()).as(payment.text()).isEqualTo(201);
        Object paymentId = payment.map().get("paymentId");

        assertThat(api.servicePost("/payments/" + paymentId + "/refund").status()).isEqualTo(200);

        List<Message> emails = api.waitFor(OBSERVER_QUEUE, routedAs("notify.refund").and(forPayment(paymentId)));
        assertThat(emails).hasSize(1);
        assertThat(emails.get(0).map())
                .containsEntry("user_id", 1)
                .containsEntry("event_title", "Two-step Party")
                .containsEntry("amount", 1500)
                .containsEntry("registration_id", String.valueOf(bookingId));
    }
}
