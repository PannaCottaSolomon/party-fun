package auth.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Feature switches, set by environment variables. The frontend reads the ones it needs from {@code GET /auth/config},
 * so each switch is set in one place only.
 *
 * <p>{@code EMAIL_VERIFICATION_ENABLED} (default {@code false} for now):
 * <ul>
 *   <li>{@code true}: a new account must enter the code Cognito emails before it can log in, and
 *       {@code POST /user/me} only accepts a verified email.</li>
 *   <li>{@code false}: the frontend skips the code: {@code POST /auth/confirm-sign-up} confirms the new account and
 *       marks its email verified, and {@code POST /user/me} does not check verification. Anyone can then sign up
 *       with an email address they don't own, so turn it on before real users sign up.</li>
 * </ul>
 */
@Component
public class FeatureFlags {

    private final boolean emailVerification;

    public FeatureFlags(@Value("${auth.features.email-verification:false}") boolean emailVerification) {
        this.emailVerification = emailVerification;
    }

    public boolean emailVerification() {
        return emailVerification;
    }
}
