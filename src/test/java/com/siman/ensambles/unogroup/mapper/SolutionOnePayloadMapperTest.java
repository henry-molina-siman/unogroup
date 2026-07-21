package com.siman.ensambles.unogroup.mapper;

import com.siman.ensambles.unogroup.dto.PayloadEnriquecidoNotificacion;
import com.siman.ensambles.unogroup.dto.SolutionOneCreatePayload;
import com.siman.ensambles.unogroup.dto.SolutionOneUpdatePayload;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SolutionOnePayloadMapperTest {

    private final SolutionOnePayloadMapper mapper = new SolutionOnePayloadMapper("armado");

    @Test
    void mapeaCreacionCuandoElPayloadTraeSku() {
        PayloadEnriquecidoNotificacion payload = new PayloadEnriquecidoNotificacion();
        payload.setOrdenId("9013059587");
        payload.setSku("104929691");
        payload.setNumeroFactura("90125424182");
        payload.setNombreCliente("Víctor Camilo Robleto");
        payload.setTelefonoCliente("+50362333852");
        payload.setClienteVip(false);
        payload.setDireccion("Ciudad Real Tenerife 100");
        payload.setCiudad("San Sebastián Salitrillo");
        payload.setDepartamento("Santa Ana");
        payload.setPais("SV");
        payload.setLatitud(13.9677874);
        payload.setLongitud(-89.6334028);
        payload.setTipoServicio("armado");
        payload.setUbicacionServicio("casa");
        payload.setMarcaProducto("Samsung");
        payload.setCategoriaProducto("TV");
        payload.setDescripcionProducto("Mueble de madera n°5");
        payload.setCantidad(1);
        payload.setEstado("creada");
        payload.setFechaOrden(Instant.parse("2026-04-10T05:00:00Z"));
        payload.setFechaPlanificadaDespacho(Instant.parse("2026-04-11T08:00:00Z"));
        payload.setFechaPlanificadaEntrega(Instant.parse("2026-04-12T08:00:00Z"));

        Object resultado = mapper.mapear(payload, true);

        assertThat(resultado).isInstanceOf(SolutionOneCreatePayload.class);
        SolutionOneCreatePayload creado = (SolutionOneCreatePayload) resultado;
        assertThat(creado.getExternalReference()).isEqualTo("9013059587");
        assertThat(creado.getItemSku()).isEqualTo("104929691");
        assertThat(creado.getCustomerCountry()).isEqualTo("SV");
        assertThat(creado.getServiceType()).isEqualTo("armado");
        assertThat(creado.getTrackingStatus()).isEqualTo("creada");
    }

    @Test
    void mapeaActualizacionCuandoElPayloadNoTraeSku() {
        PayloadEnriquecidoNotificacion payload = new PayloadEnriquecidoNotificacion();
        payload.setOrdenId("9013059587");
        payload.setEstado("alistada");
        payload.setFechaRealDespacho(Instant.parse("2026-04-11T08:00:00Z"));
        payload.setFechaRealEntrega(Instant.parse("2026-04-12T12:00:00Z"));

        Object resultado = mapper.mapear(payload, false);

        assertThat(resultado).isInstanceOf(SolutionOneUpdatePayload.class);
        SolutionOneUpdatePayload actualizado = (SolutionOneUpdatePayload) resultado;
        assertThat(actualizado.getExternalReference()).isEqualTo("9013059587");
        assertThat(actualizado.getTrackingStatus()).isEqualTo("alistada");
    }

    @Test
    void usaElDefaultConfigurableDeServiceTypeCuandoNoVienePoblado() {
        PayloadEnriquecidoNotificacion payload = new PayloadEnriquecidoNotificacion();
        payload.setOrdenId("9013059588");
        payload.setSku("104929692");
        payload.setEstado("creada");
        // tipoServicio deliberadamente ausente — simula flujo DARM sin
        // confirmar (F18): el mapper no debe fallar, debe usar el default.

        SolutionOneCreatePayload creado = (SolutionOneCreatePayload) mapper.mapear(payload, true);

        assertThat(creado.getServiceType()).isEqualTo("armado");
    }
}
