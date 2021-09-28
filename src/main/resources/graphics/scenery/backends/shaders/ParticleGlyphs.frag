#version 450
#extension GL_ARB_separate_shader_objects: enable

layout(location = 0) in SilhouetteData {
    vec3 Position;
    vec2 TexCoord;
    vec3 Color;
    flat vec3 Center;
    flat vec3 Properties;
} SilhouetteCorner;

layout(location = 0) out vec4 NormalsMaterial;
layout(location = 1) out vec4 DiffuseAlbedo;

struct Light {
    float Linear;
    float Quadratic;
    float Intensity;
    float Radius;
    vec4 Position;
    vec4 Color;
};

const int MAX_NUM_LIGHTS = 1024;

layout(set = 1, binding = 0) uniform LightParameters {
    mat4 ViewMatrices[2];
    mat4 InverseViewMatrices[2];
    mat4 ProjectionMatrix;
    mat4 InverseProjectionMatrix;
    vec3 CamPosition;
    int numLights;
    Light lights[MAX_NUM_LIGHTS];
};

layout(set = 2, binding = 0) uniform Matrices {
    mat4 ModelMatrix;
    mat4 NormalMatrix;
    int isBillboard;
} ubo;

struct MaterialInfo {
    vec3 Ka;
    vec3 Kd;
    vec3 Ks;
    float Roughness;
    float Metallic;
    float Opacity;
};

//layout(location = 0) out vec4 FragColor;


vec3 RaySphereIntersection(in vec3 eye, in vec3 fragPos, in vec3 center, in float radius)
{
    float beta = (radius * sqrt(1 - length(SilhouetteCorner.TexCoord) * length(SilhouetteCorner.TexCoord))) / length(eye - center);
    float lambda = 1 / (1 + beta);
    return eye + lambda * (fragPos - eye);
}

void main() {
    vec3 objColor = vec3(0.2, 0.6, 0.8); // TODO: calculate from particle properties
    //First: Check if ray hits sphere
    if(!(length(SilhouetteCorner.TexCoord) * length(SilhouetteCorner.TexCoord) <= 1))
    {
        discard;
    }
    //Second: Color pixel according to lighting (normal calculation + light parameters from scenery's lighting system
    else
    {
        vec3 intersection = RaySphereIntersection(CamPosition, SilhouetteCorner.Position, SilhouetteCorner.Center, SilhouetteCorner.Properties.x);
        vec3 normal = normalize((intersection - SilhouetteCorner.Center));

        NormalsMaterial = vec4(normal, 0.0);
        DiffuseAlbedo   = vec4(objColor, 1.0);
    }
}

