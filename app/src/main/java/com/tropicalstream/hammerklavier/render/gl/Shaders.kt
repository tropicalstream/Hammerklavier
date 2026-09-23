package com.tropicalstream.hammerklavier.render.gl

/**
 * Every program's GLSL ES 1.00 source (PLAN §5.8, §5.9). Fragment sources start with
 * [GlKit.FRAG_PRECISION]. Uniform budget: the skinned program is the largest at ≈ 68 vec4
 * (uState 34, uPivot 6, 2 mat4, 4 + 4 lights, a dozen scalars), well under the 128 asserted.
 *
 * Colour rules (§5.9): vertex colours and textures are un-lifted sRGB; every surface is lifted
 * with pow(c, 0.85); instrument surfaces with a presence floor get max(c, uFloor); black is
 * transparent on the waveguide, so the clear colour and every fade go toward black.
 *
 * Skin kinds are passed as float(SkinKind.ordinal) in uKind: STATIC 0, KEY_ROT 1, HAMMER_ROT 2,
 * DAMPER_LIFT 3, JACK_LIFT 4, JACK4_LIFT 5, TONGUE_ROT 6, TONGUE4_ROT 7, STRING 8, PEDAL_ROT 9,
 * ACTION_SET 10, LID 11, SHIFT_X 12, HAMMER_RAIL 13, SOSTENUTO_ROT 14.
 */
object Shaders {
    // ─────────────────────────── shared fragment pieces ───────────────────────────
    private const val LIGHTING = """
uniform vec3 uLightPos[4];
uniform vec3 uLightRgb[4];
uniform vec3 uAmbient;
uniform vec3 uEye;
uniform float uSpecExp;
uniform vec3 uFloor;
uniform float uUseFloor;
uniform vec4 uFadeC;
uniform vec2 uFadeR;
vec3 lift(vec3 c) { return pow(max(c, vec3(0.0)), vec3(0.85)); }
vec3 shade(vec3 base, vec3 N, vec3 W, float specK) {
    vec3 V = normalize(uEye - W);
    vec3 c = base * uAmbient;
    for (int i = 0; i < 4; i++) {
        vec3 L = uLightPos[i] - W;
        float d2 = dot(L, L);
        L *= inversesqrt(d2 + 0.0001);
        float att = 1.0 / (1.0 + d2);
        float nd = max(dot(N, L), 0.0);
        vec3 H = normalize(L + V);
        float sp = pow(max(dot(N, H), 0.0), uSpecExp) * specK;
        c += (base * nd + vec3(sp)) * uLightRgb[i] * att;
    }
    return c;
}
float distanceFade(vec3 W) {
    if (uFadeR.y <= 0.0) return 1.0;
    return 1.0 - smoothstep(uFadeR.x, uFadeR.y, distance(W, uFadeC.xyz));
}
"""

    // ─────────────────────────── lit (STATIC layout) ───────────────────────────
    const val LIT_VS = """
attribute vec3 aPos;
attribute vec3 aNrm;
attribute vec2 aUv;
attribute vec4 aCol;
uniform mat4 uVP;
uniform mat4 uModel;
uniform float uShiftX;                 // SECTION_CAP only: caps are built in x = 0 and drawn at the cut plane
varying vec3 vW;
varying vec3 vN;
varying vec4 vCol;
varying vec2 vUv;
varying float vMX;
void main() {
    vec3 pp = aPos + vec3(uShiftX, 0.0, 0.0);
    vec4 w = uModel * vec4(pp, 1.0);
    vMX = pp.x;
    vW = w.xyz;
    vN = (uModel * vec4(aNrm, 0.0)).xyz;
    vCol = aCol;
    vUv = aUv;
    gl_Position = uVP * w;
}
"""

    const val LIT_FS = GlKit.FRAG_PRECISION + LIGHTING + """
uniform float uBaked;
varying vec3 vW;
varying vec3 vN;
varying vec4 vCol;
varying vec2 vUv;
uniform float uClipX;
varying float vMX;
void main() {
    if (vMX > uClipX) discard;
    vec3 N = normalize(vN);
    vec3 c = mix(shade(vCol.rgb, N, vW, 0.3), vCol.rgb, uBaked);
    c = lift(c);
    c = max(c, uFloor * uUseFloor);
    c *= distanceFade(vW);
    gl_FragColor = vec4(c, vCol.a);
}
"""

    // ─────────────────────────── lacquer (STATIC layout) ───────────────────────────
    const val LACQUER_FS = GlKit.FRAG_PRECISION + LIGHTING + """
uniform sampler2D uProbe;
uniform float uF0;
uniform vec3 uRim;
varying vec3 vW;
varying vec3 vN;
varying vec4 vCol;
varying vec2 vUv;
uniform float uClipX;
varying float vMX;
void main() {
    if (vMX > uClipX) discard;
    vec3 N = normalize(vN);
    vec3 V = normalize(uEye - vW);
    float nv = clamp(dot(N, V), 0.0, 1.0);
    vec3 c = shade(vCol.rgb, N, vW, 1.0);
    float r = 1.0 - nv;
    c += uRim * (r * r * r) * 0.8;
    vec3 R = reflect(-V, N);
    vec2 puv = vec2(atan(R.x, -R.z) * 0.15915494 + 0.5, acos(clamp(R.y, -1.0, 1.0)) * 0.31830989);
    float fr = uF0 + (1.0 - uF0) * r * r * r * r * r;
    c += texture2D(uProbe, puv).rgb * fr;
    c = lift(c);
    c = max(c, uFloor * uUseFloor);
    c *= distanceFade(vW);
    gl_FragColor = vec4(c, vCol.a);
}
"""

    // ─────────────────────────── skinned (SKINNED layout) ───────────────────────────
    const val SKINNED_VS = """
attribute vec3 aPos;
attribute vec3 aNrm;
attribute vec2 aUv;
attribute vec4 aCol;
attribute float aSlot;
attribute vec4 aLane;
uniform mat4 uVP;
uniform mat4 uModel;
uniform vec4 uState[34];
uniform vec4 uPivot[6];
uniform vec4 uP0;
uniform vec4 uP1;
uniform float uKind;
uniform float uShiftX;
uniform float uRailM;
varying vec3 vW;
varying vec3 vN;
varying vec4 vCol;
varying vec2 vUv;
varying float vMX;
varying float vDim;
void rotX(inout vec3 p, inout vec3 n, float py, float pz, float a) {
    float c = cos(a);
    float s = sin(a);
    float dy = p.y - py;
    float dz = p.z - pz;
    p.y = py + dy * c - dz * s;
    p.z = pz + dy * s + dz * c;
    float ny = n.y * c - n.z * s;
    n.z = n.y * s + n.z * c;
    n.y = ny;
}
void rotZ(inout vec3 p, inout vec3 n, float px, float py, float a) {
    float c = cos(a);
    float s = sin(a);
    float dx = p.x - px;
    float dy = p.y - py;
    p.x = px + dx * c - dy * s;
    p.y = py + dx * s + dy * c;
    float nx = n.x * c - n.y * s;
    n.y = n.x * s + n.y * c;
    n.x = nx;
}
void main() {
    float v = dot(uState[int(aSlot + 0.5)], aLane);
    vec3 p = aPos;
    vec3 n = aNrm;
    float dim = 1.0;
    float k = uKind;
    bool hide = false;
    if (k < 0.5) {
    } else if (k < 1.5) {
        p.x += uShiftX;
        rotX(p, n, uP0.x, uP0.y, v * uP0.z);
    } else if (k < 2.5) {
        p.x += uShiftX;
        rotX(p, n, uP0.x, uP0.y, v * uP0.z);
    } else if (k < 3.5) {
        p += v * uP0.x * uP0.yzw;
    } else if (k < 5.5) {
        p.y += v * uP0.x;
        p.x += uP0.y * uP1.x;
    } else if (k < 7.5) {
        rotX(p, n, uP0.x, uP0.y, v * uP0.z);
    } else if (k < 9.5) {
        rotX(p, n, uP0.x, uP0.y, v * uP0.z);
    } else if (k < 10.5) {
        vec4 h = uState[int(aUv.x + 0.5)];
        vec4 pv = uPivot[int(aUv.y + 0.5)];
        p.x += h.x + uShiftX;
        rotX(p, n, pv.x, pv.y, v * pv.z);
        dim = h.y;
        hide = h.y < 0.01;
    } else if (k < 11.5) {
        rotZ(p, n, uP0.x, uP0.y, uP0.w);
        hide = uState[0].x > 0.5;
    } else if (k < 12.5) {
        p.x += uShiftX;
    } else if (k < 13.5) {
        p.z -= uRailM;
    } else {
        float a = v < 0.5 ? v * 2.0 * uP0.z : mix(uP0.z, uP0.w, (v - 0.5) * 2.0);
        rotX(p, n, uP0.x, uP0.y, a);
    }
    vMX = p.x;
    vDim = dim;
    vec4 w = uModel * vec4(p, 1.0);
    vW = w.xyz;
    vN = (uModel * vec4(n, 0.0)).xyz;
    vCol = aCol;
    vUv = aUv;
    gl_Position = hide ? vec4(0.0, 0.0, 2.0, 1.0) : uVP * w;
}
"""

    const val SKINNED_FS = GlKit.FRAG_PRECISION + LIGHTING + """
uniform float uClipX;
uniform float uBevel;
varying vec3 vW;
varying vec3 vN;
varying vec4 vCol;
varying vec2 vUv;
varying float vMX;
varying float vDim;
void main() {
    if (vMX > uClipX) discard;
    vec3 N = normalize(vN);
    vec3 c = shade(vCol.rgb, N, vW, 0.4);
    float e = smoothstep(0.0, 0.8, vUv.x) * smoothstep(0.0, 0.8, vUv.y);
    c *= mix(1.0, mix(0.72, 1.0, e), uBevel);
    c = lift(c) * vDim;
    c = max(c, uFloor * uUseFloor * vDim);
    gl_FragColor = vec4(c, vCol.a);
}
"""

    // ─────────────────────────── string (STRING layout) ───────────────────────────
    const val STRING_VS = """
attribute vec3 aPos;
attribute vec3 aDir;
attribute float aT;
attribute float aSide;
attribute float aSlot;
attribute vec4 aLane;
attribute vec3 aRgb;
uniform mat4 uVP;
uniform mat4 uModel;
uniform vec4 uState[34];
uniform vec2 uViewport;
uniform float uWidthPx;
uniform float uSwellPx;
varying vec3 vRgb;
varying float vSide;
varying float vHalf;
varying float vMX;
void main() {
    float v = clamp(dot(uState[int(aSlot + 0.5)], aLane), 0.0, 1.0);
    vec4 w = uModel * vec4(aPos, 1.0);
    vec4 c0 = uVP * w;
    vec4 c1 = uVP * (uModel * vec4(aPos + aDir * 0.05, 1.0));
    vec2 s0 = c0.xy / c0.w * uViewport * 0.5;
    vec2 s1 = c1.xy / c1.w * uViewport * 0.5;
    vec2 d = s1 - s0;
    float l = length(d);
    d = l > 0.0001 ? d / l : vec2(1.0, 0.0);
    vec2 perp = vec2(-d.y, d.x);
    float env = sin(3.14159265 * clamp(aT, 0.0, 1.0));
    float hw = max(uWidthPx, 1.0) * 0.5 + v * env * uSwellPx + 0.75;
    c0.xy += perp * aSide * hw / (uViewport * 0.5) * c0.w;
    vHalf = hw;
    vSide = aSide;
    vRgb = aRgb * (0.75 + 0.6 * v);
    vMX = aPos.x;
    gl_Position = c0;
}
"""

    const val STRING_FS = GlKit.FRAG_PRECISION + """
uniform float uClipX;
varying vec3 vRgb;
varying float vSide;
varying float vHalf;
varying float vMX;
vec3 lift(vec3 c) { return pow(max(c, vec3(0.0)), vec3(0.85)); }
void main() {
    if (vMX > uClipX) discard;
    float a = clamp((1.0 - abs(vSide)) * vHalf, 0.0, 1.0);
    gl_FragColor = vec4(lift(vRgb), a);
}
"""

    // ─────────────────────────── ribbon (STATIC layout: uv = side, half-width m) ───────────────────────────
    const val RIBBON_VS = """
attribute vec3 aPos;
attribute vec3 aNrm;
attribute vec2 aUv;
attribute vec4 aCol;
uniform mat4 uVP;
uniform mat4 uModel;
uniform vec2 uViewport;
uniform float uProjY;
varying vec4 vCol;
varying float vSide;
varying float vHalf;
varying vec3 vW;
varying float vMX;
void main() {
    vec4 w = uModel * vec4(aPos, 1.0);
    vMX = aPos.x;
    vec4 c0 = uVP * w;
    vec4 c1 = uVP * (uModel * vec4(aPos + aNrm * 0.02, 1.0));
    vec2 s0 = c0.xy / c0.w * uViewport * 0.5;
    vec2 s1 = c1.xy / c1.w * uViewport * 0.5;
    vec2 d = s1 - s0;
    float l = length(d);
    d = l > 0.0001 ? d / l : vec2(1.0, 0.0);
    vec2 perp = vec2(-d.y, d.x);
    float halfPx = aUv.y * uProjY * uViewport.y * 0.5 / max(c0.w, 0.001);
    float hw = max(halfPx, 0.75) + 1.0;
    c0.xy += perp * sign(aUv.x) * hw / (uViewport * 0.5) * c0.w;
    vHalf = hw;
    vSide = sign(aUv.x);
    vCol = aCol;
    vW = w.xyz;
    gl_Position = c0;
}
"""

    const val RIBBON_FS = GlKit.FRAG_PRECISION + """
uniform float uEmissive;
uniform float uLight;
uniform vec4 uFadeC;
uniform vec2 uFadeR;
varying vec4 vCol;
varying float vSide;
varying float vHalf;
varying vec3 vW;
vec3 lift(vec3 c) { return pow(max(c, vec3(0.0)), vec3(0.85)); }
uniform float uClipX;
varying float vMX;
void main() {
    if (vMX > uClipX) discard;
    float a = clamp((1.0 - abs(vSide)) * vHalf, 0.0, 1.0);
    float f = uFadeR.y > 0.0 ? 1.0 - smoothstep(uFadeR.x, uFadeR.y, distance(vW, uFadeC.xyz)) : 1.0;
    vec3 c = lift(vCol.rgb) * max(uEmissive, uLight) * a * f;
    gl_FragColor = vec4(c, 1.0);
}
"""

    // ─────────────────────────── sprite (dynamic: pos3 corner2 rgba4) ───────────────────────────
    const val SPRITE_VS = """
attribute vec3 aPos;
attribute vec2 aCorner;
attribute vec4 aCol;
uniform mat4 uVP;
varying vec2 vCorner;
varying vec4 vCol;
void main() {
    vCorner = aCorner;
    gl_Position = uVP * vec4(aPos, 1.0);
    // M5: a flame (or its halo) within arm's length of the eye would fill the view; fade it out by depth (w = eye depth)
    vCol = vec4(aCol.rgb, aCol.a * smoothstep(0.5, 1.2, gl_Position.w));
}
"""

    const val SPRITE_FS = GlKit.FRAG_PRECISION + """
varying vec2 vCorner;
varying vec4 vCol;
void main() {
    float r2 = dot(vCorner, vCorner);
    float f = max(0.0, 1.0 - r2);
    f *= f;
    gl_FragColor = vec4(vCol.rgb * vCol.a * f, 1.0);
}
"""

    // ─────────────────────────── decal (STATIC layout + texture) ───────────────────────────
    const val DECAL_FS = GlKit.FRAG_PRECISION + LIGHTING + """
uniform sampler2D uTex;
uniform float uHasTex;
uniform float uEmissive;
varying vec3 vW;
varying vec3 vN;
varying vec4 vCol;
varying vec2 vUv;
uniform float uClipX;
varying float vMX;
void main() {
    if (vMX > uClipX) discard;
    vec4 t = uHasTex > 0.5 ? texture2D(uTex, vUv) : vec4(1.0);
    vec3 base = vCol.rgb * t.rgb;
    vec3 c = lift(base) * max(uEmissive, 0.35);
    c *= distanceFade(vW);
    gl_FragColor = vec4(c, vCol.a * t.a);
}
"""

    // ─────────────────────────── section cap (STATIC layout) ───────────────────────────
    const val SECTION_CAP_FS = GlKit.FRAG_PRECISION + """
varying vec3 vW;
varying vec3 vN;
varying vec4 vCol;
varying vec2 vUv;
void main() {
    gl_FragColor = vec4(pow(max(vCol.rgb, vec3(0.0)), vec3(0.85)), vCol.a);
}
"""

    // ─────────────────────────── fade quad and sync disc (NDC pos2) ───────────────────────────
    const val FADE_VS = """
attribute vec2 aPos;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    const val FADE_FS = GlKit.FRAG_PRECISION + """
uniform vec4 uColor;
uniform vec4 uDisc;
void main() {
    if (uDisc.w > 0.5) {
        float d = distance(gl_FragCoord.xy, uDisc.xy);
        float a = 1.0 - smoothstep(uDisc.z - 1.0, uDisc.z + 1.0, d);
        gl_FragColor = vec4(uColor.rgb, a * uColor.a);
    } else {
        gl_FragColor = uColor;
    }
}
"""

    /** name → (vertex, fragment); used by the program table and the source validation test. */
    val ALL: List<Triple<String, String, String>> = listOf(
        Triple("lit", LIT_VS, LIT_FS), Triple("lacquer", LIT_VS, LACQUER_FS), Triple("skinned", SKINNED_VS, SKINNED_FS),
        Triple("string", STRING_VS, STRING_FS), Triple("ribbon", RIBBON_VS, RIBBON_FS), Triple("sprite", SPRITE_VS, SPRITE_FS),
        Triple("decal", LIT_VS, DECAL_FS), Triple("sectionCap", LIT_VS, SECTION_CAP_FS), Triple("fade", FADE_VS, FADE_FS))
}
