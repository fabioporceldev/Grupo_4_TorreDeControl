package torrecontrol;

/**
 * Hilo que simula el evento meteorológico: a los {@value #INICIO_MS} ms de empezar
 * la simulación cierra la Pista 2 por viento cruzado durante
 * {@value #DURACION_CIERRE_MS} ms y después la reabre.
 *
 * <h2>Garantías thread-safe</h2>
 * El meteo no toca directamente ningún dato compartido: solo llama a
 * {@link GestorPistas#cerrarPista2()} y {@link GestorPistas#abrirPista2()}, que son
 * secciones críticas protegidas por el cerrojo de la torre. Sus esperas
 * ({@code sleep}) se hacen <b>fuera</b> de ese cerrojo, así que nunca bloquea a
 * los aviones ni compite por las pistas: no puede provocar un interbloqueo.
 *
 * <h2>Interrupciones</h2>
 * Si el hilo es interrumpido con la pista cerrada (por ejemplo, porque la
 * simulación termina antes), la Pista 2 se reabre en el {@code finally} para no
 * dejar el aeropuerto en un estado incoherente.
 *
 * @author Grupo 4 - Iván, Alberto y Fabio
 */
public class GestorMeteo extends Thread {

    /** Momento en que se cierra la Pista 2: 10 segundos. */
    public static final long INICIO_MS = 10_000;

    /** Tiempo que la Pista 2 permanece cerrada: 8 segundos. */
    public static final long DURACION_CIERRE_MS = 8_000;

    /** Gestor de pistas compartido sobre el que actúa. */
    private final GestorPistas torre;

    /**
     * Crea el hilo del meteo.
     *
     * @param torre gestor de pistas compartido sobre el que actúa.
     */
    public GestorMeteo(GestorPistas torre) {
        super("GestorMeteo");
        this.torre = torre;
    }

    /**
     * Cuerpo del hilo: esperar 10 s, cerrar la Pista 2, esperar 8 s y reabrirla.
     */
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
