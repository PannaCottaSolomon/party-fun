package payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import payment.TestData;
import payment.dto.BookingResponse;
import payment.dto.CreateBookingRequest;
import payment.entity.Booking;
import payment.entity.BookingStatus;
import payment.exception.InvalidRequestException;
import payment.exception.NotFoundException;
import payment.repository.BookingRepository;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private BookingRepository bookingRepository;

    @InjectMocks
    private BookingService bookingService;

    @Test
    void createBookingStartsPending() {
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> {
            Booking saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "bookingId", 11);
            return saved;
        });

        BookingResponse response = bookingService.create(
                new CreateBookingRequest(1, 2, 2, new BigDecimal("40.00")));

        assertThat(response.bookingId()).isEqualTo(11);
        assertThat(response.status()).isEqualTo(BookingStatus.PENDING);
        assertThat(response.userId()).isEqualTo(1);
        assertThat(response.eventId()).isEqualTo(2);
        assertThat(response.totalAmount()).isEqualByComparingTo("40.00");
        assertThat(response.currency()).isEqualTo("SGD");
    }

    @Test
    void createBookingStoresTheOptionalNotificationSnapshot() {
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));

        bookingService.create(new CreateBookingRequest(1, 2, 1, new BigDecimal("20.00"), " alice@example.com ",
                "Alice", "Rooftop Party"));

        ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).save(saved.capture());
        assertThat(saved.getValue().getParticipantEmail()).isEqualTo("alice@example.com");
        assertThat(saved.getValue().getParticipantName()).isEqualTo("Alice");
        assertThat(saved.getValue().getEventTitle()).isEqualTo("Rooftop Party");
    }

    @Test
    void invalidQuantityIsRejected() {
        assertThatThrownBy(() -> bookingService.create(new CreateBookingRequest(1, 2, 0, new BigDecimal("40.00"))))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("quantity");
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void nonPositiveAmountIsRejected() {
        assertThatThrownBy(() -> bookingService.create(new CreateBookingRequest(1, 2, 1, BigDecimal.ZERO)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("totalAmount");
    }

    @Test
    void getReturnsBooking() {
        when(bookingRepository.findById(5))
                .thenReturn(Optional.of(TestData.booking(5, BookingStatus.CONFIRMED, 1, 2, "25.00")));

        BookingResponse response = bookingService.get(5);

        assertThat(response.bookingId()).isEqualTo(5);
        assertThat(response.status()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void getUnknownBookingThrowsNotFound() {
        when(bookingRepository.findById(99)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.get(99))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Booking 99 not found");
    }

    @Test
    void listAllReturnsEveryBookingNewestFirst() {
        when(bookingRepository.findAllByOrderByBookingIdDesc()).thenReturn(List.of(
                TestData.booking(9, BookingStatus.PENDING, 3, 4, "10.00"),
                TestData.booking(8, BookingStatus.REFUNDED, 1, 2, "20.00")));

        assertThat(bookingService.listAll()).extracting(BookingResponse::bookingId).containsExactly(9, 8);
    }

    @Test
    void listByUser() {
        when(bookingRepository.findByUserIdOrderByBookingIdDesc(1)).thenReturn(List.of(
                TestData.booking(2, BookingStatus.PENDING, 1, 3, "10.00"),
                TestData.booking(1, BookingStatus.CONFIRMED, 1, 4, "20.00")));

        assertThat(bookingService.listByUser(1)).extracting(BookingResponse::bookingId).containsExactly(2, 1);
    }
}
