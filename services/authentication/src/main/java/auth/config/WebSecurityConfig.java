package auth.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import auth.security.CognitoTokenVerifier;
import auth.security.JwtAuthInterceptor;

/**
 * Which paths need an access token. {@code /auth/**} stays open (you cannot need a token to get one); every
 * {@code /user} endpoint requires a valid Cognito access token, and {@code UserController} then applies the
 * owner / ADMIN rules.
 */
@Configuration(proxyBeanMethods = false)
public class WebSecurityConfig implements WebMvcConfigurer {

    private final CognitoTokenVerifier verifier;

    public WebSecurityConfig(CognitoTokenVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new JwtAuthInterceptor(verifier))
                .addPathPatterns("/user", "/user/**");
    }
}
