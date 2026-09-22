// Shared definitions for all ray-tracing stages. The generateShaderRecords task generates typed Java
// records and serializers from Slang reflection.
module world_common;

// Read-only device pointer with explicit std430 layout. The layout parameter is LOAD-BEARING: a bare
// Ptr<T> gets Slang's "natural" layout (no vec4 16-byte alignment → e.g. WorldPush.sunDir lands at 200
// instead of the Java writer's 208, silently reading garbage) and additionally requires the
// scalarBlockLayout device feature. Keep std430 unless RtDeviceBringup explicitly enables that feature;
// this alias is the single layout switch and generated Java serialization stays in lock-step.
public typealias ConstPtr<T> = Ptr<T, Access.Read, AddressSpace.Device, Std430DataLayout>;
public typealias DevicePtr<T> = Ptr<T, Access.ReadWrite, AddressSpace.Device, Std430DataLayout>;

// Push constant block. The hot inline lanes avoid dereferencing WorldPush for values that are read in
// hit shaders or used for raygen control flow — every 64-bit device address lives here for exactly that
// reason, rather than behind the worldPushAddr indirection. tableAddr/entityTableAddr/materialTableAddr
// are read in world.rahit/world.rchit, which never load WorldPush at all; the light-buffer addresses are
// only read in world.rgen, which loads WorldPush anyway, but keeping every Addr in one place means there
// is exactly one struct to check when adding a new device buffer, instead of two.
public struct WorldPushConstants {
    public uint64_t worldPushAddr;
    public uint64_t tableAddr;
    public uint64_t entityTableAddr;
    public uint64_t dhTableAddr;         // coarse DH/Voxy LOD section table (0 = no LOD proxy published)
    public uint64_t dhReadyMaskAddr;     // per-frame vanilla-ready section bitmask (0 = suppress nothing)
    public uint64_t materialTableAddr;
    public uint64_t lightBufAddr;        // RIS emitter-NEE global light buffer (0 = none published)
    public uint64_t lightAliasAddr;      // power-weighted O(1) alias table (0 = uniform fallback)
    public uint64_t lightLocalAliasAddr; // power aliases relative to each section's range
    public uint64_t lightGridCellAddr;   // dense light grid cell headers (0 = global proposals only)
    public uint64_t lightGridSpanAddr;   // packed weighted section spans referenced by cell headers
    public uint64_t pathQueueAddr;       // packed primary -> indirect continuation records
    // ReSTIR ping-pong history. Both are zero while the player toggle is off, so no history memory is
    // reachable (or even allocated) on the legacy RIS path.
    public uint64_t restirPreviousAddr;
    public uint64_t restirCurrentAddr;
    public uint frameIndex;
    public uint debugView;
    public uint lightGeneration;         // low bits reject history after the emitter hierarchy changes
    public uint restirMode;              // explicit 0=RIS fallback, 1=ReSTIR read+shade (live UI toggle)
};

// Block-breaking overlay entry: xyz = breaking block position (rebased, same space as gl_WorldRay*),
// w = bindless entityAlbedoTex slot of the destroy-stage texture.
public struct BreakEntry {
    public int4 posSlot;
};

// Compact persistent ReSTIR ABI. Positions are camera-relative so a terrain-origin rebase cannot
// invalidate otherwise-stationary history. `meta` packs M:16, age:8, the low seven bits of the
// light-hierarchy generation, and the two-sided receiver bit; M==0 is the invalid sentinel. Its std430
// array stride is reflected into RestirReservoirData.BYTE_SIZE, so Java never hand-maintains this size.
public struct PackedRestirReservoir {
    public float3 sampleCameraRelative;
    public float area;
    public float3 surfaceCameraRelative;
    public float W;
    public uint sampleNormal;
    public uint packedLe;
    public uint surfaceNormal;
    public uint meta;
};

// Per-frame world data, read through a 64-bit device address. WorldPushData.java is generated from this
// reflected type, including its serializer, byte size, vector/matrix types, and fixed-array capacities.

public struct WorldPush {
    public float4x4 invViewProj;
    public float3   camOffset;
    public uint     frameIndex;
    public float4x4 prevViewProj;
    public float3   camDelta;
    public uint     spp;
    public float2   jitter;
    public uint     flags;            // see WORLD_FLAG_* below (bit1 was PBR-toggle, now unconditional)
    public uint     maxBounces;
    public float4   sunDir;           // xyz true sun direction, w dayFactor 0..1
    public float4   lightDir;         // xyz active NEE light dir, w square half-angle (rad)
    public float4   lightRadiance;    // xyz HDR light radiance, w star brightness
    public float4   moonDir;          // xyz moon direction, w moonPhase (0 full .. 4 new)
    public float4   celestial;        // xyz celestial rotation axis, w star angle (rad)
    public float4   sunUv;            // vanilla sun sprite atlas rect (u0,v0,u1,v1)
    public float4   moonUv;           // vanilla current-moon-phase sprite atlas rect
    public float4   waterParams;      // xyz camera-biome water tint, w wave time (s)
    public float4   waterAnchor;      // xy world-stable wave-domain anchor, z previous wave time
    public float4x4 curViewProj;      // forward camera-relative view-projection
    public uint     breakCount;
    public BreakEntry breaking[8];    // Java capacity and breakCount serialization are generated from this
    public float4   lightRebase;      // xyz hierarchy-to-current rebase, w inverse total light power
    public float4   lightGridOrigin;     // xyz minimum cell origin in rebased blocks, w cell size
    public int4     lightGridDims;       // xyz dense grid dimensions, w reserved
    public uint     lightCount;       // packed Light records in pc.lightBufAddr
    public uint     risCandidates;    // RIS candidate count M per diffuse vertex (0 = emitter NEE off)
    public float4   lightScales;      // x block emitter scale, y dynamic/entity emission scale
    // Weather state, already resolved on the CPU from the client level (see RtComposite.weatherState).
    // The two strengths drive the look (overcast sky, hidden discs/stars); the two multipliers are the
    // single source of truth for how much the weather dims things, so Java's NEE radiance and the sky
    // shader can never disagree about how dark a storm is.
    public float4   weather;          // x rain 0..1, y thunder 0..1, z sky darkening, w light attenuation
    // Flat vanilla-style cloud deck (clouds.slang). Coverage already folds in the weather on the Java
    // side (RtComposite.cloudState), so the shader reads one resolved state rather than re-deriving the
    // storm ramp the sky darkening also depends on. The two slider lanes (shadow strength, opacity) are
    // pushed raw so a mid-frame change takes effect on the next frame like every other RT option.
    public float4   clouds;           // x coverage 0..1, y opacity 0..1, z shadow strength 0..1, w deck height (camera-relative blocks)
    public float4   cloudAnchor;      // xy wind-scrolled sample anchor (blocks, wrapped), z slab thickness, w view limit
    // Classic-style authored shape + colour. cloudColor.xyz is vanilla's own EnvironmentAttributes
    // CLOUD_COLOR in linear space — the value the game already resolves per dimension AND per weather,
    // so the deck greys in a storm with no hand-tuned ramp. cloudColor.w is the weather overcast fill
    // 0..1: rain pushes it toward 1, and the shader uses it to close the sky beyond the authored/slid
    // coverage (see cloudCoverage). cloudCellsAddr points at the bit-packed clouds.png occupancy map
    // riding this same BDA ring slot (0 = unavailable, fall back to the noise deck) — same transport as
    // the DH ready mask, no extra binding.
    public float4   cloudColor;
    public uint64_t cloudCellsAddr;
    // Shader-only Parallax Occlusion Mapping (LabPBR _n alpha height field). Geometry and BLASes are
    // never rebuilt; a zero relief depth disables the effect entirely.
    public float4   parallaxParams;   // x relief depth, y max texel crossings, z unused, w max distance
    public uint     dimension;        // DIMENSION_OVERWORLD / DIMENSION_NETHER / DIMENSION_END
    public uint     featureFlags;     // FEATURE_* toggles resolved from CausticaConfig
    // Analytic held-item light: xyz position in the same rebased space as camOffset, w packed
    // intensity (radiance*area product; 0 = no luminous held item this frame). NEE-sampled at
    // every diffuse receiver in world.rgen and scaled by lightScales.y — held items never enter
    // the RIS emitter buffer, so this term is what makes a torch in hand actually light a cave.
    public float4   handLight;
    // Linear RGB tint of the held light, resolved per item on the CPU (RtComposite.heldLight):
    // torch warm orange, soul torch cyan, lava deep orange-red... Only read when handLight.w > 0.
    public float4   handLightColor;
    // Water controls: x is extra NEUTRAL (per-channel-equal) extinction per block added on top of
    // the biome-tint absorption, 0 = the tuned default clarity. Higher values make water
    // progressively less see-through (the floor/rock behind fades) — the mitigation for the halo
    // DLSS-RR draws around translucent water in near-dark scenes, which is driven by the transmitted
    // lobe's dynamic range. y/z/w are the live Animated Water tuning multiplied into the wave
    // spectrum (see water.slang): y = wave height scale, z = animation speed scale, w = spectrum
    // wave count. 1.0/1.0/7 in those lanes reproduces the authored look exactly.
    public float4   waterOpacity;
    // Material appearance controls: x is extra polish for authored metallic surfaces, 0..1.
    // It only changes the GGX roughness of metals; non-metals keep their authored response.
    public float4   materialParams;
    // Experimental SHaRC (Spatially Hashed Radiance Cache). A single flat spatial-hash cache buffer
    // is allocated by the host and published as a raw device address; every shaded vertex warms it
    // (and back-propagates its light to earlier vertices' entries), and diffuse vertices from
    // startBounce on query it to replace their remaining path — see sharc.slang for the contract.
    // sharcParams: x = cell size (blocks), y = cache strength 0..1 (scale on the cached radiance),
    //              z = inverse temporal accumulation window (blend cap), w = reserved.
    // sharcParams2: x = first bounce that may query the cache, y = update coverage 0..1,
    //               z = frame lifetime, w = normal agreement threshold (reject gate, not a scale).
    // sharcParams3: x = minimum sample count before an entry may be queried, y/z/w reserved.
    // sharcGridOrigin: xyz = terrain origin block in world coordinates, w = cache entry count.
    public uint64_t sharcCacheAddr;
    public float4   sharcParams;
    public float4   sharcParams2;
    public float4   sharcParams3;
    public int4     sharcGridOrigin;
    // ReSTIR live anti-flicker tuning (lights.restir-temporal-history / -spatial-neighbours /
    // -max-age), pushed per frame and resolved by lighting.slang against the compiled RESTIR_*
    // hard caps. x = temporal effective-sample cap (more history = less flicker, more ghosting),
    // y = spatial neighbour count (0 = temporal only), z = max reservoir age in frames, w reserved.
    // Kept in the BDA struct rather than the 128-byte push-constant block, which is already full.
    public uint4    restirTuning;
    // Volumetric fog (fog.slang). x = density fraction 0..1 scaled by FOG_SIGMA_MAX — 0 disables
    // every fog term, so the config toggle and a zero slider both write this one lane; y =
    // Henyey-Greenstein anisotropy (forward bias of the scatter, which is what opens up sun
    // shafts); z = integration distance in blocks (a segment integrates no further — past it the
    // medium stops dimming, which is also the sky/miss clamp); w = height falloff scale in blocks
    // (0 = uniform; > 0 keeps full density at and below the base Y and decays above it, so mist
    // pools into valleys and flight clears it).
    public float4   fogParams;
    // The fog scatter's colour, resolved by the game itself: xyz = linear FOG_COLOR camera
    // attribute (the value vanilla's fog fades toward — biome blend, rain, dimension, all
    // composed upstream), w = the player's blend strength 0..1. fog.slang's fogTintNow lerps
    // it against the module's neutral tint, so a zero w costs no branch and the celestial and
    // emitter scatter terms can never tint differently. Trailing lane after fogParams — the
    // generated WorldPushData appends it; RtFogShaderRegressionTest pins the pairing.
    public float4   fogTint;
};

// worldPush.dimension: the sky/lighting model this frame renders with. The Overworld runs the full
// Rayleigh/Mie/ozone atmosphere with the sun/moon cycle; the other two dimensions have no celestial
// cycle at all, so they draw their own skybox.
public static const uint DIMENSION_OVERWORLD = 0u;
public static const uint DIMENSION_NETHER = 1u;
public static const uint DIMENSION_END = 2u;

// worldPush.featureFlags: player-facing toggles that gate whole effects rather than tune them. They
// live in their own word (not WorldPush.flags, which describes the camera's physical state) so a new
// option can never be confused with a per-frame state bit.
public static const uint FEATURE_SSS = 1u;               // LabPBR subsurface scattering
public static const uint FEATURE_WEATHER_LIGHTING = 2u;  // rain/storm sky darkening + light attenuation
public static const uint FEATURE_DENOISER = 4u;          // temporal/spatial denoising filter is active
public static const uint FEATURE_CLOUDS = 8u;            // cloud deck in the sky + its sun shadow
public static const uint FEATURE_CLOUDS_VOLUMETRIC = 16u; // clouds are ray-marched, not a flat deck
public static const uint FEATURE_RESTIR = 32u;            // temporal + spatial emitter-reservoir reuse
public static const uint FEATURE_NRD = 64u;              // NRD denoiser signal capture (per-lobe radiance + hit distance)
public static const uint FEATURE_VIEWZ = 128u;           // a renderer-side denoiser needs gViewZ (reprojection validation + sky cutoff)
public static const uint FEATURE_SHARC = 256u;           // experimental spatial-hash radiance cache

// WorldPush.flags: physical state of the camera/frame, not user options (those are featureFlags).
public static const uint WORLD_FLAG_SUBMERGED = 1u; // bit0: the eye is inside a water volume
public static const uint WORLD_FLAG_WAVES = 16u;   // bit4: animated geometric water waves
public static const uint WORLD_FLAG_POM_SMOOTHING = 32u; // bit5: bilinear LabPBR normal/surface sampling
// POM walks the LabPBR height field as a grid of per-texel box columns (world.rchit parallaxTrace), so
// the walk is bounded by texel crossings rather than by a fixed layer count: a head-on view resolves in
// one step and only grazing views walk far. parallaxParams.y carries the budget RtComposite derives
// from the relief depth; these bounds keep a bad push value from either killing the effect or letting a
// single hit sample the height page an unbounded number of times.
public static const int PARALLAX_MIN_CROSSINGS = 8;
public static const int PARALLAX_MAX_CROSSINGS = 128;
// How far a column side wall leans back toward the face it stands on. Raygen reuses the shading normal
// both to offset the next ray origin and as the cosine hemisphere's axis, so a wall reported exactly
// parallel to the face (the geometric truth) would launch half of its bounces into the solid block and
// print black speckle along every relief edge. Leaning the wall ~27 degrees off the face keeps the ray
// spawn safe while the wall still shades as its own surface.
public static const float PARALLAX_WALL_TILT = 0.5;

// Flag accessors live here, next to the bit definitions, and take the push explicitly: world.rmiss
// loads its own WorldPush and must NOT import world_core (that module declares the raygen bindings).
// world_core re-exports the same predicates against its module-level `worldPush` for every other pass.
public bool worldFeature(WorldPush push, uint feature) { return (push.featureFlags & feature) != 0u; }
public bool worldFlag(WorldPush push, uint flag) { return (push.flags & flag) != 0u; }

// 32-byte hot area-light record. Radiance uses packed R11G11B10 and the grid-relative owner section
// uses three unsigned 10-bit coordinates; the geometric normal is reconstructed from the half axes.
//
// 32 divides the 64-byte cache line, so a record never straddles one. At the previous 48 bytes about
// half of them did, costing two transactions apiece — and RIS fetches these at random indices, M per
// shading vertex, so that is the layout's whole purpose. float3 forces 16-byte struct alignment and 32
// is a multiple of it, so this lands with no tail padding.
//
// The rectangle area is derived rather than stored: the rect spans 2U x 2V, so area = 4*|U x V|, which
// is exactly what the collector used to write (halfU = 0.5*(aHi-aLo)*e01 and rectArea =
// |e01 x e03|*(aHi-aLo)*(bHi-bLo)). lightGeometricNormal already needs that cross product, so the
// freed lane costs one length() the compiler shares with the normalize.
public struct Light {
    public float3 pos;      // rebased world centre, f32 (the RIS target divides by distance to it)
    public uint   le;       // bitcast R11G11B10 radiance
    public uint   halfUxy;  // half2(U.x, U.y)
    public uint   halfUzVx; // half2(U.z, V.x)
    public uint   halfVyz;  // half2(V.y, V.z)
    public uint   section;  // 10:10:10 grid-relative section coord + bit 30 normal-flip
};

// Eight-byte Vose alias-table column. PDFs are reconstructed from the selected Light.
public struct LightAlias {
    public uint  aliasIndex;
    public float accept;
};

// Dense light grid cell entry. Empty coordinates have spanCount=0; populated section cells point into
// an immutable list of weighted section light ranges built and published alongside this grid.
public struct LightGridCell {
    public uint spanOffset;
    public uint spanCount;
    public float invWeightSum;
};

// One 16-byte Vose alias column for a cell's section distribution. The two light counts are uint16s.
public struct LightGridSpan {
    public uint firstLight;
    public uint aliasFirstLight;
    public uint packedLightCounts; // low16 self, high16 alias
    public float accept;
};

// TerrainPrim.flags bit 0: this emissive quad is in the light buffer (RtLightCollector membership).
public static const uint TERRAIN_PRIM_IN_LIGHT_BUFFER = 1u;
// TerrainPrim.flags bits 1..2: portal surfaces tagged by RtTerrainMesher. The mesher routes their
// quads into the SOLID bucket (never the translucent/glass path), and world.rchit shades them with
// the dedicated portal branches (Nether swirl / End night sky) instead of the block texture.
public static const uint TERRAIN_PRIM_PORTAL_NETHER = 2u;
public static const uint TERRAIN_PRIM_PORTAL_END = 4u;
// Prim.flags (entity prims): a vanilla rain/snow sheet captured by RtWeatherCapture. Shares the flags
// lane the portal tags use on terrain — particles never carry portal tags, so the bit is unambiguous.
// Raygen shades these sheets UNLIT (see PAYLOAD_WEATHER): the path tracer's direct/indirect light was
// visibly breaking the rain (blotchy over-lit streaks), and vanilla's own weather pass is essentially
// unlit too. The sheets stay real BLAS geometry, so terrain still occludes them and the heightmap
// clip still works — only the lighting response is removed.
public static const uint PRIM_WEATHER = 16u;
// Prim.flags (entity prims): an enchantment/foil glint overlay captured by RtEntityCollector. Tagging
// lets world.rchit shade it with a dedicated branch — an animated, self-lit shimmer (the same special
// capture the portal surfaces get) instead of a scene-lit paint — while world.rahit keeps the vanilla
// alpha cutout (discard texels below 0.1), so the armour/body shows where the texture has no streak.
// Entity-only bit: terrain never sets it, and the weather bit (16) is unambiguous there.
public static const uint PRIM_GLINT = 8u;
// Vanilla's glint shader scrolls its sample UV diagonally over time via a per-frame TextureMat; the
// hit shaders apply the same drift so the whole streak pattern moves (a static any-hit mask over a
// scrolling colour would freeze the streak shapes). Texture widths per second, matched to the old
// item-shimmer pace (the 26.x armor_entity_glint transform scrolls at a different, slower rate).
public static const float GLINT_SCROLL_U = 0.25;
public static const float GLINT_SCROLL_V = 0.125;

// Shared glint-UV scroll (see PRIM_GLINT). Both world.rahit (coverage gate) and world.rchit (shading)
// must sample the same scrolled texel, so this single transform keeps the any-hit gate and the
// closest-hit colour in lock-step on the moving pattern.
public float2 glintScrolledUv(MaterialHeader header, float2 uv, float time) {
    float2 localUv = (uv - header.albedoUv.xy) * header.albedoUv.zw;
    float2 scrolled = frac(localUv + float2(time * GLINT_SCROLL_U, time * GLINT_SCROLL_V));
    return header.albedoUv.xy + scrolled / header.albedoUv.zw;
}

// Largest finite half. The payload's half3 lanes carry HDR values (sky radiance most of all), so
// producers clamp to this instead of letting the conversion round up to +inf.
public static const float HALF_MAX = 65504.0;

// Radiance-ray payload (location 0). Member order and types are a cross-stage ABI.
//
// The three-component lanes are half3: they cost 6 bytes instead of 12, and payload storage is reserved
// per trace call site, so every byte here is paid twice per radiance trace (once for TraceRay, once for
// Invoke) and is preserved across the SER reorder. hitT stays f32 — it reaches 10000 blocks and the hit
// position is reconstructed from it, where half's ~4-block spacing at that magnitude would be visible.
public struct Payload {
    // Sky radiance on a miss, so this carries HDR values well above 1; half tops out at 65504.
    public half3  albedo;      // hit: block albedo. miss: sky radiance.
    public half3  normal;      // hit: geometric normal, viewer-oriented.
    public float  hitT;        // >= 0 on hit, < 0 on miss.
    public half3  motionPrev;  // per-vertex world displacement since last frame.
    public half3  f0;          // specular F0.
    public uint  flags;       // bits 0..1 material, bit 2 celestial, bit 3 water-entering, bits 4..6 emission source, bit 7 emitter-in-list, bit 8 dynamic entity, bit 9 weather, bit 10 end-portal unlit.
    public uint   roughMetal;  // packHalf2x16(roughness, metalness)
    public uint   emissionSss; // packHalf2x16(emission, sss)
    public uint   iorTransmission; // packHalf2x16(IOR, transmission factor)
    public uint   rayCone;     // packHalf2x16(width, spread)
};

public static const uint PAYLOAD_SHOW_CELESTIAL = 4u;
// Rain/snow sheet (Prim.flags carried through by world.rchit): raygen shades it unlit and ends the
// path there — no NEE, no shadow ray, no RIS, no diffuse continuation — because the path tracer's
// light response was visibly breaking the weather streaks. See PRIM_WEATHER.
public static const uint PAYLOAD_WEATHER = 512u;
// End portal night-sky surface (set by world.rchit on both the terrain and the entity/block-entity
// portal branches): raygen shades it fully UNLIT — only its own emission is added, then the path
// ends. No NEE, no hand light, no RIS, no SHaRC and no indirect bounce, so no scene light (a
// nearby torch, the sun, GI) can ever illuminate or reflect off the portal's starfield.
public static const uint PAYLOAD_END_PORTAL_UNLIT = 1024u;
// Set by world.rchit on a terrain hit whose prim carries TERRAIN_PRIM_IN_LIGHT_BUFFER: this emitter is
// RIS-sampled, so raygen gates its direct-hit emission on diffuse continuation rays (no double count).
public static const uint PAYLOAD_EMITTER_IN_LIST = 128u;
// Set by world.rchit for dynamic entity/block-entity/item geometry, letting raygen apply the dynamic
// light scale to direct-hit emission while terrain uses the block-emissive scale.
public static const uint PAYLOAD_DYNAMIC_ENTITY = 256u;
// Set by world.rchit on any dielectric hit (water or glass/ice): true when the incoming ray travels
// against the prim's outward face normal (entering the volume), false when it exits. Derived from face
// orientation rather than toggled, so a stray or missing face cannot corrupt the medium for the rest of
// the path — each crossing re-derives which side it is on. Bit 2 (PAYLOAD_SHOW_CELESTIAL) is only
// meaningful on a miss and this bit only on a hit, so they never need to be read together, but they get
// distinct bits anyway to keep the two concerns unambiguous.
public static const uint PAYLOAD_DIELECTRIC_ENTERING = 8u;
public static const uint PAYLOAD_EMISSION_SOURCE_SHIFT = 4u;
public static const uint PAYLOAD_EMISSION_SOURCE_MASK = 7u;
public static const uint EMISSION_SOURCE_NONE = 0u;
public static const uint EMISSION_SOURCE_UNIFORM = 1u;
public static const uint EMISSION_SOURCE_HEURISTIC = 2u;
public static const uint EMISSION_SOURCE_LAB_PBR = 3u;

public static const uint PAYLOAD_MATERIAL_MASK = 3u;
public static const uint MATERIAL_OPAQUE = 0u;
// Both dielectrics are volumes: they refract and push a participating medium. They stay distinct ids
// because water owns behaviour nothing else has — an animated wave surface, the caustic term, biome-tint
// absorption calibrated per block of depth, and a chit path fed by the fluid mesher rather than the
// (inset) translucent terrain layer. MATERIAL_DIELECTRIC is everything else transparent: glass, ice, and
// any solid transparent block a pack defines.
public static const uint MATERIAL_WATER = 1u;
public static const uint MATERIAL_PARTICLE = 2u;
public static const uint MATERIAL_DIELECTRIC = 3u;

// Distant Horizons / Voxy retain coarse geometry, vertex colours and a mini-material category, but no
// original block sprite. The CPU importer stores these surface classes in TerrainPrim.tint.w.
public static const uint DH_SURFACE_SOLID = 0u;
public static const uint DH_SURFACE_WATER = 1u;
public static const uint DH_SURFACE_GLASS = 2u;
public static const uint DH_SURFACE_EMISSIVE = 3u;
public static const uint DH_SURFACE_EMISSIVE_GLASS = 4u;

public static const uint DH_MATERIAL_UNKNOWN = 0u;
public static const uint DH_MATERIAL_LEAVES = 1u;
public static const uint DH_MATERIAL_STONE = 2u;
public static const uint DH_MATERIAL_WOOD = 3u;
public static const uint DH_MATERIAL_METAL = 4u;
public static const uint DH_MATERIAL_DIRT = 5u;
public static const uint DH_MATERIAL_LAVA = 6u;
public static const uint DH_MATERIAL_DEEPSLATE = 7u;
public static const uint DH_MATERIAL_SNOW = 8u;
public static const uint DH_MATERIAL_SAND = 9u;
public static const uint DH_MATERIAL_TERRACOTTA = 10u;
public static const uint DH_MATERIAL_NETHER_STONE = 11u;
public static const uint DH_MATERIAL_WATER = 12u;
public static const uint DH_MATERIAL_GRASS = 13u;
public static const uint DH_MATERIAL_AIR = 14u;
public static const uint DH_MATERIAL_ILLUMINATED = 15u;

// Entity per-triangle record (48 B). The final lane mirrors TerrainPrim's integer material metadata.
public struct Prim {
    public float4 normal; // xyz = geometric normal, w = per-primitive emission strength
    public float4 tint;   // rgb = un-premultiplied vertex/model tint, w = bindless albedo texture slot
    public uint materialId;
    public uint flags;
    public uint aux0;     // asfloat(aux0) = primitive coverage multiplier for stochastic alpha/glint
    public uint aux1;
};

// Terrain per-triangle record (48 B). The final 16-byte lane contains integer geometry-local metadata;
// shared surface parameters live once in MaterialHeader instead of being copied into every triangle.
public struct TerrainPrim {
    public float4 normal;
    public float4 tint;
    public uint materialId;
    public uint flags;
    public uint aux0;
    public uint aux1;
};

// Compiled material record (80 B). Source-format data has already been decoded into canonical pages;
// texturePage indexes the dedicated canonical page arrays. materialUv maps sprite-local UV into the packed
// page, while albedoUv maps the vanilla atlas rectangle back to sprite-local UV.
public struct MaterialHeader {
    public uint model;
    public uint features;        // low feature bits; bits 24..31 = maximum valid material-page LOD
    public uint texturePage;
    public uint extensionOffset; // zero when no optional extension record is present
    public float4 materialUv; // packed-page uvMin.xy + uvExtent.xy
    public float4 albedoUv;   // vanilla atlas uvMin.xy + inverse uvExtent.xy
    public float4 params;  // roughness, metalness, IOR, transmission
    public float4 average; // whole-sprite linear-input average RGBA for translucent shadow filtering
};

public static const uint MATERIAL_FEATURE_SPEC = 1u;
public static const uint MATERIAL_FEATURE_NORMAL = 2u;
public static const uint MATERIAL_FEATURE_HEURISTIC_EMISSION = 4u;
public static const uint MATERIAL_FEATURE_STOCHASTIC_ALPHA = 16u;
// Final HDR emission strength baseline (EMISSIVE_STRENGTH * any JSON override multiplier), baked in
// Java at material-compile time (RtMaterialRegistry) and packed here as a 16-bit fraction of the max.
// Runtime global scales are separate WorldPush values so players can tune block and dynamic lights live.
public static const uint MATERIAL_EMISSION_STRENGTH_SHIFT = 8u;
public static const uint MATERIAL_EMISSION_STRENGTH_MASK = 65535u;
public static const float MATERIAL_MAX_EMISSION_STRENGTH = 32.0;
// Runtime-generated dynamic item lights may use the neutral fallback material (packed strength 0).
// Give an explicitly emissive primitive the same baseline radiance as Java's RtMaterialRegistry.
public static const float MATERIAL_DEFAULT_DYNAMIC_EMISSION_STRENGTH = 5.0;
public static const uint MATERIAL_MAX_LOD_SHIFT = 24u;
public static const uint MATERIAL_MAX_LOD_MASK = 255u;

// Per-section geometry record: packed buffer addresses + per-bucket triangle offsets
// (gl_PrimitiveID restarts at 0 per geometry, so hit shaders re-add triBase[geometryIndex]).
public struct Section {
    public uint64_t primAddr;
    public uint64_t uvAddr;
    public uint triBase[4]; // fixed bucket order: solid/cutout/translucent/water
};

// Entity geometry record: packed buffer addresses, motion, and per-geometry triangle bases. Entity BLAS
// geometry order is opaque/any-hit; PrimitiveIndex restarts at zero for each geometry.
public struct EntityGeom {
    public uint64_t primAddr;
    public uint64_t idxAddr;
    public uint64_t uvAddr;
    public uint64_t dispAddr;
    public float4 rigidDisp;
    public uint triBase[2];
    public uint2 reserved;
};

// TLAS instance-custom-index encoding + terrain geometry buckets (shared by rahit/rchit).
public static const uint ENTITY_BIT = 0x800000u;
public static const uint PARTICLE_BIT = 0x400000u; // particles share the entity cutout path
// ENTITY_BIT | PARTICLE_BIT never occurs for real entities/particles, so this combination marks
// coarse Distant Horizons / Voxy LOD instances while still fitting Vulkan's 24-bit custom index.
public static const uint DH_INSTANCE_KIND = ENTITY_BIT | PARTICLE_BIT;
public static const uint IDX_MASK = 0x3FFFFFu;     // low 22 bits = geom-table index
public static const uint BUCKET_SOLID = 0u;
public static const uint BUCKET_CUTOUT = 1u;
public static const uint BUCKET_TRANSLUCENT = 2u;
public static const uint BUCKET_WATER = 3u;

// packHalf2x16 equivalent (Slang core module has no GLSL pack builtin; f32tof16 returns the half
// bit-pattern in the low 16 bits).
public uint packHalf2(float2 v) {
    return (f32tof16(v.y) << 16) | (f32tof16(v.x) & 0xFFFFu);
}

public float2 unpackHalf2(uint p) {
    return float2(f16tof32(p & 0xFFFFu), f16tof32(p >> 16));
}

// Turn DH's baked transparent vertex colour into a stable optical filter. Saturation keeps stained
// glass coloured even when the source alpha is low, while clear glass remains close to neutral.
public float3 glassTransmissionFilter(float3 linearColor, float alpha) {
    float3 color = max(linearColor, float3(0.0, 0.0, 0.0));
    float hi = max(color.r, max(color.g, color.b));
    float lo = min(color.r, min(color.g, color.b));
    float saturation = hi > 1.0e-4 ? (hi - lo) / hi : 0.0;
    float dyeStrength = clamp(max(alpha, saturation * 0.92), 0.0, 0.96);
    return max(lerp(float3(1.0, 1.0, 1.0), color, dyeStrength),
            float3(0.012, 0.012, 0.012));
}
