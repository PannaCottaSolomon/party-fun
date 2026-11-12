package auth.security;

import org.springframework.web.servlet.HandlerInterceptor;

import auth.exception.InvalidTokenException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authenticates a request from its {@code Authorization: Bearer <Cognito access token>} header. A missing,
 * malformed, forged or expired token is rejected with 401 before the controller runs. On success the caller's id
 * and roles are exposed as an {@link AuthenticatedUser} request attribute; deciding what that caller may do
 * (owner / ADMIN) is the controller's job, because only it knows which account a request is about.
 */
public class JwtAuthInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final CognitoTokenVerifier verifier;

    public JwtAuthInterceptor(CognitoTokenVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true; // CORS preflight carries no credentials
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw new InvalidTokenException("Missing Authorization: Bearer <access token> header");
        }

        AuthenticatedUser caller = verifier.verify(header.substring(BEARER_PREFIX.length()).trim());
        request.setAttribute(AuthenticatedUser.ATTRIBUTE, caller);
        return true;
    }
}
