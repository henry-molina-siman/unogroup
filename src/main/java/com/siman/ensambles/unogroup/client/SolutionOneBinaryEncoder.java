package com.siman.ensambles.unogroup.client;

import feign.RequestTemplate;
import feign.codec.Encoder;
import feign.jackson.JacksonEncoder;
import java.lang.reflect.Type;

/**
 * La carga hacia Solution One es un body binario (Diseño §2.9/§6.1), no
 * serializado automáticamente por Feign. Confirmado contra el ambiente de
 * prueba real: Content-Type: application/json funciona para ese body
 * binario (Diseño §9.6 F9, resuelto).
 */
public class SolutionOneBinaryEncoder implements Encoder {
    private final Encoder fallback = new JacksonEncoder(); // para otras llamadas del mismo cliente (ej. token)

    @Override
    public void encode(Object requestBody, Type bodyType, RequestTemplate template) {
        if (requestBody instanceof byte[] bytes) {
            template.body(bytes, null);
            template.header("Content-Type", "application/json"); // confirmado contra ambiente de prueba real
        } else {
            fallback.encode(requestBody, bodyType, template);
        }
    }
}
