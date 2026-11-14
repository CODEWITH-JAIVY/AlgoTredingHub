package com.algotradehub.backend.orders;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
    private final OrderService orders;
    public OrderController(OrderService orders) { this.orders = orders; }
    @GetMapping public List<OrderView> list(@AuthenticationPrincipal Jwt jwt) {
        return orders.list(UUID.fromString(jwt.getClaimAsString("uid")));
    }
    @PostMapping public OrderView place(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody OrderRequest body, HttpServletRequest request) {
        return orders.place(UUID.fromString(jwt.getClaimAsString("uid")), body, correlation(request));
    }
    @PostMapping("/{id}/cancel") public OrderView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, HttpServletRequest request) {
        return orders.cancel(UUID.fromString(jwt.getClaimAsString("uid")), id, correlation(request));
    }
    private String correlation(HttpServletRequest request) { return String.valueOf(request.getAttribute("correlationId")); }
}