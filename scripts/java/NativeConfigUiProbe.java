package com.tkisor.memorysweep.testing;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public final class NativeConfigUiProbe {
    private static final AtomicBoolean PENDING = new AtomicBoolean();
    private static volatile boolean finished;
    private static Instrumentation environment;
    private static int phase;
    private static String loaderName;
    private static ClassLoader gameLoader;
    private static Class<?> screenType;
    private static Class<?> nativeConfiguration;
    private static Object client;
    private static Object title;
    private static Object options;
    private static Object mods;
    private static Object configurationScreen;
    private static Object[] baseline;
    private static Path configPath;
    private static long captureTime;
    private static int saveObservations;

    private NativeConfigUiProbe() {
    }

    public static void premain(String arguments, Instrumentation instrumentation) {
        RuntimeUiProbe.premain("observe", instrumentation);
        if ("observe".equals(arguments)) return;
        environment = instrumentation;
        Thread worker = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 150000L;
            while (!finished && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(2000L);
                    Class<?> minecraft = loadedClass("net.minecraft.client.Minecraft", "net.minecraft.class_310");
                    if (minecraft == null) continue;
                    Thread.currentThread().setContextClassLoader(minecraft.getClassLoader());
                    Object instance = legacy("singleton", new Class<?>[]{Class.class}, minecraft);
                    if (!(instance instanceof Executor) || !PENDING.compareAndSet(false, true)) continue;
                    ((Executor) instance).execute(() -> {
                        try {
                            step(instance);
                        } catch (AssertionError failure) {
                            emit("native-config-save-failed=" + failure.getMessage());
                            emit("gui-click-persistence-failed=" + failure.getMessage());
                            finished = true;
                        } catch (Throwable failure) {
                            Throwable cause = unwrap(failure);
                            emit("ui-harness-error: " + cause.getClass().getName() + ": " + cause.getMessage());
                            finished = true;
                        } finally {
                            PENDING.set(false);
                        }
                    });
                } catch (Throwable failure) {
                    emit("native-probe-error=" + unwrap(failure));
                }
            }
            if (!finished) emit("ui-timeout=native-config-phase-" + phase);
        }, "MemorySweep-Native-Config-Test");
        worker.setDaemon(true);
        worker.start();
    }

    private static void step(Object instance) throws Exception {
        if (finished) return;
        client = instance;
        gameLoader = client.getClass().getClassLoader();
        screenType = resolve("net.minecraft.client.gui.screens.Screen", "net.minecraft.client.gui.screen.Screen", "net.minecraft.class_437");
        if (phase == 0) {
            if (!legacyField("finished", boolean.class).getBoolean(null)) return;
            String last = (String) legacyField("lastScreenClass", String.class).get(null);
            if (last.endsWith("LoadingErrorScreen")) {
                emit("native-ui-loader-error");
                finished = true;
                return;
            }
            title = legacyField("titleScreen", Object.class).get(null);
            if (title == null || currentScreen() != title) throw new IllegalStateException("Core probe did not finish on an observed title screen");
            nativeConfiguration = resolve("com.tkisor.memorysweep.universal.NativeConfiguration");
            boolean registered = (Boolean) nativeConfiguration.getMethod("isRegistered").invoke(null);
            loaderName = registered ? exists("net.neoforged.fml.ModList") ? "neoforge" : "forge" : "fabric";
            if ("fabric".equals(loaderName) && !exists("net.fabricmc.loader.api.FabricLoader")) {
                throw new AssertionError("Native configuration is not registered on the active loader");
            }
            baseline = registered ? snapshot() : null;
            if (registered && !same(baseline, new Object[]{true, 5L, true})) throw new AssertionError("Native fixture differs from true/5/true: " + Arrays.toString(baseline));
            Class<?> runtime = resolve("com.tkisor.memorysweep.universal.UniversalRuntime");
            Method configMethod = runtime.getDeclaredMethod("configPath");
            configMethod.setAccessible(true);
            configPath = (Path) configMethod.invoke(null);
            options = optionsScreen();
            show(options);
            emit("native-options-screen-opened=" + options.getClass().getName());
            phase = 1;
            return;
        }
        Object screen = currentScreen();
        if (phase == 1) {
            if (screen != options) return;
            for (Object widget : widgets(screen)) {
                if (label(widget).toLowerCase(Locale.ROOT).contains("memorysweep")) throw new AssertionError("MemorySweep widget remains in vanilla Options");
            }
            emit("native-options-button-absent=true");
            capture();
            show(title);
            if ("fabric".equals(loaderName)) {
                if ((Boolean) nativeConfiguration.getMethod("isRegistered").invoke(null)) throw new AssertionError("Fabric unexpectedly registered loader config UI");
                emit("native-fabric-no-gui-verified");
                finished = true;
                return;
            }
            mods = modListScreen();
            show(mods);
            emit("native-mod-list-opened=" + mods.getClass().getName());
            phase = 2;
            return;
        }
        if (phase == 2) {
            if (screen != mods) return;
            selectMemorySweep();
            phase = 3;
            return;
        }
        if (phase == 3) {
            if (screen != mods) return;
            Object configButton = findButton(screen, "configButton", "config", "configuration");
            if (!active(configButton)) {
                if ("neoforge".equals(loaderName) && !exists("net.neoforged.neoforge.client.gui.ConfigurationScreen")) {
                    emit("native-screen-unavailable=neoforge-builtin-not-present");
                    emit("native-screen-not-supported");
                    finished = true;
                    show(title);
                    return;
                }
                throw new AssertionError("Native Mods Config button is inactive for MemorySweep");
            }
            click(screen, configButton, "Mods.Config");
            phase = 4;
            return;
        }
        if (phase == 4) {
            if (screen == mods) throw new AssertionError("Native Config click did not open a screen");
            String name = screen.getClass().getName();
            if ("forge".equals(loaderName)) {
                if (!name.equals("com.tkisor.memorysweep.universal.generated.ForgeConfigurationScreen")) throw new AssertionError("Unexpected Forge config screen: " + name);
            } else if (!name.startsWith("net.neoforged.neoforge.client.gui.ConfigurationScreen")) {
                throw new AssertionError("NeoForge did not open its built-in screen: " + name);
            }
            emit("native-factory-registered=true");
            emit("native-screen-opened=" + name);
            if ("neoforge".equals(loaderName) && !name.contains("ConfigurationSectionScreen")) {
                Object fileButton = findConfigFileButton(screen);
                click(screen, fileButton, "NeoForge.config-file");
                return;
            }
            configurationScreen = screen;
            capture();
            captureTime = System.currentTimeMillis();
            phase = 5;
            return;
        }
        if (phase == 5) {
            if (screen != configurationScreen) throw new IllegalStateException("Configuration screen changed before interaction");
            if (System.currentTimeMillis() - captureTime < 4000L) return;
            if ("forge".equals(loaderName)) {
                editForge(screen, "17");
                click(screen, findButton(screen, null, "cancel"), "Forge.Cancel");
                phase = 6;
            } else {
                emit("native-config-cancel-not-tested=NeoForge-built-in-applies-values-and-saves-on-Done");
                editNeoForge(screen);
                capture();
                phase = 8;
            }
            return;
        }
        if (phase == 6) {
            if (screen != mods) throw new AssertionError("Cancel did not return to native Mods screen");
            if (!same(snapshot(), baseline) || !fileMatches(true, 5L, true)) throw new AssertionError("Cancel changed native values or TOML");
            emit("native-config-cancel-verified");
            click(screen, findButton(screen, "configButton", "config", "configuration"), "Mods.Config.reopen");
            phase = 7;
            return;
        }
        if (phase == 7) {
            if (!screen.getClass().getName().equals("com.tkisor.memorysweep.universal.generated.ForgeConfigurationScreen")) throw new AssertionError("Config reopen did not return Forge page");
            configurationScreen = screen;
            editForge(screen, "-1");
            Object done = findButton(screen, null, "done");
            if (active(done) || !same(snapshot(), baseline) || !fileMatches(true, 5L, true)) throw new AssertionError("Invalid interval was not rejected before commit");
            emit("native-config-invalid-interval-rejected=true");
            setEditor(singleEditor(screen), "17");
            if (!active(done)) throw new AssertionError("Valid interval did not re-enable Done");
            capture();
            phase = 8;
            return;
        }
        if (phase == 8) {
            if (screen != configurationScreen) throw new IllegalStateException("Screen changed before native Done click");
            click(screen, findButton(screen, "doneButton", "done", "save"), loaderName + ".Done");
            phase = 9;
            return;
        }
        if (phase == 9) {
            boolean persisted = fileMatches(false, 17L, false);
            Object[] values = snapshot();
            boolean nativeValues = same(values, new Object[]{false, 17L, false});
            boolean runtimeValues = runtimeMatches(false, 17L, false);
            emit("native-config-save-state=file:" + persisted + ",native:" + nativeValues + ",runtime:" + runtimeValues);
            if (!(persisted && nativeValues && runtimeValues)) {
                if (++saveObservations < 5) return;
                throw new AssertionError("Done did not synchronize all three values: " + Arrays.toString(values));
            }
            if (screen == configurationScreen) throw new AssertionError("Done did not close the edited configuration page");
            emit("native-config-save-verified");
            emit("gui-click-persistence-verified");
            show(title);
            finished = true;
        }
    }

    private static Object optionsScreen() throws Exception {
        Class<?> type = resolve("net.minecraft.client.gui.screens.options.OptionsScreen", "net.minecraft.client.gui.screens.OptionsScreen", "net.minecraft.client.gui.screen.OptionsScreen", "net.minecraft.class_429");
        for (Constructor<?> constructor : type.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if ((parameters.length != 2 && parameters.length != 3) || !parameters[0].isInstance(title)) continue;
            if (parameters.length == 3 && parameters[2] != boolean.class) continue;
            Object settings = legacy("fieldOfType", new Class<?>[]{Object.class, Class.class}, client, parameters[1]);
            if (settings == null) continue;
            return parameters.length == 2 ? constructor.newInstance(title, settings) : constructor.newInstance(title, settings, false);
        }
        throw new NoSuchMethodException("Options screen constructor");
    }

    private static Object modListScreen() throws Exception {
        Class<?> type = "forge".equals(loaderName)
                ? resolve("net.minecraftforge.client.gui.ModListScreen", "net.minecraftforge.fml.client.gui.screen.ModListScreen")
                : resolve("net.neoforged.neoforge.client.gui.modlist.ModListScreen", "net.neoforged.neoforge.client.gui.ModListScreen");
        Method factory = method(type, new Class<?>[]{screenType}, "create");
        if (factory != null && Modifier.isStatic(factory.getModifiers())) return factory.invoke(null, title);
        return type.getConstructor(screenType).newInstance(title);
    }

    private static void selectMemorySweep() throws Exception {
        Object list = namedValue(mods, "modList", "displayList");
        if (list == null) throw new NoSuchFieldException("Native Mods list widget");
        Object entry = null;
        for (Object candidate : graph(list)) {
            if (!candidate.getClass().getName().contains("Entry")) continue;
            if ("memorysweep".equals(modId(candidate))) {
                if (entry != null && entry != candidate) throw new IllegalStateException("Ambiguous MemorySweep native list entries");
                entry = candidate;
            }
        }
        if (entry == null) throw new NoSuchFieldException("MemorySweep native list entry");
        invokeSelection(list, entry);
        if (!mods.getClass().getName().contains(".modlist.")) invokeSelection(mods, entry);
        emit("native-mod-selected=memorysweep,entry=" + entry.getClass().getName());
    }

    private static void invokeSelection(Object target, Object entry) throws Exception {
        String[] names = {"setSelected", "m_6987_", "func_241215_a_", "method_25313"};
        for (String name : names) {
            for (Class<?> current = target.getClass(); current != null; current = current.getSuperclass()) {
                for (Method candidate : current.getDeclaredMethods()) {
                    if (!candidate.getName().equals(name) || candidate.getParameterTypes().length != 1
                            || !candidate.getParameterTypes()[0].isInstance(entry)) continue;
                    candidate.setAccessible(true);
                    candidate.invoke(target, entry);
                    return;
                }
            }
        }
        throw new NoSuchMethodException("Native list selection API: " + target.getClass().getName());
    }

    private static String modId(Object entry) throws Exception {
        for (String name : new String[]{"getInfo", "getContainer"}) {
            Method accessor = method(entry.getClass(), new Class<?>[0], name);
            if (accessor == null) continue;
            Object metadata = accessor.invoke(entry);
            Method identifier = method(metadata.getClass(), new Class<?>[0], "getModId");
            if (identifier != null) return (String) identifier.invoke(metadata);
        }
        Object info = namedValue(entry, "displayInfo", "modInfo", "container");
        if (info == null) return null;
        Method identifier = method(info.getClass(), new Class<?>[0], "id", "getModId");
        return identifier == null ? null : (String) identifier.invoke(info);
    }

    private static Object findConfigFileButton(Object screen) throws Exception {
        List<Object> candidates = new ArrayList<Object>();
        for (Object widget : widgets(screen)) {
            if (!isButton(widget) || !active(widget) || !visible(widget)) continue;
            String text = label(widget).toLowerCase(Locale.ROOT);
            if (text.contains("memorysweep.toml") || text.contains("common")) candidates.add(widget);
        }
        if (candidates.size() != 1) throw new NoSuchMethodException("Unique native MemorySweep config-file button: " + widgetDescriptions(screen));
        return candidates.get(0);
    }

    private static void editForge(Object screen, String interval) throws Exception {
        Object enabled = findButton(screen, null, "enable memory cleanup", "memory cleanup", "memory_sweep");
        Object silent = findButton(screen, null, "silent mode", "silent");
        click(screen, enabled, "Forge.enabled");
        setEditor(singleEditor(screen), interval);
        click(screen, silent, "Forge.silent");
    }

    private static void editNeoForge(Object screen) throws Exception {
        Object enabled = rowEditor(screen, "enable memory cleanup", "memory_sweep");
        Object silent = rowEditor(screen, "silent mode", "silent");
        if (!isButton(enabled) || !isButton(silent)) throw new NoSuchMethodException("Native boolean editor widgets");
        click(screen, enabled, "NeoForge.enabled");
        setEditor(singleEditor(screen), "17");
        click(screen, silent, "NeoForge.silent");
    }

    private static Object rowEditor(Object screen, String... labels) throws Exception {
        List<Object> all = widgets(screen);
        Object rowLabel = null;
        for (Object widget : all) {
            String text = label(widget).toLowerCase(Locale.ROOT);
            boolean matches = contains(text, labels);
            if (!matches) continue;
            if (isButton(widget)) return widget;
            if (rowLabel != null) throw new IllegalStateException("Ambiguous configuration property label");
            rowLabel = widget;
        }
        if (rowLabel == null) throw new NoSuchMethodException("Native property label " + Arrays.toString(labels) + ": " + widgetDescriptions(screen));
        int[] labelBounds = bounds(rowLabel);
        Object editor = null;
        for (Object widget : all) {
            if (!isButton(widget) || !visible(widget)) continue;
            int[] candidateBounds = bounds(widget);
            if (Math.abs(candidateBounds[1] - labelBounds[1]) > 3 || candidateBounds[0] <= labelBounds[0]) continue;
            if (editor != null) throw new IllegalStateException("Ambiguous native row editor");
            editor = widget;
        }
        if (editor == null) throw new NoSuchMethodException("Native row editor for " + label(rowLabel));
        return editor;
    }

    private static Object singleEditor(Object screen) throws Exception {
        Object editor = null;
        for (Object widget : widgets(screen)) {
            if (!widget.getClass().getName().matches(".*(?:EditBox|TextFieldWidget|class_342)$")) continue;
            if (!visible(widget)) continue;
            if (editor != null) throw new IllegalStateException("Multiple numeric editors on the configuration page");
            editor = widget;
        }
        if (editor == null) throw new NoSuchFieldException("Native interval editor: " + widgetDescriptions(screen));
        return editor;
    }

    private static void setEditor(Object editor, String value) throws Exception {
        Method setter = method(editor.getClass(), new Class<?>[]{String.class}, "setValue", "m_94144_", "func_146180_a", "method_1852");
        if (setter == null) throw new NoSuchMethodException("Native EditBox setValue");
        setter.invoke(editor, value);
        Method getter = method(editor.getClass(), new Class<?>[0], "getValue", "m_94155_", "func_146179_b", "method_1882");
        if (getter == null || !value.equals(getter.invoke(editor))) throw new IllegalStateException("Native editor rejected test text " + value);
        emit("native-interval-editor=" + value);
    }

    private static Object findButton(Object screen, String fieldName, String... labels) throws Exception {
        if (fieldName != null) {
            for (Object node : graph(screen)) {
                Object candidate = namedValue(node, fieldName);
                if (candidate != null && isButton(candidate)) return candidate;
            }
        }
        Object button = null;
        for (Object widget : widgets(screen)) {
            if (!isButton(widget) || !contains(label(widget).toLowerCase(Locale.ROOT), labels)) continue;
            if (button != null) throw new IllegalStateException("Ambiguous native button " + Arrays.toString(labels));
            button = widget;
        }
        if (button == null) throw new NoSuchFieldException("Native button " + Arrays.toString(labels) + ": " + widgetDescriptions(screen));
        return button;
    }

    private static boolean click(Object screen, Object widget, String purpose) throws Exception {
        boolean widgetVisible = visible(widget);
        boolean widgetActive = active(widget);
        int[] rectangle = bounds(widget);
        int width = integer(screen, "width", "field_22789", "f_96543_", "field_230708_k_");
        int height = integer(screen, "height", "field_22790", "f_96544_", "field_230709_l_");
        boolean contained = rectangle[2] > 0 && rectangle[3] > 0 && rectangle[0] >= 0 && rectangle[1] >= 0
                && (long) rectangle[0] + rectangle[2] <= width && (long) rectangle[1] + rectangle[3] <= height;
        boolean overlap = false;
        for (Object other : widgets(screen)) {
            if (other == widget || !isButton(other) || !visible(other)) continue;
            if (overlap(rectangle, bounds(other))) {
                overlap = true;
                emit("native-overlap-widget=" + label(other));
            }
        }
        emit("ui-visible=" + widgetVisible);
        emit("ui-active=" + widgetActive);
        emit("ui-bounds-check=" + contained);
        emit("ui-overlap=" + overlap);
        emit("native-click-purpose=" + purpose + ",label:" + label(widget) + ",bounds:" + Arrays.toString(rectangle));
        if (!(widgetVisible && widgetActive && contained && !overlap)) throw new IllegalStateException("Native widget click preconditions failed for " + purpose);
        double centerX = rectangle[0] + rectangle[2] / 2.0;
        double centerY = rectangle[1] + rectangle[3] / 2.0;
        boolean accepted;
        Method legacyClick = method(screen.getClass(), new Class<?>[]{double.class, double.class, int.class}, "mouseClicked", "method_25402", "m_6375_", "func_231044_a_");
        if (legacyClick != null) {
            accepted = Boolean.TRUE.equals(legacyClick.invoke(screen, centerX, centerY, 0));
            Method release = method(screen.getClass(), new Class<?>[]{double.class, double.class, int.class}, "mouseReleased", "method_25406", "m_6348_", "func_231048_c_");
            if (release != null) release.invoke(screen, centerX, centerY, 0);
            emit("native-click-dispatch=legacy,x:" + centerX + ",y:" + centerY + ",button:0");
        } else {
            Class<?> eventType = resolve("net.minecraft.client.input.MouseButtonEvent");
            Class<?> infoType = resolve("net.minecraft.client.input.MouseButtonInfo");
            int primary = resolve("com.mojang.blaze3d.platform.InputConstants").getField("MOUSE_BUTTON_LEFT").getInt(null);
            Object info = infoType.getConstructor(int.class, int.class).newInstance(primary, 0);
            Object event = eventType.getConstructor(double.class, double.class, infoType).newInstance(centerX, centerY, info);
            Method modernClick = method(screen.getClass(), new Class<?>[]{eventType, boolean.class}, "mouseClicked");
            if (modernClick == null) throw new NoSuchMethodException("Native mouse event API");
            accepted = Boolean.TRUE.equals(modernClick.invoke(screen, event, false));
            Method release = method(screen.getClass(), new Class<?>[]{eventType}, "mouseReleased");
            if (release != null) release.invoke(screen, event);
            emit("native-click-dispatch=event,x:" + centerX + ",y:" + centerY + ",button:" + primary);
        }
        emit("ui-native-click-accepted=" + accepted);
        if (!accepted) throw new IllegalStateException("Vanilla screen rejected native " + purpose + " click");
        return accepted;
    }

    private static Object[] snapshot() throws Exception {
        return (Object[]) nativeConfiguration.getMethod("snapshot").invoke(null);
    }

    private static boolean runtimeMatches(boolean enabled, long interval, boolean silent) throws Exception {
        Class<?> runtime = resolve("com.tkisor.memorysweep.universal.UniversalRuntime");
        Method getter = runtime.getDeclaredMethod("getConfig");
        getter.setAccessible(true);
        Object config = getter.invoke(null);
        return booleanField(config, "enabled") == enabled && numberField(config, "intervalSeconds").longValue() == interval
                && booleanField(config, "silent") == silent;
    }

    private static boolean fileMatches(boolean enabled, long interval, boolean silent) throws Exception {
        if (!Files.isRegularFile(configPath)) return false;
        String text = new String(Files.readAllBytes(configPath), StandardCharsets.UTF_8);
        return setting(text, "memory_sweep", String.valueOf(enabled)) && setting(text, "sweep_interval_seconds", String.valueOf(interval))
                && setting(text, "silent", String.valueOf(silent));
    }

    private static boolean setting(String text, String key, String expected) {
        return Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + "\\s*=\\s*" + Pattern.quote(expected) + "\\s*(?:#.*)?$").matcher(text).find();
    }

    private static boolean same(Object[] first, Object[] second) {
        return first != null && second != null && first.length == 3 && second.length == 3 && first[0].equals(second[0])
                && ((Number) first[1]).longValue() == ((Number) second[1]).longValue() && first[2].equals(second[2]);
    }

    private static Object currentScreen() throws Exception {
        Object owner = legacy("screenOwner", new Class<?>[]{Object.class, Class.class}, client, screenType);
        return legacy("fieldOfType", new Class<?>[]{Object.class, Class.class}, owner, screenType);
    }

    private static void show(Object screen) throws Exception {
        legacy("setScreen", new Class<?>[]{Object.class, Class.class, Object.class}, client, screenType, screen);
    }

    private static void capture() throws Exception {
        legacy("capture", new Class<?>[]{Object.class, ClassLoader.class}, client, gameLoader);
        emit("native-config-screenshot-requested phase=" + phase);
    }

    private static List<Object> graph(Object root) throws Exception {
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        ArrayDeque<Object> queue = new ArrayDeque<Object>();
        queue.add(root);
        List<Object> nodes = new ArrayList<Object>();
        while (!queue.isEmpty()) {
            Object node = queue.removeFirst();
            if (!visited.add(node)) continue;
            if (visited.size() > 4096) throw new IllegalStateException("Native widget graph exceeded safety bound");
            nodes.add(node);
            if (node instanceof Collection) {
                for (Object item : (Collection<?>) node) if (item != null && guiObject(item)) queue.add(item);
                continue;
            }
            if (node instanceof Map) {
                for (Object item : ((Map<?, ?>) node).values()) if (item != null && guiObject(item)) queue.add(item);
                continue;
            }
            if (!guiObject(node)) continue;
            Method children = method(node.getClass(), new Class<?>[0], "children", "getChildren", "m_6702_", "func_231039_at__", "method_25396");
            if (children != null && Collection.class.isAssignableFrom(children.getReturnType())) {
                Object value = children.invoke(node);
                if (value != null) queue.add(value);
            }
            for (Class<?> current = node.getClass(); current != null && current != Object.class; current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                    if (!field.getType().getName().startsWith("net.") && !Collection.class.isAssignableFrom(field.getType())
                            && !Map.class.isAssignableFrom(field.getType())) continue;
                    field.setAccessible(true);
                    Object value = field.get(node);
                    if (value != null && guiObject(value) && (!screenType.isInstance(value) || value == root)) queue.add(value);
                }
            }
        }
        return nodes;
    }

    private static boolean guiObject(Object object) {
        if (object instanceof Collection || object instanceof Map) return true;
        String name = object.getClass().getName();
        return name.startsWith("net.minecraft.client.gui.") || name.startsWith("net.minecraftforge.client.gui.")
                || name.startsWith("net.minecraftforge.fml.client.gui.") || name.startsWith("net.neoforged.neoforge.client.gui.")
                || name.startsWith("com.tkisor.memorysweep.universal.generated.") || intermediaryGui(object.getClass());
    }

    private static boolean intermediaryGui(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            String name = current.getName();
            if (name.equals("net.minecraft.class_339") || name.equals("net.minecraft.class_437")) return true;
            for (Class<?> contract : current.getInterfaces()) {
                if (contract.getName().equals("net.minecraft.class_364") || intermediaryGui(contract)) return true;
            }
        }
        return false;
    }

    private static List<Object> widgets(Object root) throws Exception {
        Class<?> widgetType = resolve("net.minecraft.client.gui.components.AbstractWidget", "net.minecraft.client.gui.widget.Widget", "net.minecraft.class_339");
        List<Object> widgets = new ArrayList<Object>();
        for (Object object : graph(root)) if (widgetType.isInstance(object)) widgets.add(object);
        return widgets;
    }

    private static String widgetDescriptions(Object screen) throws Exception {
        List<String> descriptions = new ArrayList<String>();
        for (Object widget : widgets(screen)) descriptions.add(widget.getClass().getSimpleName() + ":" + label(widget));
        return descriptions.toString();
    }

    private static boolean isButton(Object widget) {
        for (Class<?> current = widget.getClass(); current != null; current = current.getSuperclass()) {
            String name = current.getName();
            if (name.endsWith(".AbstractButton") || name.endsWith(".Button") || name.endsWith(".CycleButton")
                    || name.endsWith(".class_4185") || name.endsWith(".class_5676")) return true;
        }
        return false;
    }

    private static String label(Object widget) throws Exception {
        return (String) legacy("widgetLabel", new Class<?>[]{Object.class}, widget);
    }

    private static boolean active(Object widget) throws Exception {
        return (Boolean) legacy("active", new Class<?>[]{Object.class}, widget);
    }

    private static boolean visible(Object widget) throws Exception {
        return (Boolean) legacy("visible", new Class<?>[]{Object.class}, widget);
    }

    private static int[] bounds(Object widget) throws Exception {
        return (int[]) legacy("rectangle", new Class<?>[]{Object.class}, widget);
    }

    private static int integer(Object object, String... names) throws Exception {
        Object value = namedValue(object, names);
        if (!(value instanceof Integer)) throw new NoSuchFieldException("Native screen integer " + Arrays.toString(names));
        return (Integer) value;
    }

    private static boolean booleanField(Object object, String name) throws Exception {
        Object value = namedValue(object, name);
        if (!(value instanceof Boolean)) throw new NoSuchFieldException(name);
        return (Boolean) value;
    }

    private static Number numberField(Object object, String name) throws Exception {
        Object value = namedValue(object, name);
        if (!(value instanceof Number)) throw new NoSuchFieldException(name);
        return (Number) value;
    }

    private static Object namedValue(Object object, String... names) throws Exception {
        for (String name : names) {
            for (Class<?> current = object.getClass(); current != null; current = current.getSuperclass()) {
                try {
                    Field field = current.getDeclaredField(name);
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    field.setAccessible(true);
                    return field.get(object);
                } catch (NoSuchFieldException absent) {
                }
            }
        }
        return null;
    }

    private static boolean overlap(int[] first, int[] second) {
        return first[0] < (long) second[0] + second[2] && second[0] < (long) first[0] + first[2]
                && first[1] < (long) second[1] + second[3] && second[1] < (long) first[1] + first[3];
    }

    private static boolean contains(String text, String... fragments) {
        for (String fragment : fragments) if (text.contains(fragment)) return true;
        return false;
    }

    private static Method method(Class<?> type, Class<?>[] parameters, String... names) {
        for (String name : names) {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                try {
                    Method method = current.getDeclaredMethod(name, parameters);
                    method.setAccessible(true);
                    return method;
                } catch (NoSuchMethodException absent) {
                }
            }
            try {
                Method method = type.getMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException absent) {
            }
        }
        return null;
    }

    private static Object legacy(String name, Class<?>[] parameters, Object... arguments) throws Exception {
        Method method = RuntimeUiProbe.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method.invoke(null, arguments);
    }

    private static Field legacyField(String name, Class<?> type) throws Exception {
        Field field = RuntimeUiProbe.class.getDeclaredField(name);
        if (field.getType() != type) throw new NoSuchFieldException("Core probe field contract: " + name);
        field.setAccessible(true);
        return field;
    }

    private static Class<?> loadedClass(String... names) {
        for (Class<?> candidate : environment.getAllLoadedClasses()) {
            for (String name : names) if (candidate.getName().equals(name)) return candidate;
        }
        return null;
    }

    private static Class<?> resolve(String... names) throws ClassNotFoundException {
        for (String name : names) {
            try {
                return Class.forName(name, false, gameLoader);
            } catch (ClassNotFoundException absent) {
            }
        }
        throw new ClassNotFoundException(names[0]);
    }

    private static boolean exists(String name) {
        try {
            resolve(name);
            return true;
        } catch (ClassNotFoundException absent) {
            return false;
        }
    }

    private static Throwable unwrap(Throwable failure) {
        while (failure instanceof InvocationTargetException && failure.getCause() != null) failure = failure.getCause();
        return failure;
    }

    private static void emit(String message) {
        System.err.println("[MemorySweepTest] " + message);
    }
}
