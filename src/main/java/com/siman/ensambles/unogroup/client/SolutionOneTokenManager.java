package com.siman.ensambles.unogroup.client;

import com.siman.ensambles.unogroup.config.SolutionOneProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Gestiona la obtención del access_token (JWT) de Solution One. No cachea el token entre
 * distintas solicitudes de {@code SolicitudProcesamientoService} — cada
 * procesamiento adquiere un token nuevo al inicio (coherente con el
 * ejemplo del callback en Diseño §2.4, donde AUTH_TOKEN aparece siempre
 * como el primer intento de cada resultado reportado).
 */
@Component
public class SolutionOneTokenManager {

    private final SolutionOneClient client;
    private final SolutionOneProperties properties;

    public SolutionOneTokenManager(SolutionOneClient client, SolutionOneProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public String renovarToken() {
        String credenciales = properties.getUsuario() + ":" + properties.getPassword();
        String basicAuth = "Basic " + Base64.getEncoder()
                .encodeToString(credenciales.getBytes(StandardCharsets.UTF_8));
        return client.obtenerToken(basicAuth).getAccessToken();
    }
}
