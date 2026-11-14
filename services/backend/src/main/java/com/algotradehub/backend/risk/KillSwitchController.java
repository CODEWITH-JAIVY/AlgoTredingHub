package com.algotradehub.backend.risk;

import com.algotradehub.backend.audit.AuditService;
import com.algotradehub.backend.common.ApiException;
import com.algotradehub.backend.orders.OrderEntity;
import com.algotradehub.backend.orders.OrderRepository;
import com.algotradehub.backend.orders.OrderStatus;
import com.algotradehub.backend.broker.MockBrokerAdapter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/risk")
public class KillSwitchController {
    private static final List<String> UNRESOLVED_STATUSES = List.of(
            OrderStatus.OPEN.name(), OrderStatus.PARTIALLY_FILLED.name(), OrderStatus.UNKNOWN.name());
    private final AccountControlRepository controls;
    private final RiskPolicyRepository policies;
    private final OrderRepository orders;
    private final MockBrokerAdapter broker;
    private final AuditService audit;
    public KillSwitchController(AccountControlRepository controls, RiskPolicyRepository policies, OrderRepository orders, MockBrokerAdapter broker, AuditService audit) {
        this.controls = controls;
        this.policies = policies;
        this.orders = orders;
        this.broker = broker;
        this.audit = audit;
    }
    @GetMapping("/kill-switch")
    public KillSwitchView state(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = userId(jwt);
        AccountControl c = controls.findById(userId).orElseThrow();
        int unresolved = Math.toIntExact(orders.countByUserIdAndStatusIn(userId, UNRESOLVED_STATUSES));
        return new KillSwitchView(c.isKillSwitchActive(), c.getKillSwitchReason(), 0, unresolved);
    }
    @PutMapping("/kill-switch") @Transactional
    public KillSwitchView set(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody KillSwitchRequest body, HttpServletRequest request) {
        UUID userId = userId(jwt);
        policies.findByUserIdForUpdate(userId).orElseThrow();
        AccountControl control = controls.findById(userId).orElseThrow();
        int cancelled = 0;
        int unresolved = 0;
        if (body.active()) {
            control.activate(body.reason());
            List<OrderEntity> open = orders.findByUserIdAndStatusInOrderByCreatedAtDesc(userId, UNRESOLVED_STATUSES);
            for (OrderEntity order : open) {
                try {
                    var result = broker.cancelOrder(order.getBrokerOrderId());
                    if (result.status() != OrderStatus.CANCELLED) {
                        unresolved++;
                        continue;
                    }
                    order.cancel(result.brokerOrderId());
                    orders.save(order);
                    cancelled++;
                } catch (RuntimeException ignored) {
                    unresolved++;
                    // Keep order state unchanged so the unresolved order remains visible for reconciliation.
                }
            }
        } else {
            unresolved = Math.toIntExact(orders.countByUserIdAndStatusIn(userId, UNRESOLVED_STATUSES));
            if (unresolved > 0) {
                audit.record(userId, "KILL_SWITCH_DEACTIVATION_BLOCKED", "ACCOUNT", userId.toString(), "BLOCKED",
                        "UNRESOLVED_ORDERS", String.valueOf(request.getAttribute("correlationId")), "unresolved=" + unresolved);
                throw new ApiException(HttpStatus.CONFLICT, "KILL_SWITCH_UNRESOLVED_ORDERS",
                        "Resolve all open or unknown orders before deactivating the kill switch.");
            }
            control.deactivate();
        }
        controls.save(control);
        audit.record(userId, "KILL_SWITCH_CHANGE", "ACCOUNT", userId.toString(), "SUCCESS", null,
                String.valueOf(request.getAttribute("correlationId")),
                "active=" + body.active() + ";cancelled=" + cancelled + ";unresolved=" + unresolved);
        return new KillSwitchView(control.isKillSwitchActive(), control.getKillSwitchReason(), cancelled, unresolved);
    }
    private UUID userId(Jwt jwt) { return UUID.fromString(jwt.getClaimAsString("uid")); }
    public record KillSwitchRequest(boolean active, @Size(max = 500) String reason) { }
    public record KillSwitchView(boolean active, String reason, int confirmedCancelled, int unresolvedOrders) { }
}
