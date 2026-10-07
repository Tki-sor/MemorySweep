package com.tkisor.memorysweep.universal;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

public final class NativeForgeScreen {
    private static final Map<Object, State> STATES = Collections.synchronizedMap(new WeakHashMap<Object, State>());
    private static final Map<Class<?>, WeakReference<Class<?>>> SCREEN_TYPES = new WeakHashMap<>();
    private static final String HELPER = "com/tkisor/memorysweep/universal/NativeForgeScreen";

    private NativeForgeScreen() {
    }

    public static Object create(Object parent) {
        try {
            if (parent == null) throw new IllegalArgumentException("A parent screen is required");
            ClassLoader loader = parent.getClass().getClassLoader();
            Class<?> screenType = resolve(loader, "net.minecraft.client.gui.screens.Screen", "net.minecraft.client.gui.screen.Screen");
            if (!screenType.isInstance(parent)) throw new IllegalArgumentException("Parent is not a Minecraft Screen");
            Class<?> componentType = resolve(loader, "net.minecraft.network.chat.Component", "net.minecraft.util.text.ITextComponent");
            Object[] values = NativeConfiguration.snapshot();
            if (values == null || values.length != 3 || !(values[0] instanceof Boolean)
                    || !(values[1] instanceof Number) || !(values[2] instanceof Boolean)) {
                throw new IllegalStateException("Forge native configuration has not loaded");
            }
            Class<?> generated = generatedScreen(screenType, componentType);
            Object screen = generated.getConstructor(componentType).newInstance(component(loader,
                    translated(loader, "memorysweep.config.title", "MemorySweep Settings")));
            State state = new State(parent, screenType, loader, (Boolean) values[0],
                    ((Number) values[1]).longValue(), (Boolean) values[2]);
            STATES.put(screen, state);
            return screen;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not create Forge native configuration screen", exception);
        }
    }

    public static void initialize(Object screen) {
        try {
            State state = state(screen);
            if (state.intervalWidget != null) state.intervalText = editValue(state.intervalWidget);
            state.width = integerField(screen, "width", "f_96543_", "field_230708_k_");
            state.height = integerField(screen, "height", "f_96544_", "field_230709_l_");
            if (state.width < 240 || state.height < 200) {
                throw new IllegalStateException("MemorySweep settings require a GUI area of at least 240 x 200");
            }
            state.font = field(screen, "font", "f_96547_", "field_230712_o_");
            state.titleLabel = translated(state.loader, "memorysweep.config.title", "MemorySweep Settings");
            state.intervalLabel = translated(state.loader, "memorysweep.config.sweep_interval_seconds", "Sweep interval (seconds)");
            int panelWidth = Math.min(300, state.width - 40);
            state.left = (state.width - panelWidth) / 2;
            state.renderer = null;
            Object enabled = button(state, state.left, 45, panelWidth, toggleLabel(state, false), pressed -> {
                state.enabled = !state.enabled;
                setMessage(pressed, state.loader, toggleLabel(state, false));
            });
            addWidget(screen, enabled);
            state.intervalWidget = editBox(state, state.left, 91, panelWidth, 20);
            addWidget(screen, state.intervalWidget);
            Object silent = button(state, state.left, 125, panelWidth, toggleLabel(state, true), pressed -> {
                state.silent = !state.silent;
                setMessage(pressed, state.loader, toggleLabel(state, true));
            });
            addWidget(screen, silent);
            int halfWidth = (panelWidth - 8) / 2;
            state.saveButton = button(state, state.left, state.height - 35, halfWidth,
                    translated(state.loader, "gui.done", "Done"), pressed -> save(state));
            addWidget(screen, state.saveButton);
            Object cancel = button(state, state.left + halfWidth + 8, state.height - 35, halfWidth,
                    translated(state.loader, "gui.cancel", "Cancel"), pressed -> returnToParent(state));
            addWidget(screen, cancel);
            Method responder = namedMethod(state.intervalWidget.getClass(), new Class<?>[]{Consumer.class},
                    "setResponder", "m_94151_", "func_212954_a");
            responder.invoke(state.intervalWidget, (Consumer<String>) value -> {
                state.intervalText = value;
                validate(state);
            });
            validate(state);
            if (!state.openLogged) {
                state.openLogged = true;
                System.err.println("[MemorySweep] native-forge-screen-opened");
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not initialize Forge configuration widgets", exception);
        }
    }

    public static void paint(Object screen, Object graphics, int mouseX, int mouseY, float partialTick) {
        try {
            State state = state(screen);
            if (state.renderer == null) state.renderer = new TextRenderer(state, graphics.getClass());
            state.renderer.background(screen, graphics, mouseX, mouseY, partialTick);
            state.renderer.text(graphics, state.font, state.titleLabel, state.width / 2, 18, 0xffffffff, true);
            state.renderer.text(graphics, state.font, state.intervalLabel, state.left, 76, 0xffffffff, false);
            if (!state.error.isEmpty()) {
                state.renderer.text(graphics, state.font, state.error, state.width / 2, 151, 0xffff5555, true);
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not render Forge configuration screen", exception);
        }
    }

    public static void close(Object screen) {
        returnToParent(state(screen));
    }

    public static void tick(Object screen) {
        State state = state(screen);
        if (state.intervalWidget == null) return;
        try {
            Method method = optionalMethod(state.intervalWidget.getClass(), new Class<?>[0], "tick", "m_94120_", "func_146178_a");
            if (method != null) method.invoke(state.intervalWidget);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not tick Forge interval editor", exception);
        }
    }

    private static State state(Object screen) {
        State state = STATES.get(screen);
        if (state == null) throw new IllegalStateException("Unknown MemorySweep configuration screen");
        return state;
    }

    private static String toggleLabel(State state, boolean silent) {
        String key = silent ? "memorysweep.config.silent" : "memorysweep.config.memory_sweep";
        String fallback = silent ? "Silent mode" : "Memory cleanup";
        boolean enabled = silent ? state.silent : state.enabled;
        return translated(state.loader, key, fallback) + ": "
                + translated(state.loader, enabled ? "options.on" : "options.off", enabled ? "ON" : "OFF");
    }

    private static Long interval(String text) {
        if (text == null || !text.matches("[0-9]{1,10}")) return null;
        try {
            long value = Long.parseLong(text);
            return value <= Integer.MAX_VALUE ? value : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static void validate(State state) {
        boolean valid = interval(state.intervalText) != null;
        state.error = valid ? "" : "Interval must be 0..2147483647";
        try {
            setBooleanField(state.saveButton, valid, "active", "f_93623_", "field_230693_o_");
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not validate Forge interval editor", exception);
        }
    }

    private static void save(State state) {
        try {
            state.intervalText = editValue(state.intervalWidget);
            validate(state);
            Long value = interval(state.intervalText);
            if (value == null) return;
            NativeConfiguration.save(state.enabled, value, state.silent);
            System.err.println("[MemorySweep] native-forge-config-saved");
            returnToParent(state);
        } catch (RuntimeException | ReflectiveOperationException exception) {
            state.error = "Could not save configuration";
            System.err.println("[MemorySweep] native-forge-config-save-failed: " + exception);
        }
    }

    private static Object editBox(State state, int left, int top, int width, int height) throws ReflectiveOperationException {
        Class<?> editType = resolve(state.loader, "net.minecraft.client.gui.components.EditBox", "net.minecraft.client.gui.widget.TextFieldWidget");
        Object label = component(state.loader, state.intervalLabel);
        for (Constructor<?> constructor : editType.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length != 6 || !parameters[0].isInstance(state.font) || parameters[1] != int.class
                    || parameters[2] != int.class || parameters[3] != int.class || parameters[4] != int.class || !parameters[5].isInstance(label)) continue;
            Object editor = constructor.newInstance(state.font, left, top, width, height, label);
            namedMethod(editType, new Class<?>[]{int.class}, "setMaxLength", "m_94199_", "func_146203_f").invoke(editor, 10);
            namedMethod(editType, new Class<?>[]{String.class}, "setValue", "m_94144_", "func_146180_a").invoke(editor, state.intervalText);
            return editor;
        }
        throw new NoSuchMethodException("Native interval editor constructor");
    }

    private static String editValue(Object editor) throws ReflectiveOperationException {
        return (String) namedMethod(editor.getClass(), new Class<?>[0], "getValue", "m_94155_", "func_146179_b").invoke(editor);
    }

    private static Object button(State state, int left, int top, int width, String text, Consumer<Object> action) throws ReflectiveOperationException {
        Class<?> buttonType = resolve(state.loader, "net.minecraft.client.gui.components.Button", "net.minecraft.client.gui.widget.button.Button");
        Class<?> componentType = resolve(state.loader, "net.minecraft.network.chat.Component", "net.minecraft.util.text.ITextComponent");
        Object label = component(state.loader, text);
        Class<?> pressType = resolve(state.loader, buttonType.getName() + "$OnPress", buttonType.getName() + "$IPressable");
        Object callback = callback(pressType, action);
        Method factory = optionalMethod(buttonType, new Class<?>[]{componentType, pressType}, "builder", "m_253074_");
        if (factory != null) {
            Object builder = factory.invoke(null, label, callback);
            namedMethod(builder.getClass(), new Class<?>[]{int.class, int.class, int.class, int.class}, "bounds", "m_252987_")
                    .invoke(builder, left, top, width, 20);
            return namedMethod(builder.getClass(), new Class<?>[0], "build", "m_253136_").invoke(builder);
        }
        for (Constructor<?> constructor : buttonType.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 6 && parameters[0] == int.class && parameters[1] == int.class
                    && parameters[2] == int.class && parameters[3] == int.class && parameters[4].isInstance(label)
                    && parameters[5].isInstance(callback)) return constructor.newInstance(left, top, width, 20, label, callback);
        }
        throw new NoSuchMethodException("Native button constructor");
    }

    private static Object callback(Class<?> pressType, Consumer<Object> action) throws NoSuchMethodException {
        Method functional = null;
        for (Method method : pressType.getMethods()) {
            if (Modifier.isAbstract(method.getModifiers()) && method.getReturnType() == void.class && method.getParameterTypes().length == 1) {
                if (functional != null) throw new NoSuchMethodException("Ambiguous native button callback");
                functional = method;
            }
        }
        if (functional == null) throw new NoSuchMethodException("Native button callback");
        final Method pressMethod = functional;
        return Proxy.newProxyInstance(pressType.getClassLoader(), new Class<?>[]{pressType}, (proxy, method, arguments) -> {
            if (method.equals(pressMethod)) {
                action.accept(arguments[0]);
                return null;
            }
            if (method.getDeclaringClass() == Object.class) {
                if ("equals".equals(method.getName())) return proxy == arguments[0];
                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                if ("toString".equals(method.getName())) return "MemorySweep Forge settings callback";
            }
            throw new UnsupportedOperationException(method.toString());
        });
    }

    private static void setMessage(Object button, ClassLoader loader, String text) {
        try {
            Class<?> componentType = resolve(loader, "net.minecraft.network.chat.Component", "net.minecraft.util.text.ITextComponent");
            namedMethod(button.getClass(), new Class<?>[]{componentType}, "setMessage", "m_93666_", "func_238482_a_")
                    .invoke(button, component(loader, text));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not update native toggle", exception);
        }
    }

    private static void addWidget(Object screen, Object widget) throws ReflectiveOperationException {
        String[] names = {"addRenderableWidget", "m_142416_", "addButton", "func_230480_a_"};
        for (String name : names) {
            for (Class<?> current = screen.getClass(); current != null; current = current.getSuperclass()) {
                for (Method method : current.getDeclaredMethods()) {
                    if (!method.getName().equals(name) || method.getParameterTypes().length != 1
                            || !method.getParameterTypes()[0].isInstance(widget)) continue;
                    method.setAccessible(true);
                    method.invoke(screen, widget);
                    return;
                }
            }
        }
        throw new NoSuchMethodException("Native screen add widget");
    }

    private static void returnToParent(State state) {
        try {
            Class<?> minecraftType = resolve(state.loader, "net.minecraft.client.Minecraft");
            Object client = null;
            for (Method method : minecraftType.getDeclaredMethods()) {
                if (!Modifier.isStatic(method.getModifiers()) || method.getParameterTypes().length != 0
                        || method.getReturnType() != minecraftType) continue;
                method.setAccessible(true);
                client = method.invoke(null);
                if (client != null) break;
            }
            if (client == null) throw new IllegalStateException("Minecraft client singleton is unavailable");
            Object owner = client;
            Method setter = optionalMethod(minecraftType, new Class<?>[]{state.screenType}, "setScreen", "m_91152_", "displayGuiScreen", "func_147108_a");
            if (setter == null) {
                Class<?> guiType = resolve(state.loader, "net.minecraft.client.gui.Gui");
                for (Class<?> current = minecraftType; current != null; current = current.getSuperclass()) {
                    for (Field field : current.getDeclaredFields()) {
                        if (Modifier.isStatic(field.getModifiers()) || field.getType() != guiType) continue;
                        field.setAccessible(true);
                        owner = field.get(client);
                    }
                }
                if (owner == null) throw new IllegalStateException("Minecraft GUI owner is unavailable");
                setter = namedMethod(owner.getClass(), new Class<?>[]{state.screenType}, "setScreen");
            }
            setter.invoke(owner, state.parent);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not return to Forge Mods screen", exception);
        }
    }

    private static String translated(ClassLoader loader, String key, String fallback) {
        try {
            Class<?> componentType = resolve(loader, "net.minecraft.network.chat.Component", "net.minecraft.util.text.ITextComponent");
            Method factory = optionalMethod(componentType, new Class<?>[]{String.class, Object[].class}, "translatable", "m_237110_");
            Object value;
            if (factory != null) value = factory.invoke(null, key, new Object[0]);
            else {
                Class<?> translation = resolve(loader, "net.minecraft.network.chat.TranslatableComponent", "net.minecraft.util.text.TranslationTextComponent");
                value = translation.getConstructor(String.class, Object[].class).newInstance(key, new Object[0]);
            }
            Method getter = namedMethod(componentType, new Class<?>[0], "getString", "m_130260_", "func_150260_c");
            String text = (String) getter.invoke(value);
            return key.equals(text) ? fallback : text;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return fallback;
        }
    }

    private static Object component(ClassLoader loader, String text) throws ReflectiveOperationException {
        Class<?> componentType = resolve(loader, "net.minecraft.network.chat.Component", "net.minecraft.util.text.ITextComponent");
        Method factory = optionalMethod(componentType, new Class<?>[]{String.class}, "literal", "m_237113_");
        if (factory != null) return factory.invoke(null, text);
        Class<?> literal = resolve(loader, "net.minecraft.network.chat.TextComponent", "net.minecraft.util.text.StringTextComponent");
        return literal.getConstructor(String.class).newInstance(text);
    }

    private static Class<?> resolve(ClassLoader loader, String... names) throws ClassNotFoundException {
        for (String name : names) {
            try {
                return Class.forName(name, false, loader);
            } catch (ClassNotFoundException ignored) {
            }
        }
        throw new ClassNotFoundException(names[0]);
    }

    private static Method optionalMethod(Class<?> type, Class<?>[] parameters, String... names) {
        for (String name : names) {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                try {
                    Method method = current.getDeclaredMethod(name, parameters);
                    method.setAccessible(true);
                    return method;
                } catch (NoSuchMethodException ignored) {
                }
            }
            try {
                return type.getMethod(name, parameters);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private static Method namedMethod(Class<?> type, Class<?>[] parameters, String... names) throws NoSuchMethodException {
        Method method = optionalMethod(type, parameters, names);
        if (method == null) throw new NoSuchMethodException(type.getName() + "." + names[0]);
        return method;
    }

    private static Field namedField(Class<?> type, String... names) throws NoSuchFieldException {
        for (String name : names) {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                try {
                    Field field = current.getDeclaredField(name);
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException ignored) {
                }
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + names[0]);
    }

    private static Object field(Object instance, String... names) throws ReflectiveOperationException {
        return namedField(instance.getClass(), names).get(instance);
    }

    private static int integerField(Object instance, String... names) throws ReflectiveOperationException {
        return namedField(instance.getClass(), names).getInt(instance);
    }

    private static void setBooleanField(Object instance, boolean value, String... names) throws ReflectiveOperationException {
        namedField(instance.getClass(), names).setBoolean(instance, value);
    }

    private static synchronized Class<?> generatedScreen(Class<?> screenType, Class<?> componentType) throws ReflectiveOperationException {
        WeakReference<Class<?>> cached = SCREEN_TYPES.get(screenType);
        Class<?> generated = cached == null ? null : cached.get();
        if (generated != null) return generated;
        Method initialize = namedMethod(screenType, new Class<?>[0], "init", "m_7856_", "func_231160_c_");
        Method close = namedMethod(screenType, new Class<?>[0], "onClose", "m_7379_", "func_231175_as__");
        Method render = null;
        String[] renderNames = {"extractRenderState", "render", "m_6305_", "m_88315_", "func_230430_a_"};
        for (String name : renderNames) {
            for (Method method : screenType.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (method.getName().equals(name) && parameters.length == 4 && !parameters[0].isPrimitive()
                        && parameters[1] == int.class && parameters[2] == int.class && parameters[3] == float.class
                        && method.getReturnType() == void.class && !Modifier.isFinal(method.getModifiers())) render = method;
            }
            if (render != null) break;
        }
        if (render == null) throw new NoSuchMethodException("Native screen rendering entrypoint");
        screenType.getDeclaredConstructor(componentType);
        ClassLoader loader = screenType.getClassLoader();
        Asm emitter = new Asm(loader);
        String generatedName = "com/tkisor/memorysweep/universal/generated/ForgeConfigurationScreen";
        String superclass = screenType.getName().replace('.', '/');
        emitter.begin(generatedName, superclass);
        Object constructor = emitter.method(1, "<init>", "(" + descriptor(componentType) + ")V");
        emitter.variable(constructor, 25, 0);
        emitter.variable(constructor, 25, 1);
        emitter.invoke(constructor, 183, superclass, "<init>", "(" + descriptor(componentType) + ")V");
        emitter.finish(constructor);
        emitHook(emitter, superclass, initialize, "initialize", true);
        emitHook(emitter, superclass, close, "close", false);
        Method tick = optionalMethod(screenType, new Class<?>[0], "tick", "m_86600_", "m_96624_", "func_231023_e_");
        if (tick != null && !Modifier.isFinal(tick.getModifiers())) emitHook(emitter, superclass, tick, "tick", true);
        String renderDescriptor = "(" + descriptor(render.getParameterTypes()[0]) + "IIF)V";
        Object rendering = emitter.method(1, render.getName(), renderDescriptor);
        emitRenderArguments(emitter, rendering);
        emitter.invoke(rendering, 184, HELPER, "paint", "(Ljava/lang/Object;Ljava/lang/Object;IIF)V");
        emitRenderArguments(emitter, rendering);
        emitter.invoke(rendering, 183, superclass, render.getName(), renderDescriptor);
        emitter.finish(rendering);
        generated = new ScreenLoader(loader).define(generatedName.replace('/', '.'), emitter.bytes());
        SCREEN_TYPES.put(screenType, new WeakReference<>(generated));
        return generated;
    }

    private static void emitHook(Asm emitter, String superclass, Method method, String helper, boolean callSuper) throws ReflectiveOperationException {
        if (Modifier.isFinal(method.getModifiers()) || Modifier.isPrivate(method.getModifiers()) || Modifier.isStatic(method.getModifiers())) {
            throw new IllegalStateException("Cannot override native screen hook " + method);
        }
        Object visitor = emitter.method(1, method.getName(), "()V");
        if (callSuper) {
            emitter.variable(visitor, 25, 0);
            emitter.invoke(visitor, 183, superclass, method.getName(), "()V");
        }
        emitter.variable(visitor, 25, 0);
        emitter.invoke(visitor, 184, HELPER, helper, "(Ljava/lang/Object;)V");
        emitter.finish(visitor);
    }

    private static void emitRenderArguments(Asm emitter, Object visitor) throws ReflectiveOperationException {
        emitter.variable(visitor, 25, 0);
        emitter.variable(visitor, 25, 1);
        emitter.variable(visitor, 21, 2);
        emitter.variable(visitor, 21, 3);
        emitter.variable(visitor, 23, 4);
    }

    private static String descriptor(Class<?> type) {
        return "L" + type.getName().replace('.', '/') + ";";
    }

    private static final class ScreenLoader extends ClassLoader {
        private ScreenLoader(ClassLoader parent) {
            super(parent);
        }

        private Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(NativeForgeScreen.class.getName())) return NativeForgeScreen.class;
            return super.loadClass(name, resolve);
        }
    }

    private static final class State {
        private final Object parent;
        private final Class<?> screenType;
        private final ClassLoader loader;
        private boolean enabled;
        private boolean silent;
        private String intervalText;
        private String titleLabel;
        private String intervalLabel;
        private String error = "";
        private int width;
        private int height;
        private int left;
        private Object font;
        private Object intervalWidget;
        private Object saveButton;
        private TextRenderer renderer;
        private boolean openLogged;

        private State(Object parent, Class<?> screenType, ClassLoader loader, boolean enabled, long interval, boolean silent) {
            this.parent = parent;
            this.screenType = screenType;
            this.loader = loader;
            this.enabled = enabled;
            this.intervalText = Long.toString(interval);
            this.silent = silent;
        }
    }

    private static final class TextRenderer {
        private final Method background;
        private final Method centered;
        private final Method normal;
        private final boolean legacy;

        private TextRenderer(State state, Class<?> graphicsType) throws ReflectiveOperationException {
            Class<?>[] frameParameters = {graphicsType, int.class, int.class, float.class};
            Method backgroundMethod = null;
            if (optionalMethod(state.screenType, frameParameters, "extractRenderStateWithTooltipAndSubtitles") == null) {
                backgroundMethod = optionalMethod(state.screenType, frameParameters,
                        "extractBackground", "renderBackground", "m_280273_");
                if (backgroundMethod == null) backgroundMethod = optionalMethod(state.screenType, new Class<?>[]{graphicsType},
                        "renderBackground", "m_280273_", "m_7333_", "func_230446_a_");
                if (backgroundMethod == null) throw new NoSuchMethodException("Native settings background renderer");
            }
            background = backgroundMethod;
            Class<?> fontType = resolve(state.loader, "net.minecraft.client.gui.Font", "net.minecraft.client.gui.FontRenderer");
            Class<?>[] modernParameters = {fontType, String.class, int.class, int.class, int.class};
            Method title = optionalMethod(graphicsType, modernParameters, "centeredText", "drawCenteredString", "m_280137_");
            Method label = optionalMethod(graphicsType, modernParameters, "text", "drawString", "m_280488_");
            legacy = title == null || label == null;
            if (legacy) {
                Class<?>[] oldParameters = {graphicsType, fontType, String.class, int.class, int.class, int.class};
                title = namedMethod(state.screenType, oldParameters, "drawCenteredString", "m_93208_", "func_238471_a_");
                label = namedMethod(state.screenType, oldParameters, "drawString", "m_93236_", "func_238476_c_");
                if (!Modifier.isStatic(title.getModifiers()) || !Modifier.isStatic(label.getModifiers())) {
                    throw new IllegalStateException("Unexpected legacy text renderer");
                }
            }
            centered = title;
            normal = label;
        }

        private void background(Object screen, Object graphics, int mouseX, int mouseY, float partialTick) throws ReflectiveOperationException {
            if (background == null) return;
            if (background.getParameterTypes().length == 1) background.invoke(screen, graphics);
            else background.invoke(screen, graphics, mouseX, mouseY, partialTick);
        }

        private void text(Object graphics, Object font, String text, int left, int top, int color, boolean center) throws ReflectiveOperationException {
            Method method = center ? centered : normal;
            if (legacy) method.invoke(null, graphics, font, text, left, top, color);
            else method.invoke(graphics, font, text, left, top, color);
        }
    }

    private static final class Asm {
        private final Object writer;
        private final Class<?> writerType;
        private final Class<?> visitorType;

        private Asm(ClassLoader loader) throws ReflectiveOperationException {
            writerType = Class.forName("org.objectweb.asm.ClassWriter", false, loader);
            visitorType = Class.forName("org.objectweb.asm.MethodVisitor", false, loader);
            writer = writerType.getConstructor(int.class).newInstance(3);
        }

        private void begin(String name, String superclass) throws ReflectiveOperationException {
            writerType.getMethod("visit", int.class, int.class, String.class, String.class, String.class, String[].class)
                    .invoke(writer, 52, 49, name, null, superclass, null);
        }

        private Object method(int access, String name, String descriptor) throws ReflectiveOperationException {
            Object visitor = writerType.getMethod("visitMethod", int.class, String.class, String.class, String.class, String[].class)
                    .invoke(writer, access, name, descriptor, null, null);
            visitorType.getMethod("visitCode").invoke(visitor);
            return visitor;
        }

        private void variable(Object visitor, int opcode, int slot) throws ReflectiveOperationException {
            visitorType.getMethod("visitVarInsn", int.class, int.class).invoke(visitor, opcode, slot);
        }

        private void invoke(Object visitor, int opcode, String owner, String name, String descriptor) throws ReflectiveOperationException {
            visitorType.getMethod("visitMethodInsn", int.class, String.class, String.class, String.class, boolean.class)
                    .invoke(visitor, opcode, owner, name, descriptor, false);
        }

        private void finish(Object visitor) throws ReflectiveOperationException {
            visitorType.getMethod("visitInsn", int.class).invoke(visitor, 177);
            visitorType.getMethod("visitMaxs", int.class, int.class).invoke(visitor, 0, 0);
            visitorType.getMethod("visitEnd").invoke(visitor);
        }

        private byte[] bytes() throws ReflectiveOperationException {
            writerType.getMethod("visitEnd").invoke(writer);
            return (byte[]) writerType.getMethod("toByteArray").invoke(writer);
        }
    }
}
