package com.bookcase.enricher.service;

import com.bookcase.enricher.client.Candidate;
import com.bookcase.enricher.client.Lookup;
import com.bookcase.enricher.client.MetadataProvider;
import com.bookcase.enricher.config.EnricherProperties;
import com.bookcase.enricher.dto.EnrichmentTask;
import com.bookcase.enricher.repository.EnrichmentTaskRepository;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

/**
 * Уточнение метаданных одной книги.
 *
 * <p>Справочники опрашиваются по очереди, пока кто-нибудь не предложит книгу, которая пройдёт
 * сверку. Непринятый кандидат не останавливает работу: следующий справочник может знать лучше.
 *
 * <p>Здесь же проходит граница между «не нашлось» и «не смогли спросить». Первое закрывает задачу:
 * справочник ответил, книги у него нет, и завтрашний повтор ничего не изменит. Второе откладывает
 * попытку — недоступна сеть, служба отвечает отказом или сработал предохранитель.
 */
@Slf4j
@Service
public class EnrichmentService {

    private final List<MetadataProvider> providers;
    private final ProviderCache cache;
    private final CandidateMatcher matcher;
    private final EnrichmentTaskRepository tasks;
    private final FindingPublisher findings;
    private final EnricherProperties properties;

    public EnrichmentService(
            List<MetadataProvider> providers,
            ProviderCache cache,
            CandidateMatcher matcher,
            EnrichmentTaskRepository tasks,
            FindingPublisher findings,
            EnricherProperties properties) {
        this.providers = List.copyOf(providers);
        this.cache = cache;
        this.matcher = matcher;
        this.tasks = tasks;
        this.findings = findings;
        this.properties = properties;
    }

    /** Обрабатывает одну задачу: спрашивает справочники и решает судьбу задачи. */
    public void enrich(EnrichmentTask task) {
        Lookup lookup = task.toLookup();
        if (!lookup.isSearchable()) {
            // Ни названия, ни ISBN — спрашивать нечего. Такие карточки ждут человека.
            log.info("книга {}: спрашивать не о чем, ни названия, ни ISBN", task.bookId());
            tasks.markNotFound(task.bookId());
            return;
        }

        try {
            Candidate collected = null;
            CandidateMatcher.Verdict accepted = null;
            for (MetadataProvider provider : providers) {
                if (!provider.available()) {
                    continue;
                }
                Optional<Candidate> found = cache.ask(provider, lookup);
                if (found.isEmpty()) {
                    continue;
                }
                Candidate candidate = found.get();
                CandidateMatcher.Verdict verdict = matcher.check(lookup, candidate);
                if (!verdict.accepted()) {
                    log.info(
                            "книга {}: {} предложил «{}» — не принято, {}",
                            task.bookId(),
                            provider.name(),
                            candidate.title(),
                            verdict.reason());
                    continue;
                }
                if (collected == null) {
                    collected = candidate;
                    accepted = verdict;
                } else {
                    // Второй справочник спрашивается не вместо первого, а ради того, чего
                    // у первого не нашлось: у одного бывает издательство, у другого обложка.
                    collected = Candidates.fillGaps(collected, candidate);
                    log.info("книга {}: {} дополнил недостающее", task.bookId(), provider.name());
                }
                if (Candidates.isComplete(collected)) {
                    // Всё нужное собрано — остальных не тревожим: суточная квота не бесконечна.
                    break;
                }
            }
            if (collected != null) {
                apply(task, collected, accepted);
                return;
            }
            log.info("книга {}: справочники ничего подходящего не знают", task.bookId());
            tasks.markNotFound(task.bookId());
        } catch (RequestNotPermitted _) {
            // Суточная квота выбрана. Раньше завтрашнего дня спрашивать бессмысленно.
            log.warn("квота справочника исчерпана, книга {} отложена до завтра", task.bookId());
            tasks.reschedule(task.bookId(), Duration.ofHours(24), "квота справочника исчерпана");
        } catch (CallNotPermittedException _) {
            postpone(task, "справочник недоступен, предохранитель разомкнут");
        } catch (RestClientException e) {
            postpone(task, "справочник не ответил: " + e.getMessage());
        }
    }

    private void apply(EnrichmentTask task, Candidate candidate, CandidateMatcher.Verdict verdict) {
        log.info(
                "книга {}: {} нашёл «{}» по запросу «{}», {}",
                task.bookId(),
                candidate.provider(),
                candidate.title(),
                candidate.matchedBy(),
                verdict.reason());
        findings.publish(task, candidate);
        tasks.markDone(task.bookId(), candidate.provider());
    }

    /**
     * Откладывает попытку.
     *
     * <p>Пауза удваивается с каждой неудачей: если справочник лежит, частые попытки только жгут
     * квоту и заполняют журнал. После отведённого числа попыток задача признаётся безнадёжной —
     * книга при этом никуда не девается и остаётся с теми данными, что дал файл.
     */
    private void postpone(EnrichmentTask task, String reason) {
        int nextAttempt = task.attempts() + 1;
        if (nextAttempt >= properties.maxAttempts()) {
            log.warn("книга {}: попытки исчерпаны, {}", task.bookId(), reason);
            tasks.giveUp(task.bookId(), reason);
            return;
        }
        Duration delay = properties.retryDelay().multipliedBy(1L << task.attempts());
        log.info("книга {}: {}. Следующая попытка через {}", task.bookId(), reason, delay);
        tasks.reschedule(task.bookId(), delay, reason);
    }
}
