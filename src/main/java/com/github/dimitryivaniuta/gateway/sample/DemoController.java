package com.github.dimitryivaniuta.gateway.sample;

import com.github.dimitryivaniuta.gateway.sample.dto.DemoCacheResponse;
import com.github.dimitryivaniuta.gateway.sample.dto.DemoIdempotentRequest;
import com.github.dimitryivaniuta.gateway.sample.dto.DemoIdempotentResponse;
import com.github.dimitryivaniuta.gateway.sample.dto.DemoRateLimitedResponse;
import com.github.dimitryivaniuta.gateway.sample.dto.DemoRetryResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/demo")
public class DemoController {

    private final DemoService demoService;

    @GetMapping("/cache")
    public DemoCacheResponse cache(@RequestParam @NotNull Long customerId) {
        return demoService.cachedCustomerView(customerId);
    }

    @PostMapping("/idempotent")
    public DemoIdempotentResponse idempotent(@Valid @RequestBody DemoIdempotentRequest req) {
        return demoService.idempotentPayment(req);
    }

    @GetMapping("/ratelimited")
    public DemoRateLimitedResponse rateLimited() {
        return demoService.rateLimitedPing();
    }

    @GetMapping("/retry")
    public DemoRetryResponse retry(@RequestParam(defaultValue = "2") @Min(0) @Max(10) int failTimes) {
        return demoService.retryDemo(failTimes);
    }
}
