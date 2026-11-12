package events.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        JwtAuthenticationConverter jwtAuthenticationConverter = new JwtAuthenticationConverter();
        jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(new CognitoGroupsConverter());

        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/events", "/events/*")
                        .hasAnyRole("ORGANISER", "PARTICIPANT", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/events/*/signup",
                                "/events/*/bookings/*/payment")
                        .hasAnyRole("ORGANISER", "PARTICIPANT", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/events",
                                "/events/*/cancel").hasAnyRole("ORGANISER", "ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/events/*").hasAnyRole("ORGANISER", "ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/events/*").hasAnyRole("ORGANISER", "ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));

        return http.build();
    }
}
