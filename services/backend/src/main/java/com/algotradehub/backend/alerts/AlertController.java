package com.algotradehub.backend.alerts;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {
    private final AlertRuleService alerts;
    public AlertController(AlertRuleService alerts) { this.alerts = alerts; }

    @GetMapping
    public List<AlertViews.Rule> list(@AuthenticationPrincipal Jwt jwt) {
        return alerts.list(UUID.fromString(jwt.getClaimAsString("uid")));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AlertViews.Rule create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AlertRuleRequest body) {
        return alerts.create(UUID.fromString(jwt.getClaimAsString("uid")), body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        alerts.delete(UUID.fromString(jwt.getClaimAsString("uid")), id);
    }

    @GetMapping("/events")
    public List<AlertViews.Event> events(@AuthenticationPrincipal Jwt jwt,
                                         @RequestParam(defaultValue = "50") int limit) {
        return alerts.events(UUID.fromString(jwt.getClaimAsString("uid")), limit);
    }

    @PatchMapping("/events/{id}/read")
    public AlertViews.Event markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return alerts.markRead(UUID.fromString(jwt.getClaimAsString("uid")), id);
    }
}
