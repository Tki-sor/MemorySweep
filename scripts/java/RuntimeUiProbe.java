package com.tkisor.memorysweep.testing;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class RuntimeUiProbe {
    private static final AtomicBoolean pending = new AtomicBoolean();
    private static volatile boolean finished;
    private static volatile Object optionsScreen;
    private static volatile Object titleScreen;
    private static volatile boolean screenshotTaken;
    private static long screenshotRequestedAt;
    private static Set<String> priorScreenshots;
    private static boolean screenshotWaitReported;
    private static volatile String lastScreenClass = "";
    private static boolean testOptions;
    private static Instrumentation environment;
    private static boolean configReported;

    public static void premain(String arguments, Instrumentation instrumentation) {
        environment = instrumentation;
        testOptions = !"observe".equals(arguments);
        Thread thread = new Thread(() -> {
            for (int attempt = 0; attempt < 90 && !finished; attempt++) {
                try {
                    Thread.sleep(2000L);
                    Class<?> minecraft = loadedClass(instrumentation, "net.minecraft.client.Minecraft", "net.minecraft.class_310");
                    if (minecraft == null) continue;
                    Thread.currentThread().setContextClassLoader(minecraft.getClassLoader());
                    Object client = singleton(minecraft);
                    if (!(client instanceof Executor) || !pending.compareAndSet(false, true)) continue;
                    ((Executor) client).execute(() -> {
                        try {
                            inspect(client);
                        } catch (Throwable exception) {
                            System.err.println("[MemorySweepTest] ui-harness-error: " + exception);
                            finished = true;
                        } finally {
                            pending.set(false);
                        }
                    });
                } catch (Throwable exception) {
                    System.err.println("[MemorySweepTest] probe-error: " + exception);
                }
            }
            if (!finished) System.err.println("[MemorySweepTest] ui-timeout");
        }, "MemorySweep-UI-Test");
        thread.setDaemon(true);
        thread.start();
    }

    private static Class<?> loadedClass(Instrumentation instrumentation, String... names) {
        for (Class<?> candidate : instrumentation.getAllLoadedClasses()) {
            for (String name : names) {
                if (candidate.getName().equals(name)) return candidate;
            }
        }
        return null;
    }

    private static Object singleton(Class<?> minecraft) throws Exception {
        for (Field field : minecraft.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == minecraft) {
                field.setAccessible(true);
                Object value = field.get(null);
                if (value != null) return value;
            }
        }
        return null;
    }

    private static void inspect(Object client) throws Exception {
        reportRuntimeConfig();
        ClassLoader loader = client.getClass().getClassLoader();
        Class<?> screenClass = resolveClass(loader, "net.minecraft.client.gui.screens.Screen", "net.minecraft.client.gui.screen.Screen", "net.minecraft.class_437");
        Object screenOwner = screenOwner(client, screenClass);
        Object current = fieldOfType(screenOwner, screenClass);
        if (current == null) return;
        String currentScreenClass = current.getClass().getName();
        if (!currentScreenClass.equals(lastScreenClass)) {
            lastScreenClass = currentScreenClass;
            System.err.println("[MemorySweepTest] screen=" + currentScreenClass);
        }
        if (currentScreenClass.endsWith("LoadingErrorScreen")) {
            System.err.println("[MemorySweepTest] loader-screen-error=" + currentScreenClass);
            reportLoaderIssues(current);
            capture(client, loader);
            finished = true;
            return;
        }
        if (hasOverlay(screenOwner)) return;
        System.err.println("[MemorySweepTest] loading-overlay-gone");
        if (optionsScreen == null) {
            String name = current.getClass().getName();
            if (!name.endsWith("TitleScreen") && !name.endsWith("MainMenuScreen") && !name.equals("net.minecraft.class_442")) return;
            if (!testOptions && titleScreen != current) {
                titleScreen = current;
                return;
            }
            System.err.println("[MemorySweepTest] title-screen-ready");
            titleScreen = current;
            if (!testOptions) {
                capture(client, loader);
                finished = true;
                return;
            }
            Class<?> optionsClass = resolveClass(loader, "net.minecraft.client.gui.screens.options.OptionsScreen", "net.minecraft.client.gui.screens.OptionsScreen", "net.minecraft.client.gui.screen.OptionsScreen", "net.minecraft.class_429");
            for (Constructor<?> constructor : optionsClass.getConstructors()) {
                Class<?>[] parameters = constructor.getParameterTypes();
                if ((parameters.length != 2 && parameters.length != 3) || !parameters[0].isInstance(titleScreen)) continue;
                if (parameters.length == 3 && parameters[2] != boolean.class) continue;
                Object options = fieldOfType(client, parameters[1]);
                if (options == null) continue;
                optionsScreen = parameters.length == 2
                        ? constructor.newInstance(titleScreen, options)
                        : constructor.newInstance(titleScreen, options, false);
                setScreen(client, screenClass, optionsScreen);
                System.err.println("[MemorySweepTest] options-screen-opened");
                return;
            }
            throw new NoSuchMethodException("Options screen constructor");
        }
        if (current != optionsScreen) return;
        if (!screenshotTaken) {
            screenshotTaken = true;
            priorScreenshots = screenshotFiles();
            screenshotRequestedAt = System.currentTimeMillis();
            capture(client, loader);
            return;
        }
        if (!screenshotWaitReported) {
            Set<String> completedScreenshots = screenshotFiles();
            completedScreenshots.removeAll(priorScreenshots);
            if (completedScreenshots.isEmpty() && System.currentTimeMillis() - screenshotRequestedAt < 10000L) return;
            screenshotWaitReported = true;
            System.err.println("[MemorySweepTest] screenshot-completed=" + !completedScreenshots.isEmpty()
                    + ",files=" + completedScreenshots);
        }
        Class<?> widgetClass = resolveClass(loader, "net.minecraft.client.gui.components.AbstractWidget",
                "net.minecraft.client.gui.widget.Widget", "net.minecraft.class_339");
        Set<Object> widgets = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        for (Class<?> currentClass = optionsScreen.getClass(); currentClass != null; currentClass = currentClass.getSuperclass()) {
            for (Field field : currentClass.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !List.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                Object value = field.get(optionsScreen);
                if (value instanceof List) {
                    for (Object widget : (List<?>) value) {
                        if (widgetClass.isInstance(widget)) widgets.add(widget);
                    }
                }
            }
        }
        Object memoryButton = null;
        for (Object widget : widgets) {
            if (!widgetLabel(widget).contains("MemorySweep")) continue;
            if (memoryButton != null) throw new IllegalStateException("Multiple MemorySweep widgets");
            memoryButton = widget;
        }
        if (memoryButton == null) {
            System.err.println("[MemorySweepTest] options-button-absent");
        } else {
            System.err.println("[MemorySweepTest] options-button-present");
            inspectAndClick(memoryButton, widgets, loader);
        }
        setScreen(client, screenClass, titleScreen);
        finished = true;
    }

    private static Set<String> screenshotFiles() throws Exception {
        Set<String> files = new HashSet<String>();
        Path directory = Paths.get("screenshots");
        if (!Files.isDirectory(directory)) return files;
        try (DirectoryStream<Path> screenshots = Files.newDirectoryStream(directory, "*.png")) {
            for (Path path : screenshots) {
                if (!Files.isRegularFile(path) || Files.size(path) == 0L) continue;
                files.add(path.getFileName() + ":" + Files.size(path) + ":" + Files.getLastModifiedTime(path).toMillis());
            }
        }
        return files;
    }

    private static String widgetLabel(Object widget) throws Exception {
        for (Method method : widget.getClass().getMethods()) {
            if (method.getParameterTypes().length != 0 || !messageGetter(method.getName())) continue;
            Object label = method.invoke(widget);
            if (label == null) return "";
            Method stringMethod = optionalMethod(label.getClass(), new Class<?>[0], String.class, "getString");
            return stringMethod == null ? label.toString() : (String) stringMethod.invoke(label);
        }
        throw new NoSuchMethodException("Widget message getter: " + widget.getClass().getName());
    }

    private static Field requiredField(Object instance, Class<?> valueType, String... names) throws Exception {
        for (String name : names) {
            for (Class<?> currentClass = instance.getClass(); currentClass != null; currentClass = currentClass.getSuperclass()) {
                try {
                    Field field = currentClass.getDeclaredField(name);
                    if (Modifier.isStatic(field.getModifiers()) || field.getType() != valueType) continue;
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException ignored) {
                }
            }
        }
        throw new NoSuchFieldException(instance.getClass().getName() + ": " + names[0]);
    }

    private static boolean visible(Object widget) throws Exception {
        return requiredField(widget, boolean.class, "visible", "field_22764", "f_93624_", "field_230694_p_").getBoolean(widget);
    }

    private static boolean active(Object widget) throws Exception {
        return requiredField(widget, boolean.class, "active", "field_22763", "f_93623_", "field_230693_o_").getBoolean(widget);
    }

    private static int[] rectangle(Object widget) throws Exception {
        return new int[]{
                requiredField(widget, int.class, "x", "field_22760", "f_93620_", "field_230690_l_").getInt(widget),
                requiredField(widget, int.class, "y", "field_22761", "f_93621_", "field_230691_m_").getInt(widget),
                requiredField(widget, int.class, "width", "field_22758", "f_93618_", "field_230688_j_").getInt(widget),
                requiredField(widget, int.class, "height", "field_22759", "f_93619_", "field_230689_k_").getInt(widget)
        };
    }

    private static boolean overlaps(int[] first, int[] second) {
        return first[2] > 0 && first[3] > 0 && second[2] > 0 && second[3] > 0
                && first[0] < (long) second[0] + second[2] && second[0] < (long) first[0] + first[2]
                && first[1] < (long) second[1] + second[3] && second[1] < (long) first[1] + first[3];
    }

    private static void inspectAndClick(Object memoryButton, Set<Object> widgets, ClassLoader loader) throws Exception {
        boolean buttonVisible = visible(memoryButton);
        boolean buttonActive = active(memoryButton);
        int[] bounds = rectangle(memoryButton);
        int screenWidth = requiredField(optionsScreen, int.class, "width", "field_22789", "f_96543_", "field_230708_k_").getInt(optionsScreen);
        int screenHeight = requiredField(optionsScreen, int.class, "height", "field_22790", "f_96544_", "field_230709_l_").getInt(optionsScreen);
        double centerX = bounds[0] + bounds[2] / 2.0;
        double centerY = bounds[1] + bounds[3] / 2.0;
        boolean boundsValid = bounds[2] > 0 && bounds[3] > 0 && bounds[0] >= 0 && bounds[1] >= 0
                && (long) bounds[0] + bounds[2] <= screenWidth && (long) bounds[1] + bounds[3] <= screenHeight
                && centerX >= 0 && centerY >= 0 && centerX < screenWidth && centerY < screenHeight;
        System.err.println("[MemorySweepTest] ui-visible=" + buttonVisible);
        System.err.println("[MemorySweepTest] ui-active=" + buttonActive);
        System.err.println("[MemorySweepTest] ui-bounds=x:" + bounds[0] + ",y:" + bounds[1] + ",width:" + bounds[2]
                + ",height:" + bounds[3] + ",screenWidth:" + screenWidth + ",screenHeight:" + screenHeight);
        System.err.println("[MemorySweepTest] ui-bounds-check=" + boundsValid);
        boolean overlap = false;
        for (Object widget : widgets) {
            if (widget == memoryButton || !visible(widget)) continue;
            int[] otherBounds = rectangle(widget);
            if (!overlaps(bounds, otherBounds)) continue;
            overlap = true;
            System.err.println("[MemorySweepTest] ui-overlap-widget=" + widget.getClass().getName()
                    + ",label:" + widgetLabel(widget) + ",x:" + otherBounds[0] + ",y:" + otherBounds[1]
                    + ",width:" + otherBounds[2] + ",height:" + otherBounds[3]);
        }
        System.err.println("[MemorySweepTest] ui-overlap=" + overlap);
        String before = new String(Files.readAllBytes(Paths.get("config/memorysweep.toml")), StandardCharsets.UTF_8);
        String beforeLabel = widgetLabel(memoryButton);
        boolean initiallyEnabled = hasSetting(before, "true") && beforeLabel.contains("MemorySweep: ON");
        System.err.println("[MemorySweepTest] ui-initial-enabled=" + initiallyEnabled + ",label:" + beforeLabel);
        if (!buttonVisible || !buttonActive || !boundsValid || overlap || !initiallyEnabled) {
            System.err.println("[MemorySweepTest] gui-click-persistence-failed=preconditions");
            return;
        }
        boolean accepted = nativeScreenClick(loader, centerX, centerY);
        String after = new String(Files.readAllBytes(Paths.get("config/memorysweep.toml")), StandardCharsets.UTF_8);
        boolean persisted = hasSetting(after, "false");
        String afterLabel = widgetLabel(memoryButton);
        boolean labelOff = afterLabel.contains("MemorySweep: OFF");
        System.err.println("[MemorySweepTest] ui-native-click-accepted=" + accepted);
        System.err.println("[MemorySweepTest] ui-toggle-persisted=" + persisted);
        System.err.println("[MemorySweepTest] ui-label-off=" + labelOff + ",label:" + afterLabel);
        if (accepted && persisted && labelOff) {
            System.err.println("[MemorySweepTest] gui-click-persistence-verified");
        } else {
            System.err.println("[MemorySweepTest] gui-click-persistence-failed=postconditions");
        }
    }

    private static boolean hasSetting(String config, String value) {
        return java.util.regex.Pattern.compile("(?m)^\\s*memory_sweep\\s*=\\s*" + value + "\\s*(?:#.*)?$").matcher(config).find();
    }

    private static Method optionalMethod(Class<?> type, Class<?>[] parameters, Class<?> resultType, String... names) {
        for (String name : names) {
            try {
                Method method = type.getMethod(name, parameters);
                if (method.getReturnType() == resultType && !Modifier.isStatic(method.getModifiers())) return method;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private static boolean nativeScreenClick(ClassLoader loader, double centerX, double centerY) throws Exception {
        Method legacy = optionalMethod(optionsScreen.getClass(), new Class<?>[]{double.class, double.class, int.class},
                boolean.class, "mouseClicked", "method_25402", "m_6375_", "func_231044_a_");
        if (legacy != null) {
            System.err.println("[MemorySweepTest] ui-native-click=legacy,method:" + legacy.getName()
                    + ",x:" + centerX + ",y:" + centerY + ",button:0");
            boolean accepted = Boolean.TRUE.equals(legacy.invoke(optionsScreen, centerX, centerY, 0));
            Method release = optionalMethod(optionsScreen.getClass(), new Class<?>[]{double.class, double.class, int.class},
                    boolean.class, "mouseReleased", "method_25406", "m_6348_", "func_231048_c_");
            if (release != null) release.invoke(optionsScreen, centerX, centerY, 0);
            return accepted;
        }
        Class<?> eventClass = Class.forName("net.minecraft.client.input.MouseButtonEvent", false, loader);
        Class<?> infoClass = Class.forName("net.minecraft.client.input.MouseButtonInfo", false, loader);
        Method modern = optionalMethod(optionsScreen.getClass(), new Class<?>[]{eventClass, boolean.class}, boolean.class, "mouseClicked");
        if (modern == null) throw new NoSuchMethodException("Native screen mouseClicked signature unsupported");
        Class<?> inputConstants = Class.forName("com.mojang.blaze3d.platform.InputConstants", false, loader);
        int leftButton = inputConstants.getField("MOUSE_BUTTON_LEFT").getInt(null);
        System.err.println("[MemorySweepTest] ui-primary-button=" + leftButton);
        Object info = infoClass.getConstructor(int.class, int.class).newInstance(leftButton, 0);
        Object event = eventClass.getConstructor(double.class, double.class, infoClass).newInstance(centerX, centerY, info);
        System.err.println("[MemorySweepTest] ui-native-click=event,method:" + modern.getName()
                + ",event:" + event + ",doubleClick:false");
        boolean accepted = Boolean.TRUE.equals(modern.invoke(optionsScreen, event, false));
        Method release = optionalMethod(optionsScreen.getClass(), new Class<?>[]{eventClass}, boolean.class, "mouseReleased");
        if (release != null) release.invoke(optionsScreen, event);
        return accepted;
    }

    private static void reportRuntimeConfig() {
        if (configReported) return;
        Class<?> runtime = loadedClass(environment, "com.tkisor.memorysweep.universal.UniversalRuntime");
        if (runtime == null) return;
        try {
            Class<?> nativeConfig = loadedClass(environment, "com.tkisor.memorysweep.universal.NativeConfiguration");
            if (nativeConfig != null && Boolean.TRUE.equals(nativeConfig.getMethod("isRegistered").invoke(null))) {
                Object values = nativeConfig.getMethod("snapshot").invoke(null);
                if (values == null) return;
                Method refresh = runtime.getDeclaredMethod("getConfig");
                refresh.setAccessible(true);
                refresh.invoke(null);
                System.err.println("[MemorySweepTest] native-config-loaded=true");
            }
            Field configField = runtime.getDeclaredField("config");
            configField.setAccessible(true);
            Object config = configField.get(null);
            if (config == null) return;
            Field enabled = config.getClass().getDeclaredField("enabled");
            Field interval = config.getClass().getDeclaredField("intervalSeconds");
            Field silent = config.getClass().getDeclaredField("silent");
            enabled.setAccessible(true);
            interval.setAccessible(true);
            silent.setAccessible(true);
            System.err.println("[MemorySweepTest] config-values=enabled:" + enabled.getBoolean(config)
                    + ",interval:" + interval.getLong(config) + ",silent:" + silent.getBoolean(config));
            configReported = true;
        } catch (Exception exception) {
            configReported = true;
            System.err.println("[MemorySweepTest] config-probe-error=" + exception);
        }
    }

    private static void reportLoaderIssues(Object screen) throws Exception {
        for (Field field : screen.getClass().getDeclaredFields()) {
            if (!List.class.isAssignableFrom(field.getType()) || Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            Object value = field.get(screen);
            if (!(value instanceof List)) continue;
            for (Object issue : (List<?>) value) {
                if (issue == null) continue;
                System.err.println("[MemorySweepTest] loader-issue=" + field.getName() + ": " + issue);
                for (Method method : issue.getClass().getMethods()) {
                    String name = method.getName();
                    if (method.getParameterTypes().length != 0 || method.getReturnType() != String.class) continue;
                    if (!name.equals("getMessage") && !name.equals("formatToString") && !name.equals("getI18nMessage")) continue;
                    System.err.println("[MemorySweepTest] loader-detail=" + method.invoke(issue));
                }
            }
        }
    }

    private static boolean messageGetter(String name) {
        return name.equals("getMessage") || name.equals("method_25369") || name.equals("m_6035_") || name.equals("func_230458_i_");
    }

    private static boolean hasOverlay(Object owner) throws Exception {
        boolean found = false;
        for (Class<?> currentClass = owner.getClass(); currentClass != null; currentClass = currentClass.getSuperclass()) {
            for (Field field : currentClass.getDeclaredFields()) {
                String type = field.getType().getName();
                if (!type.equals("net.minecraft.client.gui.screens.Overlay") && !type.equals("net.minecraft.client.gui.screen.Overlay")
                        && !type.equals("net.minecraft.client.gui.LoadingGui") && !type.equals("net.minecraft.class_4071")) continue;
                if (Modifier.isStatic(field.getModifiers())) continue;
                found = true;
                field.setAccessible(true);
                if (field.get(owner) != null) return true;
            }
        }
        if (!found) throw new NoSuchFieldException("No screen-owner overlay field");
        return false;
    }

    private static Object screenOwner(Object client, Class<?> screenClass) throws Exception {
        for (Class<?> currentClass = client.getClass(); currentClass != null; currentClass = currentClass.getSuperclass()) {
            for (Field field : currentClass.getDeclaredFields()) {
                if (field.getType() == screenClass) return client;
            }
        }
        for (Field field : client.getClass().getDeclaredFields()) {
            if (!field.getType().getName().equals("net.minecraft.client.gui.Gui")) continue;
            field.setAccessible(true);
            Object value = field.get(client);
            if (value != null) return value;
        }
        return client;
    }

    private static void setScreen(Object client, Class<?> screenClass, Object screen) throws Exception {
        Object owner = screenOwner(client, screenClass);
        for (Method method : owner.getClass().getMethods()) {
            String name = method.getName();
            if (!name.equals("setScreen") && !name.equals("displayGuiScreen") && !name.equals("method_1507") && !name.equals("m_91152_") && !name.equals("func_147108_a")) continue;
            if (method.getParameterTypes().length == 1 && method.getParameterTypes()[0] == screenClass) {
                method.invoke(owner, screen);
                return;
            }
        }
        throw new NoSuchMethodException("Minecraft setScreen");
    }

    private static Object fieldOfType(Object instance, Class<?> fieldType) throws Exception {
        for (Class<?> currentClass = instance.getClass(); currentClass != null; currentClass = currentClass.getSuperclass()) {
            for (Field field : currentClass.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != fieldType) continue;
                field.setAccessible(true);
                Object value = field.get(instance);
                if (value != null) return value;
            }
        }
        return null;
    }

    private static Class<?> resolveClass(ClassLoader loader, String... names) throws ClassNotFoundException {
        for (String name : names) {
            try {
                return Class.forName(name, false, loader);
            } catch (ClassNotFoundException ignored) {
            }
        }
        throw new ClassNotFoundException(names[0]);
    }

    private static void capture(Object client, ClassLoader loader) {
        try {
            Class<?> screenshotClass = resolveClass(loader, "net.minecraft.client.Screenshot", "net.minecraft.util.ScreenShotHelper", "net.minecraft.class_318");
            for (Method method : screenshotClass.getMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (Modifier.isStatic(method.getModifiers()) && method.getName().equals("grab") && parameters.length == 2 && parameters[0].isInstance(client) && parameters[1] == boolean.class) {
                    method.invoke(null, client, false);
                    System.err.println("[MemorySweepTest] screenshot-requested");
                    return;
                }
            }
            Class<?> renderTargetClass = resolveClass(loader, "com.mojang.blaze3d.pipeline.RenderTarget", "net.minecraft.client.shader.Framebuffer", "net.minecraft.class_276");
            Object renderTarget = fieldOfType(client, renderTargetClass);
            if (renderTarget == null) return;
            for (Method method : screenshotClass.getMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (!Modifier.isStatic(method.getModifiers()) || parameters.length != 4 || parameters[0] != File.class || parameters[1] != String.class || parameters[2] != renderTargetClass || parameters[3] != Consumer.class) continue;
                method.invoke(null, new File(System.getProperty("user.dir")), "options-ui-probe.png", renderTarget, (Consumer<Object>) message -> System.err.println("[MemorySweepTest] screenshot: " + message));
                return;
            }
        } catch (Exception exception) {
            System.err.println("[MemorySweepTest] screenshot-unavailable: " + exception);
        }
    }
}
