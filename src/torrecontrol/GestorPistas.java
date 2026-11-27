package torrecontrol;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * La torre de control: monitor que gestiona las 2 pistas compartidas del
 * aeropuerto. Es el <b>recurso compartido</b> al que acceden a la vez los 12
 * hilos {@link Avion} y el hilo {@link GestorMeteo}.
 *
 * <h2>Estado compartido</h2>
 * <ul>
 *   <li>{@code pistaOcupada[]}: qué pistas están en uso.</li>
 *   <li>{@code pista2Cerrada}: si la Pista 2 está cerrada por viento cruzado.</li>
 *   <li>Tres colas FIFO, una por prioridad: emergencias, aterrizajes y despegues.</li>
 *   <li>{@code aterrizajesConsecutivos}: contador de la regla anti-inanición.</li>
 *   <li>Contadores de métricas (esperas, operaciones por pista, activaciones de la regla).</li>
 * </ul>
 *
 * <h2>Mecanismos de concurrencia y garantías thread-safe</h2>
 * <ul>
 *   <li><b>{@link ReentrantLock} justo ({@code fair = true})</b>: todo el estado
 *       anterior se lee y se modifica <b>solo</b> con el cerrojo cogido. Cada
 *       método público es una <b>sección crítica</b> completa ({@code lock()} al
 *       entrar, {@code unlock()} en un {@code finally}), así que no hay
 *       condiciones de carrera. Al ser justo, los hilos obtienen el cerrojo por
 *       orden de llegada: equidad explícita.</li>
 *   <li><b>{@link Condition} {@code cambioEstado}</b>: el avión que no puede entrar
 *       espera con {@code await()}, que libera el cerrojo y duerme el hilo sin
 *       espera activa (busy-waiting). Cada cambio de estado (pista liberada,
 *       avión que entra, Pista 2 reabierta) hace {@code signalAll()} y cada hilo
 *       vuelve a comprobar su condición en un {@code while}.</li>
 *   <li><b>Colas {@link ArrayDeque}</b>: no son concurrentes porque solo se tocan
 *       dentro de la sección crítica.</li>
 * </ul>
 *
 * <h2>Por qué no basta una cola de prioridad simple</h2>
 * Con una {@code PriorityBlockingQueue} un despegue (prioridad 3) solo sale cuando
 * no queda ningún aterrizaje esperando. Si siguen llegando aterrizajes, el
 * despegue espera indefinidamente: <b>inanición</b> (starvation). Por eso la
 * decisión la toma {@link #siguienteEnTurno()}, que aplica la regla anti-inanición.
 *
 * <h2>Algoritmo de turno</h2>
 * <ol>
 *   <li>Si hay emergencias, entra la primera emergencia (nunca espera por la regla).</li>
 *   <li>Si ya ha habido {@value #MAX_ATERRIZAJES_SEGUIDOS} aterrizajes consecutivos
 *       y hay un despegue esperando, entra el primer despegue.</li>
 *   <li>Si no, entra el primer aterrizaje normal.</li>
 *   <li>Si no hay aterrizajes, entra el primer despegue.</li>
 * </ol>
 * Cada aterrizaje suma 1 al contador; cada despegue lo pone a 0.
 *
 * <h2>Ausencia de interbloqueos (deadlock)</h2>
 * <ul>
 *   <li>Solo hay un cerrojo: no puede haber espera circular entre cerrojos.</li>
 *   <li>Nadie duerme ({@code sleep}) con el cerrojo cogido.</li>
 *   <li>La Pista 1 nunca se cierra: siempre habrá una pista que se libere y
 *       despierte a los que esperan.</li>
 *   <li>Cerrar la Pista 2 solo cambia una variable; el meteo no compite por pistas.</li>
 * </ul>
 *
 * <h2>Interrupciones</h2>
 * Un avión interrumpido mientras espera en {@code await()} sale de su cola, se
 * avisa al resto con {@code signalAll()} (puede cambiar quién va primero) y se
 * relanza la {@link InterruptedException}.
 *
 * @author Grupo 4 - Iván, Alberto y Fabio
 */
public class GestorPistas {

    /** Número de aterrizajes seguidos tras los que se fuerza un despegue. */
    public static final int MAX_ATERRIZAJES_SEGUIDOS = 3;

    /** Número de pistas del aeropuerto. */
    private static final int NUM_PISTAS = 2;
    /** Índice de la Pista 2 en el array {@code pistaOcupada}. */
    private static final int PISTA_2 = 1;
    /** Formato de la hora de los mensajes. */
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Cerrojo justo que protege TODO el estado compartido de la torre. */
    private final ReentrantLock cerrojo = new ReentrantLock(true);
    /** Sala de espera de los aviones; se avisa con {@code signalAll()} en cada cambio. */
    private final Condition cambioEstado = cerrojo.newCondition();

    /** Pistas ocupadas: posición 0 = Pista 1, posición 1 = Pista 2. Protegido por {@code cerrojo}. */
    private final boolean[] pistaOcupada = new boolean[NUM_PISTAS];
    /** {@code true} mientras el viento mantiene cerrada la Pista 2. Protegido por {@code cerrojo}. */
    private boolean pista2Cerrada = false;

    /** Aviones en emergencia esperando, por orden de llegada. Protegido por {@code cerrojo}. */
    private final Deque<Avion> colaEmergencias = new ArrayDeque<>();
    /** Aterrizajes normales esperando, por orden de llegada. Protegido por {@code cerrojo}. */
    private final Deque<Avion> colaAterrizajes = new ArrayDeque<>();
    /** Despegues esperando, por orden de llegada. Protegido por {@code cerrojo}. */
    private final Deque<Avion> colaDespegues = new ArrayDeque<>();

    /** Aterrizajes seguidos desde el último despegue (regla anti-inanición). Protegido por {@code cerrojo}. */
    private int aterrizajesConsecutivos = 0;

    /** Métrica: operaciones realizadas por cada tipo de maniobra. */
    private final int[] operacionesPorTipo = new int[Avion.Tipo.values().length];
    /** Métrica: suma de los tiempos de espera por tipo, en ms. */
    private final long[] esperaTotalPorTipoMs = new long[Avion.Tipo.values().length];
    /** Métrica: mayor tiempo de espera por tipo, en ms. */
    private final long[] esperaMaximaPorTipoMs = new long[Avion.Tipo.values().length];
    /** Métrica: operaciones realizadas en cada pista. */
    private final int[] operacionesPorPista = new int[NUM_PISTAS];
    /** Métrica: veces que la regla anti-inanición ha adelantado un despegue. */
    private int activacionesAntiInanicion = 0;

    /**
     * Crea la torre con las dos pistas libres y abiertas y las colas vacías.
     * Se crea una sola instancia, compartida por todos los hilos.
     */
    public GestorPistas() {
        // El estado inicial se fija en la declaración de los atributos.
    }

    /**
     * Escribe una línea con el formato del enunciado: {@code [HH:mm:ss] [ETIQUETA] mensaje}.
     * <p>
     * Es {@code static synchronized}: un único cerrojo de clase evita que dos hilos
     * que escriben a la vez mezclen sus líneas en la consola.
     *
     * @param etiqueta etiqueta entre corchetes (SOLICITUD, ALERTA, PISTA 1, METEO...).
     * @param mensaje  texto del mensaje.
     */
    public static synchronized void log(String etiqueta, String mensaje) {
        System.out.println("[" + LocalTime.now().format(HORA) + "] [" + etiqueta + "] " + mensaje);
    }

    /**
     * Un avión pide pista. El hilo se bloquea (sin espera activa) hasta que es su
     * turno según {@link #siguienteEnTurno()} y hay una pista libre y abierta.
     * <p>
     * <b>Sección crítica</b>: todo el método se ejecuta con el cerrojo cogido,
     * salvo mientras el hilo duerme en {@code await()}, que lo libera.
     *
     * @param avion avión que solicita la pista.
     * @return número de la pista asignada (1 o 2).
     * @throws InterruptedException si el hilo es interrumpido mientras espera;
     *                              en ese caso el avión sale de su cola.
     */
    public int solicitarPista(Avion avion) throws InterruptedException {
        long inicioEspera = System.currentTimeMillis();
        cerrojo.lock();
        try {
            Deque<Avion> cola = colaDe(avion.getTipo());
            cola.addLast(avion);

            if (avion.getTipo() == Avion.Tipo.EMERGENCIA) {
                log("ALERTA", "¡Avión #" + avion.getNumero() + " solicita ATERRIZAJE DE EMERGENCIA!");
            }

            boolean avisoEsperaMostrado = false;
            try {
                // Siempre en un while: al despertar hay que volver a comprobar la condición.
                while (siguienteEnTurno() != avion || pistaLibre() == -1) {
                    if (!avisoEsperaMostrado) {
                        log("SOLICITUD", "Avión #" + avion.getNumero() + " ("
                                + avion.getTipo().getDescripcion() + ") espera. " + estadoColas());
                        avisoEsperaMostrado = true;
                    }
                    cambioEstado.await(); // libera el cerrojo y duerme
                }
            } catch (InterruptedException e) {
                cola.remove(avion);
                cambioEstado.signalAll();
                throw e;
            }

            // Es su turno y hay pista: la ocupa.
            boolean porAntiInanicion = avion.getTipo() == Avion.Tipo.DESPEGUE
                    && aterrizajesConsecutivos >= MAX_ATERRIZAJES_SEGUIDOS
                    && (!colaAterrizajes.isEmpty() || !colaEmergencias.isEmpty());

            cola.removeFirst();
            int indice = pistaLibre();
            pistaOcupada[indice] = true;

            if (avion.getTipo().esAterrizaje()) {
                aterrizajesConsecutivos++;
            } else {
                if (porAntiInanicion) {
                    activacionesAntiInanicion++;
                    log("ANTI-INANICIÓN", "Priorizando despegue del Avión #" + avion.getNumero()
                            + " tras " + aterrizajesConsecutivos + " aterrizajes consecutivos.");
                }
                aterrizajesConsecutivos = 0;
            }

            registrarMetricas(avion, indice, System.currentTimeMillis() - inicioEspera);

            int numeroPista = indice + 1;
            String accion = avion.getTipo().esAterrizaje() ? "ATERRIZANDO" : "DESPEGANDO";
            log("PISTA " + numeroPista, "Avión #" + avion.getNumero() + " ("
                    + avion.getTipo().getEtiquetaPista() + ") " + accion + " en Pista " + numeroPista + ".");

            // Ha cambiado quién está primero y puede quedar otra pista libre.
            cambioEstado.signalAll();
            return numeroPista;
        } finally {
            cerrojo.unlock();
        }
    }

    /**
     * Un avión termina su maniobra y deja libre la pista. <b>Sección crítica.</b>
     *
     * @param numeroPista pista que se libera (1 o 2).
     * @param avion       avión que la libera.
     */
    public void liberarPista(int numeroPista, Avion avion) {
        cerrojo.lock();
        try {
            pistaOcupada[numeroPista - 1] = false;
            String accion = avion.getTipo().esAterrizaje() ? "ha aterrizado" : "ha despegado";
            log("PISTA " + numeroPista, "Avión #" + avion.getNumero() + " " + accion
                    + ". Pista " + numeroPista + " libre.");
            cambioEstado.signalAll();
        } finally {
            cerrojo.unlock();
        }
    }

    /**
     * Cierra la Pista 2 por viento cruzado. <b>Sección crítica</b> que no espera a
     * nada: solo marca la pista como cerrada para que no se asigne a aviones
     * nuevos. Un avión que ya maniobra en ella termina su maniobra.
     */
    public void cerrarPista2() {
        cerrojo.lock();
        try {
            pista2Cerrada = true;
            log("METEO", "¡ALERTA! Pista 2 CERRADA por viento cruzado. Operando solo con Pista 1.");
            if (pistaOcupada[PISTA_2]) {
                log("METEO", "El avión que está en la Pista 2 termina su maniobra; no se asignarán más.");
            }
        } finally {
            cerrojo.unlock();
        }
    }

    /**
     * Reabre la Pista 2 y despierta a los aviones en espera para que la usen.
     * <b>Sección crítica.</b>
     */
    public void abrirPista2() {
        cerrojo.lock();
        try {
            pista2Cerrada = false;
            log("METEO", "Pista 2 REABIERTA. Operando con 2 pistas.");
            cambioEstado.signalAll();
        } finally {
            cerrojo.unlock();
        }
    }

    /**
     * Imprime las métricas de la simulación: operaciones y tiempo de espera medio y
     * máximo por tipo, uso de cada pista y activaciones de la regla anti-inanición.
     * <b>Sección crítica</b> (lee los contadores con el cerrojo cogido).
     */
    public void imprimirResumen() {
        cerrojo.lock();
        try {
            log("MÉTRICAS", "------------------------------------------------------------");
            for (Avion.Tipo t : Avion.Tipo.values()) {
                int i = t.ordinal();
                long media = operacionesPorTipo[i] == 0 ? 0 : esperaTotalPorTipoMs[i] / operacionesPorTipo[i];
                log("MÉTRICAS", String.format("%-24s operaciones: %2d | espera media: %5.1f s | espera máx: %5.1f s",
                        t.getDescripcion(), operacionesPorTipo[i], media / 1000.0, esperaMaximaPorTipoMs[i] / 1000.0));
            }
            log("MÉTRICAS", "Uso de pistas -> Pista 1: " + operacionesPorPista[0]
                    + " | Pista 2: " + operacionesPorPista[PISTA_2]);
            log("MÉTRICAS", "Activaciones de la regla anti-inanición: " + activacionesAntiInanicion);
            log("MÉTRICAS", "------------------------------------------------------------");
        } finally {
            cerrojo.unlock();
        }
    }

    // ------------------------------------------------------------------
    // Métodos privados: se llaman SIEMPRE con el cerrojo cogido.
    // ------------------------------------------------------------------

    /**
     * Decide qué avión tiene el turno según prioridades y la regla anti-inanición.
     *
     * @return el avión que debe entrar a continuación, o {@code null} si no hay nadie.
     */
    private Avion siguienteEnTurno() {
        if (!colaEmergencias.isEmpty()) {
            return colaEmergencias.peekFirst();
        }
        if (aterrizajesConsecutivos >= MAX_ATERRIZAJES_SEGUIDOS && !colaDespegues.isEmpty()) {
            return colaDespegues.peekFirst();
        }
        if (!colaAterrizajes.isEmpty()) {
            return colaAterrizajes.peekFirst();
        }
        return colaDespegues.peekFirst();
    }

    /**
     * Busca una pista libre y abierta. La Pista 1 se prueba primero.
     *
     * @return índice de la pista (0 = Pista 1, 1 = Pista 2) o -1 si no hay ninguna.
     */
    private int pistaLibre() {
        if (!pistaOcupada[0]) {
            return 0;
        }
        if (!pistaOcupada[PISTA_2] && !pista2Cerrada) {
            return PISTA_2;
        }
        return -1;
    }

    /**
     * Devuelve la cola que corresponde a un tipo de maniobra.
     *
     * @param tipo tipo de maniobra.
     * @return cola de emergencias, aterrizajes o despegues.
     */
    private Deque<Avion> colaDe(Avion.Tipo tipo) {
        switch (tipo) {
            case EMERGENCIA:
                return colaEmergencias;
            case ATERRIZAJE:
                return colaAterrizajes;
            default:
                return colaDespegues;
        }
    }

    /**
     * Acumula las métricas de una operación que acaba de obtener pista.
     *
     * @param avion    avión que entra en pista.
     * @param indice   índice de la pista asignada.
     * @param esperaMs tiempo que ha esperado desde que pidió pista.
     */
    private void registrarMetricas(Avion avion, int indice, long esperaMs) {
        int i = avion.getTipo().ordinal();
        operacionesPorTipo[i]++;
        esperaTotalPorTipoMs[i] += esperaMs;
        esperaMaximaPorTipoMs[i] = Math.max(esperaMaximaPorTipoMs[i], esperaMs);
        operacionesPorPista[indice]++;
    }

    /**
     * Describe cuántos aviones hay en cada cola.
     *
     * @return texto con el número de aviones en cada cola.
     */
    private String estadoColas() {
        return "Cola: [Emergencias: " + colaEmergencias.size()
                + ", Aterrizajes: " + colaAterrizajes.size()
                + ", Despegues: " + colaDespegues.size() + "]";
    }
}
