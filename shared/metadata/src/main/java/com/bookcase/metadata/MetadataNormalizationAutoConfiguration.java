package com.bookcase.metadata;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Подключение общих преобразований к сервису.
 *
 * <p>Модуль объявляет свои бины сам, а не рассчитывает на то, что потребитель просканирует чужой
 * пакет: сканирование чужих пакетов — договорённость, о которой легко забыть при добавлении
 * третьего сервиса, и забывчивость проявляется отказом при запуске.
 */
@AutoConfiguration
public class MetadataNormalizationAutoConfiguration {

    /** Сервис может объявить свой нормализатор — тогда возьмётся он. */
    @Bean
    @ConditionalOnMissingBean
    public LanguageNormalizer languageNormalizer() {
        return new LanguageNormalizer();
    }
}
