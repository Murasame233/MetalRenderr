#include <metal_stdlib>
using namespace metal;
struct LodTerrainParams {
    float4x4 projection;
    float4x4 modelView;
    float4   cameraPos;
    float4   chunkOffset;
    float4   fogColor;
    float    fogStart;
    float    fogEnd;
    uint     lodLevel;
    float    lodBlendFactor;
    float    biomeBlendRadius;
    uint     _pad0;
    uint     _pad1;
};
struct LodTerrainVertex {
    packed_float3 position;
    packed_short2 texCoord;
    packed_uchar4 color;
    packed_uchar4 normal;
    packed_uchar4 biomeColor2;
    uchar         packedLight;
    uchar         lodFlags;
    short         _pad;
};
struct LodVertexOut {
    float4 position  [[position]];
    float2 texCoord;
    float4 color;
    float3 normal;
    float2 lightUV;
    float  light;
    float  fogFactor;
    float  lodBlend;
    float4 biomeBlend;
};

static inline float3 decodePackedNormal(uchar4 packedNormal) {
    return normalize(float3((int8_t)packedNormal.x,
                            (int8_t)packedNormal.y,
                            (int8_t)packedNormal.z) / 127.0f);
}

vertex LodVertexOut vertex_lod_terrain(
    device const LodTerrainVertex* vertices   [[buffer(0)]],
    constant LodTerrainParams&     params     [[buffer(1)]],
    uint vid [[vertex_id]]
) {
    LodTerrainVertex v = vertices[vid];
    LodVertexOut out;
    float3 localPos = float3(v.position);
    float3 worldPos = localPos + params.chunkOffset.xyz;
    float4 viewPos = params.modelView * float4(worldPos, 1.0);
    out.position   = params.projection * viewPos;
    out.texCoord = float2(v.texCoord) / 65535.0;
    out.color    = float4(v.color) / 255.0;
    out.normal   = decodePackedNormal(v.normal);
    uint blockLight = uint(v.packedLight & 0xFu);
    uint skyLight   = uint((v.packedLight >> 4) & 0xFu);
    out.lightUV = float2((float(blockLight) + 0.5f) / 16.0f,
                         (float(skyLight) + 0.5f) / 16.0f);
    out.light = max(max(out.lightUV.x,
                        out.lightUV.y * params.cameraPos.w), 0.15f);
    float dist = length(viewPos.xyz);
    out.fogFactor = saturate((params.fogEnd - dist) / max(params.fogEnd - params.fogStart, 0.001));
    float camDist = length(worldPos - params.cameraPos.xyz);
    out.lodBlend  = params.lodBlendFactor;
    float4 biome2 = float4(v.biomeColor2) / 255.0;
    float blendDist = clamp(camDist / max(params.biomeBlendRadius * 16.0, 1.0), 0.0, 1.0);
    out.biomeBlend = float4(biome2.rgb, blendDist * 0.5);
    return out;
}
fragment float4 fragment_lod_terrain(
    LodVertexOut in [[stage_in]],
    texture2d<float> blockAtlas [[texture(0)]],
    texture2d<float> lightmap   [[texture(1)]],
    constant LodTerrainParams& params [[buffer(1)]]
) {
    constexpr sampler texSampler(filter::nearest, address::clamp_to_edge);
    float4 texColor = blockAtlas.sample(texSampler, in.texCoord);
    float vertAlpha = in.color.a;
    if (texColor.a < 0.5) {
        if (vertAlpha > 0.994 && vertAlpha < 0.999) {
            texColor = float4(1.0);
        } else if (texColor.a < 0.004) {
            discard_fragment();
        }
    }
    float4 vertColor = in.color;
    if (in.biomeBlend.a > 0.01) {
        vertColor.rgb = mix(vertColor.rgb, in.biomeBlend.rgb, in.biomeBlend.a);
    }
    float4 baseColor = texColor * vertColor;
    float3 lightDir = normalize(float3(0.2, 1.0, 0.5));
    float nDotL = max(dot(in.normal, lightDir), 0.0);
    float lightFactor = 0.3 + 0.7 * nDotL;
    baseColor.rgb *= lightFactor;
    float4 light = lightmap.sample(texSampler, in.lightUV);
    baseColor.rgb *= light.rgb;
    baseColor.rgb = mix(params.fogColor.rgb, baseColor.rgb, in.fogFactor);
    float alpha = mix(1.0, baseColor.a, in.lodBlend);
    if (alpha < 0.01) discard_fragment();
    return float4(baseColor.rgb, 1.0);
}
struct FarLodVertex {
    packed_float3 position;
    packed_uchar4 color;
    packed_uchar4 normal;
    uchar packedLight;
    uchar _pad0;
    short _pad1;
};
struct FarLodVertexOut {
    float4 position [[position]];
    float4 color;
    float3 normal;
    float  fogFactor;
};
vertex FarLodVertexOut vertex_lod_terrain_far(
    device const FarLodVertex* vertices [[buffer(0)]],
    constant LodTerrainParams& params   [[buffer(1)]],
    uint vid [[vertex_id]]
) {
    FarLodVertex v = vertices[vid];
    FarLodVertexOut out;
    float3 worldPos = float3(v.position) + params.chunkOffset.xyz;
    float4 viewPos  = params.modelView * float4(worldPos, 1.0);
    out.position    = params.projection * viewPos;
    out.color       = float4(v.color) / 255.0;
    out.normal      = decodePackedNormal(v.normal);
    float dist     = length(viewPos.xyz);
    out.fogFactor  = saturate((params.fogEnd - dist) / max(params.fogEnd - params.fogStart, 0.001));
    return out;
}
fragment float4 fragment_lod_terrain_far(
    FarLodVertexOut in [[stage_in]],
    constant LodTerrainParams& params [[buffer(1)]]
) {
    float3 lightDir = normalize(float3(0.2, 1.0, 0.5));
    float nDotL = max(dot(in.normal, lightDir), 0.0);
    float lightFactor = 0.35 + 0.65 * nDotL;
    float3 color = in.color.rgb * lightFactor;
    color = mix(params.fogColor.rgb, color, in.fogFactor);
    return float4(color, 1.0);
}
