package com.bookcase.shared.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * Роли из токена Keycloak.
 *
 * <p>Права в наших токенах приходят не в {@code scope}, а в {@code realm_access.roles}: значение
 * {@code scope} Keycloak формирует сам и затирает то, что туда пробуют положить. Стандартный
 * преобразователь Spring про этот раздел не знает, и без него у служебной учётной записи нет ни
 * одного права, хотя роль ей выдана.
 *
 * <p>Пользовательских ролей у нас нет и не планируется: разграничение в личной библиотеке идёт по
 * владельцу, а не по ролям. Роль здесь ровно одна и служебная — право уточнителя класть обложки.
 */
public final class RealmRolesConverter {

    private static final String REALM_ACCESS = "realm_access";
    private static final String ROLES = "roles";
    private static final String PREFIX = "ROLE_";

    private RealmRolesConverter() {}

    /** Преобразователь токена: к обычным правам из {@code scope} добавляются роли realm. */
    public static Converter<Jwt, AbstractAuthenticationToken> create() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(RealmRolesConverter::authorities);
        return converter;
    }

    private static Collection<GrantedAuthority> authorities(Jwt jwt) {
        Collection<GrantedAuthority> authorities =
                new ArrayList<>(new JwtGrantedAuthoritiesConverter().convert(jwt));
        Object realmAccess = jwt.getClaim(REALM_ACCESS);
        if (realmAccess instanceof Map<?, ?> claims && claims.get(ROLES) instanceof List<?> roles) {
            roles.stream()
                    .map(String::valueOf)
                    .map(role -> new SimpleGrantedAuthority(PREFIX + role))
                    .forEach(authorities::add);
        }
        return authorities;
    }
}
