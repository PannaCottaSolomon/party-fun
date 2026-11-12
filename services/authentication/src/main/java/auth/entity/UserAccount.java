package auth.entity;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * A user account's profile: email, role, name and phone in one row. The login identity (password, sessions) lives
 * in the Cognito user pool, not here. The id is the Cognito user's {@code sub}, so the {@code sub} claim of an
 * access token is this row's primary key.
 *
 * <p>The role is kept here for profile reads and the {@code user.*} events. For authorisation the Cognito group
 * (the token's {@code cognito:groups} claim) is the source of truth.
 */
@Entity
@Table(name = "user_accounts")
public class UserAccount {

    // Assigned from Cognito's sub, never generated here.
    @Id
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String fullName;

    // PARTICIPANT or ORGANISER are chosen by the user (POST /user/me). ADMIN is a platform-operator
    // role that is never chosen by a user: the single admin account is seeded at
    // startup (see AdminBootstrap) and does not publish a "user.created" event.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    private String phoneNumber;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant updatedAt;

    @PrePersist
    @SuppressWarnings("unused")
    void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    @SuppressWarnings("unused")
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /** Each role is a Cognito group of the same name in lower case (see {@link #groupName()}). */
    public enum Role {
        PARTICIPANT,
        ORGANISER,
        ADMIN;

        /** The Cognito group for this role: {@code participant}, {@code organiser} or {@code admin}. */
        public String groupName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
