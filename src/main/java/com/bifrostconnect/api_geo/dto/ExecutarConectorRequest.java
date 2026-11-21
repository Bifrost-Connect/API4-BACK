package com.bifrostconnect.api_geo.dto;

import java.util.Map;

public record ExecutarConectorRequest(
        Map<String, Object> parametrosOverride,
        Boolean forcarReprocessamento
) {
}
