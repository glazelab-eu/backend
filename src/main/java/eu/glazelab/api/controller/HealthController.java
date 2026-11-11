package eu.glazelab.api.controller;

import eu.glazelab.api.config.SourceCode;
import eu.glazelab.api.dto.CommonDtos.HealthView;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class HealthController {
    private final SourceCode source;

    @GetMapping("/api/health") HealthView health() { return new HealthView(true, source.build()); }
}
