#version 450 core
#extension GL_ARB_separate_shader_objects: enable

#define PI 3.14159265359

layout(set = 3, binding = 0) uniform sampler2D InputNormalsMaterial;
layout(set = 3, binding = 1) uniform sampler2D InputDiffuseAlbedo;
layout(set = 3, binding = 2) uniform sampler2D InputZBuffer;

layout(location = 0) out vec4 FragColor;
layout(location = 0) in VertexData {
    vec2 textureCoord;
    mat4 projectionMatrix;
    mat4 viewMatrix;
    mat4 frustumVectors;
} Vertex;

layout(set = 0, binding = 0) uniform VRParameters {
    mat4 projectionMatrices[2];
    mat4 inverseProjectionMatrices[2];
    mat4 headShift;
    float IPD;
    int stereoEnabled;
} vrParameters;

layout(set = 1, binding = 0) uniform LightParameters {
    mat4 ViewMatrices[2];
    mat4 InverseViewMatrices[2];
    mat4 ProjectionMatrix;
    mat4 InverseProjectionMatrix;
    vec3 CamPosition;
};

layout(push_constant) uniform currentEye_t {
    int eye;
} currentEye;

layout(set = 2, binding = 0, std140) uniform ShaderParameters {
	int displayWidth;
	int displayHeight;
	float occlusionRadius;
	int occlusionSamples;
    float maxDistance;
    float bias;
    int algorithm;
};

const int NUM_STEPS = 4;

const float aoContrast = 1.0f;

const vec2 sampleDirections[] = vec2[](
    vec2(0.0988498, 0.229627),
    vec2(0.232268, 0.0924736),
    vec2(0.229627, -0.0988498),
    vec2(0.0924736, -0.232268),
    vec2(-0.0988498, -0.229627),
    vec2(-0.232268, -0.0924736),
    vec2(-0.229627, 0.0988498),
    vec2(-0.0924736, 0.232268),
    vec2(0.1977, 0.459255),
    vec2(0.464537, 0.184947),
    vec2(0.459255, -0.1977),
    vec2(0.184947, -0.464537),
    vec2(-0.1977, -0.459255),
    vec2(-0.464537, -0.184947),
    vec2(-0.459255, 0.1977),
    vec2(-0.184947, 0.464537),
    vec2(0.29655, 0.688882),
    vec2(0.696805, 0.277421),
    vec2(0.688882, -0.29655),
    vec2(0.277421, -0.696805),
    vec2(-0.29655, -0.688882),
    vec2(-0.696805, -0.277421),
    vec2(-0.688882, 0.29655),
    vec2(-0.277421, 0.696805),
    vec2(0.395399, 0.918509),
    vec2(0.929074, 0.369895),
    vec2(0.918509, -0.395399),
    vec2(0.369895, -0.929074),
    vec2(-0.395399, -0.918509),
    vec2(-0.929074, -0.369895),
    vec2(-0.918509, 0.395399),
    vec2(-0.369895, 0.929074)
);

vec3 viewFromDepth(float depth, vec2 texcoord) {
    vec2 uv = (vrParameters.stereoEnabled ^ 1) * texcoord + vrParameters.stereoEnabled * vec2((texcoord.x - 0.5 * currentEye.eye) * 2.0, texcoord.y);

	mat4 invProjection = (vrParameters.stereoEnabled ^ 1) * InverseProjectionMatrix + vrParameters.stereoEnabled * vrParameters.inverseProjectionMatrices[currentEye.eye];
	mat4 invView = (vrParameters.stereoEnabled ^ 1) * InverseViewMatrices[0] + vrParameters.stereoEnabled * (InverseViewMatrices[currentEye.eye]);

#ifndef OPENGL
    vec4 clipSpacePosition = vec4(uv * 2.0 - 1.0, depth, 1.0);
#else
    vec4 clipSpacePosition = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
#endif
    vec4 viewSpacePosition = invProjection * clipSpacePosition;

    viewSpacePosition /= viewSpacePosition.w;
    return viewSpacePosition.xyz;
}

vec3 worldFromDepth(float depth, vec2 texcoord) {
    vec2 uv = (vrParameters.stereoEnabled ^ 1) * texcoord + vrParameters.stereoEnabled * vec2((texcoord.x - 0.5 * currentEye.eye) * 2.0, texcoord.y);

	mat4 invProjection = (vrParameters.stereoEnabled ^ 1) * InverseProjectionMatrix + vrParameters.stereoEnabled * vrParameters.inverseProjectionMatrices[currentEye.eye];
	mat4 invView = (vrParameters.stereoEnabled ^ 1) * InverseViewMatrices[0] + vrParameters.stereoEnabled * (InverseViewMatrices[currentEye.eye] );

#ifndef OPENGL
    vec4 clipSpacePosition = vec4(uv * 2.0 - 1.0, depth, 1.0);
#else
    vec4 clipSpacePosition = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
#endif
    vec4 viewSpacePosition = invProjection * clipSpacePosition;

    viewSpacePosition /= viewSpacePosition.w;
    vec4 world = invView * viewSpacePosition;
    return world.xyz;
}

vec2 OctWrap( vec2 v )
{
    vec2 ret;
    ret.x = (1-abs(v.y)) * (v.x >= 0 ? 1.0 : -1.0);
    ret.y = (1-abs(v.x)) * (v.y >= 0 ? 1.0 : -1.0);
    return ret.xy;
}

/*
Decodes the octahedron normal vector from it's two component form to return the normal with its three components. Uses the
property |x| + |y| + |z| = 1 and reverses the orthogonal projection performed while encoding.
*/
vec3 DecodeOctaH( vec2 encN )
{
    encN = encN * 2.0 - 1.0;
    vec3 n;
    n.z = 1.0 - abs( encN.x ) - abs( encN.y );
    n.xy = n.z >= 0.0 ? encN.xy : OctWrap( encN.xy );
    n = normalize( n );
    return n;
}

float random (vec2 st) {
    return fract(sin(dot(st.xy,
        vec2(12.9898,78.233)))*
            43758.5453123);
}

float Falloff(float DistanceSquare)
{
  // 1 scalar mad instruction
  const float NegInvR2 = -1.0f/(occlusionRadius * occlusionRadius);
  return DistanceSquare * NegInvR2 + 1.0;
}

vec2 RotateDirection(vec2 Dir, vec2 CosSin)
{
  return vec2(Dir.x*CosSin.x - Dir.y*CosSin.y,
              Dir.x*CosSin.y + Dir.y*CosSin.x);
}

//----------------------------------------------------------------------------------
// P = view-space position at the kernel center
// N = view-space normal at the kernel center
// S = view-space position of the current sample
//----------------------------------------------------------------------------------
float ComputeAO(vec3 P, vec3 N, vec3 S)
{
  vec3 V = S - P;
  float VdotV = dot(V, V);
  float NdotV = dot(N, V) * 1.0/sqrt(VdotV);

  // Use saturate(x) instead of max(x,0.f) because that is faster on Kepler
  return clamp(NdotV - bias,0,1) * clamp(Falloff(VdotV),0,1);
}

float ComputeCoarseAO(vec2 FullResUV, float RadiusPixels, vec4 Rand, vec3 ViewPosition, vec3 ViewNormal, vec2 invRes)
{
  const float aoStrength = 1.0f/(1.0f - bias);
  // Divide by NUM_STEPS+1 so that the farthest samples are not fully attenuated
  float StepSizePixels = RadiusPixels / (NUM_STEPS + 1);

  const float Alpha = 2.0f * PI / occlusionSamples;
  float AO = 0.0f;

  for (float DirectionIndex = 0; DirectionIndex < occlusionSamples; ++DirectionIndex)
  {
    float Angle = Alpha * DirectionIndex;

    // Compute normalized 2D direction
    vec2 Direction = RotateDirection(vec2(cos(Angle), sin(Angle)), Rand.xy);

    // Jitter starting sample within the first step
    float RayPixels = (Rand.z * StepSizePixels + 1.0f);

    for (float StepIndex = 0; StepIndex < NUM_STEPS; ++StepIndex)
    {
      vec2 SnappedUV = round(RayPixels * Direction) * invRes + FullResUV;
      float d = texture(InputZBuffer, SnappedUV).r;
      vec3 S = worldFromDepth(d, SnappedUV);

      RayPixels += StepSizePixels;

      AO += ComputeAO(ViewPosition, ViewNormal, S);
    }
  }

  AO *= aoStrength / (occlusionSamples * NUM_STEPS);
  return clamp(1.0f - AO * 2.0f , 0.0f, 1.0f);
}

vec4 GetJitter()
{
  // (cos(Alpha),sin(Alpha),rand1,rand2)
//  return vec4(random(gl_FragCoord.xy));
  uint index = uint(floor(random(gl_FragCoord.xy) * 31));
  return vec4(sampleDirections[index].xy, random(gl_FragCoord.xy), 0.0);
}


void main()
{
  if(occlusionSamples == 0) {
    FragColor = vec4(0.0);
    return;
  }

  vec2 invRes = vec2(1.0f, 1.0f) / vec2(displayWidth, displayHeight);
  vec2 textureCoord = gl_FragCoord.xy * invRes;
  textureCoord = (vrParameters.stereoEnabled ^ 1) * textureCoord + vrParameters.stereoEnabled * vec2((textureCoord.x - 0.5 * currentEye.eye) * 2.0, textureCoord.y);

  float depth = texture(InputZBuffer, textureCoord).r;
  vec3 ViewPosition = worldFromDepth(depth, textureCoord);
  // Reconstruct view-space normal from nearest neighbors
//  vec3 ViewNormal = -(Vertex.viewMatrix * vec4(DecodeOctaH(texture(InputNormalsMaterial, textureCoord).rg), 1.0)).xyz;
  vec3 ViewNormal = DecodeOctaH(texture(InputNormalsMaterial, textureCoord).rg);
  mat4 projectionMatrix = Vertex.projectionMatrix;
  float fov = atan(1.0f/projectionMatrix[1][1]);
  float projScale = displayHeight / (tan(fov * 0.5f) * 2.0f);
  float radiusToScreen = occlusionRadius * 0.5f * projScale;

  // Compute projection of disk of radius control.R into screen space

  float RadiusPixels = radiusToScreen / (ViewPosition.z);
//  RadiusPixels = 50.0f;
  // Get jitter vector for the current full-res pixel
  vec4 Rand = GetJitter();

  float AO = ComputeCoarseAO(textureCoord, RadiusPixels, Rand, ViewPosition, ViewNormal, invRes);

  FragColor = vec4(pow(AO, aoContrast));
}
