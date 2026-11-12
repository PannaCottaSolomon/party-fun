package auth.dto;

import java.time.Instant;
import java.util.UUID;

import auth.entity.UserAccount;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Profile views of an account (formerly user-service's API). Passwords live only
 * in Cognito, so no profile endpoint can ever return one.
 */
public class UserDto {

    public static class Response {
        private UUID id;
        private String email;
        private String fullName;
        private UserAccount.Role role;
        private String phoneNumber;
        private Instant createdAt;
        private Instant updatedAt;

        public Response() {}

        public Response(UUID id, String email, String fullName, UserAccount.Role role,
                        String phoneNumber, Instant createdAt, Instant updatedAt) {
            this.id = id;
            this.email = email;
            this.fullName = fullName;
            this.role = role;
            this.phoneNumber = phoneNumber;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }

        public static Response fromEntity(UserAccount user) {
            return new Response(user.getId(), user.getEmail(), user.getFullName(), user.getRole(),
                    user.getPhoneNumber(), user.getCreatedAt(), user.getUpdatedAt());
        }

        public UUID getId() { return id; }
        public void setId(UUID id) { this.id = id; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getFullName() { return fullName; }
        public void setFullName(String fullName) { this.fullName = fullName; }
        public UserAccount.Role getRole() { return role; }
        public void setRole(UserAccount.Role role) { this.role = role; }
        public String getPhoneNumber() { return phoneNumber; }
        public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
        public Instant getCreatedAt() { return createdAt; }
        public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
        public Instant getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    }

    /**
     * First-time profile creation ({@code POST /user/me}) after signing up in Cognito through the frontend. The
     * email is not part of the request: it is read from Cognito, where it was verified.
     */
    public static class OnboardRequest {
        @NotBlank
        private String fullName;

        @NotNull
        private UserAccount.Role role;

        // Optional profile field.
        private String phoneNumber;

        public OnboardRequest() {}

        public OnboardRequest(String fullName, UserAccount.Role role, String phoneNumber) {
            this.fullName = fullName;
            this.role = role;
            this.phoneNumber = phoneNumber;
        }

        public String getFullName() { return fullName; }
        public void setFullName(String fullName) { this.fullName = fullName; }
        public UserAccount.Role getRole() { return role; }
        public void setRole(UserAccount.Role role) { this.role = role; }
        public String getPhoneNumber() { return phoneNumber; }
        public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
    }

    /** Partial profile update. Email, password and role are deliberately not updatable here. */
    public static class UpdateRequest {
        private String fullName;
        private String phoneNumber;

        public UpdateRequest() {}

        public UpdateRequest(String fullName, String phoneNumber) {
            this.fullName = fullName;
            this.phoneNumber = phoneNumber;
        }

        public String getFullName() { return fullName; }
        public void setFullName(String fullName) { this.fullName = fullName; }
        public String getPhoneNumber() { return phoneNumber; }
        public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
    }
}
