package com.algotradehub.backend.alerts;

import com.algotradehub.backend.common.ApiException;
import com.algotradehub.backend.orders.Instrument;
import com.algotradehub.backend.orders.InstrumentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AlertRuleService {
    private final AlertRuleRepository rules;
    private final AlertEventRepository events;
    private final InstrumentRepository instruments;
    private final AlertMarketDataGate dataGate;
    private final Clock clock;
    private final int maxRulesPerUser;

    public AlertRuleService(AlertRuleRepository rules, AlertEventRepository events, InstrumentRepository instruments,
                            AlertMarketDataGate dataGate, Clock clock,
                            @Value("${app.alerts.max-rules-per-user:100}") int maxRulesPerUser) {
        this.rules = rules;
        this.events = events;
        this.instruments = instruments;
        this.dataGate = dataGate;
        this.clock = clock;
        this.maxRulesPerUser = Math.max(1, Math.min(maxRulesPerUser, 500));
    }

    @Transactional(readOnly = true)
    public List<AlertViews.Rule> list(UUID userId) {
        return rules.findByUserIdOrderByCreatedAtDesc(userId).stream().map(AlertViews.Rule::from).toList();
    }

    @Transactional
    public AlertViews.Rule create(UUID userId, AlertRuleRequest request) {
        Instrument instrument = instruments.findById(request.instrumentId()).filter(Instrument::isActive)
                .orElseThrow(() -> reject(HttpStatus.NOT_FOUND, "INSTRUMENT_NOT_FOUND", "Instrument was not found or is inactive."));
        if (rules.countByUserIdAndActiveTrue(userId) >= maxRulesPerUser) {
            throw reject(HttpStatus.TOO_MANY_REQUESTS, "ALERT_LIMIT_REACHED", "The active alert limit has been reached.");
        }
        Instant now = clock.instant();
        String pauseReason = dataGate.configurationPauseReason();
        String state;
        if (pauseReason == null) {
            state = "WAITING_FOR_DATA";
        } else {
            state = "PAUSED";
        }
        if (pauseReason == null) pauseReason = "WAITING_FOR_FRESH_CANDLE";
        String direction = null;
        if (request.direction() != null) {
            direction = request.direction().name();
        }
        Integer lookbackCandles = null;
        if (request.ruleType() == AlertRuleRequest.RuleType.VOLUME_SPIKE) {
            lookbackCandles = 20;
        }
        AlertRuleEntity rule = new AlertRuleEntity(UUID.randomUUID(), userId, instrument.getId(), request.ruleType().name(),
                direction, request.price(), request.upperPrice(), request.volumeMultiplier(), lookbackCandles,
                state, pauseReason, now);
        return AlertViews.Rule.from(rules.save(rule));
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        AlertRuleEntity rule = rules.findByIdAndUserId(id, userId)
                .orElseThrow(() -> reject(HttpStatus.NOT_FOUND, "ALERT_NOT_FOUND", "Alert was not found."));
        rules.delete(rule);
    }

    @Transactional(readOnly = true)
    public List<AlertViews.Event> events(UUID userId, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        return events.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, limit)).stream().map(AlertViews.Event::from).toList();
    }

    @Transactional
    public AlertViews.Event markRead(UUID userId, UUID eventId) {
        AlertEventEntity event = events.findByIdAndUserId(eventId, userId)
                .orElseThrow(() -> reject(HttpStatus.NOT_FOUND, "ALERT_EVENT_NOT_FOUND", "Alert event was not found."));
        event.markRead(clock.instant());
        return AlertViews.Event.from(events.save(event));
    }

    private ApiException reject(HttpStatus status, String code, String message) { return new ApiException(status, code, message); }
}
