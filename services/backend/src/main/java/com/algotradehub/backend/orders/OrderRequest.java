package com.algotradehub.backend.orders;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

public record OrderRequest(
        @NotBlank @Size(min = 8, max = 100) String clientOrderId,
        @NotNull UUID instrumentId,
        @NotNull OrderSide side,
        @NotNull OrderType orderType,
        @Positive long quantity,
        @DecimalMin(value = "0.0", inclusive = false) BigDecimal limitPrice,
        @DecimalMin(value = "0.0", inclusive = false) BigDecimal triggerPrice,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal protectiveStopPrice) { }