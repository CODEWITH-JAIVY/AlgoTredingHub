package com.algotradehub.backend.alerts;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.AssertTrue;
import java.math.BigDecimal;
import java.util.UUID;

public record AlertRuleRequest(@NotNull UUID instrumentId,
                               @NotNull RuleType ruleType,
                               Direction direction,
                               @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6) BigDecimal price,
                               @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6) BigDecimal upperPrice,
                               @DecimalMin("1.10") @DecimalMax("10.00") @Digits(integer = 2, fraction = 2) BigDecimal volumeMultiplier) {
    public AlertRuleRequest {
        if (ruleType == null) {
            ruleType = RuleType.PRICE_CROSSING;
        }
        if (ruleType == RuleType.VOLUME_SPIKE && volumeMultiplier == null) {
            volumeMultiplier = new BigDecimal("2.00");
        }
    }

    public AlertRuleRequest(UUID instrumentId, Direction direction, BigDecimal price) {
        this(instrumentId, RuleType.PRICE_CROSSING, direction, price, null, null);
    }

    @AssertTrue(message = "Alert rule fields do not match the selected type.")
    public boolean isRuleShapeValid() {
        if (ruleType == null) return false;
        if (ruleType == RuleType.VOLUME_SPIKE) {
            return direction == null && price == null && upperPrice == null && volumeMultiplier != null
                    && volumeMultiplier.compareTo(new BigDecimal("1.10")) >= 0
                    && volumeMultiplier.compareTo(new BigDecimal("10.00")) <= 0;
        }
        if (price == null || volumeMultiplier != null) return false;
        if (ruleType == RuleType.ZONE_TOUCH) {
            return direction == null && upperPrice != null && upperPrice.compareTo(price) > 0;
        }
        return direction != null && upperPrice == null;
    }

    public enum RuleType { PRICE_CROSSING, ZONE_TOUCH, VOLUME_SPIKE }
    public enum Direction { ABOVE, BELOW }
}
