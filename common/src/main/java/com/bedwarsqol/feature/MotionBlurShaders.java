package com.bedwarsqol.feature;

/**
 * Motion Blur's GLSL 1.20 sources, the most the Mac's legacy OpenGL 2.1 context compiles. Kept as Java strings so they
 * never go through the resource manager.
 *
 * <p>The vertex shader passes a clip-space quad straight through (the matrices are never read) and derives the
 * texture coordinate from it. The fragment shader takes {@link #U_TAPS} taps along {@link #U_SMEAR} (a vector in
 * texture units), centred on the pixel, with triangle weights; each pixel's taps are shifted by a 4x4 ordered offset
 * of up to half a tap spacing, computed arithmetically from the pixel position. Alpha is written as 1.
 */
public final class MotionBlurShaders {

    /** The copied frame, a {@code sampler2D} on texture unit 0. */
    public static final String U_SCENE = "u_scene";
    /** The smear, a {@code vec2} in texture units: its full length, centred on the pixel. */
    public static final String U_SMEAR = "u_smear";
    /** How many taps, a {@code float} 2..{@link #LOOP_TAPS}. */
    public static final String U_TAPS = "u_taps";
    /** Every uniform the sources declare; the GL helper binds each. */
    public static final String[] UNIFORMS = {U_SCENE, U_SMEAR, U_TAPS};
    /** The fragment loop's constant bound: the most taps any Strength asks for. */
    public static final int LOOP_TAPS = 16;

    public static final String VERTEX = "#version 120\n"
            + "varying vec2 v_uv;\n"
            + "void main() {\n"
            + "    gl_Position = vec4(gl_Vertex.xy, 0.0, 1.0);\n"
            + "    v_uv = gl_Vertex.xy * 0.5 + 0.5;\n"
            + "}\n";

    public static final String FRAGMENT = "#version 120\n"
            + "uniform sampler2D " + U_SCENE + ";\n"
            + "uniform vec2 " + U_SMEAR + ";\n"
            + "uniform float " + U_TAPS + ";\n"
            + "varying vec2 v_uv;\n"
            + "float bayer2(vec2 a) {\n"
            + "    return mod(2.0 * a.x + 3.0 * a.y, 4.0);\n"
            + "}\n"
            + "void main() {\n"
            + "    vec2 p = floor(mod(gl_FragCoord.xy, 4.0));\n"
            + "    float order = 4.0 * bayer2(mod(p, 2.0)) + bayer2(floor(p * 0.5));\n"
            + "    float jitter = (order + 0.5) / 16.0 - 0.5;\n"
            + "    vec3 sum = vec3(0.0);\n"
            + "    float total = 0.0;\n"
            + "    for (int i = 0; i < " + LOOP_TAPS + "; i++) {\n"
            + "        if (float(i) >= " + U_TAPS + ") break;\n"
            + "        float f = (float(i) + 0.5) / " + U_TAPS + " - 0.5;\n"
            + "        float w = 1.0 - abs(2.0 * f);\n"
            + "        vec2 uv = v_uv + " + U_SMEAR + " * (f + jitter / " + U_TAPS + ");\n"
            + "        sum += texture2D(" + U_SCENE + ", uv).rgb * w;\n"
            + "        total += w;\n"
            + "    }\n"
            + "    gl_FragColor = vec4(sum / total, 1.0);\n"
            + "}\n";

    private MotionBlurShaders() {
    }
}
