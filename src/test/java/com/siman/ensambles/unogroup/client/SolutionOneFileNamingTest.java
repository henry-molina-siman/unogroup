package com.siman.ensambles.unogroup.client;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

class SolutionOneFileNamingTest {

    @Test
    void construyeElPathConRaizSegunTipoDeSubida() {
        Instant trackingOrderTime = Instant.parse("2026-04-10T05:00:00Z");
        String fechaHoy = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC).format(Instant.now());

        String path = SolutionOneFileNaming.construirPath("siman", "create",
                trackingOrderTime, "9013059587", "104929691");

        assertThat(path).isEqualTo("siman/create/" + fechaHoy + "/create_20260410050000_9013059587_104929691.json");
    }

    @Test
    void elSkuSiempreViajaEnElNombreDeArchivoAunEnActualizaciones() {
        Instant trackingOrderTime = Instant.parse("2026-04-10T05:00:00Z");

        String path = SolutionOneFileNaming.construirPath("siman", "update",
                trackingOrderTime, "9013059587", "104929691");

        assertThat(path).contains("siman/update/").contains("update_20260410050000_9013059587_104929691.json");
    }
}
