package events.config;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

public class CognitoGroupsConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        List<String> groups = jwt.getClaimAsStringList("cognito:groups");

        if (groups == null || groups.isEmpty()) {
            return Collections.emptyList();
        }

        return groups.stream()
                .map(group -> new SimpleGrantedAuthority("ROLE_" + group.toUpperCase(Locale.ROOT)))
                .collect(Collectors.toList());
    }
}
