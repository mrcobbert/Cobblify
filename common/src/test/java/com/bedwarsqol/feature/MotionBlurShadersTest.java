package com.bedwarsqol.feature;

import org.junit.Test;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Motion Blur's shaders are GLSL 1.20, the most the Mac's legacy OpenGL 2.1 context compiles: {@code #version 120},
 * {@code texture2D}, {@code varying} and {@code gl_FragColor}; no GLSL 1.30+ {@code texture(}, {@code in}/{@code out}
 * qualifiers or {@code layout}, no arrays, and loops only with a constant bound and a {@code break}. Every uniform the
 * GL helper binds is declared, and no other.
 */
public class MotionBlurShadersTest {

    private static final String[] SOURCES = {MotionBlurShaders.VERTEX, MotionBlurShaders.FRAGMENT};

    @Test
    public void everySourceIsGlsl120() {
        for (String src : SOURCES) {
            assertTrue(src, src.startsWith("#version 120\n"));
            assertTrue(src, src.contains("varying "));
            assertFalse(src, Pattern.compile("\\btexture\\s*\\(").matcher(src).find());
            assertFalse(src, Pattern.compile("\\b(in|out|inout)\\s").matcher(src).find());
            assertFalse(src, src.contains("layout"));
            assertFalse("no arrays: " + src, src.contains("["));
            assertFalse(src, src.contains("#extension"));
        }
        assertTrue(MotionBlurShaders.FRAGMENT.contains("texture2D("));
        assertTrue(MotionBlurShaders.FRAGMENT.contains("gl_FragColor"));
        assertTrue(MotionBlurShaders.VERTEX.contains("gl_Position"));
    }

    @Test
    public void loopsHaveConstantBoundsAndBreak() {
        Pattern anyFor = Pattern.compile("\\bfor\\s*\\(");
        Pattern constFor = Pattern.compile("\\bfor\\s*\\(\\s*int\\s+(\\w+)\\s*=\\s*0\\s*;\\s*\\1\\s*<\\s*(\\d+)\\s*;\\s*\\1\\+\\+\\s*\\)");
        Pattern whileLoop = Pattern.compile("\\b(while|do)\\b");
        int loops = 0;
        for (String src : SOURCES) {
            assertFalse(src, whileLoop.matcher(src).find());
            Matcher any = anyFor.matcher(src);
            Matcher constant = constFor.matcher(src);
            int a = 0;
            int c = 0;
            while (any.find()) a++;
            while (constant.find()) {
                c++;
                int bound = Integer.parseInt(constant.group(2));
                assertEquals(MotionBlurShaders.LOOP_TAPS, bound);
            }
            assertEquals("every loop has a constant bound", a, c);
            if (a > 0) assertTrue("a loop stops with break", src.contains("break;"));
            loops += a;
        }
        assertEquals(1, loops);
        // The constant bound covers the most taps any Strength asks for.
        assertTrue(MotionBlurShaders.LOOP_TAPS >= MotionBlurRules.maxTaps(MotionBlurRules.MAX_STRENGTH));
    }

    @Test
    public void declaresExactlyTheUniformsTheGlHelperBinds() {
        Pattern decl = Pattern.compile("\\buniform\\s+(\\w+)\\s+(\\w+)\\s*;");
        Set<String> declared = new LinkedHashSet<String>();
        for (String src : SOURCES) {
            Matcher m = decl.matcher(src);
            while (m.find()) declared.add(m.group(2));
        }
        Set<String> bound = new LinkedHashSet<String>();
        for (String name : MotionBlurShaders.UNIFORMS) bound.add(name);
        assertEquals(bound, declared);
        assertTrue(MotionBlurShaders.FRAGMENT.contains("uniform sampler2D " + MotionBlurShaders.U_SCENE + ";"));
        assertTrue(MotionBlurShaders.FRAGMENT.contains("uniform vec2 " + MotionBlurShaders.U_SMEAR + ";"));
        assertTrue(MotionBlurShaders.FRAGMENT.contains("uniform float " + MotionBlurShaders.U_TAPS + ";"));
    }

    @Test
    public void varyingsMatchBetweenStages() {
        Pattern v = Pattern.compile("\\bvarying\\s+(\\w+)\\s+(\\w+)\\s*;");
        Set<String> vs = new LinkedHashSet<String>();
        Set<String> fs = new LinkedHashSet<String>();
        Matcher m = v.matcher(MotionBlurShaders.VERTEX);
        while (m.find()) vs.add(m.group(1) + " " + m.group(2));
        m = v.matcher(MotionBlurShaders.FRAGMENT);
        while (m.find()) fs.add(m.group(1) + " " + m.group(2));
        assertFalse(vs.isEmpty());
        assertEquals(vs, fs);
    }
}
