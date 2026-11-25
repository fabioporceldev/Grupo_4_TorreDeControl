package torrecontrol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Clase principal de la simulación "Torre de Control" (Grupo 4).
 * <p>
 * Crea el {@link GestorPistas} compartido, el hilo {@link GestorMeteo} y
 * 12 hilos {@link Avion}:
 * <ul>
 *   <li>2 aterrizajes de emergencia, que llegan más tarde (entre 4 y 14 s) para
 *       que se vea cómo se cuelan por delante de los demás.</li>
 *   <li>5 aterrizajes normales y 5 despegues, que llegan en los primeros 6 s.</li>
 * </ul>
 * Después espera con {@code join()} a que terminen todos los aviones y, si el
 * meteo sigue vivo, lo interrumpe.
 *
 * @author Grupo 4 - Iván, Alberto y Fabio
 */
public class TorreDeControl {

    private static final int NUM_EMERGENCIAS = 2;
    private static final int NUM_ATERRIZAJES = 5;
    private static final int NUM_DESPEGUES = 5;

    /**
     * Punto de entrada.
     *
     * @param args no se usan.
     * @throws InterruptedException si el hilo main es interrumpido en un join.
     */
    public static void main(String[] args) throws InterruptedException {
        Random random = new Random();
        GestorPistas torre = new GestorPistas();
        GestorMeteo meteo = new GestorMeteo(torre);

        // Se crea la lista de maniobras y se baraja para que el orden varíe.
        List<TipoOperacion> tipos = new ArrayList<>();
        for (int i = 0; i < NUM_EMERGENCIAS; i++) tipos.add(TipoOperacion.EMERGENCIA);
        for (int i = 0; i < NUM_ATERRIZAJES; i++) tipos.add(TipoOperacion.ATERRIZAJE);
        for (int i = 0; i < NUM_DESPEGUES; i++) tipos.add(TipoOperacion.DESPEGUE);
        Collections.shuffle(tipos, random);

        List<Avion> aviones = new ArrayList<>();
        for (int i = 0; i < tipos.size(); i++) {
            TipoOperacion tipo = tipos.get(i);
            long retraso = (tipo == TipoOperacion.EMERGENCIA)
                    ? 4_000 + random.nextInt(10_000)  // emergencias: entre 4 y 14 s
                    : random.nextInt(6_000);          // resto: entre 0 y 6 s
            aviones.add(new Avion(i + 1, tipo, retraso, torre));
        }

        Log.escribir("TORRE", "Inicio de la simulación: 12 aviones, 2 pistas.");
        meteo.start();
        for (Avion avion : aviones) {
            avion.start();
        }

        for (Avion avion : aviones) {
            avion.join();
        }
        meteo.interrupt(); // si la pista 2 sigue cerrada, el meteo la reabre al salir
        meteo.join();
        Log.escribir("TORRE", "Fin de la simulación: todos los aviones han operado.");
    }
}
