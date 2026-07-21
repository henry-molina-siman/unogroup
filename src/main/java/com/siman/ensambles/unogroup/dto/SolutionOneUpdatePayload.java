package com.siman.ensambles.unogroup.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * Payload de actualización en formato Solution One. Sin item_sku — el sku
 * se identifica por la ruta/nombre del archivo, no por un campo del body
 * (Diseño §4.4, confirmado).
 */
@Getter
@Builder
public class SolutionOneUpdatePayload {

    @JsonProperty("external_reference")
    private String externalReference;
    @JsonProperty("tracking_status")
    private String trackingStatus;
    @JsonProperty("tracking_dispatched_time")
    private Instant trackingDispatchedTime;
    @JsonProperty("tracking_delivered_time")
    private Instant trackingDeliveredTime;
}
