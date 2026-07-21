package com.siman.ensambles.unogroup.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * Payload de creación en formato Solution One (snake_case), confirmado
 * contra el mapeo real Diseño §4.4 / doc Implementación §1.4.7.
 * item_sku SOLO viaja en creación — en actualización el sku se identifica
 * por el nombre de archivo (ver {@link SolutionOneUpdatePayload}).
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.ALWAYS)
public class SolutionOneCreatePayload {

    @JsonProperty("external_reference")
    private String externalReference;
    @JsonProperty("external_reference_alt_1")
    private String externalReferenceAlt1;
    @JsonProperty("external_reference_alt_2")
    private String externalReferenceAlt2;
    @JsonProperty("customer_name")
    private String customerName;
    @JsonProperty("customer_phone")
    private String customerPhone;
    @JsonProperty("customer_email")
    private String customerEmail;
    @JsonProperty("customer_vip")
    private Boolean customerVip;
    @JsonProperty("customer_address")
    private String customerAddress;
    @JsonProperty("customer_city")
    private String customerCity;
    @JsonProperty("customer_state")
    private String customerState;
    @JsonProperty("customer_country")
    private String customerCountry;
    @JsonProperty("customer_latitude")
    private Double customerLatitude;
    @JsonProperty("customer_longitude")
    private Double customerLongitude;
    @JsonProperty("service_type")
    private String serviceType;
    @JsonProperty("service_location")
    private String serviceLocation;
    @JsonProperty("service_location_reference")
    private String serviceLocationReference;
    @JsonProperty("item_sku")
    private String itemSku;
    @JsonProperty("item_brand")
    private String itemBrand;
    @JsonProperty("item_category")
    private String itemCategory;
    @JsonProperty("item_description")
    private String itemDescription;
    @JsonProperty("item_quantity")
    private Integer itemQuantity;
    @JsonProperty("tracking_status")
    private String trackingStatus;
    @JsonProperty("tracking_order_time")
    private Instant trackingOrderTime;
    @JsonProperty("tracking_dispatch_plan_time")
    private Instant trackingDispatchPlanTime;
    @JsonProperty("tracking_delivery_plan_time")
    private Instant trackingDeliveryPlanTime;
}
