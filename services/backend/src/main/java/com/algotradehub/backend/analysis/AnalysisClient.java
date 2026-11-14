package com.algotradehub.backend.analysis;

import java.util.Map;

public interface AnalysisClient {
    AnalysisEngineResponse analyze(Map<String, Object> payload, String correlationId);
}
