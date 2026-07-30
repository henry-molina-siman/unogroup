package com.siman.ensambles.unogroup.client;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guía de Transacciones HTTP §3.2 — enmascarado obligatorio de headers
 * sensibles y de parámetros de query string, sin ocultar por completo el
 * valor (se conserva la señal de "sí se envió algo").
 */
class HttpMaskingTest {

    @Test
    void enmascaraHeaderAuthorizationConservandoPrefijoYUltimos4Caracteres() {
        Map<String, String> headers = Map.of("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9abcdWT9a");

        Map<String, String> resultado = HttpMasking.enmascararHeaders(headers);

        assertThat(resultado.get("Authorization")).isEqualTo("Bearer ***WT9a");
    }

    @Test
    void noEnmascaraHeadersNoSensibles() {
        Map<String, String> headers = Map.of("Content-Type", "application/json");

        Map<String, String> resultado = HttpMasking.enmascararHeaders(headers);

        assertThat(resultado.get("Content-Type")).isEqualTo("application/json");
    }

    @Test
    void enmascaraHeaderTerminadoEnTokenCaseInsensitive() {
        Map<String, String> headers = Map.of("X-Api-Token", "abcd1234WXYZ");

        Map<String, String> resultado = HttpMasking.enmascararHeaders(headers);

        assertThat(resultado.get("X-Api-Token")).isEqualTo("***WXYZ");
    }

    @Test
    void enmascaraValorDeQueryParamSensibleSinTocarLosDemas() {
        String url = "https://data.solution1.us/api/v2/user/files/upload?path=siman%2Fcreate%2Fx.json&mkdir_parents=true&secret=abcd1234EFGH";

        String resultado = HttpMasking.enmascararUrl(url);

        assertThat(resultado).contains("path=siman%2Fcreate%2Fx.json");
        assertThat(resultado).contains("mkdir_parents=true");
        assertThat(resultado).contains("secret=***EFGH");
        assertThat(resultado).doesNotContain("abcd1234EFGH");
    }

    @Test
    void urlSinQueryStringQuedaSinCambios() {
        String url = "https://data.solution1.us/api/v2/user/token";

        assertThat(HttpMasking.enmascararUrl(url)).isEqualTo(url);
    }
}
