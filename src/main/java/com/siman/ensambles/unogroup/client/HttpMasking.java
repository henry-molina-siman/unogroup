package com.siman.ensambles.unogroup.client;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Enmascarado obligatorio de valores sensibles en {@code request.url}
 * (query string) y en headers de request/response — Guía de Transacciones
 * HTTP §3.2. NO aplica a {@code body} (limitación conocida v3).
 */
public final class HttpMasking {

    private static final Set<String> HEADERS_SENSIBLES = Set.of("authorization", "cookie", "set-cookie");
    private static final List<String> SUFIJOS_HEADER_SENSIBLES = List.of("-key", "-token", "-secret");
    private static final List<String> CLAVES_QUERY_SENSIBLES = List.of("token", "key", "secret", "password");

    private HttpMasking() {
    }

    public static Map<String, String> enmascararHeaders(Map<String, String> headers) {
        if (headers == null) {
            return null;
        }
        Map<String, String> resultado = new LinkedHashMap<>();
        headers.forEach((nombre, valor) -> resultado.put(nombre, esHeaderSensible(nombre) ? enmascararValor(valor) : valor));
        return resultado;
    }

    public static String enmascararUrl(String url) {
        if (url == null) {
            return null;
        }
        int indiceQuery = url.indexOf('?');
        if (indiceQuery < 0) {
            return url;
        }
        String base = url.substring(0, indiceQuery);
        String query = url.substring(indiceQuery + 1);
        String[] pares = query.split("&");
        StringBuilder resultado = new StringBuilder(base).append('?');
        for (int i = 0; i < pares.length; i++) {
            if (i > 0) {
                resultado.append('&');
            }
            resultado.append(enmascararParQuery(pares[i]));
        }
        return resultado.toString();
    }

    /** Muestra solo los últimos 4 caracteres, conservando un prefijo tipo "Bearer "/"Basic " sin enmascarar. */
    public static String enmascararValor(String valor) {
        if (valor == null) {
            return null;
        }
        int indiceEspacio = valor.indexOf(' ');
        String prefijo = "";
        String credencial = valor;
        if (indiceEspacio > 0) {
            prefijo = valor.substring(0, indiceEspacio + 1);
            credencial = valor.substring(indiceEspacio + 1);
        }
        String sufijo = credencial.length() <= 4 ? credencial : credencial.substring(credencial.length() - 4);
        return prefijo + "***" + sufijo;
    }

    private static String enmascararParQuery(String par) {
        int indiceIgual = par.indexOf('=');
        if (indiceIgual < 0) {
            return par;
        }
        String clave = par.substring(0, indiceIgual);
        String valor = par.substring(indiceIgual + 1);
        boolean sensible = CLAVES_QUERY_SENSIBLES.stream().anyMatch(k -> clave.toLowerCase(Locale.ROOT).contains(k));
        return clave + "=" + (sensible ? enmascararValor(valor) : valor);
    }

    private static boolean esHeaderSensible(String nombre) {
        String nombreNormalizado = nombre.toLowerCase(Locale.ROOT);
        if (HEADERS_SENSIBLES.contains(nombreNormalizado)) {
            return true;
        }
        return SUFIJOS_HEADER_SENSIBLES.stream().anyMatch(nombreNormalizado::endsWith);
    }
}
