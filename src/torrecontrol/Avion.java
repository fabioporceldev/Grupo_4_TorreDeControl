package torrecontrol;

/**
 * Hilo que representa un avión. Su ciclo de vida es:
 * <ol>
 *   <li>Espera un tiempo hasta "llegar" al aeropuerto (retraso de llegada).</li>
 *   <li>Pide pista a la torre ({@link GestorPistas#solicitarPista(Avion)}). Aquí
 *       puede quedarse bloqueado (estado WAITING) hasta que sea su turno.</li>
 *   <li>Hace su maniobra durante {@value #DURACION_MANIOBRA_MS} ms (estado
 *       TIMED_WAITING) sin tener el cerrojo cogido.</li>
 *   <li>Libera la pista ({@link GestorPistas#liberarPista(int, Avion)}).</li>
 * </ol>
 * La pista se libera en un bloque {@code finally}: aunque el hilo sea
 * interrumpido durante la maniobra, la pista nunca se queda ocupada para siempre.
 *
 * @author Grupo 4 - Iván, Alberto y Fabio
 */
public class Avion extends Thread {

    /** Duración de cada maniobra (aterrizaje o despegue): 3 segundos. */
    public static final long DURACION_MANIOBRA_MS = 3000;

    private final int numero;
    private final TipoOperacion tipo;
    private final long retrasoLlegadaMs;
    private final GestorPistas torre;

    /**
     * @param numero           identificador del avión.
     * @param tipo             maniobra que va a pedir.
     * @param retrasoLlegadaMs milisegundos que tarda en llegar y pedir pista.
     * @param torre            gestor de pistas compartido.
     */
    public Avion(int numero, TipoOperacion tipo, long retrasoLlegadaMs, GestorPistas torre) {
        super("Avion-" + numero);
        this.numero = numero;
        this.tipo = tipo;
        this.retrasoLlegadaMs = retrasoLlegadaMs;
        this.torre = torre;
    }

    @Override
    public void run() {
        try {
            Thread.sleep(retrasoLlegadaMs);
            int pista = torre.solicitarPista(this);
            try {
                Thread.sleep(DURACION_MANIOBRA_MS); // maniobra fuera del cerrojo
            } finally {
                torre.liberarPista(pista, this);
            }
        } catch (InterruptedException e) {
            Log.escribir("AVIÓN", "Avión #" + numero + " interrumpido. Abandona la operación.");
            Thread.currentThread().interrupt(); // se conserva el estado de interrupción
        }
    }

    /** @return número identificador del avión. */
    public int getNumero() {
        return numero;
    }

    /** @return tipo de maniobra que solicita. */
    public TipoOperacion getTipo() {
        return tipo;
    }
}
