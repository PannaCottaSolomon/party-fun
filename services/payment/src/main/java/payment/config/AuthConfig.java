package payment.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import payment.exception.ForbiddenException;
import payment.exception.UnauthorizedException;

/**
 * Role-based access to the Admin and refund endpoints.
 *
 * <ul>
 *   <li><b>Admin login</b> ({@code Authorization: Bearer <token>}): an Amazon Cognito access token of a user in the
 *       {@code admin} group, as the frontend's Admin dashboard sends it through the API Gateway, verified against
 *       the user pool's public keys ({@link CognitoVerifier}). On when {@code COGNITO_USER_POOL_ID} is set.</li>
 *   <li><b>Admin JWT</b> ({@code Authorization: Bearer <token>}): issued by the party.fun JWT issuer with
 *       {@code role = ADMIN}, verified locally ({@link JwtVerifier}). A valid token with another role is refused
 *       (403), as is a Cognito token of a user outside the {@code admin} group.</li>
 *   <li>{@code X-Admin-Api-Key} = {@code payment_admin_api_key}: operator fallback for Admin endpoints (e.g. while
 *       the issuer cannot issue ADMIN tokens yet). Off when empty.</li>
 *   <li>{@code X-Service-Api-Key} = {@code payment_service_api_key}: internal service-to-service refunds (the Event
 *       Service's compensation refund). Accepted only on {@code POST /payments/{id}/refund}.</li>
 * </ul>
 *
 * <table>
 *   <caption>Who may call what</caption>
 *   <tr><th>Endpoint</th><th>Admin (JWT or Admin key)</th><th>Service key</th></tr>
 *   <tr><td>{@code /admin/**} (failed refunds, DLQ count, recovery)</td><td>yes</td><td>no (403)</td></tr>
 *   <tr><td>{@code POST /payments/{id}/retry-refund} (deprecated alias of the Admin retry)</td><td>yes</td>
 *       <td>no (403)</td></tr>
 *   <tr><td>{@code POST /payments/booking/{id}/refund}</td><td>yes</td><td>no (403)</td></tr>
 *   <tr><td>{@code POST /payments/{id}/refund}</td><td>yes</td><td>yes</td></tr>
 * </table>
 *
 * Missing or invalid credentials are 401; valid credentials without the right role are 403. An endpoint for which no
 * credential at all is configured is disabled (403), never left open. Keys are compared in constant time; tokens and
 * keys are never logged or echoed back. The frontend's role toggle is never trusted: only a verified token counts.
 */
@Configuration(proxyBeanMethods = false)
public final class AuthConfig implements WebMvcConfigurer {

    public static final String ADMIN_HEADER = "X-Admin-Api-Key";
    public static final String SERVICE_HEADER = "X-Service-Api-Key";
    public static final String ADMIN_ROLE = "ADMIN";

    private static final Logger log = LoggerFactory.getLogger(AuthConfig.class);

    private final JwtVerifier jwt;
    private final CognitoVerifier cognito;
    private final String adminKey;
    private final String serviceKey;

    public AuthConfig(@Value("${auth.jwt.secret:}") String jwtSecret,
                      @Value("${auth.jwt.issuer:}") String jwtIssuer,
                      @Value("${auth.cognito.region:}") String cognitoRegion,
                      @Value("${auth.cognito.user-pool-id:}") String cognitoUserPoolId,
                      @Value("${payment.admin-api-key:}") String adminKey,
                      @Value("${payment.service-api-key:}") String serviceKey) {
        this.jwt = new JwtVerifier(jwtSecret, jwtIssuer);
        this.cognito = new CognitoVerifier(cognitoRegion, cognitoUserPoolId);
        this.adminKey = adminKey == null ? "" : adminKey.trim();
        this.serviceKey = serviceKey == null ? "" : serviceKey.trim();
        if (!this.adminKey.isEmpty() && this.adminKey.equals(this.serviceKey)) {
            // Otherwise every holder of the service key could call the Admin-only endpoints.
            throw new IllegalStateException("payment_admin_api_key and payment_service_api_key must be different");
        }
        if (!jwt.enabled() && !cognito.enabled()) {
            log.warn("Neither auth_jwt_secret nor COGNITO_USER_POOL_ID is set: no Admin token is accepted; Admin "
                    + "endpoints only accept the operator key (payment_admin_api_key){}",
                    this.adminKey.isEmpty() ? ", which is not set either" : "");
        }
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AccessInterceptor(jwt, cognito, adminKey, serviceKey, true))
                .addPathPatterns("/payments/*/refund");
        registry.addInterceptor(new AccessInterceptor(jwt, cognito, adminKey, serviceKey, false))
                .addPathPatterns("/admin/**", "/payments/*/retry-refund", "/payments/booking/*/refund");
    }

    /** Checks one group of endpoints: Admin only, or Admin or the internal service. */
    static final class AccessInterceptor implements HandlerInterceptor {

        private static final Logger log = LoggerFactory.getLogger(AccessInterceptor.class);
        private static final String BEARER = "Bearer ";

        private final JwtVerifier jwt;
        private final CognitoVerifier cognito;
        private final byte[] adminKey;
        private final byte[] serviceKey;
        private final boolean serviceAllowed;

        AccessInterceptor(JwtVerifier jwt, CognitoVerifier cognito, String adminKey, String serviceKey,
                          boolean serviceAllowed) {
            this.jwt = jwt;
            this.cognito = cognito;
            this.adminKey = adminKey.getBytes(StandardCharsets.UTF_8);
            this.serviceKey = serviceKey.getBytes(StandardCharsets.UTF_8);
            this.serviceAllowed = serviceAllowed;
        }

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                return true; // CORS preflight carries no credentials
            }
            boolean serviceConfigured = serviceAllowed && serviceKey.length > 0;
            if (!jwt.enabled() && !cognito.enabled() && adminKey.length == 0 && !serviceConfigured) {
                throw new ForbiddenException(serviceAllowed
                        ? "Refund endpoint is disabled: none of auth_jwt_secret, COGNITO_USER_POOL_ID,"
                                + " payment_admin_api_key or payment_service_api_key is configured"
                        : "Admin endpoints are disabled: none of auth_jwt_secret, COGNITO_USER_POOL_ID or"
                                + " payment_admin_api_key is configured");
            }

            String authorization = request.getHeader("Authorization");
            boolean bearer = authorization != null
                    && authorization.regionMatches(true, 0, BEARER, 0, BEARER.length());
            String token = bearer ? authorization.substring(BEARER.length()).trim() : null;
            if (token != null && cognito.issued(token)) {
                Claims claims = cognito.verify(token);
                if (CognitoVerifier.isAdmin(claims)) {
                    log.info("{} {} authorised for Admin {} (Cognito)", request.getMethod(), request.getRequestURI(),
                            claims.getSubject());
                    return true;
                }
                throw new ForbiddenException("This endpoint requires the ADMIN role");
            }
            if (token != null && jwt.enabled()) {
                Claims claims = jwt.verify(token);
                Object role = claims.get("role");
                if (ADMIN_ROLE.equals(role)) {
                    log.info("{} {} authorised for Admin {}", request.getMethod(), request.getRequestURI(),
                            claims.getSubject());
                    return true;
                }
                throw new ForbiddenException("This endpoint requires the ADMIN role");
            }
            if (matches(adminKey, request.getHeader(ADMIN_HEADER))) {
                log.info("{} {} authorised with the Admin operator key", request.getMethod(), request.getRequestURI());
                return true;
            }
            boolean validServiceKey = matches(serviceKey, request.getHeader(SERVICE_HEADER));
            if (validServiceKey && serviceAllowed) {
                log.info("{} {} authorised with the service key", request.getMethod(), request.getRequestURI());
                return true;
            }
            if (validServiceKey) {
                throw new ForbiddenException("This is an Admin endpoint: " + SERVICE_HEADER
                        + " is not accepted; send an Admin token (Authorization: Bearer ...)");
            }
            throw new UnauthorizedException(serviceAllowed
                    ? "Missing or invalid credentials: send an Admin token (Authorization: Bearer ...) or "
                            + SERVICE_HEADER
                    : "Missing or invalid credentials: send an Admin token (Authorization: Bearer ...)");
        }

        private static boolean matches(byte[] expected, String supplied) {
            return expected.length > 0 && supplied != null
                    && MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.UTF_8));
        }
    }
}
