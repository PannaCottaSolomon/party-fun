package auth.config;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import auth.cognito.CognitoGateway;
import auth.entity.UserAccount;
import auth.repository.UserAccountRepository;

/**
 * Prepares the Cognito user pool on startup: creates any missing role group ({@code participant},
 * {@code organiser}, {@code admin}) and seeds the single platform ADMIN account.
 *
 * <p>ADMIN can never be chosen by a user (see {@code UserService#onboard}); the one admin is provisioned here from
 * configuration so it cannot be obtained through any endpoint. The seed is:
 * <ul>
 *   <li><b>idempotent</b> — an existing Cognito user with that email is reused (its password is never reset) and
 *       the profile row is only inserted once, so restarts and redeploys never duplicate it;</li>
 *   <li><b>fail-closed</b> — if no admin password is configured the seed is skipped, so a real environment that
 *       forgot to set one does not get a blank/guessable admin;</li>
 *   <li><b>isolated</b> — it does not publish {@code user.created}; the admin is a platform operator, not a
 *       participant/organiser other services should know about.</li>
 * </ul>
 *
 * <p>The admin logs in through the normal {@code POST /auth/login} and receives a Cognito access token whose
 * {@code cognito:groups} contains {@code admin}. The password must meet the user pool's password policy (Cognito
 * default: 8+ characters with upper case, lower case, a digit and a symbol). A Cognito failure is logged and does
 * not stop the service from starting. Set {@code ADMIN_BOOTSTRAP_EMAIL} / {@code ADMIN_BOOTSTRAP_PASSWORD} (and
 * optionally {@code ADMIN_BOOTSTRAP_NAME}, and {@code ADMIN_BOOTSTRAP_USERNAME} for a pool that signs in with
 * usernames) in every real environment.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserAccountRepository userAccountRepository;
    private final CognitoGateway cognito;

    private final String adminEmail;
    private final String adminUsername;
    private final String adminPassword;
    private final String adminFullName;

    public AdminBootstrap(UserAccountRepository userAccountRepository,
                          CognitoGateway cognito,
                          @Value("${auth.admin.email:}") String adminEmail,
                          @Value("${auth.admin.username:}") String adminUsername,
                          @Value("${auth.admin.password:}") String adminPassword,
                          @Value("${auth.admin.full-name:Platform Admin}") String adminFullName) {
        this.userAccountRepository = userAccountRepository;
        this.cognito = cognito;
        this.adminEmail = adminEmail == null ? "" : adminEmail.trim().toLowerCase();
        // Pools that sign in with email use the email as the username; pools that sign in with a username need a
        // plain one (Cognito refuses an email-shaped username there).
        this.adminUsername = adminUsername == null || adminUsername.isBlank() ? this.adminEmail
                : adminUsername.trim().toLowerCase();
        this.adminPassword = adminPassword == null ? "" : adminPassword;
        this.adminFullName = adminFullName;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!cognito.isConfigured()) {
            log.warn("Cognito bootstrap skipped: COGNITO_USER_POOL_ID / COGNITO_CLIENT_ID are not configured");
            return;
        }
        try {
            cognito.ensureGroups();
            seedAdmin();
        } catch (RuntimeException e) {
            log.error("Cognito bootstrap failed; registration or the ADMIN login may not work until it is fixed", e);
        }
    }

    private void seedAdmin() {
        if (adminEmail.isEmpty() || adminPassword.isEmpty()) {
            log.info("Admin bootstrap skipped: auth.admin.email / auth.admin.password are not configured");
            return;
        }
        if (userAccountRepository.existsByEmail(adminEmail)) {
            log.info("Admin bootstrap skipped: an account already exists for {}", adminEmail);
            return;
        }

        UUID id = cognito.ensureUser(adminUsername, adminEmail, adminPassword, UserAccount.Role.ADMIN);

        UserAccount admin = new UserAccount();
        admin.setId(id);
        admin.setEmail(adminEmail);
        admin.setFullName(adminFullName);
        admin.setRole(UserAccount.Role.ADMIN);
        userAccountRepository.save(admin);

        log.info("Seeded ADMIN account {} (Cognito username {})", adminEmail, adminUsername);
    }
}
