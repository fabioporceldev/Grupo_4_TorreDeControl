package torrecontrol;

/**
 * Tipos de maniobra que puede solicitar un avión, ordenados por prioridad.
 * <p>
 * El número de prioridad más bajo es el más urgente:
 * <ol>
 *   <li>{@link #EMERGENCIA}: aterrizaje de emergencia (máxima prioridad).</li>
 *   <li>{@link #ATERRIZAJE}: aterrizaje normal.</li>
 *   <li>{@link #DESPEGUE}: despegue (mínima prioridad).</li>
 * </ol>
 *
 * @author Grupo 4 - Iván, Alberto y Fabio
 */
public enum TipoOperacion {

    /** Aterrizaje de emergencia. Prioridad 1. */
    EMERGENCIA(1, "Aterrizaje de Emergencia", "EMERGENCIA", true),

    /** Aterrizaje normal. Prioridad 2. */
    ATERRIZAJE(2, "Aterrizaje Normal", "Aterrizaje Normal", true),

    /** Despegue. Prioridad 3. */
    DESPEGUE(3, "Despegue", "Despegue", false);

    private final int prioridad;
    private final String descripcion;
    private final String etiquetaPista;
    private final boolean esAterrizaje;

    TipoOperacion(int prioridad, String descripcion, String etiquetaPista, boolean esAterrizaje) {
        this.prioridad = prioridad;
        this.descripcion = descripcion;
        this.etiquetaPista = etiquetaPista;
        this.esAterrizaje = esAterrizaje;
    }

    /** @return nivel de prioridad (1 = más urgente). */
    public int getPrioridad() {
        return prioridad;
    }

    /** @return texto usado en los mensajes de solicitud, p. ej. "Aterrizaje Normal". */
    public String getDescripcion() {
        return descripcion;
    }

    /** @return texto usado en los mensajes de pista, p. ej. "EMERGENCIA". */
    public String getEtiquetaPista() {
        return etiquetaPista;
    }

    /**
     * Indica si la maniobra es un aterrizaje (normal o de emergencia).
     * Se usa para contar los aterrizajes consecutivos de la regla anti-inanición.
     *
     * @return {@code true} si es aterrizaje, {@code false} si es despegue.
     */
    public boolean esAterrizaje() {
        return esAterrizaje;
    }
}
