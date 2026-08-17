package com.bookcase.shared.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Общие настройки безопасности сервисов.
 *
 * @param allowedOrigins адреса страниц, с которых принимаются изменяющие запросы. Пустой список
 *     означает, что проверка источника не делается: так удобно в тестах и при обращении из
 *     программных клиентов, у которых источника нет вовсе.
 */
@ConfigurationProperties("bookcase.security")
public record SharedSecurityProperties(@DefaultValue List<String> allowedOrigins) {

    public SharedSecurityProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
