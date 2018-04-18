#version 450 core
#extension GL_ARB_separate_shader_objects: enable

const float PI = 3.14159265358979323846264;
const int NUM_OBJECT_TEXTURES = 6;

layout(location = 0) in VertexData {
    vec3 Position;
    vec3 Normal;
    vec2 TexCoord;
    vec3 FragPosition;
} Vertex;

layout(set = 3, binding = 0) uniform sampler2D ObjectTextures[NUM_OBJECT_TEXTURES];

layout(set = 4, binding = 0) uniform ShaderProperties {
    int transparent;
    vec4 atlasSize;
    vec4 fontColor;
    vec4 backgroundColor;
};

layout(location = 0) out vec4 FragColor;

float aastep (float threshold , float value) {
  float afwidth = 0.5 * length ( vec2(dFdx(value), dFdy(value)));
  return smoothstep (threshold-afwidth, threshold+afwidth, value );
}

float contour(in float d, in float w) {
    // smoothstep(lower edge0, upper edge1, x)
    return smoothstep(0.5 - w, 0.5 + w, d);
}

float samp(in vec2 uv, float w) {
    return contour(texture(ObjectTextures[1], uv).a, w);
}

vec3 blendMultiply(vec3 lhs, vec3 rhs) {
    return lhs*rhs;
}

vec3 blendMultiply(vec3 lhs, vec3 rhs, float opacity) {
    return (blendMultiply(lhs, rhs) * opacity + lhs * (1.0 - opacity));
}

void main() {
    // Bilinear SDF interpolation by Stefan Gustavson, OpenGL Insights, 2011
    // see https://github.com/OpenGLInsights/OpenGLInsightsCode/tree/master/Chapter%2012%202D%20Shape%20Rendering%20by%20Distance%20Fields/demo

    vec3 rgb = vec3(1.0f, 1.0f, 1.0f);
    float pattern = 0.0;

    if(false) {
    float pattern = 0.0f;
    float texw = atlasSize.x;
    float texh = atlasSize.y;

    float oneu = 1.0f/texw;
    float onev = 1.0f/texh;

    vec2 uv = Vertex.TexCoord * vec2 ( texw , texh ) ; // Scale to texture rect coords
    vec2 uv00 = floor ( uv - vec2 (0.5) ); // Lower left of lower left texel
    vec2 uvlerp = uv - uv00 - vec2 (0.5) ; // Texel - local blends [0 ,1]

    // Perform explicit texture interpolation of distance value D.
    // If hardware interpolation is OK , use D = texture2D ( disttex , st).
    // Center st00 on lower left texel and rescale to [0 ,1] for lookup
    vec2 st00 = ( uv00 + vec2 (0.5) ) * vec2 ( oneu , onev );
    // Sample distance D from the centers of the four closest texels
    float aascale = 1.0;
    float D00 = texture( ObjectTextures[1], st00 ).r ;
    float D10 = texture( ObjectTextures[1], st00 + vec2 (aascale*oneu , 0.0) ).r;
    float D01 = texture( ObjectTextures[1], st00 + vec2 (0.0 , aascale*onev ) ).r;
    float D11 = texture( ObjectTextures[1], st00 + vec2 (aascale*oneu, aascale*onev ) ).r;

    vec2 D00_10 = vec2 ( D00 , D10 );
    vec2 D01_11 = vec2 ( D01 , D11 );

    vec2 D0_1 = mix ( D00_10 , D01_11 , uvlerp.y);
    float D = mix( D0_1.x , D0_1.y , uvlerp.x);

    pattern = aastep(0.5, D);
    } else {
        float dist = texture(ObjectTextures[1], Vertex.TexCoord).r;
        float width = fwidth(dist);
        pattern = contour(dist, width);

        const float dscale = 0.554;
        vec2 deltaUV = dscale * (dFdx(Vertex.TexCoord) + dFdy(Vertex.TexCoord));
        vec4 box = vec4(Vertex.TexCoord - deltaUV, Vertex.TexCoord + deltaUV);

        float alphaSum = samp(box.xy, width) + samp(box.zw, width) + samp(box.xw, width) + samp(box.zy, width);
        pattern = (pattern + 0.5 * alphaSum)/3.0;
    }

    if(transparent == 1) {
        FragColor = vec4(fontColor.rgb, pattern);
    } else {
        float a = smoothstep(0.1, 0.9, pattern);
        rgb = mix(backgroundColor.rgb, fontColor.rgb, a);

        FragColor = vec4(rgb, 1.0);
    }
}
