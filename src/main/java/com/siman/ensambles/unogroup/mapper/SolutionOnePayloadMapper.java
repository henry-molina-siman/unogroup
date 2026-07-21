package com.siman.ensambles.unogroup.mapper;

import com.siman.ensambles.unogroup.dto.PayloadEnriquecidoNotificacion;
import com.siman.ensambles.unogroup.dto.SolutionOneCreatePayload;
import com.siman.ensambles.unogroup.dto.SolutionOneUpdatePayload;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Traduce el payload_enriquecido recibido (lenguaje Siman) al formato
 * Solution One, según la tabla de 26 campos confirmada (Diseño §4.4,
 * doc Implementación §1.4.7).
 */
@Component
public class SolutionOnePayloadMapper {

    private final String darmServiceTypeDefault;

    public SolutionOnePayloadMapper(
            @Value("${ensambles.mapper.darm-service-type:armado}") String darmServiceTypeDefault) {
        this.darmServiceTypeDefault = darmServiceTypeDefault;
    }

    /** Devuelve {@link SolutionOneCreatePayload} o {@link SolutionOneUpdatePayload} según el {@code accion} recibido explícitamente. */
    public Object mapear(PayloadEnriquecidoNotificacion payload, boolean esCreacion) {
        return esCreacion ? mapearCreacion(payload) : mapearActualizacion(payload);
    }

    private SolutionOneCreatePayload mapearCreacion(PayloadEnriquecidoNotificacion p) {
        return SolutionOneCreatePayload.builder()
                .externalReference(p.getOrdenId())
                .externalReferenceAlt1(p.getNumeroFactura())
                .externalReferenceAlt2(p.getNumeroPedidoEcommerce())
                .customerName(p.getNombreCliente())
                .customerPhone(p.getTelefonoCliente())
                .customerEmail(p.getCorreoCliente())
                .customerVip(p.getClienteVip())
                .customerAddress(p.getDireccion())
                .customerCity(p.getCiudad())
                .customerState(p.getDepartamento())
                .customerCountry(p.getPais())
                .customerLatitude(p.getLatitud())
                .customerLongitude(p.getLongitud())
                .serviceType(resolverServiceType(p))
                .serviceLocation(p.getUbicacionServicio())
                .serviceLocationReference(p.getReferenciaUbicacionServicio())
                .itemSku(p.getSku())
                .itemBrand(p.getMarcaProducto())
                .itemCategory(p.getCategoriaProducto())
                .itemDescription(p.getDescripcionProducto())
                .itemQuantity(p.getCantidad())
                .trackingStatus(p.getEstado())
                .trackingOrderTime(p.getFechaOrden())
                .trackingDispatchPlanTime(p.getFechaPlanificadaDespacho())
                .trackingDeliveryPlanTime(p.getFechaPlanificadaEntrega())
                .build();
    }

    private SolutionOneUpdatePayload mapearActualizacion(PayloadEnriquecidoNotificacion p) {
        return SolutionOneUpdatePayload.builder()
                .externalReference(p.getOrdenId())
                .trackingStatus(p.getEstado())
                .trackingDispatchedTime(p.getFechaRealDespacho())
                .trackingDeliveredTime(p.getFechaRealEntrega())
                .build();
    }

    private String resolverServiceType(PayloadEnriquecidoNotificacion p) {
        // F18 (Diseño §9.6): valor de service_type para DARM sin confirmar
        // con UnoGroup (solo "armado" está confirmado para ASSE/ENSA/CARM/TARM).
        // Si orquestador-app no lo pobló, se usa el default configurable en
        // vez de bloquear el mapeo o adivinar un valor fijo en código.
        return StringUtils.hasText(p.getTipoServicio()) ? p.getTipoServicio() : darmServiceTypeDefault;
    }
}
