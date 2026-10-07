package com.tkisor.memorysweep.universal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class UniversalRuntime {
    private static final long DEFAULT_INTERVAL_SECONDS = 900L;
    private static final AtomicBoolean CLIENT_RUNNING = new AtomicBoolean();
    private static final AtomicBoolean SERVER_RUNNING = new AtomicBoolean();
    private static final AtomicBoolean CLIENT_TICK_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean SERVER_TICK_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean SCHEDULER_STARTED = new AtomicBoolean();
    private static volatile Config config;
    private static volatile long lastClientSweep = System.currentTimeMillis();
    private static volatile long lastServerSweep = System.currentTimeMillis();

    private UniversalRuntime() {
    }

    public static void start() {
        loadConfig();
        startScheduler();
        System.err.println("[MemorySweep] universal runtime started at " + configPath());
    }

    private static void startScheduler() {
        if (!SCHEDULER_STARTED.compareAndSet(false, true)) {
            return;
        }
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "MemorySweep-Universal");
            thread.setDaemon(true);
            return thread;
        });
        System.err.println("[MemorySweep] universal scheduler started");
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (hasClientInstance()) {
                    clientTick();
                }
                if (hasServerInstance()) {
                    serverTick();
                }
            } catch (Throwable exception) {
                System.err.println("[MemorySweep] scheduler error: " + exception.getClass().getName() + ": " + exception.getMessage());
            }
        }, 1L, 1L, TimeUnit.SECONDS);
    }

    private static boolean hasClientInstance() {
        try {
            return findClientInstance() != null;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            if (CLIENT_TICK_LOGGED.compareAndSet(false, true)) {
                System.err.println("[MemorySweep] client probe failed: " + exception.getClass().getName() + ": " + exception.getMessage());
            }
            return false;
        }
    }

    private static Object findClientInstance() throws ReflectiveOperationException {
        Class<?> minecraft = classFor("net.minecraft.client.Minecraft", "net.minecraft.class_310");
        try {
            return minecraft.getMethod("getInstance").invoke(null);
        } catch (NoSuchMethodException ignored) {
            for (java.lang.reflect.Method method : minecraft.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isStatic(method.getModifiers())
                        && method.getParameterTypes().length == 0
                        && minecraft.isAssignableFrom(method.getReturnType())) {
                    method.setAccessible(true);
                    Object instance = method.invoke(null);
                    if (instance != null) {
                        return instance;
                    }
                }
            }
        }
        return null;
    }

    private static boolean hasServerInstance() {
        for (String hookName : new String[]{
                "net.minecraftforge.server.ServerLifecycleHooks",
                "net.neoforged.neoforge.server.ServerLifecycleHooks"}) {
            try {
                Object server = Class.forName(hookName).getMethod("getCurrentServer").invoke(null);
                return server != null;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        return false;
    }

    public static void clientTick() {
        Config current = getConfig();
        if (CLIENT_TICK_LOGGED.compareAndSet(false, true)) {
            System.err.println("[MemorySweep] client tick active");
        }
        if (!current.enabled || current.intervalSeconds == 0L || !elapsed(lastClientSweep, current.intervalSeconds)) {
            return;
        }
        if (!CLIENT_RUNNING.compareAndSet(false, true)) {
            return;
        }
        lastClientSweep = System.currentTimeMillis();
        long before = usedMemory();
        if (!current.silent) {
            sendClientMessage("[MemorySweep] Cleaning client memory: " + megabytes(before) + " MB");
        }
        CompletableFuture.runAsync(() -> {
            System.gc();
            sleep(100L);
            long after = usedMemory();
            CLIENT_RUNNING.set(false);
            System.err.println("[MemorySweep] client cleanup completed");
            if (!current.silent) {
                sendClientMessage("[MemorySweep] Client memory after cleanup: " + megabytes(after) + " MB");
            }
        });
    }

    public static void serverTick() {
        Config current = getConfig();
        if (SERVER_TICK_LOGGED.compareAndSet(false, true)) {
            System.err.println("[MemorySweep] server tick active");
        }
        if (!current.enabled || current.intervalSeconds == 0L || !elapsed(lastServerSweep, current.intervalSeconds)) {
            return;
        }
        if (!SERVER_RUNNING.compareAndSet(false, true)) {
            return;
        }
        lastServerSweep = System.currentTimeMillis();
        CompletableFuture.runAsync(() -> {
            System.gc();
            sleep(100L);
            SERVER_RUNNING.set(false);
            System.err.println("[MemorySweep] server cleanup completed");
        });
    }

    private static Class<?> componentClass() throws ClassNotFoundException {
        return classFor("net.minecraft.network.chat.Component", "net.minecraft.util.text.ITextComponent", "net.minecraft.class_2561");
    }

    private static java.lang.reflect.Method methodFor(Class<?> type, Class<?>[] parameters, String... names) throws NoSuchMethodException {
        for (String name : names) {
            try {
                return type.getMethod(name, parameters);
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + names[0]);
    }

    private static Class<?> classFor(String... names) throws ClassNotFoundException {
        for (String name : names) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException ignored) {
            }
        }
        throw new ClassNotFoundException(names[0]);
    }

    private static Config getConfig() {
        if (config == null) {
            loadConfig();
        }
        Object[] nativeValues = NativeConfiguration.snapshot();
        if (nativeValues != null) {
            config.enabled = (Boolean) nativeValues[0];
            config.intervalSeconds = ((Number) nativeValues[1]).longValue();
            config.silent = (Boolean) nativeValues[2];
        }
        return config;
    }

    private static synchronized void loadConfig() {
        if (config != null) {
            return;
        }
        config = new Config();
        if (NativeConfiguration.isRegistered()) {
            return;
        }
        Path path = configPath();
        try {
            if (Files.exists(path)) {
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    String value = line.trim();
                    if (value.isEmpty() || value.startsWith("#") || !value.contains("=")) {
                        continue;
                    }
                    String[] entry = value.split("=", 2);
                    config.apply(entry[0].trim(), entry[1].trim());
                }
            }
            Files.createDirectories(path.getParent());
            Files.write(path, config.toml().getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            System.err.println("MemorySweep could not read config: " + exception.getMessage());
        }
    }

    private static Path configPath() {
        if (NativeConfiguration.isRegistered()) {
            for (String name : new String[]{"net.minecraftforge.fml.loading.FMLPaths", "net.neoforged.fml.loading.FMLPaths"}) {
                try {
                    Class<?> paths = Class.forName(name);
                    Object directory = paths.getField("CONFIGDIR").get(null);
                    return ((Path) paths.getMethod("get").invoke(directory)).resolve("memorysweep.toml");
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
        } else {
            try {
                Class<?> loaderClass = Class.forName("net.fabricmc.loader.api.FabricLoader");
                Object loader = loaderClass.getMethod("getInstance").invoke(null);
                return ((Path) loaderClass.getMethod("getConfigDir").invoke(loader)).resolve("memorysweep.toml");
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        return Paths.get(System.getProperty("user.dir"), "config", "memorysweep.toml");
    }

    private static boolean elapsed(long last, long intervalSeconds) {
        return System.currentTimeMillis() - last >= intervalSeconds * 1000L;
    }

    private static long usedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static long megabytes(long bytes) {
        return bytes / 1024L / 1024L;
    }

    private static void sleep(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sendClientMessage(String message) {
        try {
            Object minecraft = findClientInstance();
            Object player = findPlayer(minecraft);
            if (player == null) {
                return;
            }
            Object component = component(message);
            if (component == null) {
                return;
            }
            for (java.lang.reflect.Method method : player.getClass().getMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length == 2 && parameters[1] == boolean.class
                        && (method.getName().contains("display") || method.getName().contains("send"))) {
                    method.invoke(player, component, true);
                    return;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    private static Object findPlayer(Object minecraft) throws ReflectiveOperationException {
        Class<?> type = minecraft.getClass();
        while (type != null) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (field.getName().equals("player")) {
                    field.setAccessible(true);
                    return field.get(minecraft);
                }
            }
            type = type.getSuperclass();
        }
        for (java.lang.reflect.Method method : minecraft.getClass().getMethods()) {
            if (method.getParameterTypes().length == 0 && method.getName().toLowerCase().contains("player")) {
                return method.invoke(minecraft);
            }
        }
        return null;
    }

    private static Object component(String message) throws ReflectiveOperationException {
        try {
            return methodFor(componentClass(), new Class<?>[]{String.class},
                    "literal", "method_43470", "m_237113_").invoke(null, message);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            Class<?> legacy = classFor("net.minecraft.network.chat.TextComponent",
                    "net.minecraft.util.text.StringTextComponent", "net.minecraft.class_2585");
            return legacy.getConstructor(String.class).newInstance(message);
        }
    }

    private static final class Config {
        private volatile boolean enabled = true;
        private volatile long intervalSeconds = DEFAULT_INTERVAL_SECONDS;
        private volatile boolean silent;

        private void apply(String key, String value) {
            if ("memory_sweep".equals(key)) {
                enabled = Boolean.parseBoolean(value);
            } else if ("sweep_interval_seconds".equals(key)) {
                intervalSeconds = Math.max(0L, Long.parseLong(value));
            } else if ("silent".equals(key)) {
                silent = Boolean.parseBoolean(value);
            }
        }

        private String toml() {
            return "# MemorySweep universal configuration\n"
                    + "memory_sweep = " + enabled + "\n"
                    + "sweep_interval_seconds = " + intervalSeconds + "\n"
                    + "silent = " + silent + "\n";
        }
    }
}
