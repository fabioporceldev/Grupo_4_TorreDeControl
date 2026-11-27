package torrecontrol;

/**
 * Hilo que representa un avión que quiere aterrizar o despegar.
 *
 * <h2>Ciclo de vida del hilo</h2>
 * <ol>
 *   <li><b>Llegada</b>: duerme {@code retrasoLlegadaMs} (estado TIMED_WAITING).</li>
 *   <li><b>Petición de pista</b>: llama a {@link GestorPistas#solicitarPista(Avion)}.
 *       Si no es su turno o no hay pista libre, queda bloqueado dentro del
 *       monitor (estado WAITING) sin consumir CPU.</li>
 *   <li><b>Maniobra</b>: duerme {@value #DURACION_MANIOBRA_MS} ms <b>fuera</b> de la
 *       sección crítica, para que la otra pista pueda usarse a la vez.</li>
 *   <li><b>Liberación</b>: llama a {@link GestorPistas#liberarPista(int, Avion)}
 *       dentro de un {@code finally}, así la pista se libera aunque el hilo sea
 *       interrumpido durante la maniobra.</li>
 * </ol>
 *
 * <h2>Garantías thread-safe</h2>
 * Los atributos de un avión son {@code final} y se fijan en el constructor, antes
 * de {@code start()}: son inmutables y se pueden leer desde cualquier hilo sin
 * sincronización. El avión no comparte ningún otro estado; todo lo compartido
 * vive en {@link GestorPistas}.
 *
 * @author Grupo 4 - Iván, Alberto y Fabio
 */
public class Avion extends Thread {

    /**
     * Tipos de maniobra que puede solicitar un avión, ordenados por prioridad
     * (el número más bajo es el más urgente).
     * <ol>
     *   <li>{@link #EMERGENCIA}: aterrizaje de emergencia (máxima prioridad).</li>
     *   <li>{@link #ATERRIZAJE}: aterrizaje normal.</li>
     *   <li>{@link #DESPEGUE}: despegue (mínima prioridad).</li>
     * </ol>
     * Es un {@code enum} anidado porque solo tiene sentido como característica
     * de un avión. Sus valores son constantes inmutables: thread-safe.
     */
    public enum Tipo {

        /** Aterrizaje de emergencia. Prioridad 1. */
        EMERGENCIA(1, "Aterrizaje de Emergencia", "EMERGENCIA", true),

        /** Aterrizaje normal. Prioridad 2. */
        ATERRIZAJE(2, "Aterrizaje Normal", "Aterrizaje Normal", true),

        /** Despegue. Prioridad 3. */
        DESPEGUE(3, "Despegue", "Despegue", false);

        /** Nivel de prioridad: 1 es el más urgente. */
        private final int prioridad;
        /** Texto usado en los mensajes de solicitud. */
        private final String descripcion;
        /** Texto usado en los mensajes de pista. */
        private final String etiquetaPista;
        /** {@code true} si la maniobra es un aterrizaje. */
        private final boolean esAterrizaje;

        /**
         * Crea un tipo de maniobra.
         *
         * @param prioridad     nivel de prioridad (1 = más urgente).
         * @param descripcion   texto de los mensajes de solicitud.
         * @param etiquetaPista texto de los mensajes de pista.
         * @param esAterrizaje  {@code true} si es un aterrizaje.
         */
        Tipo(int prioridad, String descripcion, String etiquetaPista, boolean esAterrizaje) {
            this.prioridad = prioridad;
            this.descripcion = descripcion;
            this.etiquetaPista = etiquetaPista;
            this.esAterrizaje = esAterrizaje;
        }

        /**
         * Devuelve el nivel de prioridad.
         *
         * @return nivel de prioridad (1 = más urgente).
         */
        public int getPrioridad() {
            return prioridad;
        }

        /**
         * Devuelve el texto de los mensajes de solicitud.
         *
         * @return texto, p. ej. "Aterrizaje Normal".
         */
        public String getDescripcion() {
            return descripcion;
        }

        /**
         * Devuelve el texto de los mensajes de pista.
         *
         * @return texto, p. ej. "EMERGENCIA".
         */
        public String getEtiquetaPista() {
            return etiquetaPista;
        }

        /**
         * Indica si la maniobra es un aterrizaje (normal o de emergencia). La torre
         * lo usa para contar los aterrizajes consecutivos de la regla anti-inanición.
         *
         * @return {@code true} si es aterrizaje, {@code false} si es despegue.
         */
        public boolean esAterrizaje() {
            return esAterrizaje;
        }
    }

    /** Duración de cada maniobra (aterrizaje o despegue): 3 segundos. */
    public static final long DURACION_MANIOBRA_MS = 3000;

    /** Identificador del avión (1..12). */
    private final int numero;
    /** Maniobra que solicita. */
    private final Tipo tipo;
    /** Milisegundos que tarda en llegar y pedir pista. */
    private final long retrasoLlegadaMs;
    /** Gestor de pistas compartido por todos los hilos. */
    private final GestorPistas torre;

    /**
     * Crea un avión. Todos los aviones reciben la <b>misma</b> instancia de
     * {@link GestorPistas}: así comparten el recurso.
     *
     * @param numero           identificador del avión (1..12).
     * @param tipo             maniobra que va a solicitar.
     * @param retrasoLlegadaMs milisegundos que tarda en llegar y pedir pista.
     * @param torre            gestor de pistas compartido.
     */
    public Avion(int numero, Tipo tipo, long retrasoLlegadaMs, GestorPistas torre) {
        super("Avion-" + numero);
        this.numero = numero;
        this.tipo = tipo;
        this.retrasoLlegadaMs = retrasoLlegadaMs;
        this.torre = torre;
    }

    /**
     * Cuerpo del hilo: llegar, pedir pista, maniobrar y liberar la pista.
     * <p>
     * Si el hilo es interrumpido mientras espera pista, {@link GestorPistas} ya lo
     * ha sacado de su cola; aquí solo se informa y se restaura el indicador de
     * interrupción con {@code Thread.currentThread().interrupt()}.
     */
    @Override
    public void run() {
        try {
            Thread.sleep(retrasoLlegadaMs);
            int pista = torre.solicitarPista(this);
            try {
                Thread.sleep(DURACION_MANIOBRA_MS); // maniobra fuera de la sección crítica
            } finally {
                torre.liberarPista(pista, this);
            }
        } catch (InterruptedException e) {
            GestorPistas.log("AVIÓN", "Avión #" + numero + " interrumpido. Abandona la operación.");
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Devuelve el número identificador del avión.
     *
     * @return número del avión.
     */
    public int getNumero() {
        return numero;
    }

    /**
     * Devuelve el tipo de maniobra que solicita el avión.
     *
     * @return tipo de maniobra.
     */
    public Tipo getTipo() {
        return tipo;
    }
}
