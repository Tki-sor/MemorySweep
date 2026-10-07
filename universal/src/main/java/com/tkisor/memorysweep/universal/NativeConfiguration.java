package com.tkisor.memorysweep.universal;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

public final class NativeConfiguration {
    private static volatile boolean registered;
    private static Object spec;
    private static Object enabledValue;
    private static Object intervalValue;
    private static Object silentValue;

    private NativeConfiguration() {
    }

    public static synchronized void register(String loader) {
        if (registered) {
            return;
        }
        if (!"forge".equals(loader) && !"neoforge".equals(loader)) {
            throw new IllegalArgumentException("Native configuration requires Forge or NeoForge");
        }
        String namespace = "forge".equals(loader) ? "net.minecraftforge" : "net.neoforged";
        Object context;
        Object container;
        try {
            Class<?> contextClass = Class.forName(namespace + ".fml.ModLoadingContext");
            context = contextClass.getMethod("get").invoke(null);
            container = activeContainer(context);
            if (!"memorysweep".equals(container.getClass().getMethod("getModId").invoke(container))) {
                throw new IllegalStateException("Active container is not MemorySweep");
            }
            Class<?> specClass = "forge".equals(loader)
                    ? Class.forName("net.minecraftforge.common.ForgeConfigSpec")
                    : resolveClass("net.neoforged.neoforge.common.ModConfigSpec", "net.neoforged.neoforge.common.ForgeConfigSpec");
            Object builder = Class.forName(specClass.getName() + "$Builder").getConstructor().newInstance();
            describe(builder, "memory_sweep", "Enable periodic JVM memory cleanup.");
            Object enabled = builder.getClass().getMethod("define", String.class, boolean.class)
                    .invoke(builder, "memory_sweep", true);
            describe(builder, "sweep_interval_seconds", "Seconds between cleanups. Zero disables periodic cleanup.");
            Object interval = builder.getClass().getMethod("defineInRange", String.class, long.class, long.class, long.class)
                    .invoke(builder, "sweep_interval_seconds", 900L, 0L, 2147483647L);
            describe(builder, "silent", "Suppress cleanup notifications.");
            Object silent = builder.getClass().getMethod("define", String.class, boolean.class)
                    .invoke(builder, "silent", false);
            Object configurationSpec = builder.getClass().getMethod("build").invoke(builder);
            Class<?> configTypeClass = Class.forName(namespace + ".fml.config.ModConfig$Type");
            Object commonType = configTypeClass.getField("COMMON").get(null);
            Method registration = configRegistration(container, commonType, configurationSpec);
            Object target = container;
            if (registration == null) {
                registration = configRegistration(context, commonType, configurationSpec);
                target = context;
            }
            if (registration == null) {
                throw new NoSuchMethodException("Loader registerConfig(Type, spec, String)");
            }
            registration.invoke(target, commonType, configurationSpec, "memorysweep.toml");
            spec = configurationSpec;
            enabledValue = enabled;
            intervalValue = interval;
            silentValue = silent;
            registered = true;
            System.err.println("[MemorySweep] native-config-registered loader=" + loader + ",type=COMMON,file=memorysweep.toml");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            System.err.println("[MemorySweep] native-registration-failed loader=" + loader + ": " + cause(exception));
            return;
        }
        try {
            if (!isClient(namespace)) {
                return;
            }
            if ("forge".equals(loader)) {
                registerForgeScreen(context, container);
            } else {
                registerNeoForgeScreen(container);
            }
        } catch (ClassNotFoundException exception) {
            System.err.println("[MemorySweep] native-screen-unavailable loader=" + loader + ": " + exception.getMessage());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            System.err.println("[MemorySweep] native-screen-registration-failed loader=" + loader + ": " + cause(exception));
        }
    }

    public static boolean isRegistered() {
        return registered;
    }

    public static synchronized Object[] snapshot() {
        if (!registered) {
            return null;
        }
        try {
            if (!isLoaded()) {
                return null;
            }
            Object enabled = enabledValue.getClass().getMethod("get").invoke(enabledValue);
            Object interval = intervalValue.getClass().getMethod("get").invoke(intervalValue);
            Object silent = silentValue.getClass().getMethod("get").invoke(silentValue);
            if (!(enabled instanceof Boolean) || !(interval instanceof Long) || !(silent instanceof Boolean)) {
                throw new IllegalStateException("Unexpected native MemorySweep configuration value types");
            }
            return new Object[]{enabled, interval, silent};
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof IllegalStateException) {
                return null;
            }
            throw new IllegalStateException("Could not read native MemorySweep configuration", cause(exception));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not read native MemorySweep configuration", exception);
        }
    }

    public static synchronized void save(boolean enabled, long intervalSeconds, boolean silent) {
        if (intervalSeconds < 0L || intervalSeconds > 2147483647L) {
            throw new IllegalArgumentException("Cleanup interval must be between 0 and 2147483647 seconds");
        }
        try {
            if (!registered || !isLoaded()) {
                throw new IllegalStateException("Native MemorySweep configuration is not loaded");
            }
            enabledValue.getClass().getMethod("set", Object.class).invoke(enabledValue, enabled);
            intervalValue.getClass().getMethod("set", Object.class).invoke(intervalValue, intervalSeconds);
            silentValue.getClass().getMethod("set", Object.class).invoke(silentValue, silent);
            spec.getClass().getMethod("save").invoke(spec);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not save native MemorySweep configuration", cause(exception));
        }
    }

    private static void describe(Object builder, String key, String description) throws ReflectiveOperationException {
        builder.getClass().getMethod("comment", String.class).invoke(builder, description);
        builder.getClass().getMethod("translation", String.class).invoke(builder, "memorysweep.config." + key);
    }

    private static Object activeContainer(Object context) throws ReflectiveOperationException {
        try {
            return context.getClass().getMethod("getActiveContainer").invoke(context);
        } catch (NoSuchMethodException ignored) {
            return context.getClass().getMethod("getContainer").invoke(context);
        }
    }

    private static Method configRegistration(Object target, Object configType, Object configurationSpec) {
        for (Method method : target.getClass().getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getName().equals("registerConfig") && parameters.length == 3
                    && parameters[0].isInstance(configType) && parameters[1].isInstance(configurationSpec)
                    && parameters[2] == String.class) {
                return method;
            }
        }
        return null;
    }

    private static boolean isLoaded() throws ReflectiveOperationException {
        try {
            return Boolean.TRUE.equals(spec.getClass().getMethod("isLoaded").invoke(spec));
        } catch (NoSuchMethodException ignored) {
            for (String fieldName : new String[]{"childConfig", "loadedConfig"}) {
                try {
                    Field field = spec.getClass().getDeclaredField(fieldName);
                    field.setAccessible(true);
                    return field.get(spec) != null;
                } catch (NoSuchFieldException absent) {
                }
            }
            throw new NoSuchMethodException("Native configuration load-state API");
        }
    }

    private static boolean isClient(String namespace) throws ReflectiveOperationException {
        Class<?> environment = Class.forName(namespace + ".fml.loading.FMLEnvironment");
        Object dist;
        try {
            dist = environment.getField("dist").get(null);
        } catch (NoSuchFieldException ignored) {
            dist = environment.getMethod("getDist").invoke(null);
        }
        return "CLIENT".equals(((Enum<?>) dist).name());
    }

    private static void registerForgeScreen(Object context, Object container) throws ReflectiveOperationException {
        BiFunction<Object, Object, Object> screenFunction = (client, parent) -> createForgeScreen(parent);
        Class<?> factoryClass;
        try {
            factoryClass = resolveClass("net.minecraftforge.client.ConfigScreenHandler$ConfigScreenFactory",
                    "net.minecraftforge.client.ConfigGuiHandler$ConfigGuiFactory");
        } catch (ClassNotFoundException ignored) {
            Class<?> extensionClass = Class.forName("net.minecraftforge.fml.ExtensionPoint");
            Object extension = extensionClass.getField("CONFIGGUIFACTORY").get(null);
            context.getClass().getMethod("registerExtensionPoint", extensionClass, Supplier.class)
                    .invoke(context, extension, (Supplier<Object>) () -> screenFunction);
            System.err.println("[MemorySweep] native-screen-registered loader=forge,factory=CONFIGGUIFACTORY");
            return;
        }
        Object factory;
        try {
            factory = factoryClass.getConstructor(BiFunction.class).newInstance(screenFunction);
        } catch (NoSuchMethodException ignored) {
            Function<Object, Object> parentFunction = NativeConfiguration::createForgeScreen;
            factory = factoryClass.getConstructor(Function.class).newInstance(parentFunction);
        }
        registerClassExtension(container, context, factoryClass, factory);
        System.err.println("[MemorySweep] native-screen-registered loader=forge,factory=" + factoryClass.getName());
    }

    private static Object createForgeScreen(Object parent) {
        try {
            Class<?> screen = Class.forName("com.tkisor.memorysweep.universal.NativeForgeScreen");
            return screen.getMethod("create", Object.class).invoke(null, parent);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not create MemorySweep Forge configuration screen", cause(exception));
        }
    }

    private static void registerNeoForgeScreen(Object container) throws ReflectiveOperationException {
        Class<?> factoryClass = Class.forName("net.neoforged.neoforge.client.gui.IConfigScreenFactory");
        Class<?> screenClass = Class.forName("net.neoforged.neoforge.client.gui.ConfigurationScreen");
        Method createMethod = null;
        for (Method method : factoryClass.getMethods()) {
            if (method.getName().equals("createScreen") && method.getParameterTypes().length == 2) {
                createMethod = method;
                break;
            }
        }
        if (createMethod == null) {
            throw new NoSuchMethodException("NeoForge IConfigScreenFactory.createScreen");
        }
        Constructor<?> screenConstructor = screenClass.getConstructor(createMethod.getParameterTypes());
        final Method factoryMethod = createMethod;
        Object factory = Proxy.newProxyInstance(factoryClass.getClassLoader(), new Class<?>[]{factoryClass}, (proxy, method, arguments) -> {
            if (method.equals(factoryMethod)) {
                return screenConstructor.newInstance(arguments);
            }
            if (method.getDeclaringClass() == Object.class) {
                if (method.getName().equals("equals")) return proxy == arguments[0];
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName().equals("toString")) return "MemorySweep native NeoForge configuration factory";
            }
            throw new UnsupportedOperationException(method.toString());
        });
        registerClassExtension(container, null, factoryClass, factory);
        System.err.println("[MemorySweep] native-screen-registered loader=neoforge,factory=" + screenClass.getName());
    }

    private static void registerClassExtension(Object container, Object context, Class<?> extensionClass, Object factory)
            throws ReflectiveOperationException {
        Object target = container;
        Method registration;
        try {
            registration = container.getClass().getMethod("registerExtensionPoint", Class.class, Supplier.class);
        } catch (NoSuchMethodException ignored) {
            if (context == null) {
                throw ignored;
            }
            target = context;
            registration = context.getClass().getMethod("registerExtensionPoint", Class.class, Supplier.class);
        }
        registration.invoke(target, extensionClass, (Supplier<Object>) () -> factory);
    }

    private static Class<?> resolveClass(String... names) throws ClassNotFoundException {
        ClassNotFoundException last = null;
        for (String name : names) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException exception) {
                last = exception;
            }
        }
        throw last;
    }

    private static Throwable cause(Throwable exception) {
        return exception instanceof InvocationTargetException && exception.getCause() != null ? exception.getCause() : exception;
    }
}
