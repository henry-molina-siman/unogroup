package com.siman.ensambles.unogroup.client;

import com.siman.ensambles.unogroup.service.HttpRequestRegistrado;
import com.siman.ensambles.unogroup.service.HttpResponseRegistrado;
import com.siman.ensambles.unogroup.service.TransaccionErrorRegistrado;
import feign.Client;
import feign.Request;
import feign.Response;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Envoltorio único sobre el {@link Client} de Feign hacia Solution One:
 * captura method/url/headers/body de cada llamada real (auth + upload),
 * aplica el enmascarado obligatorio (Guía de Transacciones HTTP §3.2), y
 * clasifica los fallos de red en {@link TransaccionErrorRegistrado}. Deja
 * el resultado disponible para que {@code SolutionOneRetryPolicy} solo
 * tenga que anexar la metadata de negocio (secuencia/proposito/esReintento)
 * — así ningún call site nuevo tiene que acordarse de aplicar el filtro
 * manualmente (implementación sugerida por la guía §3.2).
 *
 * <p>Registrado como el {@code Client} del {@code solutionOneClient}
 * (ver {@link SolutionOneFeignConfig}) — este bean vive en el contexto
 * principal (para que {@code SolutionOneRetryPolicy} lo pueda inyectar
 * normalmente) y Spring Cloud OpenFeign lo resuelve desde ahí como
 * parámetro del método {@code @Bean} en la configuración específica del
 * cliente (el contexto hijo de Feign tiene el contexto principal como
 * padre).
 *
 * <p><b>ThreadLocal:</b> cada solicitud se procesa de principio a fin en
 * un único hilo (virtual), sin multiplexado (Diseño §2.11) — seguro
 * mientras no se compartan hilos entre procesamientos concurrentes.
 */
@Component
public class CapturingFeignClient implements Client {

    private final Client delegate;

    private final ThreadLocal<HttpRequestRegistrado> ultimaRequest = new ThreadLocal<>();
    private final ThreadLocal<HttpResponseRegistrado> ultimaResponse = new ThreadLocal<>();
    private final ThreadLocal<TransaccionErrorRegistrado> ultimoError = new ThreadLocal<>();

    public CapturingFeignClient() {
        this(new Client.Default(null, null));
    }

    CapturingFeignClient(Client delegate) {
        this.delegate = delegate;
    }

    @Override
    public Response execute(Request request, Request.Options options) throws IOException {
        Instant inicioRequest = Instant.now();
        long inicioMs = System.currentTimeMillis();

        Map<String, String> requestHeaders = aplanarHeaders(request.headers());
        HttpRequestRegistrado requestRegistrado = HttpRequestRegistrado.builder()
                .method(request.httpMethod().name())
                .url(HttpMasking.enmascararUrl(request.url()))
                .timestamp(inicioRequest)
                .contentType(buscarHeader(requestHeaders, "Content-Type"))
                .headers(HttpMasking.enmascararHeaders(requestHeaders))
                .body(request.body() != null ? new String(request.body(), StandardCharsets.UTF_8) : null)
                .build();

        try {
            Response response = delegate.execute(request, options);
            byte[] cuerpo = leerCuerpo(response);
            int duracionMs = (int) (System.currentTimeMillis() - inicioMs);
            Map<String, String> responseHeaders = aplanarHeaders(response.headers());

            ultimaRequest.set(requestRegistrado);
            ultimaResponse.set(HttpResponseRegistrado.builder()
                    .statusCode(response.status())
                    .timestamp(Instant.now())
                    .durationMs(duracionMs)
                    .contentType(buscarHeader(responseHeaders, "Content-Type"))
                    .headers(HttpMasking.enmascararHeaders(responseHeaders))
                    .body(cuerpo != null ? new String(cuerpo, StandardCharsets.UTF_8) : null)
                    .build());
            ultimoError.remove();

            return response.toBuilder().body(cuerpo).build();
        } catch (IOException ex) {
            int duracionMs = (int) (System.currentTimeMillis() - inicioMs);
            ultimaRequest.set(requestRegistrado);
            ultimaResponse.remove();
            ultimoError.set(TransaccionErrorRegistrado.builder()
                    .tipo(clasificar(ex))
                    .mensaje(ex.getMessage())
                    .timestamp(Instant.now())
                    .durationMs(duracionMs)
                    .build());
            throw ex;
        }
    }

    /** Debe llamarse inmediatamente después de cada invocación al cliente Feign capturado. */
    public HttpRequestRegistrado tomarUltimaRequest() {
        HttpRequestRegistrado valor = ultimaRequest.get();
        ultimaRequest.remove();
        return valor;
    }

    public HttpResponseRegistrado tomarUltimaResponse() {
        HttpResponseRegistrado valor = ultimaResponse.get();
        ultimaResponse.remove();
        return valor;
    }

    public TransaccionErrorRegistrado tomarUltimoError() {
        TransaccionErrorRegistrado valor = ultimoError.get();
        ultimoError.remove();
        return valor;
    }

    private String clasificar(IOException ex) {
        if (ex instanceof SocketTimeoutException) {
            return "TIMEOUT";
        }
        if (ex instanceof ConnectException) {
            return "CONEXION_RECHAZADA";
        }
        if (ex instanceof UnknownHostException) {
            return "DNS";
        }
        return "DESCONOCIDO";
    }

    private Map<String, String> aplanarHeaders(Map<String, Collection<String>> headers) {
        Map<String, String> resultado = new LinkedHashMap<>();
        if (headers == null) {
            return resultado;
        }
        headers.forEach((nombre, valores) -> resultado.put(nombre, valores == null || valores.isEmpty() ? null : String.join(",", valores)));
        return resultado;
    }

    private String buscarHeader(Map<String, String> headers, String nombre) {
        for (Map.Entry<String, String> entrada : headers.entrySet()) {
            if (entrada.getKey().equalsIgnoreCase(nombre)) {
                return entrada.getValue();
            }
        }
        return null;
    }

    private byte[] leerCuerpo(Response response) throws IOException {
        if (response.body() == null) {
            return null;
        }
        return response.body().asInputStream().readAllBytes();
    }
}
