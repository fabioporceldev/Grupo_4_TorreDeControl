package torrecontrol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Clase principal de la simulación "Torre de Control" (Grupo 4).
 * <p>
 * Monta y lanza la simulación:
 * <ol>
 *   <li>Crea <b>una sola</b> instancia de {@link GestorPistas} (el recurso compartido)
 *       y el hilo {@link GestorMeteo}.</li>
 *   <li>Crea 12 hilos {@link Avion}, todos con la misma torre:
 *       <ul>
 *         <li>2 aterrizajes de emergencia, que llegan más tarde (entre 4 y 14 s)
 *             para que se vea cómo pasan por delante de los demás;</li>
 *         <li>5 aterrizajes normales y 5 despegues, que llegan en los primeros 6 s.</li>
 *       </ul></li>
 *   <li>Arranca todos los hilos con {@code start()}.</li>
 *   <li>Espera con {@code join()} a que terminen los aviones, interrumpe el meteo si
 *       sigue vivo e imprime las métricas.</li>
 * </ol>
 * Esta clase no comparte estado con los hilos: solo los crea y espera por ellos.
 *
 * @author Grupo 4 - Iván, Alberto y Fabio
 */
public class TorreDeControl {

    /** Número de aviones con aterrizaje de emergencia. */
    private static final int NUM_EMERGENCIAS = 2;
    /** Número de aviones con aterrizaje normal. */
    private static final int NUM_ATERRIZAJES = 5;
    /** Número de aviones que despegan. */
    private static final int NUM_DESPEGUES = 5;

    /** Clase de arranque: no se instancia. */
    private TorreDeControl() {
    }

    /**
     * Punto de entrada de la simulación.
     *
     * @param args no se usan.
     * @throws InterruptedException si el hilo main es interrumpido en un {@code join()}.
     */
    public static void main(String[] args) throws InterruptedException {
        Random random = new Random();
        GestorPistas torre = new GestorPistas();
        GestorMeteo meteo = new GestorMeteo(torre);

        // Lista de maniobras barajada para que el orden varíe en cada ejecución.
        List<Avion.Tipo> tipos = new ArrayList<>();
        for (int i = 0; i < NUM_EMERGENCIAS; i++) tipos.add(Avion.Tipo.EMERGENCIA);
        for (int i = 0; i < NUM_ATERRIZAJES; i++) tipos.add(Avion.Tipo.ATERRIZAJE);
        for (int i = 0; i < NUM_DESPEGUES; i++) tipos.add(Avion.Tipo.DESPEGUE);
        Collections.shuffle(tipos, random);

        List<Avion> aviones = new ArrayList<>();
        for (int i = 0; i < tipos.size(); i++) {
            Avion.Tipo tipo = tipos.get(i);
            long retraso = (tipo == Avion.Tipo.EMERGENCIA)
                    ? 4_000 + random.nextInt(10_000)  // emergencias: entre 4 y 14 s
                    : random.nextInt(6_000);          // resto: entre 0 y 6 s
            aviones.add(new Avion(i + 1, tipo, retraso, torre));
        }

        GestorPistas.log("TORRE", "Inicio de la simulación: " + aviones.size() + " aviones, 2 pistas.");
        meteo.start();
        for (Avion avion : aviones) {
            avion.start();
        }

        for (Avion avion : aviones) {
            avion.join();
        }
        meteo.interrupt(); // si la Pista 2 sigue cerrada, el meteo la reabre al salir
        meteo.join();

        GestorPistas.log("TORRE", "Fin de la simulación: todos los aviones han operado.");
        torre.imprimirResumen();
    }
}
