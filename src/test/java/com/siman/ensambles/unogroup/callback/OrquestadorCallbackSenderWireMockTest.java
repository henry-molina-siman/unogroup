package com.siman.ensambles.unogroup.callback;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

@SpringBootTest
class OrquestadorCallbackSenderWireMockTest {

    static WireMockServer wireMock = new WireMockServer(0);

    @BeforeAll
    static void iniciar() {
        wireMock.start();
    }

    @AfterAll
    static void detener() {
        wireMock.stop();
    }

    @AfterEach
    void limpiar() {
        wireMock.resetAll();
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registry) {
        registry.add("ensambles.callback.orquestador-url", () -> "http://localhost:" + wireMock.port());
        registry.add("ensambles.callback.reintentos.max-intentos", () -> 3);
        registry.add("ensambles.callback.reintentos.backoff-inicial-ms", () -> 10);
        registry.add("ensambles.callback.reintentos.backoff-multiplicador", () -> 2);
    }

    @Autowired
    private OrquestadorCallbackSender sender;

    private ResultadoSolicitudRequest requestDePrueba() {
        return ResultadoSolicitudRequest.builder()
                .ordenId("9013059587")
                .sku("104929691")
                .resultadoFinal("ENVIADA_PARTNER")
                .intentos(List.of(IntentoDto.builder().numero(1).tipoPeticion("UPLOAD_CREATE")
                        .codigoHttp(201).exitoso(true).build()))
                .build();
    }

    @Test
    void noReintentaSiElPrimerIntentoTieneExito() {
        wireMock.stubFor(post(urlPathEqualTo("/internal/orquestador/solicitudes/resultado"))
                .willReturn(ok()));

        sender.enviar(requestDePrueba());

        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/internal/orquestador/solicitudes/resultado")));
    }

    @Test
    void agotaLosIntentosYNoLanzaExcepcionSiTodoFalla() {
        wireMock.stubFor(post(urlPathEqualTo("/internal/orquestador/solicitudes/resultado"))
                .willReturn(aResponse().withStatus(500)));

        sender.enviar(requestDePrueba()); // no debe propagar la excepción — se registra en logs

        wireMock.verify(3, postRequestedFor(urlPathEqualTo("/internal/orquestador/solicitudes/resultado")));
    }
}
