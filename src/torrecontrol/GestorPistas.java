package torrecontrol;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Monitor que gestiona las 2 pistas compartidas del aeropuerto. Es el recurso
 * compartido por todos los hilos {@link Avion} y por el hilo {@link GestorMeteo}.
 *
 * <h2>Mecanismos de concurrencia elegidos</h2>
 * <ul>
 *   <li><b>{@link ReentrantLock} justo ({@code fair = true})</b>: protege todo el
 *       estado (pistas ocupadas, pista cerrada, colas y contador). Solo un hilo
 *       a la vez modifica el estado, así se evitan condiciones de carrera. Al ser
 *       justo, los hilos consiguen el cerrojo por orden de llegada.</li>
 *   <li><b>{@link Condition} {@code cambioEstado}</b>: los aviones que no pueden
 *       entrar esperan con {@code await()} (sin consumir CPU). Cada vez que cambia
 *       algo (se libera una pista, entra un avión, se reabre la pista 2) se
 *       despierta a todos con {@code signalAll()} y cada uno vuelve a comprobar
 *       si ahora es su turno.</li>
 *   <li><b>Tres colas FIFO ({@link ArrayDeque})</b>, una por prioridad. Dentro de
 *       cada prioridad se respeta el orden de llegada. No son colas concurrentes
 *       porque solo se tocan con el cerrojo cogido.</li>
 * </ul>
 *
 * <h2>Por qué no basta con una cola de prioridad simple</h2>
 * Con una {@code PriorityBlockingQueue} los despegues (prioridad 3) solo salen
 * cuando no queda ningún aterrizaje esperando. Si siguen llegando aterrizajes,
 * un despegue puede esperar indefinidamente: eso es <b>inanición</b>
 * (starvation). Por eso la decisión de quién entra la toma el método
 * {@link #siguienteEnTurno()}, que aplica la regla anti-inanición.
 *
 * <h2>Algoritmo de turno (prioridades + anti-inanición)</h2>
 * <ol>
 *   <li>Si hay emergencias esperando, entra la primera emergencia. Una emergencia
 *       nunca espera por la regla anti-inanición (decisión de diseño: la
 *       seguridad del avión en emergencia está por encima).</li>
 *   <li>Si ya ha habido {@value #MAX_ATERRIZAJES_SEGUIDOS} aterrizajes consecutivos
 *       y hay algún despegue esperando, entra el primer despegue.</li>
 *   <li>Si no, entra el primer aterrizaje normal.</li>
 *   <li>Si no hay aterrizajes, entra el primer despegue.</li>
 * </ol>
 * Cada aterrizaje (normal o de emergencia) suma 1 al contador de aterrizajes
 * consecutivos; cada despegue lo pone a 0.
 *
 * <h2>Evento meteorológico y ausencia de interbloqueos</h2>
 * Cerrar la pista 2 solo cambia una variable ({@code pista2Cerrada}) con el
 * cerrojo cogido. Un avión que ya estaba maniobrando en la pista 2 termina su
 * maniobra; lo que se impide es asignar la pista 2 a aviones nuevos. No hay
 * interbloqueo porque:
 * <ul>
 *   <li>Solo existe un cerrojo, así que no puede haber espera circular entre cerrojos.</li>
 *   <li>Nadie duerme ({@code sleep}) con el cerrojo cogido: la maniobra de 3 s
 *       y las esperas del meteo se hacen fuera.</li>
 *   <li>La pista 1 nunca se cierra, así que siempre hay una pista que acabará
 *       liberándose y despertará a los que esperan.</li>
 *   <li>Al reabrir la pista 2 se hace {@code signalAll()} para que los aviones
 *       en espera la aprovechen enseguida.</li>
 * </ul>
 *
 * <h2>Interrupciones</h2>
 * Si un avión es interrumpido mientras espera en {@code await()}, se le saca de
 * su cola, se avisa al resto con {@code signalAll()} (porque puede haber cambiado
 * quién va primero) y se relanza la {@link InterruptedException}.
 *
 * @author Grupo 4 - Iván, Alberto y Fabio
 */
public class GestorPistas {

    /** Número de aterrizajes seguidos tras los que se fuerza un despegue. */
    public static final int MAX_ATERRIZAJES_SEGUIDOS = 3;

    private static final int NUM_PISTAS = 2;
    private static final int PISTA_2 = 1; // índice de la pista 2 en el array

    private final ReentrantLock cerrojo = new ReentrantLock(true);
    private final Condition cambioEstado = cerrojo.newCondition();

    private final boolean[] pistaOcupada = new boolean[NUM_PISTAS];
    private boolean pista2Cerrada = false;

    private final Deque<Avion> colaEmergencias = new ArrayDeque<>();
    private final Deque<Avion> colaAterrizajes = new ArrayDeque<>();
    private final Deque<Avion> colaDespegues = new ArrayDeque<>();

    private int aterrizajesConsecutivos = 0;

    /**
     * Un avión pide pista. El hilo se bloquea hasta que es su turno y hay una
     * pista libre y abierta.
     *
     * @param avion avión que solicita la pista.
     * @return número de la pista asignada (1 o 2).
     * @throws InterruptedException si el hilo es interrumpido mientras espera;
     *                              en ese caso el avión sale de la cola.
     */
    public int solicitarPista(Avion avion) throws InterruptedException {
        cerrojo.lock();
        try {
            Deque<Avion> cola = colaDe(avion.getTipo());
            cola.addLast(avion);

            if (avion.getTipo() == TipoOperacion.EMERGENCIA) {
                Log.escribir("ALERTA", "¡Avión #" + avion.getNumero()
                        + " solicita ATERRIZAJE DE EMERGENCIA!");
            }

            boolean avisoEsperaMostrado = false;
            try {
                // Se espera mientras no sea su turno o no haya pista disponible.
                // Siempre en un while: tras despertar hay que volver a comprobar.
                while (siguienteEnTurno() != avion || pistaLibre() == -1) {
                    if (!avisoEsperaMostrado) {
                        Log.escribir("SOLICITUD", "Avión #" + avion.getNumero() + " ("
                                + avion.getTipo().getDescripcion() + ") espera. " + estadoColas());
                        avisoEsperaMostrado = true;
                    }
                    cambioEstado.await();
                }
            } catch (InterruptedException e) {
                // Sale de la cola y avisa a los demás: puede cambiar quién va primero.
                cola.remove(avion);
                cambioEstado.signalAll();
                throw e;
            }

            // Es su turno y hay pista: la ocupa.
            boolean porAntiInanicion = avion.getTipo() == TipoOperacion.DESPEGUE
                    && aterrizajesConsecutivos >= MAX_ATERRIZAJES_SEGUIDOS
                    && (!colaAterrizajes.isEmpty() || !colaEmergencias.isEmpty());

            cola.removeFirst();
            int indice = pistaLibre();
            pistaOcupada[indice] = true;

            if (avion.getTipo().esAterrizaje()) {
                aterrizajesConsecutivos++;
            } else {
                if (porAntiInanicion) {
                    Log.escribir("ANTI-INANICIÓN", "Priorizando despegue del Avión #"
                            + avion.getNumero() + " tras " + aterrizajesConsecutivos
                            + " aterrizajes consecutivos.");
                }
                aterrizajesConsecutivos = 0;
            }

            int numeroPista = indice + 1;
            String accion = avion.getTipo().esAterrizaje() ? "ATERRIZANDO" : "DESPEGANDO";
            Log.escribir("PISTA " + numeroPista, "Avión #" + avion.getNumero() + " ("
                    + avion.getTipo().getEtiquetaPista() + ") " + accion + " en Pista "
                    + numeroPista + ".");

            // Ha cambiado quién está primero en las colas y puede quedar otra
            // pista libre: se despierta a los demás para que lo comprueben.
            cambioEstado.signalAll();
            return numeroPista;
        } finally {
            cerrojo.unlock();
        }
    }

    /**
     * Un avión termina su maniobra y deja libre la pista.
     *
     * @param numeroPista pista que se libera (1 o 2).
     * @param avion       avión que la libera.
     */
    public void liberarPista(int numeroPista, Avion avion) {
        cerrojo.lock();
        try {
            pistaOcupada[numeroPista - 1] = false;
            String accion = avion.getTipo().esAterrizaje() ? "ha aterrizado" : "ha despegado";
            Log.escribir("PISTA " + numeroPista, "Avión #" + avion.getNumero() + " " + accion
                    + ". Pista " + numeroPista + " libre.");
            cambioEstado.signalAll();
        } finally {
            cerrojo.unlock();
        }
    }

    /**
     * Cierra la pista 2 por viento cruzado. No espera a nada y no bloquea a
     * nadie: solo marca la pista como cerrada para que no se asigne.
     */
    public void cerrarPista2() {
        cerrojo.lock();
        try {
            pista2Cerrada = true;
            Log.escribir("METEO", "¡ALERTA! Pista 2 CERRADA por viento cruzado. Operando solo con Pista 1.");
            if (pistaOcupada[PISTA_2]) {
                Log.escribir("METEO", "El avión que está en la Pista 2 termina su maniobra; no se asignarán más.");
            }
        } finally {
            cerrojo.unlock();
        }
    }

    /**
     * Reabre la pista 2 y despierta a los aviones en espera para que puedan usarla.
     */
    public void abrirPista2() {
        cerrojo.lock();
        try {
            pista2Cerrada = false;
            Log.escribir("METEO", "Pista 2 REABIERTA. Operando con 2 pistas.");
            cambioEstado.signalAll();
        } finally {
            cerrojo.unlock();
        }
    }

    // ------------------------------------------------------------------
    // Métodos privados: se llaman siempre con el cerrojo cogido.
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
     * Busca una pista libre y abierta. La pista 1 se prueba primero.
     *
     * @return índice de la pista (0 = pista 1, 1 = pista 2) o -1 si no hay ninguna.
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

    private Deque<Avion> colaDe(TipoOperacion tipo) {
        switch (tipo) {
            case EMERGENCIA:
                return colaEmergencias;
            case ATERRIZAJE:
                return colaAterrizajes;
            default:
                return colaDespegues;
        }
    }

    private String estadoColas() {
        return "Cola: [Emergencias: " + colaEmergencias.size()
                + ", Aterrizajes: " + colaAterrizajes.size()
                + ", Despegues: " + colaDespegues.size() + "]";
    }
}
