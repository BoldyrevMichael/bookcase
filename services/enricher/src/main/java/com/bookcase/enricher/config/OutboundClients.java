package com.bookcase.enricher.config;

import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/**
 * Пределы ожидания для походов наружу.
 *
 * <p>Без них недоступный справочник держит фонового работника столько, сколько сочтёт нужным
 * операционная система: соединение к молчащему адресу может висеть минутами. Очередь в это время
 * стоит, а в журнале видно только «ошибка ввода-вывода» без пояснений — именно так это и выглядело
 * в тот день, когда Open Library перестала отвечать.
 *
 * <p>Числа скромные намеренно: уточнение — работа фоновая и необязательная. Отложить книгу до
 * следующей попытки лучше, чем занимать работника ожиданием.
 */
@Configuration
public class OutboundClients {

    /** Одна фабрика на всех, кто ходит наружу: справочники и загрузка обложек. */
    @Bean
    public ClientHttpRequestFactory outboundRequestFactory(EnricherProperties properties) {
        JdkClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder()
                                .connectTimeout(properties.connectTimeout())
                                .build());
        factory.setReadTimeout(properties.readTimeout());
        return factory;
    }
}
