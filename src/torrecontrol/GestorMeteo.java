package torrecontrol;

/**
 * Hilo que simula el evento meteorológico: a los {@value #INICIO_MS} ms de
 * empezar la simulación cierra la pista 2 por viento cruzado durante
 * {@value #DURACION_CIERRE_MS} ms y después la reabre.
 * <p>
 * Las esperas se hacen con {@code sleep} fuera del cerrojo del
 * {@link GestorPistas}, así el meteo nunca bloquea a los aviones.
 * Si el hilo es interrumpido con la pista cerrada (por ejemplo porque la
 * simulación termina antes), la pista se reabre en el {@code finally} para no
 * dejar el aeropuerto en un estado incoherente.
 *
 * @author Grupo 4 - Iván, Alberto y Fabio
 */
public class GestorMeteo extends Thread {

    /** Momento en que se cierra la pista 2: 10 segundos. */
    public static final long INICIO_MS = 10_000;

    /** Tiempo que la pista 2 permanece cerrada: 8 segundos. */
    public static final long DURACION_CIERRE_MS = 8_000;

    private final GestorPistas torre;

    /**
     * @param torre gestor de pistas sobre el que actúa.
     */
    public GestorMeteo(GestorPistas torre) {
        super("GestorMeteo");
        this.torre = torre;
    }

    @Override
    public void run() {
        boolean pistaCerrada = false;
        try {
            Thread.sleep(INICIO_MS);
            torre.cerrarPista2();
            pistaCerrada = true;
            Thread.sleep(DURACION_CIERRE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (pistaCerrada) {
                torre.abrirPista2();
            }
        }
    }
}
