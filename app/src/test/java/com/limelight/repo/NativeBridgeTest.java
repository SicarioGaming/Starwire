package com.limelight.repo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.limelight.RepoFiles;
import com.limelight.nvstream.jni.MoonBridge;

import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * callbacks.c calls back into MoonBridge by name and JNI signature, which the compiler can't
 * check: a typo only shows as a crash when the callback first runs. This compares every one with
 * the real Java method.
 */
public class NativeBridgeTest {
    private static final Pattern LOOKUP = Pattern.compile(
            "GetStaticMethodID\\(env, clazz, \"(\\w+)\", \"([^\"]+)\"\\)");

    @Test
    public void everyCallbackSignatureMatchesMoonBridge() throws Exception {
        String callbacks = RepoFiles.read("app/src/main/jni/moonlight-core/callbacks.c");
        Matcher m = LOOKUP.matcher(callbacks);
        List<String> wrong = new ArrayList<>();
        int checked = 0;
        while (m.find()) {
            checked++;
            String found = null;
            for (Method method : MoonBridge.class.getDeclaredMethods()) {
                if (method.getName().equals(m.group(1)) && Modifier.isStatic(method.getModifiers())) {
                    found = descriptor(method);
                    if (found.equals(m.group(2))) {
                        break;
                    }
                }
            }
            if (!m.group(2).equals(found)) {
                wrong.add(m.group(1) + ": C says " + m.group(2) + ", Java is " + found);
            }
        }
        assertTrue("no lookups found in callbacks.c", checked > 20);
        assertTrue("JNI signatures that don't match: " + wrong, wrong.isEmpty());
    }

    @Test
    public void theTriggerPayloadSizeIsTheOneJavaReads() throws Exception {
        String limelight = RepoFiles.read("app/src/main/jni/moonlight-core/moonlight-common-c/src/Limelight.h");
        Matcher m = Pattern.compile("#define DS_EFFECT_PAYLOAD_SIZE (\\d+)").matcher(limelight);
        assertTrue(m.find());
        assertEquals(com.limelight.binding.input.dualsense.TriggerEffect.PARAMETER_SIZE, Integer.parseInt(m.group(1)));
    }

    private static String descriptor(Method method) {
        StringBuilder s = new StringBuilder("(");
        for (Class<?> type : method.getParameterTypes()) {
            s.append(descriptor(type));
        }
        return s.append(')').append(descriptor(method.getReturnType())).toString();
    }

    private static String descriptor(Class<?> type) {
        if (type.isArray()) {
            return "[" + descriptor(type.getComponentType());
        }
        if (type == void.class) return "V";
        if (type == boolean.class) return "Z";
        if (type == byte.class) return "B";
        if (type == char.class) return "C";
        if (type == short.class) return "S";
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == float.class) return "F";
        if (type == double.class) return "D";
        return "L" + type.getName().replace('.', '/') + ";";
    }
}
