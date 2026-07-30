package com.siman.ensambles.unogroup.service;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica la tabla de reintentos de SolutionOneRetryPolicy (Diseño §2.9)
 * contra un WireMock que simula Solution One — sin depender de la
 * infraestructura real ni de credenciales verdaderas.
 */
@SpringBootTest
class SolutionOneRetryPolicyWireMockTest {

    static WireMockServer wireMock = new WireMockServer(0);

    @BeforeAll
    static void iniciarWireMock() {
        wireMock.start();
    }

    @AfterAll
    static void detenerWireMock() {
        wireMock.stop();
    }

    @AfterEach
    void limpiarStubs() {
        wireMock.resetAll();
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registry) {
        registry.add("ensambles.adapter.solutionone.base-url", () -> "http://localhost:" + wireMock.port());
        registry.add("ensambles.adapter.solutionone.usuario", () -> "test-user");
        registry.add("ensambles.adapter.solutionone.password", () -> "test-pass");
        registry.add("ensambles.adapter.solutionone.reintentos.max-intentos", () -> 3);
        registry.add("ensambles.adapter.solutionone.reintentos.backoff-inicial-ms", () -> 10);
        registry.add("ensambles.adapter.solutionone.reintentos.backoff-multiplicador", () -> 2);
    }

    @Autowired
    private SolutionOneRetryPolicy retryPolicy;

    @Test
    void exitoInmediatoCon201NoReintenta() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/user/token"))
                .willReturn(okJson("{\"access_token\":\"fake-jwt\",\"expires_at\":\"2026-07-15T22:32:37Z\"}")));
        wireMock.stubFor(post(urlPathEqualTo("/api/v2/user/files/upload"))
                .willReturn(aResponse().withStatus(201)));

        ResultadoProcesamiento resultado = retryPolicy.subir("{}".getBytes(), "assembly/20260713/x.json", "UPLOAD_CREATE");

        assertThat(resultado.exitoso()).isTrue();
        assertThat(resultado.transacciones()).hasSize(2); // AUTH_TOKEN + 1 UPLOAD_CREATE
        assertThat(resultado.transacciones().get(1).getResponse().getStatusCode()).isEqualTo(201);
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/api/v2/user/files/upload")));
    }

    @Test
    void codigo400NoReintenta() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/user/token")).willReturn(okJson("{\"access_token\":\"fake-jwt\",\"expires_at\":\"2026-07-15T22:32:37Z\"}")));
        wireMock.stubFor(post(urlPathEqualTo("/api/v2/user/files/upload")).willReturn(aResponse().withStatus(400)));

        ResultadoProcesamiento resultado = retryPolicy.subir("{}".getBytes(), "assembly/20260713/x.json", "UPLOAD_CREATE");

        assertThat(resultado.exitoso()).isFalse();
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/api/v2/user/files/upload")));
    }

    @Test
    void codigo401RenuevaTokenYReintentaUnaVez() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/user/token")).willReturn(okJson("{\"access_token\":\"fake-jwt\",\"expires_at\":\"2026-07-15T22:32:37Z\"}")));
        wireMock.stubFor(post(urlPathEqualTo("/api/v2/user/files/upload"))
                .inScenario("401-retry")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(401))
                .willSetStateTo("renovado"));
        wireMock.stubFor(post(urlPathEqualTo("/api/v2/user/files/upload"))
                .inScenario("401-retry")
                .whenScenarioStateIs("renovado")
                .willReturn(aResponse().withStatus(201)));

        ResultadoProcesamiento resultado = retryPolicy.subir("{}".getBytes(), "assembly/20260713/x.json", "UPLOAD_CREATE");

        assertThat(resultado.exitoso()).isTrue();
        wireMock.verify(2, getRequestedFor(urlPathEqualTo("/api/v2/user/token"))); // token inicial + renovación
        wireMock.verify(2, postRequestedFor(urlPathEqualTo("/api/v2/user/files/upload")));
    }

    @Test
    void codigo500ReintentaConBackoffHastaAgotarIntentos() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v2/user/token")).willReturn(okJson("{\"access_token\":\"fake-jwt\",\"expires_at\":\"2026-07-15T22:32:37Z\"}")));
        wireMock.stubFor(post(urlPathEqualTo("/api/v2/user/files/upload")).willReturn(aResponse().withStatus(500)));

        ResultadoProcesamiento resultado = retryPolicy.subir("{}".getBytes(), "assembly/20260713/x.json", "UPLOAD_CREATE");

        assertThat(resultado.exitoso()).isFalse();
        // max-intentos=3 en este test (override), todos fallan con 500
        wireMock.verify(3, postRequestedFor(urlPathEqualTo("/api/v2/user/files/upload")));
    }
}
