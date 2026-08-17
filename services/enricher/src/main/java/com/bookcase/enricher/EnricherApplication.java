package com.bookcase.enricher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Единственный сервис, который ходит в интернет. */
@SpringBootApplication
@ConfigurationPropertiesScan
// Уточнение идёт по расписанию из базы, а не по приходу события: ждать своей очереди
// книга может часами, и держать это ожидание в потребителе Kafka нельзя.
@EnableScheduling
public class EnricherApplication {

    public static void main(String[] args) {
        SpringApplication.run(EnricherApplication.class, args);
    }
}
