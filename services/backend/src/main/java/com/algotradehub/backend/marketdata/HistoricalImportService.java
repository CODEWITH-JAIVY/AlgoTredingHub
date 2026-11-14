package com.algotradehub.backend.marketdata;

import com.algotradehub.backend.common.ApiException;
import com.algotradehub.backend.orders.Instrument;
import com.algotradehub.backend.orders.InstrumentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.DateTimeException;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HexFormat;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class HistoricalImportService {
    private static final Duration MAX_RANGE = Duration.ofDays(90);
    private static final int HARD_PAGE_LIMIT = 500;
    private final InstrumentRepository instruments;
    private final HistoryImportJobRepository jobs;
    private final MarketCandleRepository candles;
    private final MarketDataSourceRateLimitService sourceRateLimits;
    private final Map<String, HistoricalCandleSource> sources;
    private final Clock clock;

    @Autowired
    public HistoricalImportService(InstrumentRepository instruments, HistoryImportJobRepository jobs,
                                   MarketCandleRepository candles, MarketDataSourceRateLimitService sourceRateLimits,
                                   List<HistoricalCandleSource> sources) {
        this(instruments, jobs, candles, sourceRateLimits, sources, Clock.systemUTC());
    }

    HistoricalImportService(InstrumentRepository instruments, HistoryImportJobRepository jobs,
                            MarketCandleRepository candles, MarketDataSourceRateLimitService sourceRateLimits,
                            List<HistoricalCandleSource> sources, Clock clock) {
        this.instruments = instruments;
        this.jobs = jobs;
        this.candles = candles;
        this.sourceRateLimits = sourceRateLimits;
        this.clock = clock;
        this.sources = sources.stream().collect(Collectors.toUnmodifiableMap(HistoricalCandleSource::sourceId, Function.identity()));
    }

    @Transactional
    public ImportJobView start(UUID instrumentId, String sourceId, Instant from, Instant to) {
        if (instrumentId == null || sourceId == null || sourceId.isBlank() || from == null || to == null
                || !isValidRange(from, to)) {
            throw reject(HttpStatus.BAD_REQUEST, "INVALID_IMPORT_RANGE", "A positive one-minute import range no longer than 90 days is required.");
        }
        Instrument instrument = instruments.findById(instrumentId).filter(Instrument::isActive)
                .orElseThrow(() -> reject(HttpStatus.NOT_FOUND, "INSTRUMENT_UNAVAILABLE", "Instrument is missing or inactive."));
        HistoricalCandleSource source = requireApprovedSource(sourceId);
        String key = requestKey(instrumentId, sourceId, from, to);
        Instant now = clock.instant();
        jobs.createIfAbsent(UUID.randomUUID(), key, instrument.getId(), source.sourceId(), from, to, now);
        HistoryImportJobEntity job = jobs.findByRequestKey(key)
                .orElseThrow(() -> reject(HttpStatus.SERVICE_UNAVAILABLE, "IMPORT_JOB_NOT_PERSISTED", "Import job could not be saved."));
        return toView(job);
    }

    @Transactional
    public ImportJobView importNextPage(UUID jobId) {
        HistoryImportJobEntity job = jobs.findByIdForUpdate(jobId)
                .orElseThrow(() -> reject(HttpStatus.NOT_FOUND, "IMPORT_JOB_NOT_FOUND", "Historical import job was not found."));
        if ("COMPLETE".equals(job.getStatus())) return toView(job);
        HistoricalCandleSource source = requireApprovedSource(job.getSourceId());
        Instant now = clock.instant();
        Instant lastRequest = job.getLastRequestAt();
        if (lastRequest != null && now.isBefore(lastRequest.plus(source.minimumRequestInterval()))) {
            throw reject(HttpStatus.TOO_MANY_REQUESTS, "IMPORT_RATE_LIMITED", "The provider's minimum request interval has not elapsed.");
        }
        if (!sourceRateLimits.tryReserve(source.sourceId(), now, source.minimumRequestInterval())) {
            throw reject(HttpStatus.TOO_MANY_REQUESTS, "IMPORT_RATE_LIMITED", "The provider's shared request limit has not elapsed.");
        }
        int pageSize = Math.min(source.maxPageSize(), HARD_PAGE_LIMIT);
        job.markRequested(now);
        jobs.saveAndFlush(job);
        String masterVersion;
        HistoricalCandlePage page;
        try {
            Instrument instrument = currentActiveInstrument(job.getInstrumentId());
            masterVersion = instrument.getMasterVersion();
            page = source.fetchPage(job.getInstrumentId(), masterVersion,
                    job.getRangeStart(), job.getRangeEnd(), job.getNextCursor(), pageSize);
            if (!masterVersion.equals(currentMasterVersion(job.getInstrumentId()))) {
                throw reject(HttpStatus.CONFLICT, "INSTRUMENT_MASTER_CHANGED", "The instrument master changed during the history request.");
            }
            validatePage(job, instrument, source, page, masterVersion, pageSize);
        } catch (RuntimeException failure) {
            String errorCode = "SOURCE_PAGE_FAILED";
            if (failure instanceof ApiException api) {
                errorCode = api.code();
            }
            job.markFailure(errorCode, clock.instant());
            jobs.save(job);
            return toView(job);
        }
        long inserted = 0;
        for (CandleBar bar : page.candles()) {
            if (!candles.existsByInstrumentIdAndTimeframeSecondsAndBucketStartAndSourceId(
                    bar.instrumentId(), bar.timeframeSeconds(), bar.bucketStart(), bar.sourceId())) {
                candles.save(new MarketCandleEntity(bar));
                inserted++;
            }
        }
        job.advance(page.nextCursor(), inserted, page.exhausted(), clock.instant());
        jobs.save(job);
        return toView(job);
    }

    static String requestKey(UUID instrumentId, String sourceId, Instant from, Instant to) {
        String value = instrumentId + "|" + sourceId + "|60|" + from + "|" + to;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime.", impossible);
        }
    }

    private void validatePage(HistoryImportJobEntity job, Instrument instrument, HistoricalCandleSource source,
                              HistoricalCandlePage page, String masterVersion, int pageSize) {
        if (page == null) throw reject(HttpStatus.BAD_GATEWAY, "INVALID_PROVIDER_PAGE", "The provider returned no page envelope.");
        if (page.candles().size() > pageSize) throw reject(HttpStatus.BAD_GATEWAY, "PROVIDER_PAGE_TOO_LARGE", "Provider response exceeded the requested page size.");
        if (page.rangeComplete() && !page.exhausted()) {
            throw reject(HttpStatus.BAD_GATEWAY, "INVALID_PROVIDER_COMPLETENESS", "A provider cannot mark a paginated range complete while more pages remain.");
        }
        if (page.exhausted() && !page.rangeComplete()) {
            throw reject(HttpStatus.BAD_GATEWAY, "INCOMPLETE_PROVIDER_RANGE", "The provider exhausted its pages without confirming full range coverage.");
        }
        if (!page.exhausted() && (page.nextCursor() == null || page.nextCursor().isBlank()
                || page.nextCursor().equals(job.getNextCursor()))) {
            throw reject(HttpStatus.BAD_GATEWAY, "IMPORT_CURSOR_STALLED", "The provider did not advance its history cursor.");
        }
        if (page.nextCursor() != null && page.nextCursor().length() > 512) {
            throw reject(HttpStatus.BAD_GATEWAY, "IMPORT_CURSOR_TOO_LONG", "Provider cursor exceeded the supported length.");
        }
        HashSet<Instant> seen = new HashSet<>();
        Instant previous = null;
        for (CandleBar bar : page.candles()) {
            if (bar == null || bar.bucketStart() == null || bar.open() == null || bar.high() == null
                    || bar.low() == null || bar.close() == null || bar.finalizedAt() == null
                    || !job.getInstrumentId().equals(bar.instrumentId()) || bar.timeframeSeconds() != 60
                    || !source.sourceId().equals(bar.sourceId()) || !"PROVIDER".equals(bar.quality()) || !bar.finalized()
                    || !masterVersion.equals(bar.masterVersion())
                    || bar.bucketStart().isBefore(job.getRangeStart()) || !bar.bucketStart().isBefore(job.getRangeEnd())) {
                throw reject(HttpStatus.BAD_GATEWAY, "INVALID_PROVIDER_CANDLE", "A provider candle is outside the requested import contract.");
            }
            if (bar.bucketStart().getNano() != 0 || bar.bucketStart().getEpochSecond() % 60 != 0
                    || bar.finalizedAt().isBefore(bar.bucketStart().plusSeconds(60))
                    || bar.volume() < 0 || bar.tradeCount() < 0
                    || !validPrice(bar.open(), instrument) || !validPrice(bar.high(), instrument)
                    || !validPrice(bar.low(), instrument) || !validPrice(bar.close(), instrument)
                    || bar.high().compareTo(bar.open()) < 0 || bar.high().compareTo(bar.close()) < 0
                    || bar.low().compareTo(bar.open()) > 0 || bar.low().compareTo(bar.close()) > 0
                    || bar.high().compareTo(bar.low()) < 0) {
                throw reject(HttpStatus.BAD_GATEWAY, "INVALID_PROVIDER_CANDLE_VALUES", "Provider OHLC, volume, timestamp, or tick size is invalid.");
            }
            if (!seen.add(bar.bucketStart()) || (previous != null && bar.bucketStart().isBefore(previous))) {
                throw reject(HttpStatus.BAD_GATEWAY, "DUPLICATE_OR_UNORDERED_PAGE", "Provider candles must be unique and chronological within a page.");
            }
            previous = bar.bucketStart();
        }
    }

    private String currentMasterVersion(UUID id) {
        return currentActiveInstrument(id).getMasterVersion();
    }

    private Instrument currentActiveInstrument(UUID id) {
        return instruments.findById(id).filter(Instrument::isActive)
                .orElseThrow(() -> reject(HttpStatus.CONFLICT, "INSTRUMENT_MASTER_CHANGED", "The instrument became inactive during history import."));
    }

    private boolean validPrice(BigDecimal price, Instrument instrument) {
        if (price == null || instrument.getTickSize() == null || instrument.getTickSize().signum() <= 0) return false;
        BigDecimal normalized = price.stripTrailingZeros();
        int integerDigits = normalized.precision() - normalized.scale();
        return price.signum() > 0 && normalized.scale() <= 6 && integerDigits <= 12
                && price.remainder(instrument.getTickSize()).compareTo(BigDecimal.ZERO) == 0;
    }

    private boolean isValidRange(Instant from, Instant to) {
        try {
            return isMinuteBoundary(from) && isMinuteBoundary(to) && to.isAfter(from)
                    && Duration.between(from, to).compareTo(MAX_RANGE) <= 0;
        } catch (DateTimeException | ArithmeticException invalidRange) {
            return false;
        }
    }

    private boolean isMinuteBoundary(Instant instant) {
        return instant.getNano() == 0 && instant.getEpochSecond() % 60 == 0;
    }

    private HistoricalCandleSource requireApprovedSource(String sourceId) {
        HistoricalCandleSource source = sources.get(sourceId);
        if (sourceId == null || sourceId.isBlank() || sourceId.length() > 50 || source == null
                || !sourceId.equals(source.sourceId())) {
            throw reject(HttpStatus.SERVICE_UNAVAILABLE, "MARKET_SOURCE_NOT_CONFIGURED", "No historical provider is configured.");
        }
        if (!source.enabled()) throw reject(HttpStatus.SERVICE_UNAVAILABLE, "MARKET_SOURCE_DISABLED", "The historical provider is not enabled.");
        if (source.rights() == null || !source.rights().permitsHistoricalImportAndDisplay()) {
            throw reject(HttpStatus.FORBIDDEN, "MARKET_DATA_RIGHTS_UNCONFIRMED", "Historical import is disabled until storage, end-user display, and redistribution rights are confirmed.");
        }
        if (source.maxPageSize() < 1 || source.minimumRequestInterval() == null
                || source.minimumRequestInterval().isNegative() || source.minimumRequestInterval().isZero()) {
            throw reject(HttpStatus.SERVICE_UNAVAILABLE, "SOURCE_LIMITS_INVALID", "The configured provider has invalid request limits.");
        }
        return source;
    }

    private ImportJobView toView(HistoryImportJobEntity job) {
        return new ImportJobView(job.getId(), job.getInstrumentId(), job.getSourceId(), job.getStatus(),
                job.getImportedCount(), job.getNextCursor() != null, job.getLastErrorCode());
    }

    private ApiException reject(HttpStatus status, String code, String message) { return new ApiException(status, code, message); }

    public record ImportJobView(UUID id, UUID instrumentId, String source, String status, long importedCount,
                                boolean hasCheckpoint, String lastErrorCode) { }
}
