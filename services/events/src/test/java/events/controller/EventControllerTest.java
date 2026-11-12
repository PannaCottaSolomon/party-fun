package events.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import events.client.PaymentUnavailableException;
import events.config.SecurityConfig;
import events.dto.EventDto;
import events.entity.Booking;
import events.entity.BookingStatus;
import events.exception.BookingNotFoundException;
import events.exception.BookingNotPayableException;
import events.exception.DuplicateBookingException;
import events.exception.EventFullException;
import events.exception.EventNotFoundException;
import events.exception.EventNotOpenException;
import events.exception.InvalidRequestException;
import events.service.EventService;

/**
 * Web-layer tests for {@link EventController} and {@link EventExceptionHandler}: routes, parameters, status codes
 * and RFC 9457 problem bodies, and which Cognito groups may call each endpoint (the URL rules of
 * {@link SecurityConfig} and the controller's {@code @PreAuthorize} rules). The service and the JWT decoder are
 * mocked; no database, broker or Cognito user pool is needed.
 */
@WebMvcTest(EventController.class)
@Import(SecurityConfig.class)
class EventControllerTest {

    // Bearer tokens understood by the mocked decoder: each becomes a JWT whose cognito:groups claim holds the
    // token's own name, except NO_GROUPS (no groups claim) and FORGED (rejected, like a token with a bad signature)
    private static final String ADMIN = "admin";
    private static final String ORGANISER = "organiser";
    private static final String PARTICIPANT = "participant";
    private static final String GUEST = "guest";  // a group this API does not know
    private static final String NO_GROUPS = "no-groups";
    private static final String FORGED = "forged";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EventService eventService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void decodeTestTokens() {
        when(jwtDecoder.decode(anyString())).thenAnswer(invocation -> {
            String token = invocation.getArgument(0);
            if (token.equals(FORGED)) {
                throw new BadJwtException("Signed JWT rejected: Invalid signature");
            }
            Jwt.Builder jwt = Jwt.withTokenValue(token).header("alg", "RS256").subject("user-" + token);
            if (!token.equals(NO_GROUPS)) {
                jwt.claim("cognito:groups", List.of(token));
            }
            return jwt.build();
        });
    }

    @Test
    void createReturns201WithTheSavedEvent() throws Exception {
        EventDto saved = new EventDto();
        saved.setId(1);
        saved.setTitle("Party");
        saved.setCurrency("SGD");
        when(eventService.create(any(EventDto.class))).thenReturn(saved);

        mockMvc.perform(post("/events").with(bearer(ORGANISER)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Party\",\"capacity\":10,\"ticketPrice\":15.00}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.currency").value("SGD"));
    }

    @Test
    void listGetAndPatchReturn200() throws Exception {
        EventDto event = new EventDto();
        event.setId(1);
        when(eventService.listAll()).thenReturn(List.of(event));
        when(eventService.get(1)).thenReturn(event);
        when(eventService.update(eq(1), any(EventDto.class))).thenReturn(event);

        mockMvc.perform(get("/events").with(bearer(PARTICIPANT)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(1));
        mockMvc.perform(get("/events/1").with(bearer(PARTICIPANT))).andExpect(status().isOk());
        mockMvc.perform(patch("/events/1").with(bearer(ORGANISER)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"location\":\"B\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void cancelReturns200AndDeleteReturns204() throws Exception {
        when(eventService.cancel(1)).thenReturn(new EventDto());

        mockMvc.perform(post("/events/1/cancel").with(bearer(ORGANISER))).andExpect(status().isOk());
        mockMvc.perform(delete("/events/1").with(bearer(ORGANISER))).andExpect(status().isNoContent());
        verify(eventService).delete(1);
    }

    @Test
    void signupPassesUserAndPaymentMethodAndReturns201WithOneBookingByDefault() throws Exception {
        when(eventService.signUp(1, 42, "pm_card_visa", 1)).thenReturn(List.of(booking(BookingStatus.CONFIRMED)));

        mockMvc.perform(post("/events/1/signup").with(bearer(PARTICIPANT))
                        .param("userId", "42").param("paymentMethodId", "pm_card_visa"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$[0].paymentBookingId").value(7));
    }

    @Test
    void signupWithAQuantityReturnsOneBookingPerTicket() throws Exception {
        when(eventService.signUp(1, 42, "pm_card_visa", 3)).thenReturn(List.of(booking(BookingStatus.CONFIRMED),
                booking(BookingStatus.CONFIRMED), booking(BookingStatus.PENDING)));

        mockMvc.perform(post("/events/1/signup").with(bearer(PARTICIPANT))
                        .param("userId", "42").param("paymentMethodId", "pm_card_visa").param("quantity", "3"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[2].status").value("PENDING"));
    }

    @Test
    void signupWithANonNumericQuantityIsABadRequest() throws Exception {
        mockMvc.perform(post("/events/1/signup").with(bearer(PARTICIPANT))
                        .param("userId", "42").param("quantity", "two"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(eventService);
    }

    @Test
    void signupWithoutPaymentMethodPassesNull() throws Exception {
        when(eventService.signUp(eq(1), eq(42), isNull(), eq(1))).thenReturn(List.of(booking(BookingStatus.CONFIRMED)));

        mockMvc.perform(post("/events/1/signup").with(bearer(PARTICIPANT)).param("userId", "42"))
                .andExpect(status().isCreated());
    }

    @Test
    void listsAUsersBookings() throws Exception {
        when(eventService.listBookingsForUser(42)).thenReturn(List.of(booking(BookingStatus.CONFIRMED),
                booking(BookingStatus.EXPIRED)));

        mockMvc.perform(get("/events/bookings").with(bearer(PARTICIPANT)).param("userId", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].status").value("EXPIRED"));
    }

    @Test
    void paymentRetryReturns200() throws Exception {
        when(eventService.payForBooking(1, 5, "pm_card_visa")).thenReturn(booking(BookingStatus.CONFIRMED));

        mockMvc.perform(post("/events/1/bookings/5/payment").with(bearer(PARTICIPANT))
                        .param("paymentMethodId", "pm_card_visa"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    static Stream<Arguments> errors() {
        return Stream.of(
                Arguments.of(new EventNotFoundException(1), 404, "Event 1 not found"),
                Arguments.of(new BookingNotFoundException(1, 5), 404, "Booking 5 not found for event 1"),
                Arguments.of(new EventNotOpenException(1), 409, "Event 1 is not open for signups"),
                Arguments.of(new EventFullException(1), 409, "Event 1 has no remaining capacity"),
                Arguments.of(new DuplicateBookingException(1, 42), 409, "User 42 already has an active booking for event 1"),
                Arguments.of(new BookingNotPayableException(5, "it is CONFIRMED"), 409, "Booking 5 cannot be paid: it is CONFIRMED"),
                Arguments.of(new InvalidRequestException("Event title is required"), 400, "Event title is required"),
                Arguments.of(new PaymentUnavailableException("timeout"), 503, "The payment service is unavailable, please try again"),
                Arguments.of(new CannotAcquireLockException("lock timeout"), 503, "The event is busy, please try again"),
                Arguments.of(new DataAccessResourceFailureException("down"), 503, "The database is unavailable, please try again later"));
    }

    @ParameterizedTest
    @MethodSource("errors")
    void errorsBecomeProblemDetails(RuntimeException exception, int status, String detail) throws Exception {
        when(eventService.payForBooking(1, 5, "pm")).thenThrow(exception);

        mockMvc.perform(post("/events/1/bookings/5/payment").with(bearer(PARTICIPANT)).param("paymentMethodId", "pm"))
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.detail").value(detail));
    }

    // ---------- security: which groups may call which endpoint ----------

    // Browsing, signing up and paying: any participant, organiser or admin
    static Stream<String> participantEndpoints() {
        return Stream.of("GET /events", "GET /events/1", "POST /events/1/signup", "POST /events/1/bookings/5/payment");
    }

    // Managing events: organisers and admins only
    static Stream<String> organiserEndpoints() {
        return Stream.of("POST /events", "PATCH /events/1", "POST /events/1/cancel", "DELETE /events/1");
    }

    static Stream<String> allEndpoints() {
        return Stream.concat(participantEndpoints(), organiserEndpoints());
    }

    @ParameterizedTest
    @MethodSource("allEndpoints")
    void everyEndpointRejectsARequestWithoutATokenWith401(String endpoint) throws Exception {
        mockMvc.perform(request(endpoint)).andExpect(status().isUnauthorized());
        verifyNoInteractions(eventService);
    }

    @ParameterizedTest
    @MethodSource("allEndpoints")
    void organisersAndAdminsMayCallEveryEndpoint(String endpoint) throws Exception {
        mockMvc.perform(request(endpoint).with(bearer(ORGANISER))).andExpect(status().is2xxSuccessful());
        mockMvc.perform(request(endpoint).with(bearer(ADMIN))).andExpect(status().is2xxSuccessful());
    }

    @ParameterizedTest
    @MethodSource("participantEndpoints")
    void participantsMayBrowseSignUpAndPay(String endpoint) throws Exception {
        mockMvc.perform(request(endpoint).with(bearer(PARTICIPANT))).andExpect(status().is2xxSuccessful());
    }

    @ParameterizedTest
    @MethodSource("organiserEndpoints")
    void participantsMayNotCreateChangeCancelOrDeleteEvents(String endpoint) throws Exception {
        mockMvc.perform(request(endpoint).with(bearer(PARTICIPANT))).andExpect(status().isForbidden());
        verifyNoInteractions(eventService);
    }

    @ParameterizedTest
    @ValueSource(strings = { GUEST, NO_GROUPS })
    void aUserInNoKnownGroupIsForbidden(String token) throws Exception {
        mockMvc.perform(get("/events").with(bearer(token))).andExpect(status().isForbidden());
        verifyNoInteractions(eventService);
    }

    @Test
    void aTokenTheDecoderRejectsIsRejectedWith401() throws Exception {
        mockMvc.perform(get("/events").with(bearer(FORGED)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("invalid_token")));
        verifyNoInteractions(eventService);
    }

    // ---------- helpers ----------

    // A valid request to the endpoint named "METHOD path"
    private static MockHttpServletRequestBuilder request(String endpoint) {
        return switch (endpoint) {
            case "GET /events" -> get("/events");
            case "GET /events/1" -> get("/events/1");
            case "POST /events/1/signup" -> post("/events/1/signup").param("userId", "42");
            case "POST /events/1/bookings/5/payment" ->
                    post("/events/1/bookings/5/payment").param("paymentMethodId", "pm_card_visa");
            case "POST /events" ->
                    post("/events").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Party\",\"capacity\":10}");
            case "PATCH /events/1" ->
                    patch("/events/1").contentType(MediaType.APPLICATION_JSON).content("{\"location\":\"B\"}");
            case "POST /events/1/cancel" -> post("/events/1/cancel");
            case "DELETE /events/1" -> delete("/events/1");
            default -> throw new IllegalArgumentException("Unknown endpoint " + endpoint);
        };
    }

    // Sends the token in the Authorization header, as the frontend does with a Cognito access token
    private static RequestPostProcessor bearer(String token) {
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            return request;
        };
    }

    private static Booking booking(BookingStatus status) {
        Booking booking = new Booking();
        booking.setId(5);
        booking.setEventId(1);
        booking.setUserId(42);
        booking.setStatus(status);
        booking.setPaymentBookingId(7);
        return booking;
    }
}
