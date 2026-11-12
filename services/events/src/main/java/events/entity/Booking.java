package events.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;


@Entity
@Table(name = "bookings")
public class Booking {

    // Primary key (PostgreSQL identity column)
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    // Who booked what (plain ids, no foreign-key relationships)
    private Integer userId;
    private Integer eventId;

    // The signup (order) this ticket belongs to: every ticket of one signup is paid by one charge and confirmed
    // together. Null on bookings made before orders existed, each of which is an order of its own.
    private String orderId;

    // Amount to charge, copied from the event's ticket price at signup time
    private BigDecimal totalAmount;
    private String currency;

    // Saga state, stored as the enum name (e.g. 'PENDING')
    @Enumerated(EnumType.STRING)
    private BookingStatus status;

    // Saga timestamps
    private LocalDateTime createdAt;    // when the place was reserved
    private LocalDateTime expiresAt;    // payment deadline (createdAt + 15 minutes)
    private LocalDateTime confirmedAt;  // set when payment succeeds
    private LocalDateTime failedAt;     // set when a payment attempt fails (booking stays PENDING)

    // Payment outcome details
    private Integer paymentBookingId;   // the matching booking in the payment service (created on the first charge)
    private String paymentReference;    // id of the successful payment in the payment service
    private String failureReason;       // why the last payment attempt failed

    // Compensation refund (the card was charged after the booking stopped being PENDING, see BookingRefundService).
    // Owed while refundRequiredAt is set and refundedAt is not; paymentReference then names the payment to refund.
    // Refunds of a cancelled event are not tracked here: the payment service handles them (event.cancelled).
    private LocalDateTime refundRequiredAt;  // when the refund became owed
    private LocalDateTime refundedAt;        // when the payment service took the refund over (refunded or its own)
    private String refundReference;          // how it was settled, e.g. REFUNDED or REFUND_FAILED (payment's DLQ)

    // No-args constructor required by JPA and Jackson
    public Booking() {
    }

    // Getters and setters
    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Integer getUserId() {
        return userId;
    }

    public void setUserId(Integer userId) {
        this.userId = userId;
    }

    public Integer getEventId() {
        return eventId;
    }

    public void setEventId(Integer eventId) {
        this.eventId = eventId;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public void setStatus(BookingStatus status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public LocalDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(LocalDateTime confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public LocalDateTime getFailedAt() {
        return failedAt;
    }

    public void setFailedAt(LocalDateTime failedAt) {
        this.failedAt = failedAt;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public Integer getPaymentBookingId() {
        return paymentBookingId;
    }

    public void setPaymentBookingId(Integer paymentBookingId) {
        this.paymentBookingId = paymentBookingId;
    }

    public String getPaymentReference() {
        return paymentReference;
    }

    public void setPaymentReference(String paymentReference) {
        this.paymentReference = paymentReference;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public LocalDateTime getRefundRequiredAt() {
        return refundRequiredAt;
    }

    public void setRefundRequiredAt(LocalDateTime refundRequiredAt) {
        this.refundRequiredAt = refundRequiredAt;
    }

    public LocalDateTime getRefundedAt() {
        return refundedAt;
    }

    public void setRefundedAt(LocalDateTime refundedAt) {
        this.refundedAt = refundedAt;
    }

    public String getRefundReference() {
        return refundReference;
    }

    public void setRefundReference(String refundReference) {
        this.refundReference = refundReference;
    }
}
