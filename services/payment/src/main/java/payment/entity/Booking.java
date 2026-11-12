package payment.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import payment.exception.InvalidStateException;

/**
 * A participant's booking for an event. {@code userId} and {@code eventId} are IDs owned by the
 * User and Event services: plain columns, never database foreign keys.
 *
 * <p>Bookings created through {@code POST /payments/checkout} also keep the Event Service's own booking id
 * ({@code eventBookingId}, unique, used for idempotency) and a snapshot of the participant's email/name and the
 * event title, taken at payment time. The snapshot is what lets this service send {@code notify.refund} itself;
 * it is never used for anything else.
 */
@Entity
@Table(name = "booking")
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "booking_id")
    private Integer bookingId;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(name = "event_id", nullable = false)
    private Integer eventId;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    // Stored as VARCHAR (not a MySQL ENUM) to match the existing table.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 20)
    private BookingStatus status;

    /** The Event Service's booking id (checkout bookings only). Unique: a second checkout reuses this booking. */
    @Column(name = "event_booking_id", length = 64, unique = true)
    private String eventBookingId;

    @Column(name = "participant_email", length = 255)
    private String participantEmail;

    @Column(name = "participant_name", length = 255)
    private String participantName;

    @Column(name = "event_title", length = 255)
    private String eventTitle;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    protected Booking() {
        // for JPA
    }

    public Booking(Integer userId, Integer eventId, Integer quantity, BigDecimal totalAmount) {
        this.userId = userId;
        this.eventId = eventId;
        this.quantity = quantity;
        this.totalAmount = totalAmount;
        this.status = BookingStatus.PENDING;
    }

    /** A checkout booking: also records the Event Service booking id and the notification snapshot. */
    public static Booking forCheckout(String eventBookingId, Integer userId, Integer eventId, Integer quantity,
                                      BigDecimal totalAmount) {
        Booking booking = new Booking(userId, eventId, quantity, totalAmount);
        booking.eventBookingId = eventBookingId;
        return booking;
    }

    /** Fills in participant/event details for notifications; values already stored are kept unless replaced. */
    public void recordNotificationDetails(String email, String name, String title) {
        if (email != null && !email.isBlank()) {
            participantEmail = email.trim();
        }
        if (name != null && !name.isBlank()) {
            participantName = name.trim();
        }
        if (title != null && !title.isBlank()) {
            eventTitle = title.trim();
        }
    }

    /** Moves to {@code next}, or throws {@link InvalidStateException} if the lifecycle forbids it. */
    public void transitionTo(BookingStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new InvalidStateException(
                    "Booking %d cannot change from %s to %s".formatted(bookingId, status, next));
        }
        status = next;
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Integer getBookingId() {
        return bookingId;
    }

    public Integer getUserId() {
        return userId;
    }

    public Integer getEventId() {
        return eventId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public String getEventBookingId() {
        return eventBookingId;
    }

    public String getParticipantEmail() {
        return participantEmail;
    }

    public String getParticipantName() {
        return participantName;
    }

    public String getEventTitle() {
        return eventTitle;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
