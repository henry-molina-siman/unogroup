package com.siman.ensambles.unogroup.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

/**
 * Respuesta de {@code /api/v2/user/token}, confirmada como JSON
 * ({@code access_token} + {@code expires_at}), no un JWT plano.
 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class SolutionOneTokenResponse {

    @JsonProperty("access_token")
    private String accessToken;
    @JsonProperty("expires_at")
    private String expiresAt;
}
