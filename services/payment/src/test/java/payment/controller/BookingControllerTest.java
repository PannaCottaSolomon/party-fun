package payment.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import payment.dto.BookingResponse;
import payment.entity.BookingStatus;
import payment.exception.NotFoundException;
import payment.service.BookingService;

@WebMvcTest({BookingController.class, HealthController.class})
class BookingControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private BookingService bookingService;

    private static BookingResponse booking(int id) {
        return new BookingResponse(id, null, 1, 2, null, 2, new BigDecimal("40.00"), "SGD", BookingStatus.PENDING,
                null, null);
    }

    @Test
    void health() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("payment"))
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void createBookingReturns201() throws Exception {
        when(bookingService.create(any())).thenReturn(booking(1));

        mvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1,\"eventId\":2,\"quantity\":2,\"totalAmount\":40.00}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bookingId").value(1))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void invalidQuantityIs400WithFieldErrors() throws Exception {
        mvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1,\"eventId\":2,\"quantity\":0,\"totalAmount\":40.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors.quantity").exists());
        verifyNoInteractions(bookingService);
    }

    @Test
    void missingFieldsAre400() throws Exception {
        mvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.userId").exists())
                .andExpect(jsonPath("$.fieldErrors.eventId").exists())
                .andExpect(jsonPath("$.fieldErrors.totalAmount").exists());
    }

    @Test
    void malformedJsonIs400() throws Exception {
        mvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request body is missing or is not valid JSON"));
    }

    @Test
    void unknownBookingIs404WithoutStackTrace() throws Exception {
        when(bookingService.get(99)).thenThrow(new NotFoundException("Booking 99 not found"));

        mvc.perform(get("/bookings/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Booking 99 not found"))
                .andExpect(jsonPath("$.path").value("/bookings/99"))
                .andExpect(content().string(not(containsString("Exception"))));
    }

    @Test
    void listAllBookings() throws Exception {
        when(bookingService.listAll()).thenReturn(List.of(booking(3), booking(2), booking(1)));

        mvc.perform(get("/bookings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].bookingId").value(3));
    }

    @Test
    void listByUser() throws Exception {
        when(bookingService.listByUser(1)).thenReturn(List.of(booking(2), booking(1)));

        mvc.perform(get("/bookings/user/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }
}
