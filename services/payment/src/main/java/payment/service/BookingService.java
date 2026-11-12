package payment.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import payment.dto.BookingResponse;
import payment.dto.CreateBookingRequest;
import payment.entity.Booking;
import payment.exception.InvalidRequestException;
import payment.exception.NotFoundException;
import payment.repository.BookingRepository;

@Service
public class BookingService {

    private final BookingRepository bookingRepository;

    public BookingService(BookingRepository bookingRepository) {
        this.bookingRepository = bookingRepository;
    }

    /** Creates a PENDING booking. userId/eventId are external IDs and are not checked against other services. */
    @Transactional
    public BookingResponse create(CreateBookingRequest request) {
        if (request.quantity() == null || request.quantity() <= 0) {
            throw new InvalidRequestException("quantity must be greater than zero");
        }
        if (request.totalAmount() == null || request.totalAmount().signum() <= 0) {
            throw new InvalidRequestException("totalAmount must be greater than zero");
        }
        Booking booking = new Booking(request.userId(), request.eventId(), request.quantity(), request.totalAmount());
        booking.recordNotificationDetails(request.participantEmail(), request.participantName(), request.eventTitle());
        return BookingResponse.from(bookingRepository.save(booking));
    }

    @Transactional(readOnly = true)
    public BookingResponse get(Integer bookingId) {
        return bookingRepository.findById(bookingId)
                .map(BookingResponse::from)
                .orElseThrow(() -> new NotFoundException("Booking " + bookingId + " not found"));
    }

    /** All bookings, newest first. */
    @Transactional(readOnly = true)
    public List<BookingResponse> listAll() {
        return bookingRepository.findAllByOrderByBookingIdDesc().stream()
                .map(BookingResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> listByUser(Integer userId) {
        return bookingRepository.findByUserIdOrderByBookingIdDesc(userId).stream()
                .map(BookingResponse::from)
                .toList();
    }
}
