package torrecontrol;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Utilidad para escribir por consola con el formato pedido en el enunciado:
 * <pre>[10:15:02] [ETIQUETA] mensaje</pre>
 * <p>
 * El método es {@code synchronized} para que dos hilos que escriben a la vez
 * no mezclen sus líneas en la consola.
 *
 * @author Grupo 4 - Iván, Alberto y Fabio
 */
public final class Log {

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss");

    private Log() {
        // Clase de utilidad: no se instancia.
    }

    /**
     * Escribe una línea con la hora actual y la etiqueta indicada.
     *
     * @param etiqueta etiqueta entre corchetes (SOLICITUD, ALERTA, PISTA 1, METEO...).
     * @param mensaje  texto del mensaje.
     */
    public static synchronized void escribir(String etiqueta, String mensaje) {
        System.out.println("[" + LocalTime.now().format(HORA) + "] [" + etiqueta + "] " + mensaje);
    }
}
