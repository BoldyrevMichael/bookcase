package com.bookcase.ingester.messaging;

import com.bookcase.events.BookIngestionRequested;
import com.bookcase.events.Topics;
import com.bookcase.ingester.service.IngestionProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.SameIntervalTopicReuseStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Приём просьб разобрать файл.
 *
 * <p>Повторы вынесены в отдельный топик, а не сделаны на месте: разбор книги занимает секунды, и
 * ждать в потребителе — значит остановить всю партицию из-за одного файла, до которого не
 * достучалось хранилище. Топик повторов один. Растущая пауза потребовала бы по топику на каждый
 * промежуток — при том, что разница между «подождать полминуты» и «подождать минуту» здесь ничего
 * не решает: временные неполадки хранилища длятся либо секунды, либо долго.
 *
 * <p>Топик окончательных отказов несёт в имени имя потребителя. У одного топика читателей может
 * быть несколько, и общая корзина отказов не позволила бы понять, чей это отказ и кому его
 * разбирать.
 */
@Slf4j
@Component
public class IngestionListener {

    private final IngestionProcessor processor;

    public IngestionListener(IngestionProcessor processor) {
        this.processor = processor;
    }

    @RetryableTopic(
            attempts = "${bookcase.ingester.retry-attempts}",
            backOff = @BackOff(delayString = "${bookcase.ingester.retry-delay-millis}"),
            sameIntervalTopicReuseStrategy = SameIntervalTopicReuseStrategy.SINGLE_TOPIC,
            retryTopicSuffix = Topics.INGESTER_RETRY_SUFFIX,
            dltTopicSuffix = Topics.INGESTER_DLT_SUFFIX,
            // Топики заведены заранее и с обдуманным числом партиций.
            autoCreateTopics = "false")
    @KafkaListener(topics = Topics.BOOK_INGESTION_REQUESTED, groupId = "ingester")
    public void onIngestionRequested(BookIngestionRequested request) {
        processor.process(request);
    }

    /**
     * Сюда попадает то, что не вышло разобрать за все попытки.
     *
     * <p>Задача не должна остаться навсегда в состоянии «разбирается»: пользователю нужен ответ,
     * пусть и отрицательный. Файл при этом никуда не девается — разбор можно повторить, когда
     * причина устранена.
     */
    @DltHandler
    public void onGaveUp(
            BookIngestionRequested request,
            @Header(name = KafkaHeaders.EXCEPTION_MESSAGE, required = false) String reason) {
        log.warn("разбор файла {} прекращён после всех попыток: {}", request.sha256(), reason);
        processor.fail(
                request,
                "разобрать файл не удалось: " + (reason == null ? "неизвестная причина" : reason));
    }
}
