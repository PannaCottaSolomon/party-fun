package events.client;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/*  Synchronous HTTP client for the payment microservice (base URL: events.payment.base-url).

    The payment service owns its own bookings and payments, so charging one of our bookings takes two calls:
    POST /bookings creates the payment-side booking (amount in SGD), then POST /payments charges it through Stripe.

    Every call has a connect and a read timeout, so a slow payment service cannot tie up request threads.
    Errors are translated into two exceptions so callers never handle raw HTTP errors:
      PaymentRejectedException     the payment service answered 4xx: a definite "no" (nothing was charged)
      PaymentUnavailableException  timeout, connection refused, 5xx or an unreadable answer: the outcome is unknown */
@Component
public class PaymentClient {

    // Request bodies (wire format of the payment service)
    // eventTitle lets the payment service name the event in the participant's refund email
    record CreateBookingRequest(Integer userId, Integer eventId, Integer quantity, BigDecimal totalAmount,
            String eventTitle) {
    }

    record CreatePaymentRequest(Integer bookingId, String paymentMethodId) {
    }

    // Response bodies: only the fields this service reads; anything else the payment service adds is ignored
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CreatedBooking(Integer bookingId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorBody(String message) {
    }

    // Header the payment service requires on POST /payments/{paymentId}/refund from another service
    static final String SERVICE_KEY_HEADER = "X-Service-Api-Key";

    private final RestClient restClient;
    private final String serviceApiKey;  // empty: no key configured, so the payment service refuses refunds (401)

    // Base URL, timeouts and service key from application.properties (tests point the base URL at WireMock).
    // Spring's own RestClient.Builder (not RestClient.builder()) is used so every call to Payment carries the trace
    // (traceparent header).
    @Autowired
    public PaymentClient(RestClient.Builder builder,
            @Value("${events.payment.base-url}") String baseUrl,
            @Value("${events.payment.connect-timeout}") Duration connectTimeout,
            @Value("${events.payment.read-timeout}") Duration readTimeout,
            @Value("${events.payment.service-api-key}") String serviceApiKey) {
        this.restClient = builder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory(connectTimeout, readTimeout))
                .build();
        this.serviceApiKey = serviceApiKey == null ? "" : serviceApiKey.trim();
    }

    // Tests outside Spring: a plain builder (no tracing)
    public PaymentClient(String baseUrl, Duration connectTimeout, Duration readTimeout, String serviceApiKey) {
        this(RestClient.builder(), baseUrl, connectTimeout, readTimeout, serviceApiKey);
    }

    // POST /bookings: creates the payment-side booking for an order (quantity tickets, totalAmount in SGD for all of
    // them) and returns its bookingId
    public Integer createBooking(Integer userId, Integer eventId, int quantity, BigDecimal totalAmount,
            String eventTitle) {
        CreatedBooking created = call(() -> restClient.post()
                .uri("/bookings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateBookingRequest(userId, eventId, quantity, totalAmount, eventTitle))
                .retrieve()
                .body(CreatedBooking.class));
        if (created == null || created.bookingId() == null) {
            throw new PaymentUnavailableException("The payment service returned no booking id");
        }
        return created.bookingId();
    }

    // POST /payments: charges a payment-side booking. A declined card is NOT an exception: the payment service
    // answers 201 with status FAILED and a failureReason.
    public PaymentResponse createPayment(Integer paymentBookingId, String paymentMethodId) {
        PaymentResponse payment = call(() -> restClient.post()
                .uri("/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreatePaymentRequest(paymentBookingId, paymentMethodId))
                .retrieve()
                .body(PaymentResponse.class));
        if (payment == null || payment.paymentId() == null || payment.status() == null) {
            throw new PaymentUnavailableException("The payment service returned an incomplete payment");
        }
        return payment;
    }

    // GET /payments/booking/{bookingId}: every payment of a payment-side booking, oldest first
    public List<PaymentResponse> findPayments(Integer paymentBookingId) {
        PaymentResponse[] payments = call(() -> restClient.get()
                .uri("/payments/booking/{bookingId}", paymentBookingId)
                .retrieve()
                .body(PaymentResponse[].class));
        return payments == null ? List.of() : Arrays.asList(payments);
    }

    // POST /payments/{paymentId}/refund: refunds one payment (the payment service retries Stripe itself, and
    // refunding an already refunded payment is a no-op). Authenticated with the service key; the payment service
    // answers 502 when Stripe kept failing (it then owns the refund: REFUND_FAILED, its refund DLQ, admin alert).
    public RefundResponse refund(Integer paymentId) {
        RefundResponse refund = call(() -> restClient.post()
                .uri("/payments/{paymentId}/refund", paymentId)
                .headers(headers -> {
                    if (!serviceApiKey.isEmpty()) {
                        headers.set(SERVICE_KEY_HEADER, serviceApiKey);
                    }
                })
                .retrieve()
                .body(RefundResponse.class));
        if (refund == null) {
            throw new PaymentUnavailableException("The payment service returned no refund result");
        }
        return refund;
    }

    // Runs one HTTP call and translates its failures (see the class comment)
    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (HttpClientErrorException exception) {
            throw new PaymentRejectedException(exception.getStatusCode().value(), reason(exception));
        } catch (RestClientException exception) {
            // The most specific cause names the real problem (e.g. SocketTimeoutException: Read timed out)
            throw new PaymentUnavailableException(
                    "The payment service did not answer: " + exception.getMostSpecificCause(), exception);
        }
    }

    // The "message" of the payment service's JSON error body, or the HTTP status if the body is unreadable
    private static String reason(RestClientResponseException exception) {
        try {
            ErrorBody body = exception.getResponseBodyAs(ErrorBody.class);
            if (body != null && body.message() != null && !body.message().isBlank()) {
                return body.message();
            }
        } catch (RuntimeException unreadableBody) {
            // fall through to the status line
        }
        return exception.getStatusCode().toString();
    }

    // Buffered, so every request carries a Content-Length header instead of a chunked body (the JSON bodies are
    // tiny, and some servers and proxies reject chunked requests)
    private static ClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return new BufferingClientHttpRequestFactory(factory);
    }
}
