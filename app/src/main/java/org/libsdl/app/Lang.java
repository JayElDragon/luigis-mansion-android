package org.libsdl.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.Toast;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * App language (Options > Help > Language): English or Español.
 * Covers the app's own menus, messages and the boot-menu artwork labels. The game itself keeps the
 * text of the USA disc (English).
 *
 * Every text is written in English in the code; t() swaps it for Spanish when Spanish is on.
 * Until the player picks one, the phone's language decides (Spanish phone = Español).
 */
final class Lang {
    private Lang() { }

    static final String KEY = "lang";
    static final String[] NAMES = {"English", "Español"};
    static volatile boolean es;

    static void load(Context c) {
        SharedPreferences p = c.getSharedPreferences("lm", Context.MODE_PRIVATE);
        String v = p.getString(KEY, null);
        es = v != null ? v.equals("es") : "es".equals(Locale.getDefault().getLanguage());
    }

    static int index() { return es ? 1 : 0; }

    static void set(Context c, int i) {
        es = i == 1;
        c.getSharedPreferences("lm", Context.MODE_PRIVATE).edit().putString(KEY, es ? "es" : "en").commit();
    }

    // ---- text ----

    static String t(String s) {
        if (!es || s == null || s.isEmpty()) return s;
        String r = one(s);
        if (r != null) return r;
        if (s.indexOf('\n') >= 0) {  // messages put together from several lines / paragraphs
            String[] lines = s.split("\n", -1);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) sb.append('\n');
                String x = one(lines[i]);
                sb.append(x != null ? x : lines[i]);
            }
            return sb.toString();
        }
        return s;
    }

    static CharSequence t(CharSequence s) { return s == null ? null : t(s.toString()); }

    private static String one(String s) {
        String r = M.get(s);
        if (r != null) return r;
        String trimmed = s.trim();
        if (!trimmed.equals(s) && (r = M.get(trimmed)) != null) return r;
        for (Object[] p : P) {
            Matcher m = ((Pattern) p[0]).matcher(s);
            if (m.matches()) {
                String out = (String) p[1];
                for (int g = 1; g <= m.groupCount(); g++) {
                    String v = m.group(g);
                    String tv = M.get(v);
                    out = out.replace("$" + g, tv != null ? tv : v);
                }
                return out;
            }
        }
        return null;
    }

    static Toast toast(Context c, CharSequence s, int d) { return Toast.makeText(c, t(s), d); }

    /** Dialog builder that shows titles, messages and buttons in the chosen language. */
    static AlertDialog.Builder dialog(Context c, int theme) { return new B(c, theme); }

    private static final class B extends AlertDialog.Builder {
        B(Context c, int theme) { super(c, theme); }
        @Override public AlertDialog.Builder setTitle(CharSequence s) { return super.setTitle(t(s)); }
        @Override public AlertDialog.Builder setMessage(CharSequence s) { return super.setMessage(t(s)); }
        @Override public AlertDialog.Builder setPositiveButton(CharSequence s, DialogInterface.OnClickListener l) { return super.setPositiveButton(t(s), l); }
        @Override public AlertDialog.Builder setNegativeButton(CharSequence s, DialogInterface.OnClickListener l) { return super.setNegativeButton(t(s), l); }
        @Override public AlertDialog.Builder setNeutralButton(CharSequence s, DialogInterface.OnClickListener l) { return super.setNeutralButton(t(s), l); }
    }

    // ---- Spanish labels painted in the artwork's own style (assets/es_*.png) ----

    private static final Map<String, Bitmap> art = new HashMap<>();

    static Bitmap art(Context c, String name) {
        synchronized (art) {
            if (art.containsKey(name)) return art.get(name);
            Bitmap b = null;
            try (InputStream in = c.getAssets().open(name)) { b = BitmapFactory.decodeStream(in); } catch (Throwable ignored) { }
            art.put(name, b);
            return b;
        }
    }

    // ---- dictionary ----

    private static final Map<String, String> M = new HashMap<>();
    private static final java.util.List<Object[]> P = new java.util.ArrayList<>();

    private static void m(String en, String es) { M.put(en, es); }
    private static void p(String regex, String es) { P.add(new Object[] {Pattern.compile(regex), es}); }

    static {
        // boot menu
        m("Play", "Jugar"); m("Options", "Opciones"); m("Game Files", "Archivos del juego"); m("Quit", "Salir");
        m("Select", "Elegir"); m("Back", "Atrás"); m("Play DLC", "Jugar DLC");
        m("Unofficial port by MsBunnana & JayElDragon", "Port no oficial por MsBunnana y JayElDragon");

        // options: tabs
        m("Graphics", "Gráficos"); m("Controls", "Controles"); m("HUD", "HUD"); m("Audio", "Audio");
        m("Cheats", "Trucos"); m("Help", "Ayuda");
        m("ON", "SÍ"); m("OFF", "NO");

        // options: graphics
        m("Language", "Idioma"); m("Menus and messages of the app", "Menús y mensajes de la app");
        m("Screen Format", "Formato de pantalla"); m("Original, widescreen or ultra-wide", "Original, panorámica o ultra ancha");
        m("Auto", "Auto");
        m("HUD Position", "Posición del HUD"); m("Position of on-screen elements", "Posición de los elementos en pantalla");
        m("Centered (Original)", "Centrado (Original)"); m("Screen edges", "Bordes de pantalla");
        m("Show FPS Counter", "Mostrar FPS"); m("Display frames per second", "Muestra los cuadros por segundo");
        m("Quality Preset", "Calidad general"); m("Adjust overall graphics quality", "Ajusta la calidad gráfica general");
        m("Custom", "Personalizado"); m("Low-end", "Gama baja"); m("Balanced", "Equilibrado"); m("Smooth", "Fluido");
        m("High quality", "Alta calidad"); m("Maximum", "Máximo");
        m("Frame Rate", "Cuadros por segundo"); m("Target frame rate limit", "Límite de cuadros por segundo");
        m("Render Resolution", "Resolución interna"); m("Internal rendering resolution", "Resolución a la que se dibuja el juego");
        m("1x - Fast", "1x - Rápido"); m("2x - Balanced", "2x - Equilibrado"); m("3x - Sharp", "3x - Nítido"); m("4x - Ultra", "4x - Ultra");
        m("Anti-aliasing", "Antialiasing"); m("Smooth edges (MSAA)", "Suaviza los bordes (MSAA)"); m("Off", "No");
        m("VSync", "VSync"); m("Synchronize with display refresh rate", "Sincroniza con la frecuencia de la pantalla");
        m("Performance Mode", "Modo rendimiento"); m("Cooler phone: 60 fps, 1x, no AA, no reverb", "Teléfono más fresco: 60 fps, 1x, sin AA ni reverb");
        m("Boost CPU", "Impulsar CPU");
        m("Experimental: game on the fastest cores (warmer phone)", "Experimental: el juego en los núcleos más rápidos (más calor)");
        m("Experimental: game on the fastest cores", "Experimental: el juego en los núcleos más rápidos");
        m("Not accessible on this device", "No disponible en este dispositivo");
        m("Boost CPU is only for high-end phones", "Impulsar CPU es solo para teléfonos de gama alta");
        m("Custom Textures", "Texturas personalizadas"); m("Load HD texture packs (PNG / DDS)", "Carga paquetes de texturas HD (PNG / DDS)");
        m("Texture Pack", "Paquete de texturas"); m("Which installed pack to use", "Qué paquete instalado usar");
        m("None installed", "Ninguno instalado"); m("None", "Ninguno");
        m("Install Texture Pack", "Instalar texturas"); m("Add a .zip pack from your phone", "Agrega un paquete .zip desde tu teléfono");
        m("Choose…", "Elegir…");
        m("Install packs from the main menu, then pick them here", "Instala los paquetes desde el menú principal y elígelos aquí");
        m("Remove Texture Pack", "Quitar texturas"); m("Delete the selected pack from this app", "Borra el paquete elegido de la app");
        m("Tap again", "Toca otra vez"); m("Remove", "Quitar");
        m("No texture pack selected", "No hay paquete de texturas elegido"); m("Texture pack removed", "Paquete de texturas quitado");

        // options: controls
        m("On-screen Controls", "Controles en pantalla"); m("Show the touch buttons", "Muestra los botones táctiles");
        m("Hide with Controller", "Ocultar con control"); m("Hide touch buttons when a gamepad is connected", "Oculta los botones táctiles al conectar un control");
        m("Preview Controls", "Probar controles"); m("Try the touch layout without starting the game", "Prueba los botones táctiles sin iniciar el juego");
        m("Open", "Abrir"); m("Close options to use the touch controls", "Cierra las opciones para usar los controles táctiles");
        m("Control Opacity", "Opacidad de controles"); m("How see-through the buttons are", "Qué tan transparentes son los botones");
        m("Touch Haptics", "Vibración táctil"); m("Vibrate when a touch button is pressed", "Vibra al tocar un botón");
        m("Light", "Suave"); m("Medium", "Media"); m("Strong", "Fuerte");
        m("Edit Layout", "Editar diseño"); m("Move and resize the on-screen buttons", "Mueve y cambia el tamaño de los botones");
        m("Reset Layout", "Restablecer diseño"); m("Back to the layout that came with the app", "Vuelve al diseño original de la app");
        m("Reset", "Restablecer"); m("Layout reset to default", "Diseño restablecido");
        m("Camera Stick Feel", "Sensación de cámara");
        m("Right stick on controllers: Smooth is gentler for small moves", "Stick derecho del control: Fluido es más suave en movimientos pequeños");
        m("Original", "Original");
        m("Camera Stick Speed", "Velocidad de cámara"); m("How fast the camera turns at full tilt", "Qué tan rápido gira la cámara con el stick al máximo");
        m("Controller Settings", "Ajustes del control");
        m("Remap buttons, rumble, dead zones, Xbox / PlayStation pads", "Botones, vibración, zonas muertas, controles Xbox / PlayStation");
        m("Start the game, then open Options with the MENU button", "Inicia el juego y abre Opciones con el botón MENÚ");

        // options: audio
        m("Master Volume", "Volumen general"); m("Overall loudness", "Volumen de todo");
        m("Music Volume", "Volumen de música"); m("Background music", "Música de fondo");
        m("Effects Volume", "Volumen de efectos"); m("Sound effects and voices", "Efectos de sonido y voces");
        m("Reverb", "Reverberación"); m("Echo effects (turn off for speed)", "Efectos de eco (apágalo para más velocidad)");
        m("Menu Music", "Música del menú"); m("Title screen music", "Música de la pantalla de título");

        // options: cheats
        m("No Damage", "Sin daño"); m("Luigi takes no damage · applies next launch", "Luigi no recibe daño · se aplica al reiniciar");
        m("Extra Health", "Salud extra"); m("5x starting and maximum health", "5x salud inicial y máxima");
        m("Weaker Ghosts", "Fantasmas débiles"); m("Ghost health reduced to 25%", "Salud de fantasmas al 25%");
        m("Super Poltergust", "Super Poltergust"); m("4x vacuum strength", "4x fuerza de la aspiradora");
        m("Poltergust Ride", "Paseo en Poltergust"); m("Glide with momentum throughout the mansion", "Deslízate con impulso por la mansión");
        m("Mirror Mansion", "Mansión espejo"); m("Mirror the world and its controls", "Refleja el mundo y sus controles");
        m("Skip Cutscenes", "Saltar escenas"); m("Press START during a cutscene to skip", "Pulsa START durante una escena para saltarla");

        // options: help
        m("Save Log", "Guardar registro"); m("Saves a report to Downloads · says what went wrong", "Guarda un informe en Descargas · dice qué falló");
        m("Save…", "Guardar…");
        m("Version", "Versión"); m("Luigi's Mansion Android", "Luigi's Mansion Android");
        m("Device", "Dispositivo"); m("Phone, Android and graphics chip", "Teléfono, Android y chip gráfico"); m("View", "Ver");
        m("This device", "Este dispositivo"); m("OK", "Aceptar");
        m("Safe Mode", "Modo seguro"); m("Lightest settings after a crash", "Ajustes más ligeros tras un cierre inesperado");
        m("Safe Mode off: your settings are back", "Modo seguro desactivado: tus ajustes volvieron"); m("Safe Mode on", "Modo seguro activado");
        m("Back Up Saves Now", "Respaldar partidas"); m("Copy to Download/Luigi's Mansion Android (also automatic)", "Copia a Download/Luigi's Mansion Android (también automático)");
        m("Back up", "Respaldar");
        m("Saves backed up to Download/Luigi's Mansion Android", "Partidas respaldadas en Download/Luigi's Mansion Android");
        m("Backup failed", "No se pudo respaldar"); m("No save yet: play first", "Aún no hay partida: juega primero");
        m("Restore Saves", "Restaurar partidas"); m("Pick an LM-saves.zip backup", "Elige un respaldo LM-saves.zip");
        m("Close the game first, then restore from the start menu", "Cierra el juego y restaura desde el menú de inicio");
        m("Clear Shader Cache", "Borrar caché de shaders"); m("Fixes some black screens; first load is slower", "Arregla algunas pantallas negras; la primera carga es más lenta");
        m("Clear", "Borrar"); m("Close the game first, then clear it from the start menu", "Cierra el juego y bórrala desde el menú de inicio");
        m("Shader cache cleared", "Caché de shaders borrada");
        m("Reset Settings", "Restablecer ajustes"); m("Graphics, audio and controls back to default", "Gráficos, audio y controles a sus valores originales");
        m("Settings reset for this phone", "Ajustes restablecidos para este teléfono");
        m("Clear Log", "Borrar registros"); m("Delete saved reports and logs", "Borra los informes y registros guardados"); m("Logs cleared", "Registros borrados");

        // game files screen
        m("Install game (ISO / RVZ)", "Instalar juego (ISO / RVZ)"); m("Install DLC (Premium Deluxe)", "Instalar DLC (Premium Deluxe)");
        m("Choose your USA disc (ISO / RVZ)", "Elige tu disco de EE. UU. (ISO / RVZ)");
        m("Add Premium Deluxe from its .xdelta or GameBanana .zip", "Agrega Premium Deluxe desde su .xdelta o el .zip de GameBanana");
        m("Return to the main menu", "Volver al menú principal");
        m("Set up your USA game first, then add the DLC", "Primero instala tu juego de EE. UU. y luego agrega el DLC");
        m("Installed ✓  ·  choose a patch again to reinstall", "Instalado ✓  ·  elige un parche otra vez para reinstalar");
        m("Installing…", "Instalando…"); m("Copying your disc into the port's storage", "Copiando tu disco al almacenamiento del port");
        m("Current disc", "Disco actual"); m("No game installed", "No hay juego instalado");
        m("Keep this screen open until installation finishes.", "Mantén esta pantalla abierta hasta que termine la instalación.");
        m("Your original file and saved games are kept.", "Tu archivo original y tus partidas se conservan.");
        m("Premium Deluxe", "Premium Deluxe"); m("Installing Premium Deluxe…", "Instalando Premium Deluxe…");
        m("Building the DLC from your own game file", "Creando el DLC a partir de tu propio archivo del juego");
        p("^DLC installed: (.*)$", "DLC instalado: $1");
        p("^(.*) \\((\\d+) MB, played from your phone's storage\\)$", "$1 ($2 MB, se juega desde el almacenamiento del teléfono)");
        p("^(.*) \\(can't be opened: choose it again\\)$", "$1 (no se puede abrir: elígelo otra vez)");
        p("^(.*) \\((\\d+) MB, copy inside the app\\)$", "$1 ($2 MB, copia dentro de la app)");

        // start-up and crash dialogs
        m("Old shader cache cleared", "Caché de shaders antigua borrada");
        p("^Old shader cache cleared \\((.*)\\)\\. The first minutes may load a little slower\\.$",
                "Caché de shaders antigua borrada ($1). Los primeros minutos pueden cargar un poco más lento.");
        m("The game closed unexpectedly", "El juego se cerró inesperadamente");
        m("Sorry about that. You can start in Safe Mode (lighter settings, no texture pack, fresh shader cache), and save a log: it says what went wrong and can be shared so it gets fixed.",
                "Perdón por eso. Puedes iniciar en Modo seguro (ajustes más ligeros, sin texturas, caché de shaders nueva) y guardar un registro: dice qué falló y se puede compartir para arreglarlo.");
        m("Safe Mode on. Turn it off in Options > Help.", "Modo seguro activado. Desactívalo en Opciones > Ayuda.");
        m("Save log", "Guardar registro"); m("Not now", "Ahora no");
        m("Welcome", "Bienvenido");
        m("Your saves are backed up automatically to Download/Luigi's Mansion Android/ after you play, so they survive reinstalling the app.",
                "Tus partidas se respaldan automáticamente en Download/Luigi's Mansion Android/ después de jugar, así no se pierden si reinstalas la app.");
        m("Had the game before? Restore your saves now.", "¿Ya tenías el juego? Restaura tus partidas ahora.");
        m("Restore saves", "Restaurar partidas");
        m("Before you play", "Antes de jugar");
        m("• This phone's graphics chip does not report Vulkan 1.1, which the game's graphics engine uses. It may not start, or may show a black screen.",
                "• El chip gráfico de este teléfono no indica Vulkan 1.1, que usa el motor gráfico del juego. Puede que no inicie o muestre una pantalla negra.");
        m("• This phone has less than 4 GB of memory. Use the Low-end preset and close other apps.",
                "• Este teléfono tiene menos de 4 GB de memoria. Usa la calidad Gama baja y cierra otras apps.");
        m("• Less than 1 GB of free storage. The game needs room for its shader cache and saves.",
                "• Queda menos de 1 GB libre. El juego necesita espacio para su caché de shaders y partidas.");
        m("If the game has trouble, Options > Help > Save Log saves a report.", "Si el juego tiene problemas, Opciones > Ayuda > Guardar registro guarda un informe.");

        // playing
        m("Install the DLC in Game Files first", "Primero instala el DLC en Archivos del juego");
        m("No game file yet", "Aún no hay archivo del juego");
        m("Open Game Files and choose your Luigi's Mansion disc image (.iso or .rvz) first.", "Abre Archivos del juego y elige primero tu imagen de disco de Luigi's Mansion (.iso o .rvz).");
        m("Cancel", "Cancelar");
        m("This game file can't be played", "Este archivo del juego no se puede jugar");
        m("Open Game Files to choose another copy of your disc.", "Abre Archivos del juego para elegir otra copia de tu disco.");
        m("Safe Mode is on (lighter settings). Turn it off in Options > Help.", "El Modo seguro está activado (ajustes más ligeros). Desactívalo en Opciones > Ayuda.");
        m("Set up your USA game first (Install game)", "Primero instala tu juego de EE. UU. (Instalar juego)");
        m("Install DLC: Premium Deluxe", "Instalar DLC: Premium Deluxe");
        m("Choose the Premium Deluxe 2.2.1 patch: the .xdelta file, or the .zip from GameBanana.",
                "Elige el parche Premium Deluxe 2.2.1: el archivo .xdelta o el .zip de GameBanana.");
        m("The app makes the DLC from your own game file (about 220 MB, a few minutes). Your game file is not changed. The DLC has its own saves.",
                "La app crea el DLC a partir de tu propio archivo del juego (unos 220 MB, unos minutos). Tu archivo no se modifica. El DLC tiene sus propias partidas.");
        m("Choose patch", "Elegir parche");
        m("Please pick an .iso or .rvz file", "Elige un archivo .iso o .rvz");
        m("How should the app use this file?", "¿Cómo debe usar la app este archivo?");
        m("Play from where it is (recommended): no copy, saves 1.4 GB, and the file stays on your phone even if the app is removed or reinstalled.",
                "Jugar desde donde está (recomendado): sin copiar, ahorra 1.4 GB y el archivo se queda en tu teléfono aunque borres o reinstales la app.");
        m("Copy into the app: works even if you later move or delete the original, but uses the space twice and is deleted with the app.",
                "Copiar a la app: funciona aunque luego muevas o borres el original, pero ocupa el doble de espacio y se borra con la app.");
        m("Play from where it is", "Jugar desde donde está"); m("Copy into the app", "Copiar a la app");
        m("This file can't be kept linked; copying it into the app instead", "Este archivo no se puede mantener vinculado; se copiará a la app");
        m("Game ready", "Juego listo");
        p("^The game will be read from \"(.*)\" where it is\\. Don't move or rename that file\\.$",
                "El juego se leerá desde \"$1\" donde está. No muevas ni cambies el nombre de ese archivo.");
        m("Done", "Listo");
        m("No storage available", "No hay almacenamiento disponible");
        m("Not enough free storage for this file", "No hay espacio suficiente para este archivo");
        m("Installation complete", "Instalación completa");
        m("The game is installed in the port's storage and ready to play.", "El juego está instalado en el almacenamiento del port y listo para jugar.");
        m("Installation failed", "La instalación falló");
        m("Unable to install the selected disc.", "No se pudo instalar el disco elegido.");
        m("DLC installed!", "¡DLC instalado!");
        p("^(.*) is ready\\. On the main menu press Play, then Play DLC\\.$", "$1 está listo. En el menú principal pulsa Jugar y luego Jugar DLC.");
        m("DLC install failed", "No se pudo instalar el DLC");
        m("The DLC could not be installed.", "No se pudo instalar el DLC.");
        m("Your game file can't be opened. Choose it again in Game Files.", "No se puede abrir tu archivo del juego. Elígelo otra vez en Archivos del juego.");
        m("No game file found.", "No se encontró el archivo del juego.");
        m("The DLC needs an .iso of your game (not .rvz). Choose your .iso in Game Files.", "El DLC necesita un .iso de tu juego (no .rvz). Elige tu .iso en Archivos del juego.");
        m("Restore this save backup?", "¿Restaurar este respaldo?");
        m("Your current save is kept aside inside the app first, then the backup replaces it.", "Tu partida actual se guarda aparte en la app y luego el respaldo la reemplaza.");
        m("Restore", "Restaurar"); m("Saves restored", "Partidas restauradas");
        p("^(\\d+) save file\\(s\\) restored\\.$", "$1 archivo(s) de partida restaurado(s).");
        m("Restore failed", "No se pudo restaurar"); m("Could not read this backup.", "No se pudo leer este respaldo.");
        m("Please pick a .zip texture pack", "Elige un paquete de texturas .zip");
        m("Installing texture pack", "Instalando paquete de texturas"); m("Unpacking… keep this screen open.", "Descomprimiendo… mantén esta pantalla abierta.");
        p("^Unpacking… (\\d+) files$", "Descomprimiendo… $1 archivos");
        m("Unable to install this texture pack.", "No se pudo instalar este paquete de texturas.");
        m("Texture pack installed", "Paquete de texturas instalado");
        p("^(\\d+) textures found\\. It is selected and Custom Textures is turned on\\.$", "Se encontraron $1 texturas. Está elegido y Texturas personalizadas está activado.");
        m("Install failed", "La instalación falló");
        m("TOUCH CONTROLS PREVIEW", "VISTA PREVIA DE CONTROLES TÁCTILES"); m("Tap here or press Back to return", "Toca aquí o pulsa Atrás para volver");

        // frame rate / picture
        m("Even, smooth frames (keep on) · next launch", "Cuadros parejos y fluidos (déjalo activado) · al reiniciar");
        m("Sharp Picture", "Imagen nítida"); m("No console TV blur filter (sharper at 2x and up)", "Sin el filtro borroso de TV de la consola (más nítido en 2x o más)");
        p("^The screen stayed at (\\d+) Hz, so the game runs at (\\d+) fps\\. Battery saver or the phone's screen motion smoothness setting may limit it\\.$",
                "La pantalla se quedó en $1 Hz, así que el juego va a $2 fps. El ahorro de batería o el ajuste de fluidez de pantalla del teléfono pueden limitarlo.");

        // texture pack heat
        m("Weaker Boos", "Boos más débiles"); m("Reduces the Boos' health to 25 · applies next launch", "Reduce la vida de los Boos a 25 · se aplica al iniciar");
        m("Auto Cool-down", "Enfriamiento automático"); m("Lowers the frame rate while the phone is hot", "Baja los cuadros por segundo mientras el teléfono está caliente");
        m("Texture Pack Size", "Tamaño de texturas"); m("Smaller = cooler phone · applied when you press Play", "Más pequeño = teléfono más fresco · se aplica al pulsar Jugar");
        m("1024 (balanced)", "1024 (equilibrado)"); m("512 (coolest)", "512 (más fresco)"); m("2048 (sharp)", "2048 (nítido)"); m("Full (hottest)", "Completo (más calor)");
        m("Making your texture pack cooler", "Optimizando tu paquete de texturas");
        m("One time only: smaller copies for far-away surfaces, so the phone runs cooler. Keep this screen open.",
                "Solo una vez: copias más pequeñas para superficies lejanas, así el teléfono se calienta menos. Mantén esta pantalla abierta.");
        p("^Making the textures phone-friendly… (\\d+) done$", "Adaptando las texturas al teléfono… $1 listas");

        // in game
        m("MENU", "MENÚ"); m("CLOSE", "CERRAR"); m("Open port options", "Abrir opciones del port");
        m("Phone is warm: Boost CPU paused for this session", "El teléfono está caliente: Impulsar CPU en pausa por esta sesión");
        m("Phone cooled down: frame rate restored", "El teléfono se enfrió: cuadros por segundo restaurados");
        p("^Phone is warm: frame rate lowered to (\\d+) fps$", "El teléfono está caliente: bajó a $1 fps");
        m("Save", "Guardar"); m("Tap a button, then drag it. Pinch or use - / + to resize.", "Toca un botón y arrástralo. Pellizca o usa - / + para cambiar el tamaño.");
        m("Drag to move. Pinch or use - / + to resize.", "Arrastra para mover. Pellizca o usa - / + para cambiar el tamaño.");

        // disc / DLC / backup problems
        m("This is the European version (GLMP01). This build of the game runs the USA version (GLME01) only for now.",
                "Esta es la versión europea (GLMP01). Por ahora esta versión del port solo ejecuta la versión de EE. UU. (GLME01).");
        p("^This is the (.*) version \\((.*)\\)\\. Only the USA version \\(GLME01\\) is supported\\.$",
                "Esta es la versión de $1 ($2). Solo se admite la versión de EE. UU. (GLME01).");
        m("USA", "EE. UU."); m("Europe", "Europa"); m("Japan", "Japón"); m("Korea", "Corea"); m("other region", "otra región");
        m("The file is too small to be a GameCube disc.", "El archivo es demasiado pequeño para ser un disco de GameCube.");
        m("This file is not a Luigi's Mansion GameCube disc.", "Este archivo no es un disco de Luigi's Mansion de GameCube.");
        m("Invalid GameCube disc header.", "Encabezado de disco de GameCube no válido.");
        m("The ISO is truncated or has an invalid file table. Make a new copy of your disc.", "El ISO está incompleto o tiene una tabla de archivos no válida. Haz una nueva copia de tu disco.");
        p("^This disc is revision (\\d+)\\. Only revision 0 of the USA version is supported\\.$", "Este disco es la revisión $1. Solo se admite la revisión 0 de la versión de EE. UU.");
        m("Could not read the disc file.", "No se pudo leer el archivo del disco.");
        m("Cannot open the selected file.", "No se puede abrir el archivo elegido.");
        m("App storage is unavailable.", "El almacenamiento de la app no está disponible.");
        m("Not enough free space to install this disc.", "No hay espacio suficiente para instalar este disco.");
        m("Incomplete disc image. The previous installation was kept.", "Imagen de disco incompleta. Se conservó la instalación anterior.");
        p("^The game file \"(.*)\" can't be opened any more \\(moved, renamed or deleted\\)\\. Open Game Files and choose it again\\.$",
                "El archivo del juego \"$1\" ya no se puede abrir (se movió, cambió de nombre o se borró). Abre Archivos del juego y elígelo otra vez.");
        m("The game file can't be read. Open Game Files and choose it again.", "No se puede leer el archivo del juego. Abre Archivos del juego y elígelo otra vez.");
        p("^Your game file can't be used for the DLC: (.*)$", "Tu archivo del juego no sirve para el DLC: $1");
        m("Can't open the patch file.", "No se puede abrir el archivo del parche.");
        m("No .xdelta patch was found in this zip.", "No se encontró un parche .xdelta en este zip.");
        m("Couldn't save the DLC (storage full?).", "No se pudo guardar el DLC (¿almacenamiento lleno?).");
        m("The patched disc has no valid game code.", "El disco parcheado no tiene código de juego válido.");
        p("^This patch is a different Premium Deluxe version\\. This app's DLC engine is made for (.*)\\.$",
                "Este parche es otra versión de Premium Deluxe. El motor del DLC de esta app está hecho para $1.");
        m("Couldn't check the patched disc.", "No se pudo comprobar el disco parcheado.");
        m("This zip is not a Luigi's Mansion save backup.", "Este zip no es un respaldo de partidas de Luigi's Mansion.");
        m("No texture files named like tex1_…_.png or .dds were found in this zip.", "No se encontraron texturas con nombres como tex1_…_.png o .dds en este zip.");
        m("Could not save the texture pack.", "No se pudo guardar el paquete de texturas.");

        // save log
        m("Saving log…", "Guardando registro…"); m("Log saved", "Registro guardado"); m("Share", "Compartir");
        m("Close", "Cerrar"); m("Share log", "Compartir registro");
        p("^Saved to (.*)$", "Guardado en $1");
        p("^Saved in the app's files \\((.*)\\)\\.$", "Guardado en los archivos de la app ($1).");
        p("^What happened: (.*)$", "Qué pasó: $1");
        m("The top of the file explains the problem in plain words.", "El inicio del archivo explica el problema con palabras simples.");
        m("see the report", "mira el informe");
        m("Couldn't open sharing. The log is in Download/Luigi's Mansion Android/Logs.", "No se pudo abrir compartir. El registro está en Download/Luigi's Mansion Android/Logs.");
        m("No text viewer found. Open it with a file manager: Download/Luigi's Mansion Android/Logs.", "No hay visor de texto. Ábrelo con un administrador de archivos: Download/Luigi's Mansion Android/Logs.");
        m("the game engine crashed", "el motor del juego falló");
        m("the app crashed (Java error)", "la app falló (error de Java)");
        m("Android closed it because the phone ran out of memory", "Android lo cerró porque el teléfono se quedó sin memoria");
        m("it froze and Android closed it (not responding)", "se congeló y Android lo cerró (no responde)");
        m("closed by the player (Force stop or cleared)", "lo cerró el jugador (Forzar detención o borrado)");
        m("closed by the player", "lo cerró el jugador");
        m("Android stopped it for using too much power", "Android lo detuvo por usar demasiada energía");
        m("it failed to start", "no pudo iniciar");
        m("The game used a system function the Android version doesn't support yet. A fix is needed in the port.", "El juego usó una función del sistema que la versión de Android aún no admite. Hace falta un arreglo en el port.");
        m("The game jumped to a part of its code the port doesn't know about yet. A fix is needed in the port.", "El juego saltó a una parte de su código que el port aún no conoce. Hace falta un arreglo en el port.");
        m("The game engine stopped on an error.", "El motor del juego se detuvo por un error.");
        m("The phone ran out of memory. Close other apps, or use the Low-end preset / Safe Mode.", "El teléfono se quedó sin memoria. Cierra otras apps o usa Gama baja / Modo seguro.");
        m("The graphics driver reported a problem. Try Safe Mode; updating the phone can help too.", "El controlador gráfico reportó un problema. Prueba el Modo seguro; actualizar el teléfono también puede ayudar.");
        m("There was a problem reading the game file. Open Game Files and choose it again.", "Hubo un problema al leer el archivo del juego. Abre Archivos del juego y elígelo otra vez.");
        m("The game crashed reading or writing memory it shouldn't (SIGSEGV).", "El juego falló al leer o escribir memoria indebida (SIGSEGV).");
        m("The game stopped itself after an error (SIGABRT).", "El juego se detuvo tras un error (SIGABRT).");
        m("The game crashed.", "El juego falló.");
        m("The game closed without leaving an error in the log. Android's record above is the best clue.", "El juego se cerró sin dejar un error en el registro. El registro de Android es la mejor pista.");
        m("No crash was found. Everything in the last run looks normal.", "No se encontró ningún fallo. Todo en la última partida se ve normal.");
        m("The app itself hit an error:", "La app tuvo un error:");
        p("^the game closed itself \\(code (.*)\\)$", "el juego se cerró solo (código $1)");
    }
}
