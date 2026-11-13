package cobdev.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code field}, {@code invoke} and {@code has}: reflection limited to Cobblify's own classes
 * ({@link Policy#modClass}). A path is {@code com.bedwarsqol.Some.Class.staticField[.field...]}; every
 * object it walks through must itself be a com.bedwarsqol class, so a path can never step into
 * Minecraft or the JDK. Cobblify's names are not remapped, so the same path works in dev and prod.
 */
final class Mods {

    private Mods() {
    }

    private static ClassLoader loader() {
        return Mods.class.getClassLoader();
    }

    /** Is the class (and optionally a field or method on it) present in this build? Never initializes it. */
    static Map<String, Object> has(String className, String member) {
        Refused.check(Policy.modClass(className));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("class", className);
        Class<?> c;
        try {
            c = Class.forName(className, false, loader());
        } catch (ClassNotFoundException | LinkageError e) {
            out.put("present", false);
            return out;
        }
        boolean present = true;
        if (member != null && !member.isEmpty()) {
            present = findField(c, member) != null;
            if (!present) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals(member)) present = true;
                }
            }
            out.put("member", member);
        }
        out.put("present", present);
        return out;
    }

    static Map<String, Object> get(String path) throws Exception {
        Target t = resolve(path, true);
        Map<String, Object> out = ok(path);
        out.put("type", t.field.getType().getName());
        out.put("value", plain(t.field.get(t.owner)));
        return out;
    }

    static Map<String, Object> set(String path, String raw) throws Exception {
        Target t = resolve(path, true);
        if (Modifier.isFinal(t.field.getModifiers())) throw new Refused(path + " is final");
        Object before = t.field.get(t.owner);
        t.field.set(t.owner, parse(t.field.getType(), raw));
        Map<String, Object> out = ok(path);
        out.put("before", plain(before));
        out.put("value", plain(t.field.get(t.owner)));
        return out;
    }

    /** {@code path} names a method: static on a class, or on the object a field path ends at. */
    static Map<String, Object> invoke(String path, List<String> args) throws Exception {
        int dot = path.lastIndexOf('.');
        if (dot < 0) throw new Refused("invoke takes com.bedwarsqol.Class.method or Class.field.method");
        String ownerPath = path.substring(0, dot);
        String name = path.substring(dot + 1);
        Class<?> type;
        Object target = null;
        Class<?> asClass = loadLongest(ownerPath, true);
        if (asClass != null && classNameOf(ownerPath, asClass)) {
            type = asClass;
        } else {
            Target t = resolve(ownerPath, true);
            target = t.field.get(t.owner);
            if (target == null) throw new Refused(ownerPath + " is null");
            type = target.getClass();
        }
        Refused.check(Policy.modObject(type));
        Method chosen = null;
        for (Class<?> k = type; k != null && Policy.modObject(k) == null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != args.size()) continue;
                if ((target == null) != Modifier.isStatic(m.getModifiers())) continue;
                if (!convertible(m.getParameterTypes())) continue;
                if (chosen != null) throw new Refused(path + " is overloaded with " + args.size() + " argument(s)");
                chosen = m;
            }
            if (chosen != null) break;
        }
        if (chosen == null) {
            throw new Refused("no " + (target == null ? "static " : "") + "method " + name + " with " + args.size()
                    + " string-convertible argument(s) on " + type.getName());
        }
        chosen.setAccessible(true);
        Class<?>[] types = chosen.getParameterTypes();
        Object[] values = new Object[types.length];
        for (int i = 0; i < types.length; i++) values[i] = parse(types[i], args.get(i));
        Object result;
        try {
            result = chosen.invoke(target, values);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof Exception) throw (Exception) c;
            throw e;
        }
        Map<String, Object> out = ok(path);
        out.put("returnType", chosen.getReturnType().getName());
        out.put("value", plain(result));
        return out;
    }

    // ------------------------------------------------------------------------------------------

    private static final class Target {
        final Object owner;
        final Field field;

        Target(Object owner, Field field) {
            this.owner = owner;
            this.field = field;
        }
    }

    private static boolean classNameOf(String path, Class<?> c) {
        return c.getName().replace('$', '.').equals(path.replace('$', '.'));
    }

    /** Longest prefix of {@code path} (dot-separated) that names a Cobblify class; inner classes too. */
    private static Class<?> loadLongest(String path, boolean init) {
        Refused.check(Policy.modClass(path));
        String[] parts = path.split("\\.");
        for (int n = parts.length; n >= 3; n--) {
            String name = String.join(".", Arrays.copyOfRange(parts, 0, n));
            for (String candidate : innerVariants(name)) {
                if (Policy.modClass(candidate) != null) continue;
                try {
                    return Class.forName(candidate, init, loader());
                } catch (ClassNotFoundException | LinkageError ignored) {
                    // shorter prefix next
                }
            }
        }
        return null;
    }

    /** a.b.C.D -> [a.b.C.D, a.b.C$D]: lets callers write inner classes with dots. */
    private static List<String> innerVariants(String name) {
        List<String> out = new ArrayList<>();
        out.add(name);
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            String outer = name.substring(0, dot);
            if (Character.isUpperCase(outer.charAt(outer.lastIndexOf('.') + 1))) {
                out.add(outer + "$" + name.substring(dot + 1));
            }
        }
        return out;
    }

    private static Target resolve(String path, boolean init) throws Exception {
        Class<?> c = loadLongest(path, init);
        if (c == null) throw new Refused("no Cobblify class at the start of " + path + " (not in this build?)");
        String rest = path.substring(c.getName().length()).replace('$', '.');
        if (rest.startsWith(".")) rest = rest.substring(1);
        if (rest.isEmpty()) throw new Refused(path + " names a class, not a field");
        String[] names = rest.split("\\.");
        Object owner = null;
        Class<?> type = c;
        for (int i = 0; i < names.length; i++) {
            Refused.check(Policy.modObject(type));
            Field f = findField(type, names[i]);
            if (f == null) throw new Refused("no field " + names[i] + " on " + type.getName());
            if (owner == null && !Modifier.isStatic(f.getModifiers())) {
                throw new Refused(type.getName() + "." + names[i] + " is an instance field; start from a static one");
            }
            if (i == names.length - 1) return new Target(Modifier.isStatic(f.getModifiers()) ? null : owner, f);
            owner = f.get(Modifier.isStatic(f.getModifiers()) ? null : owner);
            if (owner == null) throw new Refused(names[i] + " is null in " + path);
            type = owner.getClass();
        }
        throw new IllegalStateException("unreachable");
    }

    private static Field findField(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            }
        }
        return null;
    }

    private static boolean convertible(Class<?>[] types) {
        for (Class<?> t : types) {
            if (!(t.isPrimitive() || t == String.class || t.isEnum() || Number.class.isAssignableFrom(t)
                    || t == Boolean.class)) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object parse(Class<?> t, String raw) {
        try {
            if (t == String.class) return raw;
            if (t == boolean.class || t == Boolean.class) {
                if (!raw.equals("true") && !raw.equals("false")) throw new Refused("expected true or false, got " + raw);
                return Boolean.valueOf(raw);
            }
            if (t == int.class || t == Integer.class) return Integer.valueOf(raw);
            if (t == long.class || t == Long.class) return Long.valueOf(raw);
            if (t == float.class || t == Float.class) return Float.valueOf(raw);
            if (t == double.class || t == Double.class) return Double.valueOf(raw);
            if (t == short.class || t == Short.class) return Short.valueOf(raw);
            if (t == byte.class || t == Byte.class) return Byte.valueOf(raw);
            if (t == char.class || t == Character.class) {
                if (raw.length() != 1) throw new Refused("expected one character, got " + raw);
                return raw.charAt(0);
            }
            if (t.isEnum()) return Enum.valueOf((Class) t, raw);
        } catch (IllegalArgumentException e) {
            if (e instanceof NumberFormatException || t.isEnum()) {
                throw new Refused("cannot read " + raw + " as " + t.getName());
            }
            throw e;
        }
        throw new Refused("cannot set a " + t.getName() + " from text");
    }

    /** JSON-friendly copy: numbers, booleans and strings stay; arrays and lists become lists. */
    static Object plain(Object v) {
        if (v == null || v instanceof Number || v instanceof Boolean || v instanceof String) return v;
        if (v instanceof Character || v instanceof Enum) return v.toString();
        if (v.getClass().isArray()) {
            List<Object> out = new ArrayList<>();
            int n = java.lang.reflect.Array.getLength(v);
            for (int i = 0; i < Math.min(n, 256); i++) out.add(plain(java.lang.reflect.Array.get(v, i)));
            return out;
        }
        if (v instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object o : (List<?>) v) {
                if (out.size() >= 256) break;
                out.add(plain(o));
            }
            return out;
        }
        return String.valueOf(v);
    }

    private static Map<String, Object> ok(String path) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("path", path);
        return out;
    }
}
