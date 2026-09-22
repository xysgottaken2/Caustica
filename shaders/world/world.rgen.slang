// Indirect pass (pass B of the wavefront split — see docs/WAVEFRONT_PLAN.md).
//
// Resumes the continuations the primary pass queued and runs the bounce loop: NEE toward the sun, RIS
// emitter lighting, thin-surface SSS, BSDF continuation and Russian roulette. It captures no guides and
// owns no motion vectors — guides and primary are deliberately absent from the include list
// below, so guide state cannot leak into this pass by accident.
//
// Lighting is iterative here rather than recursive from closest-hit, so maxRayRecursionDepth stays 1.
// At each surface vertex:
//   * NEE toward the sun — a single analytic directional light, gated by one hit-object visibility
//     query (TerminateOnFirstHit | SkipClosestHit). Lambertian BRDF = albedo/PI.
//   * a cosine-weighted Lambertian continuation ray (throughput *= albedo; the PI and cos cancel).
// Sky / ambient light needs no separate AO pass: continuation rays that escape land in world.rmiss,
// which returns sky radiance — so soft sky fill and contact AO both fall out of the path integral.
//
// Each frame emits a single SPP-averaged (still noisy) estimate; temporal convergence is the denoiser's
// job. With RR disabled the output is the raw noisy path trace — a reference view, not a converged
// image. Output is HDR (R16G16B16A16_SFLOAT); the HDR -> LDR tonemap happens at the display.comp seam.
//
// The build emits an ordinary TraceRay fallback and an optional EXT SER variant from this source.
import world_common;
import world_core;
import math;
import medium;
import segment;
import water;
import trace;
#ifdef CAUSTICA_ENABLE_EXT_SER
import trace_ser;
#endif
import lighting;
import clouds;
import fog;
import sharc;

// First-bounce lobe decomposition for NRD (REBLUR_DIFFUSE_SPECULAR): alongside the combined
// radiance L, accumulates per-lobe radiance (outLdiff/outLspec) and the first-bounce hit distance
// per lobe (0 = lobe not traced / no data, REBLUR's convention). Every term added to L lands in
// exactly one of the two accumulators, so L == outLdiff + outLspec remains bit-exact and the
// ordinary (non-NRD) output is untouched. The lobe a path belongs to is decided once: dielectric
// segments are specular from the start; an opaque shading point classifies by its chosen
// continuation lobe; until then (camera prefix, sky) the diffuse bucket is the catch-all.
public float3 tracePath(PathSegment seg, uint2 pix, uint sampleIndex,
                 bool restirEligiblePath, bool restirOwner, float restirSampleScale,
                 uint2 dimensions,
                 out float3 outLdiff, out float3 outLspec,
                 out float outHitDiff, out float outHitSpec) {
    float3 L = float3(0.0, 0.0, 0.0);
    float3 Ldiff = float3(0.0, 0.0, 0.0);
    float3 Lspec = float3(0.0, 0.0, 0.0);
    float hitDiff = 0.0;
    float hitSpec = 0.0;
    // channel: 0 = not yet classified (diffuse catch-all), 1 = diffuse path, 2 = specular path.
    int channel = seg.bounce > 0 ? 2 : 0;
    // Segments split off a dielectric start mid-flight: their first trace inside this call IS the
    // specular continuation hop, so its hit distance feeds the specular channel.
    int pendingHitChannel = seg.bounce > 0 ? 2 : 0;
    float3 ro = seg.ro;
    float3 rd = seg.rd;
    float3 throughput = seg.throughput;

    // ---- Cloud over the prefix Pass A already consumed.
    //
    // Pass A hands back a segment that starts AT THE CAMERA for an opaque hit, but for a dielectric
    // (water, glass, ice) it consumes the camera->surface hop itself and queues continuations that
    // start at the surface. Pass A has no radiance accumulator, so it cannot apply the cloud crossed on
    // the way there — and Pass B's loop only ever sees the post-surface segments. The result was the
    // reported bug: looking down at an ocean from above the clouds, the water was drawn at full
    // brightness with no cloud in front of it, while the terrain beside it was correctly dimmed.
    //
    // Recover it here by integrating the cloud over camera -> seg.ro for any segment that does not
    // start at the camera. Applying it to BOTH split branches is exact rather than a double count: the
    // two branches carry throughputs F and (1 - F), so their prefix contributions sum to exactly one
    // crossing (F*S + (1-F)*S = S), and each branch's own throughput picks up the same transmittance
    // the real prefix would have applied.
    // Pass A is fixed at one sample per pixel. Decorrelate each Pass B resample here so configured
    // SPP produces independent lighting/BSDF paths from the shared terminal continuation. The seed
    // is initialised BEFORE the prefix recovery because the fog occlusion sample consumes one rndf
    // draw from it — the march jitter and the path jitter must share one stream.
    uint seed = seg.seed ^ sampleIndex * 2246822519u;
    seed = pcg(seed);
    if (seg.bounce > 0) {
        float3 toStart = ro - worldPush.camOffset;
        float prefixDist = length(toStart);
        if (prefixDist > 1.0e-4) {
            // Fog first, then cloud: ground-level fog in front of the deck dims the deck's own
            // in-scatter, the same aerial perspective the hit-branch blocks apply in the same order.
            //
            // The medium gate is the CAMERA's own medium, not the continuation's stack: seg.medium
            // already contains the dielectric Pass A consumed at the far end of this prefix (a water
            // or glass entry queues its continuation with the new medium pushed), but the stretch
            // being recovered here was travelled in the medium BEFORE that interface. Gating on the
            // pushed stack skipped camera->surface fog for every dielectric the player looks "into"
            // — the reported water/glass-unfogged bug. fogAppliesHere stays right where the segment
            // really does lie inside the medium it samples: the pass-B hit/miss blocks.
            if (fogEnabled(worldPush) && !cameraSubmerged()) {
                FogVolume preFog = fogSegment(worldPush, worldPush.camOffset, toStart / prefixDist,
                                              prefixDist, seed, seg.showCelestial);
                L += throughput * preFog.scatter;
                Lspec += throughput * preFog.scatter;
                throughput *= preFog.transmittance;
            }
            // The cloud crossing belongs to the camera->surface prefix, so it is recovered here in
            // full rather than applied per split branch.
            CloudVolume pre = cloudSegment(worldPush, float3(0.0, 0.0, 0.0), toStart / prefixDist,
                                           prefixDist, cloudSkyAmbient(worldPush), seg.showCelestial);
            L += throughput * pre.scatter;
            Lspec += throughput * pre.scatter;
            throughput *= pre.transmittance;
        }
    }
    float rayConeWidth = seg.rayConeWidth;
    float rayConeSpread = max(seg.rayConeSpread, RAY_CONE_MIN_SPREAD);
    int maxBounces = int(worldPush.maxBounces);
    int rrStart = maxBounces <= 3 ? 1 : 2;
    // RIS emitter NEE: direct lighting from block emitters is active when lights are published and the
    // candidate count is non-zero. Off => emitters are gathered only on path hits (legacy behaviour).
    bool risOn = worldPush.risCandidates > 0u && worldPush.lightCount > 0u && pc.lightBufAddr != 0;
    // The explicit pc.restirMode uniform chooses the final-color estimator. On the first stable receiver
    // of leaf 0, sample 0 shades the ReSTIR reservoir and is scaled by SPP; sibling SPP paths skip ONLY
    // that receiver. Consequently frame averaging contains one full ReSTIR estimate rather than one
    // ReSTIR estimate diluted by (SPP-1) legacy RIS estimates. Deeper/random paths remain ordinary RIS.
    bool restirPathOn = restirEligiblePath && restirEnabled() && risOn;
    bool restirReceiverPending = restirPathOn;
    // SHaRC back-propagation state: the last vertices that wrote cache entries, with the path
    // throughput at registration (see sharc.slang). Every new shaded vertex, a sky miss and a
    // cache-hit termination push their outgoing radiance back into these entries, which is what
    // teaches the cache multi-bounce GI instead of only direct light.
    SharcPathState sharcState = sharcInitPath();
    // Update sparsity (NVIDIA's sparse Update pass): only a fraction of paths warms the cache, and
    // a selected path updates at every vertex so each entry's samples stay complete. Evaluated once
    // per path from the freshly mixed seed, so SPP samples and split leaves gate independently.
    bool sharcUpdatePath = sharcEnabled() && sharcReady(worldPush)
            && sharcPathSelected(worldPush, seed);
    // Independent light-proposal RNG. Screen-space coherent selection is intentionally deferred until
    // there is a real presampled RIS tile buffer; direct sharing made tile-shaped lighting noise visible.
    // Measured: making a 16x16 tile share this seed recovers at most 1.3ms, so a future pool should share
    // the POOL rather than the seed — the win is in removing dependent loads, not in coherence.
    uint proposalSeed = pix.x * 1973u + pix.y * 9277u + 26699u
            ^ worldPush.frameIndex * 2654435761u ^ sampleIndex * 2246822519u;
    proposalSeed = pcg(proposalSeed);
    // The medium the segment starts in. For the camera segment that is water when the eye is submerged
    // (so the first ray already carries the right relative index and absorption); for a segment split off
    // a dielectric it is whatever the parent was travelling through.
    MediumStack medium = seg.medium;
    bool waterWaves = waterWavesEnabled(); // animated geometric water waves
    // Sky-disc gate (see world.rmiss): the sun/moon disc is shown to the primary ray and to rays spawned
    // by a specular/dielectric bounce (mirror reflections + refractions of the sun/moon), but hidden from
    // diffuse-sampled continuation rays — those are already covered by the sun NEE, so the tiny disc
    // would be a double-counted firefly. Reset true on every specular/water bounce, false on a diffuse one.
    bool showCelestial = seg.showCelestial;
    // Pass B treats every hit it sees as one indirect-depth step, regardless of material or selected
    // continuation lobe. Deliberately start at zero instead of seg.bounce: the first hit of either
    // queued split branch is still the primary-terminal shading point. Hits 0 and 1 retain SSS; RIS
    // remains active with its full configured candidate count at every hit. Pass A's primary/interface
    // prefix is outside this SSS quality budget.
    int indirectDepth = 0;
    for (int bounce = seg.bounce; bounce <= maxBounces; bounce++) {
        // Radiance SBT records run any-hit only for true alpha cutout. Translucent/water go straight to
        // closest-hit for dielectric handling. Geometry is double-sided; the chit flips the normal.
        // Primary (bounce 0) is the camera ray (CULL_PRIMARY): sees particles but not the first-person
        // player. Bounce rays are secondary (CULL_SECONDARY): exclude particles, include the player.
#ifdef CAUSTICA_ENABLE_EXT_SER
        // SER lifetime phase: keep paths that are not roulette-eligible, paths that may terminate via
        // roulette, and paths guaranteed to end at the bounce cap in separate coherence groups.
        uint pathPhaseHint = bounce >= maxBounces ? 2u : (bounce >= rrStart ? 1u : 0u);
        traceRadianceReordered(bounce == 0 ? CULL_PRIMARY : CULL_SECONDARY, ro, 0.0, rd, 10000.0,
                               showCelestial, rayConeWidth, rayConeSpread, pathPhaseHint);
#else
        traceRadiance(bounce == 0 ? CULL_PRIMARY : CULL_SECONDARY, ro, 0.0, rd, 10000.0,
                      showCelestial, rayConeWidth, rayConeSpread);
#endif

        // First-bounce hit distance capture: the trace we just ran is the continuation hop whose
        // distance REBLUR wants for the lobe that spawned it. A miss leaves the distance at 0 —
        // REBLUR's "no data" value, which it treats as a skipped lobe rather than zero distance.
        if (pendingHitChannel != 0 && payload.hitT >= 0.0) {
            if (pendingHitChannel == 1) {
                hitDiff = payload.hitT;
            } else {
                hitSpec = payload.hitT;
            }
            pendingHitChannel = 0;
        }

        if (payload.hitT < 0.0) {
            // world.rmiss writes sky radiance (gradient + sun/moon disc + stars) into payload.albedo. The
            // packed show-celestial flag decides whether this path segment may see the bright disc.
            float3 sky = payload.albedo;
            // Fog between here and the sky: a ray that misses runs through the whole air column,
            // so the sky it carries — sun and moon discs included, which is half of what makes
            // god rays read — arrives dimmed by the column's transmittance and lifted by its
            // in-scatter. The integration-distance clamp lives inside fogSegment; the SHaRC
            // propagation below sees the SAME dimmed sky the eye does, never the raw one.
            if (fogEnabled(worldPush) && fogAppliesHere(medium)) {
                FogVolume skyFog = fogSegment(worldPush, ro, rd, 1.0e9, seed, showCelestial);
                sky = sky * skyFog.transmittance + skyFog.scatter;
            }
            L += throughput * sky;
            if (channel == 2) {
                Lspec += throughput * sky;
            } else {
                Ldiff += throughput * sky;
            }
            // SHaRC: the sky is a real light source for every earlier path vertex. Propagating it
            // back teaches their voxels the sky ambient their continuation rays would find — this is
            // NVIDIA's SharcUpdateMiss. Only diffuse continuations propagate: a showCelestial
            // segment's sky contains the sun/moon disc (it is rendered into specular reflections),
            // and diffuse receivers already get that light from NEE — feeding the disc into the
            // cache would inject exactly the fireflies the disc-gating exists to prevent.
            if (sharcUpdatePath && !showCelestial) {
                sharcPropagateToPath(worldPush, sharcState, ro, sky, throughput);
            }
            break;
        }

        // Beer–Lambert: attenuate along the segment just travelled by the medium it lay inside. Applies
        // to every hit reached while inside a volume dielectric (its own exit face, or whatever content
        // lies within it), shifting the transmitted radiance with distance. Air's extinction is zero, so
        // the ordinary case costs one comparison.
        if (any(medium.current.extinction > 0.0)) {
            throughput *= exp(-medium.current.extinction * payload.hitT);
        }

        // ---- Volumetric fog crossed on the way to this hit (see fog.slang).
        //
        // Same {scatter, transmittance} shape as the cloud block right below, deliberately applied
        // BEFORE it so ground fog dims the cloud's in-scatter like any other background. Fog lives
        // in the path integral, not at a display seam, which is what makes it show up in
        // reflections, refractions and the cave's dark in exactly the places the surface shadows
        // do — there is no second fog world to keep in sync.
        //
        // fogSegThroughput: the path's throughput at this segment's START. A segment's own
        // in-scatter must be weighted by everything the path travelled through BEFORE it, not by
        // the segment's own darkening; the emitter term below accumulates at shading time (the
        // light sample only exists then) and must not silently pick up this segment's fog T.
        float3 fogSegThroughput = throughput;
        if (fogEnabled(worldPush) && fogAppliesHere(medium)) {
            FogVolume segFog = fogSegment(worldPush, ro, rd, payload.hitT, seed, showCelestial);
            L += throughput * segFog.scatter;
            if (channel == 2) {
                Lspec += throughput * segFog.scatter;
            } else {
                Ldiff += throughput * segFog.scatter;
            }
            throughput *= segFog.transmittance;
        }

        // Cloud crossed on the way to this hit. The deck is a real volume in the world, so a ray that
        // ends on GEOMETRY can still pass through cloud before it gets there — looking down at the
        // ground from above the deck is exactly that case, and it is the one that was broken: clouds
        // were composited only in the miss shader, which a terrain hit never reaches, so the world
        // showed straight through them. Bounded by payload.hitT, so cloud in front of the surface is
        // drawn and cloud behind it is rejected.
        //
        // Expressed as the same in-scatter/transmittance pair the medium extinction above uses, because
        // it is the same kind of thing: a participating medium along this segment. Being in the path
        // integral rather than at a display seam is what makes it correct in reflections and
        // refractions too.
        {
            // showCelestial marks the rays whose clouds are seen directly (camera + specular bounces),
            // so it selects the full march; diffuse continuations take the cheap one — see cloudSegment.
            CloudVolume segCloud = cloudSegment(worldPush, ro - worldPush.camOffset, rd,
                                                payload.hitT, cloudSkyAmbient(worldPush),
                                                showCelestial);
            L += throughput * segCloud.scatter;
            if (channel == 2) {
                Lspec += throughput * segCloud.scatter;
            } else {
                Ldiff += throughput * segCloud.scatter;
            }
            throughput *= segCloud.transmittance;
        }

        float3 n = payload.normal; // oriented toward the incoming ray by the closest-hit
        float3 hitPos = ro + rd * payload.hitT;
        rayConeWidth = max(rayConeWidth + rayConeSpread * max(payload.hitT, 0.0), RAY_CONE_MIN_WIDTH);
        uint material = payloadMaterial();
        int hitDepth = indirectDepth++;

        // ---- Dielectric interface: water, stained glass, ice. ONE handler and one behaviour — every
        // dielectric is a volume. Fresnel from the relative index across the face, then reflect or
        // refract; no diffuse response and no sun NEE, because the surface is specular. Transmission
        // pushes or pops a participating medium, and the segment travelled inside it is attenuated by
        // that medium's extinction rather than by a tint multiply at the interface.
        //
        // Water is the only material that adds anything: the animated geometric wave (height-field
        // intersection + matching normal), and a medium marked for the caustic term. Which side of the
        // face we are on comes from the geometry (payloadDielectricEntering), so a stray or missing
        // face cannot desync the medium for the rest of the path.
        if (material == MATERIAL_WATER || material == MATERIAL_DIELECTRIC) {
            // A dielectric has no local shading term. At the path cap its continuation cannot be traced,
            // so avoid computing an unused Fresnel choice, medium update, and roulette step.
            if (bounce >= maxBounces) {
                break;
            }
            bool isWater = material == MATERIAL_WATER;
            bool entering = payloadDielectricEntering();
            float3 tint = payload.albedo;
            float transmission = clamp(payloadTransmission(), 0.0, 1.0);

            // Lift the (near-flat) rest-plane hit onto the wave height field before any Fresnel use →
            // real crests/troughs, glints, broken reflections, wobbling refraction. The wave domain is
            // world-stable: rebased hit XZ plus the pushed anchor (terrain origin mod 4096) survives
            // the per-move terrain rebase.
            float3 geometricNormal = n; // unrippled; ray origins offset along this
            WaterWaves waterSurf = flatWaterWaves(hitPos, payload.hitT, geometricNormal);
            if (isWater && waterWaves) {
                float waterFootprint = rayConeWidth / max(abs(dot(-rd, geometricNormal)), 0.2);
                waterSurf = evaluateWaterWaves(ro, rd, payload.hitT, geometricNormal,
                        worldPush.waterParams.w, worldPush.waterParams.w, waterFootprint);
                n = waterSurf.normal;
                hitPos = waterSurf.position;
            }

            // The medium this face opens into, and the one it returns to on the way out — which is what
            // the stack remembers.
            Medium entered = makeDielectricMedium(isWater, max(payloadIor(), 1.0), tint, transmission);
            float etaI = medium.current.ior;
            float etaT = entering ? entered.ior : medium.outer.ior;

            float cosI = clamp(dot(-rd, n), 0.0, 1.0);
            float F = fresnelDielectric(cosI, etaI, etaT);
            float3 transmittedDir = refract(rd, n, etaI / etaT);
            // The translucent terrain layer is recessed by TRANSLUCENT_INSET (RtTerrainMesher), so a
            // glass/ice face touching a slab or stair sits a hair behind that neighbour's surface. A full
            // SURF_BIAS would restart the transmitted ray past the neighbour and see through it. Water
            // comes from the fluid mesher, which applies no inset, so it takes the ordinary bias.
            float transmitBias = isWater ? SURF_BIAS : INSET_TRANSMIT_BIAS;

            bool chooseReflection = rndf(seed) < F;
            if (chooseReflection) {
                rd = reflect(rd, n);
                ro = isWater && waterWaves
                        ? waterWaveContinueOrigin(waterSurf, rd, SURF_BIAS)
                        : offsetSurfaceOrigin(hitPos, geometricNormal, rd, SURF_BIAS);
            } else {
                if (dot(transmittedDir, transmittedDir) <= 0.0) break; // TIR with F < 1 cannot happen
                rd = normalize(transmittedDir);
                ro = isWater && waterWaves
                        ? waterWaveContinueOrigin(waterSurf, rd, transmitBias)
                        : offsetSurfaceOrigin(hitPos, geometricNormal, rd, transmitBias);
                // Crossing into or out of the volume. Absorption is the medium's job from here, so the
                // tint is NOT also multiplied into the throughput — that would double-count it.
                if (entering) {
                    mediumPush(medium, entered);
                } else {
                    mediumPop(medium);
                }
            }
            // A dielectric interface IS the specular event for this path: from here on everything
            // belongs to the specular channel, and the next trace's hit distance is the specular hit
            // distance REBLUR wants (measured from the interface).
            if (channel == 0) {
                channel = 2;
                pendingHitChannel = 2;
            }
            showCelestial = true; // specular interface: the continuation ray may see the sun/moon disc
            if (bounce >= rrStart) {
                float q = clamp(max(throughput.r, max(throughput.g, throughput.b)), 0.02, 1.0);
                if (rndf(seed) > q) {
                    break;
                }
                throughput /= q;
            }
            continue;
        }

        // Particles (material == 2): camera-facing receiver billboard. The instance mask keeps particles
        // off secondary rays, so this only fires at bounce 0; they receive direct light and one diffuse
        // path continuation into the scene, but never appear in shadows/reflections/GI or emit radiance.
        if (material == MATERIAL_PARTICLE) {
            // Particles shade diffuse-only (no specular term exists for billboards), so an
            // unclassified path hitting one becomes a diffuse path here.
            if (channel == 0) {
                channel = 1;
            }
            float3 albedo = payload.albedo;

            // Rain/snow sheets: deliberately UNLIT. The full particle light path below (sun NEE with a
            // shadow ray, RIS block lights, a diffuse bounce off the sheet) read as blotchy, blown-out
            // streaks on something the eye expects to be a soft translucent veil — vanilla's own weather
            // pass is effectively unlit too (a lightmap-brightened blend, no per-drop lighting). So the
            // sheet contributes its texel colour scaled only by the day/night curve, and the path ENDS
            // here: rays that passed the stochastic cutout already carried the see-through share, and
            // bouncing off rain would add the scene's light right back into it — the exact thing being
            // removed. The sheets stay real geometry in the BLAS: terrain still occludes them, the
            // heightmap clip still holds, they just stop participating in lighting.
            if ((payload.flags & PAYLOAD_WEATHER) != 0u) {
                // Calibrated against feedback from in-game: the first pass (0.35..1.0) read inverted —
                // far too bright against the near-black night sky and too dark against daylight. The
                // sheet now nearly vanishes at night and sits well above the texture's own mid-grey in
                // the day, so streaks read as a light veil over the bright sky.
                float weatherSheet = lerp(0.12, 1.9, worldPush.sunDir.w);
                L += throughput * albedo * weatherSheet;
                if (channel == 2) {
                    Lspec += throughput * albedo * weatherSheet;
                } else {
                    Ldiff += throughput * albedo * weatherSheet;
                }
                break;
            }

            float3 lightDir = worldPush.lightDir.xyz;
            float lightHalfAngle = worldPush.lightDir.w;
            if (lightHalfAngle > 0.0) {
                lightDir = sampleSquare(lightDir, lightHalfAngle, seed);
            }
            // Billboards are effectively two-sided receivers. Bias from the side facing the light; shadow
            // rays exclude particles anyway, but this keeps the origin sane when the light is behind the
            // camera side. Same zero-radiance skip as the surface NEE below (Nether/End, or a fully
            // overcast sky with weather lighting on).
            bool particleCelestialLit = max(worldPush.lightRadiance.x,
                    max(worldPush.lightRadiance.y, worldPush.lightRadiance.z)) > 0.0;
            float signedNdl = dot(n, lightDir);
            float ndl = particleCelestialLit ? abs(signedNdl) : 0.0;
            if (ndl > 0.0) {
                float3 shadowOrigin = hitPos + (signedNdl >= 0.0 ? n : -n) * SURF_BIAS;
                float3 vis = visibility(shadowOrigin, lightDir, 10000.0).transmittance;
                if (max(vis.r, max(vis.g, vis.b)) > 0.0) {
                    // Particles are lit by the same sun as everything else, so they must dim under the
                    // same cloud. Do this only after scene visibility survives: an opaque house/wall has
                    // already removed the direct sun, and running the cloud attenuator on that fully
                    // blocked path is both wasted work and the overlap that made block shadows appear
                    // double-dark when ray-traced clouds were enabled.
                    vis *= cloudSunShadow(worldPush, hitPos - worldPush.camOffset, lightDir);
                    if (max(vis.r, max(vis.g, vis.b)) > 0.0) {
                        float3 particleNee = throughput * albedo * INV_PI * worldPush.lightRadiance.xyz * ndl * vis;
                        L += particleNee;
                        if (channel == 2) {
                            Lspec += particleNee;
                        } else {
                            Ldiff += particleNee;
                        }
                    }
                }
            }

            // Same analytic held-item light as surfaces. Billboards are two-sided receivers; bias
            // the shadow origin toward the light's side, exactly like the celestial NEE above does.
            if (worldPush.handLight.w > 0.0) {
                float3 toHand = worldPush.handLight.xyz - hitPos;
                float3 handOrigin = hitPos + (dot(n, toHand) >= 0.0 ? n : -n) * SURF_BIAS;
                float3 particleHandIrr = handLightIrradiance(handOrigin, hitPos, n, true);
                if (max(particleHandIrr.x, max(particleHandIrr.y, particleHandIrr.z)) > 0.0) {
                    float3 particleHand = throughput * albedo * INV_PI * particleHandIrr;
                    L += particleHand;
                    if (channel == 2) {
                        Lspec += particleHand;
                    } else {
                        Ldiff += particleHand;
                    }
                }
            }

            float3 v = -rd;

            // RIS direct lighting from block emitters (lava, glowstone, torches, ...) — same reservoir
            // sampler as terrain/entities, but two-sided: a billboard has no back face, so light
            // striking either side of the quad should still land (matches the sun/moon NEE above).
            if (risOn) {
                bool shadeWithRestir = restirReceiverPending && restirOwner;
                bool skipSiblingRis = restirReceiverPending && !restirOwner;
                restirReceiverPending = false;
                if (!skipSiblingRis) {
                    uint freshCandidates = shadeWithRestir
                            ? min(worldPush.risCandidates, RESTIR_FRESH_CANDIDATES)
                            : worldPush.risCandidates;
                    Reservoir r = risInitial(hitPos, n, v, rd, albedo,
                            float3(0.0, 0.0, 0.0), 1.0, true, 0.0, freshCandidates,
                            seed, proposalSeed);
                    if (shadeWithRestir) {
                        r = restirSpatiotemporal(r, pix, dimensions, hitPos, payload.motionPrev,
                                n, v, rd, albedo, float3(0.0, 0.0, 0.0),
                                1.0, true, 0.0, seed);
                    }
                    float estimatorScale = shadeWithRestir ? restirSampleScale : 1.0;
                    float3 particleRis = throughput * shadeReservoir(r, hitPos, n, v, rd, albedo,
                            float3(0.0, 0.0, 0.0), 1.0, true, 0.0) * estimatorScale;
                    L += particleRis;
                    // twoSided shading has no specular term — the whole estimate is diffuse.
                    if (channel == 2) {
                        Lspec += particleRis;
                    } else {
                        Ldiff += particleRis;
                    }
                }
            }

            if (bounce >= maxBounces) {
                break;
            }
            throughput *= albedo;
            ro = hitPos + n * SURF_BIAS;
            rd = cosineDir(n, seed);
            rayConeSpread = max(rayConeSpread, RAY_CONE_DIFFUSE_SPREAD);
            showCelestial = false; // diffuse receiver: direct sun/moon was handled by NEE above
            continue;
        }

        float3 albedo = payload.albedo;
        // SHaRC needs the surface's OWN incoming light (emission + NEE + RIS + hand light + SSS) to
        // warm the cache, so capture the accumulated path radiance before the first local term lands.
        // localAdded / throughput below recovers a path-independent environment estimate.
        float3 surfaceLightBaseline = L;
        // Boat interior fix: detect dynamic entity (boat, minecart) early for two-sided lighting
        bool isDynamicEntityEarly = payloadDynamicEntity();
        // LabPBR SSS strength (0 when the material has none, or when the player turned SSS off:
        // payloadActiveSss returns a hard zero in that case, so every SSS term below — the RIS back
        // lobe and the explicit transmission pass — collapses without needing its own check).
        float sss = payloadActiveSss();
        float3 p = hitPos + n * SURF_BIAS;
        float3 v = -rd; // view direction (toward the camera / incoming ray)

        // Material split. Metals tint F0 by albedo and have no diffuse lobe; dielectrics use a fixed
        // 0.04 F0, sourced from the chit (LabPBR custom/metal F0, or the dielectric default it applies).
        float metal = clamp(payloadMetalness(), 0.0, 1.0);
        float rough = metallicShininessRoughness(clamp(payloadRoughness(), 0.0, 1.0), metal);
        // Unresolvably tight lobes become exact delta mirrors; everything else keeps its authored value.
        // Snapping to 0 also propagates into the guide buffers, which is what tells RR to hold the
        // reflection sharp instead of accumulating it as a glossy lobe.
        bool exactSpecular = isDeltaAlpha(rough);
        rough = exactSpecular ? 0.0 : rough;
        float3 diffAlb = albedo * (1.0 - metal);
        float3 F0 = payload.f0;

        // End portal night sky: fully UNLIT. The portal's radiance is purely its own emission
        // (albedo * emission, same term the emissive block below would add), and the path ends
        // right here — before NEE, hand light, RIS, the SHaRC cache query and the indirect bounce.
        // Without this, a torch placed near the portal showed up as a specular glint (F0) and a
        // diffuse wash over the starfield, and the bounce could even carry that light back into
        // the scene. The emitter gate mirrors the regular emission term below: a portal that RIS
        // samples as a light source is not double-counted on diffuse continuations.
        if ((payload.flags & PAYLOAD_END_PORTAL_UNLIT) != 0u) {
            float emission = payloadEmission();
            bool gateEmitter = risOn && payloadEmitterInList();
            if (emission > 0.0 && (!gateEmitter || showCelestial)) {
                float emissionScale = isDynamicEntityEarly ? worldPush.lightScales.y : worldPush.lightScales.x;
                float3 emissive = throughput * albedo * emission * max(emissionScale, 0.0);
                L += emissive;
                // Emission has no lobe; REBLUR convention is to carry it in the diffuse signal.
                if (channel == 2) {
                    Lspec += emissive;
                } else {
                    Ldiff += emissive;
                }
            }
            break;
        }

        // ---- Experimental SHaRC: cache query (NVIDIA's Render/Query pass).
        //
        // A cache entry holds the temporally accumulated OUTGOING radiance of the surfaces in this
        // cell: their direct light AND the deeper light back-propagated into them. So on a hit the
        // cached value REPLACES this vertex's entire shading plus the rest of the path —
        // `L += throughput * cached` and stop, exactly the RTXGI sample's usage. No albedo and no
        // 1/pi factor: the cached quantity is already outgoing radiance, and re-applying a BRDF here
        // is what made the old integration invisible (its "GI tail" came out ~10x too dark).
        //
        // The query must run BEFORE any local term lands, otherwise the vertex's own NEE would be
        // added on top of a value that already contains it. Guards: only diffuse-capable vertices
        // past the start bounce, never the ReSTIR receiver vertex (it must shade its reservoir),
        // and the arriving segment must be longer than one cell diagonal so a path can never read
        // back the entry its own previous vertex just wrote (self-feedback / energy gain).
        if (sharcEnabled() && sharcReady(worldPush)
                && bounce >= int(worldPush.sharcParams2.x)
                && channel != 2
                && !exactSpecular
                && worldPush.sharcParams.y > 0.0
                && !restirReceiverPending
                && payload.hitT > worldPush.sharcParams.x * SHARC_CELL_DIAGONAL) {
            gv_sharcDbgQueried = 1u;
            float3 sharcCached;
            float sharcSamples;
            if (sharcQuery(worldPush, hitPos, n, sharcCached, sharcSamples)) {
                gv_sharcDbgHit = 1u;
                gv_sharcDbgCount = max(gv_sharcDbgCount, sharcSamples);
                float3 sharcGi = throughput * sharcCached * worldPush.sharcParams.y;
                L += sharcGi;
                // Cached GI is the diffuse lobe's tail; even an unclassified (channel 0) split-leaf
                // vertex reached here via its diffuse continuation.
                Ldiff += sharcGi;
                // Feed the cached tail back one hop so upstream voxels learn multi-bounce GI
                // (NVIDIA's cache resampling), then stop tracing this path.
                sharcPropagateToPath(worldPush, sharcState, hitPos, sharcCached, throughput);
                break;
            }
        }

        // Emissive surfaces (lava, glowstone, torches, ...) add radiance directly, colored by albedo.
        // RIS emitter NEE: the direct-hit emission term is gated only for emitters RIS actually samples
        // (payload emitter-in-list bit) — and then only off diffuse continuation rays (showCelestial
        // false), which the previous vertex's RIS already accounted for; the primary ray and
        // specular/dielectric bounces (showCelestial true) still add it, since no emitter NEE happened
        // along those. Emitters NOT in the light buffer (sparse/sub-threshold footprints, entities) are
        // never NEE-sampled, so they always gather — bit-identical to the no-NEE path, no energy lost.
        // Emission is the material HDR baseline (EMISSIVE_STRENGTH * JSON override, baked in Java).
        // Apply the runtime global scale here so terrain blocks and dynamic entities can be adjusted
        // independently from the options menu.
        float emission = payloadEmission();
        // One gate drives both the RIS call below and the direct-hit emission suppression here. They must
        // agree: gateEmitter exists only because RIS already accounted for this emitter, so a bounce where
        // RIS is skipped must also gather emission directly or that light is lost outright.
        bool gateEmitter = risOn && payloadEmitterInList();
        if (emission > 0.0 && (!gateEmitter || showCelestial)) {
            float emissionScale = isDynamicEntityEarly ? worldPush.lightScales.y : worldPush.lightScales.x;
            float3 emissive = throughput * albedo * emission * max(emissionScale, 0.0);
            L += emissive;
            // Emission has no lobe; REBLUR convention is to carry it in the diffuse signal (a
            // specular bucket would mislead the hit-distance-driven specular filter).
            if (channel == 2) {
                Lspec += emissive;
            } else {
                Ldiff += emissive;
            }
        }

        // NEE: direct light from the dominant celestial body (sun by day, moon by night) — Lambert
        // diffuse + GGX specular, one shadow ray. Jitter the light direction within its square angular
        // extent (worldPush.lightDir.w half-angle) so the shadow ray samples the light's quad — soft penumbrae
        // over accumulation. The same sampled direction drives ndl, the shadow ray, and the specular
        // half-vector.
        //
        // The radiance itself already carries the weather attenuation and the dimension's celestial
        // state: Java writes a zeroed lightRadiance in the Nether and the End (neither has a sun or a
        // moon) and a rain/thunder-scaled one in the Overworld. celestialLit therefore skips the whole
        // NEE block — including its shadow ray — wherever the celestial light cannot contribute, which
        // is every pixel of every Nether and End frame.
        bool celestialLit = max(worldPush.lightRadiance.x,
                max(worldPush.lightRadiance.y, worldPush.lightRadiance.z)) > 0.0;
        float3 lightDir = worldPush.lightDir.xyz;
        float lightHalfAngle = worldPush.lightDir.w;
        if (lightHalfAngle > 0.0) {
            lightDir = sampleSquare(lightDir, lightHalfAngle, seed);
        }
        // For dynamic entities (boat interior), use two-sided NDL so interior faces get sun/sky light
        float ndlRaw = dot(n, lightDir);
        float ndl = celestialLit ? (isDynamicEntityEarly ? abs(ndlRaw) : max(0.0, ndlRaw)) : 0.0;
        // Cloud occlusion of the celestial light at this shading point. It is shared lazily by the
        // front-face NEE below and the SSS transmission pass further down: both are the same sun arriving
        // at the same point, so a cloud that shades one must shade the other by exactly the same amount.
        // The important ordering is scene visibility FIRST, cloud visibility SECOND. If a house already
        // blocks the shadow ray, the direct-light path has zero throughput and the cloud query must not
        // stack another attenuation term on top of that fully blocked path. This keeps block/structure
        // shadows stable when the camera moves over them while ray-traced clouds are enabled.
        float cloudShadow = 1.0;
        bool cloudShadowReady = false;
        if (ndl > 0.0) {
            VisibilityResult shadow = visibility(p, lightDir, 10000.0);
            float3 vis = shadow.transmittance;
            if (max(vis.r, max(vis.g, vis.b)) > 0.0) {
                cloudShadow = cloudSunShadow(worldPush, hitPos - worldPush.camOffset, lightDir);
                cloudShadowReady = true;
                vis *= cloudShadow;
                // Underwater receiver whose shadow ray crossed a water surface: scale the direct light by
                // the wave-refraction caustic at the exit point. Focusing scales the incident irradiance,
                // so it applies to the whole NEE term (diffuse + specular).
                if (medium.current.water && waterWaves && shadow.waterHitT > 0.0) {
                    vis *= waterCaustic(p + lightDir * shadow.waterHitT, lightDir, shadow.waterHitT);
                }
                if (max(vis.r, max(vis.g, vis.b)) > 0.0) {
                    // Lambertian diffuse (f = albedo/PI) and Cook–Torrance specular kept as separate
                    // terms: their sum is the brdf the combined path always used (bit-identical L), and
                    // the split is what the NRD per-lobe signal needs.
                    float3 brdfDiff = diffAlb * INV_PI;
                    float3 h = normalize(lightDir + v);
                    float ndh = max(0.0, dot(n, h));
                    float ndv = max(1.0e-4, dot(n, v));
                    float vdh = max(0.0, dot(v, h));
                    float D = ggxD(ndh, rough);
                    float G = ggxG1(ndv, rough) * ggxG1(ndl, rough); // separable Smith masking
                    float3 F = fresnelSchlick(vdh, F0);
                    float3 brdfSpec = (D * G) * F / (4.0 * ndv * ndl); // Cook–Torrance specular
                    float3 brdf = brdfDiff + brdfSpec;
                    float3 nee = throughput * brdf * worldPush.lightRadiance.xyz * ndl * vis;
                    L += nee;
                    if (channel == 0) {
                        // Unclassified primary hit: split direct light by BRDF lobe.
                        Ldiff += throughput * brdfDiff * worldPush.lightRadiance.xyz * ndl * vis;
                        Lspec += throughput * brdfSpec * worldPush.lightRadiance.xyz * ndl * vis;
                    } else if (channel == 1) {
                        Ldiff += nee;
                    } else {
                        Lspec += nee;
                    }
                }
            }
        }

        // Analytic held-item light: direct NEE toward the luminous item in the player's hand
        // (WorldPush.handLight). Held items never enter the RIS emitter buffer, so without this
        // term their captured flame lights the scene through rare indirect bounce hits only —
        // a torch in hand would barely brighten the blocks in front of it. Lambertian only; the
        // flame itself still gathers as ordinary emissive geometry on direct hit. Two-sided for
        // dynamic entities, mirroring the celestial NDL convention above.
        float3 handIrr = handLightIrradiance(p, hitPos, n, isDynamicEntityEarly);
        if (max(handIrr.x, max(handIrr.y, handIrr.z)) > 0.0) {
            float3 handNee = throughput * diffAlb * INV_PI * handIrr;
            L += handNee;
            if (channel == 2) {
                Lspec += handNee;
            } else {
                Ldiff += handNee;
            }
        }

        float activeSss = hitDepth <= MAX_SSS_INDIRECT_DEPTH ? sss : 0.0;

        // Boat interior fix: dynamic entities (boats, minecarts) have concave interior faces
        // where a single-sided NDL would be negative, causing black interior. Reuse early flag.
        bool twoSidedEmitter = isDynamicEntityEarly; // fix boat interior + minecart

        // Emitter direct lighting. In ReSTIR mode the owner sample shades the reused reservoir here and
        // sibling SPP samples omit this one receiver, so frame averaging contains the ReSTIR result rather
        // than silently blending it back toward RIS. OFF and deeper vertices execute the original RIS path.
        // Front lobe + SSS backlighting still share one shadow ray.
        if (risOn) {
            bool shadeWithRestir = restirReceiverPending && restirOwner;
            bool skipSiblingRis = restirReceiverPending && !restirOwner;
            restirReceiverPending = false;
            if (!skipSiblingRis) {
                uint freshCandidates = shadeWithRestir
                        ? min(worldPush.risCandidates, RESTIR_FRESH_CANDIDATES)
                        : worldPush.risCandidates;
                Reservoir r = risInitial(hitPos, n, v, rd, diffAlb, F0, rough, twoSidedEmitter,
                        activeSss, freshCandidates, seed, proposalSeed);
                if (shadeWithRestir) {
                    r = restirSpatiotemporal(r, pix, dimensions, hitPos, payload.motionPrev,
                            n, v, rd, diffAlb, F0, rough, twoSidedEmitter, activeSss, seed);
                }
                float estimatorScale = shadeWithRestir ? restirSampleScale : 1.0;
                float3 ris = throughput * shadeReservoir(r, hitPos, n, v, rd, diffAlb, F0, rough,
                        twoSidedEmitter, activeSss) * estimatorScale;
                L += ris;
                // Fog in-scatter from the very light the reservoir just shaded the surface with —
                // same sample, same estimator scale, no second light selection anywhere, which is
                // the whole trick that keeps cost flat at five hundred torches. The term fires its
                // own short shadow ray from the fog's quadrature point instead of reusing the
                // receiver's, so a wall between the flame and the MIDDLE of the segment cannot leak
                // the cone (fog.slang). Emission-family light rides the diffuse bucket, matching
                // the direct-hit emission convention above, and the pre-fog segment throughput
                // keeps it from eating this segment's own darkening twice.
                if (fogEnabled(worldPush) && fogAppliesHere(medium) && showCelestial
                        && r.W > 0.0 && r.phat > 0.0) {
                    float3 fogE = fogEmitterScatter(worldPush, ro, rd, payload.hitT,
                            r.pos, r.lnrm, r.le, r.area) * estimatorScale;
                    fogE *= fogSegThroughput;
                    L += fogE;
                    if (channel == 2) {
                        Lspec += fogE;
                    } else {
                        Ldiff += fogE;
                    }
                }
                if (nrdEnabled()) {
                    if (channel == 0) {
                        // Unclassified primary hit: split the reservoir estimate by BRDF lobe. Costs a
                        // second shadow ray, so it only runs on the NRD path.
                        float3 risDiff;
                        float3 risSpec;
                        shadeReservoirSplit(r, hitPos, n, v, rd, diffAlb, F0, rough,
                                twoSidedEmitter, activeSss, risDiff, risSpec);
                        Ldiff += throughput * risDiff * estimatorScale;
                        Lspec += throughput * risSpec * estimatorScale;
                    } else if (channel == 1) {
                        Ldiff += ris;
                    } else {
                        Lspec += ris;
                    }
                }
            }
        }

        // Thin-surface SSS transmission. Light entering from the back face scatters through toward the
        // viewer via a forward-biased HG phase (leaves/grass backlit by the sun glow when you look
        // through them toward the light source). Shadow ray fires from the back face to avoid
        // self-occlusion. cosT = dot(lightDir, rd): rd tracks the ray toward the sun when the sun is
        // behind the slab, giving cosT ≈ 1 → forward-scatter peak.
        if (sss > 0.0 && celestialLit && hitDepth <= MAX_SSS_INDIRECT_DEPTH) {
            float backNdl = max(0.0, dot(-n, lightDir));
            if (backNdl > 0.0) {
                VisibilityResult shadowBack = visibility(hitPos - n * SURF_BIAS, lightDir, 10000.0);
                float3 visB = shadowBack.transmittance;
                if (max(visB.r, max(visB.g, visB.b)) > 0.0) {
                    // Same cloud attenuation as the front-face NEE above — see cloudShadow. Compute it
                    // lazily if the front lobe was unlit or blocked, but only after this back-face scene
                    // visibility ray has proven that some direct light remains.
                    if (!cloudShadowReady) {
                        cloudShadow = cloudSunShadow(worldPush, hitPos - worldPush.camOffset, lightDir);
                        cloudShadowReady = true;
                    }
                    visB *= cloudShadow;
                    // Same caustic as the front-face NEE — underwater kelp/seagrass transmission should
                    // flicker with the same light bands as the floor around it.
                    if (medium.current.water && waterWaves && shadowBack.waterHitT > 0.0) {
                        visB *= waterCaustic(hitPos + lightDir * shadowBack.waterHitT,
                                             lightDir, shadowBack.waterHitT);
                    }
                    if (max(visB.r, max(visB.g, visB.b)) > 0.0) {
                        float cosT = dot(lightDir, rd);
                        float3 sssTerm = throughput * diffAlb * sss * SSS_STRENGTH * hg(cosT, SSS_G) * backNdl * worldPush.lightRadiance.xyz * visB;
                        L += sssTerm;
                        // Back-face transmission is a diffuse-scattering event.
                        if (channel == 2) {
                            Lspec += sssTerm;
                        } else {
                            Ldiff += sssTerm;
                        }
                    }
                }
            }
        }

        // ---- Experimental SHaRC: cache update (NVIDIA's Update pass, inlined into the render trace).
        //
        // Cache keys are ABSOLUTE WORLD coordinates: hitPos is terrain-rebased, and sharc.slang adds
        // the terrain origin from sharcGridOrigin.xyz before hashing. (The original integration
        // hashed hitPos - camOffset, which is CAMERA-relative — see gv_hitCamRel in guides.slang —
        // so every camera move, sub-pixel TAA jitter included, smeared entries into different cells
        // and the cache could never converge.)
        //
        // EVERY shaded diffuse-capable vertex of an update path warms the cache — not just the
        // early bounces. The old write-side restriction to bounce < startBounce left the cells that
        // queries actually look up (deep, non-camera-visible surfaces) permanently cold, which is
        // why the debug view showed a wall of red. The written value is the vertex's own outgoing
        // radiance: everything the path added since surfaceLightBaseline (emission + NEE/RIS + hand
        // light + SSS), demodulated by the path throughput. That light is also back-propagated into
        // the previous vertices' entries, so each entry converges toward the full outgoing radiance
        // of its surface — the direct-plus-indirect quantity the query above replaces a vertex with.
        if (sharcUpdatePath && !exactSpecular) {
            float3 localAdded = L - surfaceLightBaseline;
            if (max(throughput.r, max(throughput.g, throughput.b)) > 1.0e-3
                    && !any(isnan(localAdded)) && !any(isinf(localAdded))) {
                float3 localOut = localAdded / throughput;
                sharcPropagateToPath(worldPush, sharcState, hitPos, localOut, throughput);
                uint sharcIndex;
                if (sharcWrite(worldPush, hitPos, n, localOut, sharcIndex)) {
                    sharcRegisterPathVertex(sharcState, sharcIndex, hitPos, throughput);
                }
            }
        }

        // Preserve all local lighting at the terminal hit, but do not sample a continuation that the
        // bounce loop cannot trace.
        if (bounce >= maxBounces) {
            break;
        }

        // Indirect continuation: importance-sample the diffuse OR the GGX specular lobe, chosen by their
        // relative reflectance.
        float ps = exactSpecular && luminance(diffAlb) <= 1.0e-6
                       ? 1.0
                       : clamp(luminance(F0) / (luminance(F0) + luminance(diffAlb) + 1.0e-4), 0.1, 0.9);
        if (rndf(seed) < ps) {
            float3 l;
            if (exactSpecular) {
                // Authored zero is a delta distribution, not a narrow finite GGX lobe. This avoids the
                // degenerate VNDF at alpha=0 and preserves a genuinely sharp reflection without a floor.
                l = reflect(rd, n);
                throughput *= fresnelSchlick(clamp(dot(n, v), 0.0, 1.0), F0) / ps;
            } else {
                float3 h = sampleGGXVNDF(n, v, rough, seed);
                l = reflect(rd, h); // rd = -v: reflect the incoming ray about the microfacet normal
                float ndl2 = dot(n, l);
                if (ndl2 <= 0.0) {
                    break; // microfacet reflects below the surface: terminate this path
                }
                // VNDF + separable Smith ⇒ weight = F · G2/G1(v) = F · G1(l); divide by the lobe pdf ps.
                float3 F = fresnelSchlick(max(0.0, dot(v, h)), F0);
                throughput *= F * ggxG1(ndl2, rough) / ps;
                rayConeSpread = max(rayConeSpread, rough * RAY_CONE_GLOSSY_SPREAD_SCALE);
            }
            ro = p;
            rd = l;
            showCelestial = true; // specular lobe: this ray is a (glossy) mirror — show the disc
            // First-bounce decomposition: the lobe chosen at the unclassified primary hit decides the
            // channel everything from here on belongs to, and the next trace's hit distance is that
            // lobe's hit distance for REBLUR. Deeper bounces keep the channel they inherited.
            if (channel == 0) {
                channel = 2;
                pendingHitChannel = 2;
            }
        } else {
            throughput *= diffAlb / (1.0 - ps);
            ro = p;
            rd = cosineDir(n, seed);
            rayConeSpread = max(rayConeSpread, RAY_CONE_DIFFUSE_SPREAD);
            showCelestial = false; // diffuse lobe: disc covered by NEE, hide it (anti-firefly)
            if (channel == 0) {
                channel = 1;
                pendingHitChannel = 1;
            }
        }

        // Russian roulette on deeper bounces: survive with prob = max channel, rescale survivors.
        // Standard path tracing - no forced boost, let natural indirect do the work.
        if (bounce >= rrStart) {
            float q = clamp(max(throughput.r, max(throughput.g, throughput.b)), 0.02, 1.0);
            if (rndf(seed) > q) {
                break;
            }
            throughput /= q;
        }
    }
    // Every term was classified into exactly one accumulator, so the split is exact.
    outLdiff = Ldiff;
    outLspec = Lspec;
    outHitDiff = hitDiff;
    outHitSpec = hitSpec;
    return L;
}

[shader("raygeneration")]
void main() {
    worldPush = ConstPtr<WorldPush>(pc.worldPushAddr)[0];
    uint2 dispatchIndex = DispatchRaysIndex().xy;
    uint2 dimensions = DispatchRaysDimensions().xy;
    uint pixelIndex = dispatchIndex.y * dimensions.x + dispatchIndex.x;
    if (restirEnabled()) {
        // Every pixel owns exactly one record. Invalidate first so sky, a terminal dielectric, a debug
        // frame, or a frame with no emitters cannot leave stale current-buffer history behind.
        restirInvalidate(pixelIndex);
    }
    if (pc.debugView != 0u && pc.debugView != SHARC_DEBUG_VIEW_ID) {
        return; // pass A owns guide visualization output
    }
    int2 pix = int2(dispatchIndex);
    uint spp = max(worldPush.spp, 1u);
    // SHaRC debug view state, aggregated over every sample and split leaf this pixel traced.
    gv_sharcDbgQueried = 0u;
    gv_sharcDbgHit = 0u;
    gv_sharcDbgCount = 0.0;
    ConstPtr<PackedPathSegment> queue = ConstPtr<PackedPathSegment>(pc.pathQueueAddr);
    float3 frameRadiance = float3(0.0, 0.0, 0.0);
    float3 frameDiff = float3(0.0, 0.0, 0.0);
    float3 frameSpec = float3(0.0, 0.0, 0.0);
    // Per-lobe hit distance across samples: min of the finite values (NRD's own front-end averaging
    // for specular hit distances is a min; 0 is reserved for "no data" and skipped).
    float frameHitDiff = 0.0;
    float frameHitSpec = 0.0;
    uint recordIndex = pixelIndex;
    for (uint leaf = 0u; leaf < MAX_PATH_SEGMENTS; ++leaf) {
        PackedPathSegment packed = queue[recordIndex];
        PathSegment segment = unpackPathSegment(packed);
        for (uint s = 0u; s < spp; ++s) {
            float3 sampleDiff;
            float3 sampleSpec;
            float sampleHitDiff;
            float sampleHitSpec;
            frameRadiance += tracePath(segment, uint2(pix), s + leaf * spp,
                    leaf == 0u, leaf == 0u && s == 0u, float(spp), dimensions,
                    sampleDiff, sampleSpec, sampleHitDiff, sampleHitSpec);
            frameDiff += sampleDiff;
            frameSpec += sampleSpec;
            if (sampleHitDiff > 0.0) {
                frameHitDiff = frameHitDiff > 0.0 ? min(frameHitDiff, sampleHitDiff) : sampleHitDiff;
            }
            if (sampleHitSpec > 0.0) {
                frameHitSpec = frameHitSpec > 0.0 ? min(frameHitSpec, sampleHitSpec) : sampleHitSpec;
            }
        }
        if (packed.nextRecord == PATH_NO_NEXT) {
            break;
        }
        recordIndex = packed.nextRecord;
    }
    float3 radiance = frameRadiance / float(spp);

    // Sanitize the combined radiance every downstream consumer reads. A single non-finite sample
    // (a 0/0 or inf-inf from some BSDF corner surviving the walk) would be written into outImage
    // and from there straight into DLSS-RR's or SVGF's history, where it survives every temporal
    // blend and then spreads through the spatial kernels — one bad pixel becomes a persistent
    // bright smudge. The NRD branch below scrubs its own lobe images; this is the same guard for
    // the combined image. The FP16 bound also matters for RR specifically: outImage is the raw
    // R16G16B16A16 colour input, and anything above 65504 stores as Inf in that texture.
    // isnan/isinf rather than a bare clamp: clamp() propagates NaN on most implementations.
    if (any(isnan(radiance)) || any(isinf(radiance))) {
        radiance = float3(0.0, 0.0, 0.0);
    }
    radiance = clamp(radiance, 0.0, 65504.0);

    // Firefly clamp for the raw (undenoised) view only — see UNDENOISED_FIREFLY_MAX_LUMINANCE. Scaling
    // the whole colour by the luminance ratio preserves hue and saturation, so a clamped highlight
    // desaturates toward nothing rather than shifting colour.
    if (!denoiserEnabled()) {
        float lum = luminance(radiance);
        if (lum > UNDENOISED_FIREFLY_MAX_LUMINANCE) {
            radiance *= UNDENOISED_FIREFLY_MAX_LUMINANCE / lum;
        }
    }

    // SHaRC cache debug view: green = a query hit (brightness tracks the entry's sample count, so
    // the cache is seen warming up), red = a query missed (cold cell / rejected entry), dark grey =
    // no query reached (path ended before the start bounce, specular, sky, or SHaRC off). This is
    // the first-class replacement for the temporary red/green overlay used to diagnose the cache.
    if (pc.debugView == SHARC_DEBUG_VIEW_ID) {
        float3 sharcDbg;
        if (gv_sharcDbgHit != 0u) {
            float warm = clamp(gv_sharcDbgCount / 8.0, 0.0, 1.0);
            sharcDbg = lerp(float3(0.05, 0.30, 0.05), float3(0.10, 1.00, 0.10), warm);
        } else if (gv_sharcDbgQueried != 0u) {
            sharcDbg = float3(0.55, 0.04, 0.04);
        } else {
            sharcDbg = float3(0.12, 0.12, 0.14);
        }
        radiance = sharcDbg;
    }
    outImage[pix] = float4(radiance, 1.0);

    if (nrdEnabled()) {
        // ---- REBLUR front-end pack. Mirrors REBLUR_FrontEnd_PackRadianceAndNormHitDist: DEMODULATED
        // YCoCg radiance clamped to the FP16 range, plus the normalized hit distance. Invalid
        // (NaN/Inf) samples become black with "no data" rather than poisoning the denoiser's history.
        // The undenoised firefly clamp above deliberately does NOT apply here — REBLUR's own
        // anti-firefly pass owns that job on the denoised path, and clamping twice would darken it.
        float3 diffRad = frameDiff / float(spp);
        float3 specRad = frameSpec / float(spp);
        if (any(isnan(diffRad)) || any(isinf(diffRad))) {
            diffRad = float3(0.0, 0.0, 0.0);
        }
        if (any(isnan(specRad)) || any(isinf(specRad))) {
            specRad = float3(0.0, 0.0, 0.0);
        }
        diffRad = clamp(diffRad, 0.0, 65504.0);
        specRad = clamp(specRad, 0.0, 65504.0);

        // ---- Relative firefly bound on the per-lobe signals.
        //
        // These used to be handed to REBLUR unclamped, on the assumption that its own anti-firefly
        // pass owns the job. It does not catch this case: a low-pdf sample in shadow is a ~100x
        // outlier against a surface level of ~0.05, and REBLUR's suppressor works against a local
        // mean it forms AFTER accumulation, by which point the spike has already been averaged in
        // and spread. The result is bright specks in shadow that appear with NRD exactly as they do
        // with the built-in denoiser, because the cause is upstream of both.
        //
        // The bound is relative to this pixel's own combined radiance, so it scales with the local
        // light level: a real highlight in a bright area keeps its energy, while a lone spike over a
        // dark surface is cut to a multiple of what that surface is actually receiving.
        {
            float localLevel = luminance(radiance) + NRD_FIREFLY_FLOOR;
            float maxLobe = localLevel * NRD_FIREFLY_RELATIVE_MAX;
            float diffLum = luminance(diffRad);
            if (diffLum > maxLobe) {
                diffRad *= maxLobe / diffLum;
            }
            float specLum = luminance(specRad);
            if (specLum > maxLobe) {
                specRad *= maxLobe / specLum;
            }
        }

        // Material demodulation (NRD_MaterialFactors). NRD wants radiance with the surface material
        // divided out, so its spatial kernels never average across albedo/texture detail; the
        // combine pass multiplies the same factors back after denoising. Both sides read the factors
        // from the SAME guide textures, so the round trip is exact — see nrdMaterialFactors.
        float3 albedoGuide = gAlbedo[pix].rgb;
        float3 specAlbedoGuide = gSpecAlbedo[pix].rgb;
        float rough = clamp(gNormal[pix].w, 0.0, 1.0);
        float3 diffFactor;
        float3 specFactor;
        nrdMaterialFactors(albedoGuide, specAlbedoGuide, rough, diffFactor, specFactor);
        diffRad /= diffFactor;
        specRad /= specFactor;
        // The division can push a bright sample past FP16; re-clamp so the stored signal is always
        // representable (an Inf here would spread through REBLUR's history).
        diffRad = clamp(diffRad, 0.0, 65504.0);
        specRad = clamp(specRad, 0.0, 65504.0);

        // viewZ was written at the primary hit by Pass A (sky = 1e6 = INF for NRD).
        float viewZ = gViewZ[pix];
        float normHitDiff = frameHitDiff > 0.0 ? nrdNormHitDist(frameHitDiff, viewZ, rough) : 0.0;
        float normHitSpec = frameHitSpec > 0.0 ? nrdNormHitDist(frameHitSpec, viewZ, rough) : 0.0;
        gNrdDiff[pix] = float4(nrdLinearToYCoCg(diffRad), normHitDiff);
        gNrdSpec[pix] = float4(nrdLinearToYCoCg(specRad), normHitSpec);
    }
}
