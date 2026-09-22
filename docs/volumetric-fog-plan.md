# Volumetric fog + god rays — plano de método

Notas de design para o volumetric fog "por fonte de luz" (sol, lua, tochas, blocos emissivos).
Estilo de trabalho igual ao `cloud-rework-plan.md`: método primeiro, depois mapa de integração
concreto por arquivo, depois riscos conhecidos.

> **STATUS (2026-08-31):** M0 + M1 + M2 implementados nesta branch. `shaders/world/fog.slang`
> é o módulo novo (forma fechada + quadratura de altura); `world.rgen.slang` chama `fogSegment`
> nos três sítios planejados (bloco do hit antes das nuvens, recovery do prefixo do Pass A, ramo
> de miss com o céu atenuado) e `fogEmitterScatter` no shading do reservatório ReSTIR (nada de
> segundo shadow ray pra seleção de luz — o winner do RIS é a luz amostrada). O tiers são os da
> nuvem (`showCelestial` = march completo + 1 shadow ray do sol com `cloudSunShadow`; bounce
> difuso = só Beer-Lambert, sem in-scatter, pra não re-iluminar ar de caverna). **Fix pós-playtest:**
> esse tier barato *vazava* in-scatter (o lightTerm era computado fora do gate `highQuality`, só a
> oclusão estava dentro — cavernas claras de dia); agora `lightTerm` só existe no tier cheio. O
> recovery do prefixo gatia no `seg.medium` já empurrado pela interface (água/vidro entrando ficavam
> sem fog camera→superfície); agora gatia no meio da câmera (`!cameraSubmerged()`). E o default de
> densidade caiu 30%→10%: com sunPeak≈21 o wash forward-scatter soterrava os discos de sol/lua no
> tonemap + auto-exposure (mantido como tuning, mas a causa dos discos sumidos se revelou outra:
> binding do atlas — 13 vs 3+GUIDE_COUNT=12, herança do crescimento dos guides na #71; conserto
> no rmiss + guarda textual no `RtCloudShaderRegressionTest`). Flicker residual = OMM do mod
> (toggle próprio no menu), não do fog. **M3 meia-vida implementada em seguida:** weather×fog
> (chuva engrossa `sigmaS` nos DOIS scatter sites e só dim-mua o `sunVis` celestial — o raio de
> sombra continua sendo a única autoridade de oclusão; tudo atrás do `FEATURE_WEATHER_LIGHTING`,
> lane que já existia) e tint por bioma = `WorldPush.fogTint` trailing carregado do
> `EnvironmentAttributes.FOG_COLOR` da câmera (o próprio jogo resolve bioma/clima/dimensão; o
> slider só controla a força do lerp). Densidade POR REGIÃO continua proibida (é a grade voxel dos
> 500 bugs vestida de slider) e haze ambiente Nether/End continua fora (termo desobstruído =
> família de leak). `WorldPush.fogParams`
> (4 lanes) + config `composite.fog*` (toggle, densidade, alcance, anisotropia, falloff de altura)
> + sub-screen "Volumetric Fog" (en_us/pt_br). O ambient NÃO-ocluído ficou deliberadamente fora:
> ele é a única parte que re-traria os leaks de caverna. M3 (tint de bioma/clima via
> EnvironmentAttributes) e M4 (AABBs locais, turbidez da água) seguem planejados abaixo.

## TL;DR

**Não usar grade voxel/world-space.** Usar **single-scattering integrado por segmento de caminho,
em forma fechada**, pendurado no stack `Medium` que já existe (`shaders/world/medium.slang`) e
nas sombras/amostras que o path tracer **já lança** (shadow ray do sol no NEE celestial,
reservatórios do ReSTIR para emissores). É o mesmo método do volumetric fog do Quake II RTX /
DICE-Frostbite (fechados os integrais de Beer–Lambert, 1–2 amostras de luz por segmento,
acumulação temporal em vez de march).

Regra que evita as 500 classes de bug: **o fog é uma propriedade do segmento do caminho, não do
espaço do mundo.** Toda ray que atravessa a cena — primária, reflexão, refração, bounce difuso,
shadow ray — usa a mesma função. Não existe "fog que só existe na câmera" para vazar.

Custo por pixel: O(SPP × bounces), **independente do número de luzes** (o ReSTIR já escolhe a luz
dominante por você; 500 tochas não multiplicam nada). Zero upload por frame, zero VRAM extra,
zero estrutura nova de CPU.

## Por que world-space/voxel quebrou aqui (e ia quebrar sempre)

1. **Só cobre o ray da câmera.** Num path tracer, fog precisa valer em *todo* segmento: a
   reflexão na poça d'água, a refração no vidro, o bounce difuso do cave. Com grade voxel você tem
   duas saídas e ambas são o bug que você viu: ou o march roda só no primary (god ray que atravessa
   parede, névoa "vendo" através do bloco, reflexo sem fog), ou roda em toda ray (o march DDA vira
   o custo dominante do frame).
2. **Light → voxel splatting escala com #luzes.** 500 tochas = upload/injeção por frame, culling
   redundante com o `RtLightGrid`/`RtLightHierarchy` que já existe pra superfície, e ghosting em
   toda borda de voxel/quando um chunk atualiza. É a mesma classe do bug da "linha que segue a
   câmera" documentado no `cloud-rework-plan.md` item 1: campo espacial + march sem integração por
   segmento do path.
3. **O renderizador já tem a estrutura de dados certa**: BVH (occlusão exata por geometria), grade
   de luz clusterizada + ReSTIR (seleção por-célula da luz dominante), NEE celestial com jitter
   angular (penumbra suave), TAA/SVGF/NRD (a "accumulation" que o march precisa). Grade nova só
   re-implementa isso pior.

## O método: integração fechada por segmento

Fog = um meio participativo homogêneo (por padrão) com coeficientes por bloco:

```
sigma_t = sigma_s + sigma_a          (extinção total — JÁ existe como Medium.extinction)
albedo_v = sigma_s / sigma_t         (nevoeiro: ~0.95–1.0, quase puro espalhamento)
T(d) = exp(-sigma_t * d)             (Beer–Lambert do segmento — JÁ existe, linha ~190 do world.rgen)
I(d) = (1 - exp(-sigma_t * d)) / sigma_t   (integral fechada ∫₀ᵈ T(s) ds — a única "mágica" nova)
```

In-scatter de um segmento `[0, d]` com iluminação L_in aproximadamente constante dentro dele:

```
scatter = sigma_s * albedo_v * fase_HG * L_in * I(d)
```

Que é o par {scatter, transmittance} que o `cloudSegment` já devolve (`CloudVolume`) e o rgen já
sabe compor com o throughput no lugar certo do path. **A nuvem é o template exato da fiação; o fog
só não precisa de march nenhum na densidade.**

### Iluminação dentro do segmento (o "por fonte de luz")

- **Sol/lua (direcional):** `L_in = worldPush.lightRadiance.xyz`. Visibilidade `V(sun)` — aqui
  mora o god ray. M1: amostrar `V` **num ponto jittered ao longo do segmento**
  (`s = d * (frame_offset + rand)`), com 1 shadow ray `visibility()` por segmento primário/miss
  (que é onde o céu aparece atrás e a shaft precisa ser oclusa por montanha/bloco); nos hits
  geométricos **compartilhar o shadow ray do NEE celestial que já é lançado** (`vis` +
  `cloudShadow` da linha ~595 do rgen) → custo zero extra. O ponto jittered muda por frame e o
  TAA/SVGF acumula — isso é "light march de 1 passo + accumulation", convergindo como convergem
  as sombras do resto do renderizador.
- **Emissores (tocha, glowstone, ...):** nenhum loop sobre luzes. Onde o rgen **já** soma o
  "Emitter direct lighting" via reservatório ReSTIR (~linha 660), cada amostra do reservoir tem
  `{pos, le, vis, area}` prontos. Adicionar a quadratura do fog na mesma linha:

  ```
  // q = res.pos (luz), p = hit, ro/rd = segmento atual, d = payload.hitT
  float3 sp   = ro + rd * clamp(dot(q - ro, rd), 0.0, d);  // ponto de aproximação máxima
  float  tSeg = saturação: exp(-sigma_t * s);               // câmera -> sp
  float3 toL  = q - sp; float len = length(toL);
  float  tL   = exp(-sigma_t * len);                        // sp -> luz (vis partilhado do NEE)
  scatter += sigma_s * fase_HG(dot(rd, toL/len), g) * res.le * res.vis * tSeg * tL / (len2*PI);
  ```

  **Zero rays extras.** 500 tochas custa igual a 5.
- **Ambiente/céu:** `cloudSkyAmbient` já existe como noção de ambient do meio; o fog usa um termo
  análogo (sky irradiance no ponto do segmento) com `g≈0`, sem ray nenhum. É o que preenche a
  névoa dentro de caverna iluminada só por GI.
- **Held item light:** idem — onde `handLightIrradiance` roda, adicionar a mesma quadratura.

### God rays

Não existe um segundo algoritmo. God ray = fog + oclusão geométrica + faseforward:

- **Oclusão** vem do BVH através do `visibility()` compartilhado acima — shaft atravessando parede
  é impossível por construção (era o bug estrutural do voxel).
- **Fase:** Henyey–Greenstein com `g ∈ [0.4, 0.8]` (poeira/fumaça 0.6–0.7; névoa úmida 0.3–0.5).
  Copiar o padrão dual-lobe do `cloudPhase` se quiser halo traseiro fraco (back lobe ~ -0.2).
- **Penumbra/vivacidade:** usar a MESMA direção jitterada do sol (`sampleSquare(lightDir,
  lightDir.w, seed)` da linha ~587) para a sombra do fog e para o NEE da superfície. Direções
  diferentes = "fog aceso com chão na sombra" flickerando — não fazer.
- **Discos no céu:** em `world.rmiss.slang`, atenuar o disco do sol/lua e o horizonte do céu pelo
  `T(fogFar)` da coluna (integral analítica até o clamp) — sem isso o fog "some" olhando pro sol e
  o look do god ray contra o céu não fecha.

### Onde entra no código (mapa concreto)

1. **`shaders/world/medium.slang`** — `Medium` ganha `float3 scatter; float g;` (e `airMedium()`
   passa a carregar o meio do fog quando ativado; `makeDielectricMedium` inalterado). A atenuação
   do segmento NÃO muda de dono: continua sendo o bloco Beer–Lambert do rgen
   (`throughput *= exp(-extinction * hitT)`) — **o fog só devolve scatter**, e assim nunca tem
   double-attenuation com água/vidro.
2. **`shaders/world/fog.slang` (novo)** — módulo irmão de `clouds.slang`, mesma disciplina: só
   importa `world_common` (+ `medium` para o struct), entrada recebe `WorldPush` explicitamente.
   API:
   ```
   FogVolume fogSegment(WorldPush push, float3 originRel, float3 dir, float maxT,
                        float3 sunVis, float3 skyAmbient, float3 emitterScatter,
                        bool highQuality);  // devolve scatter; transmittance fica com o medium block
   ```
   Homogêneo = forma fechada (nada de passo). Perfil de altura (M3) = quadratura midpoint de 8
   pontos na tier FULL, 2 na tier CHEAP — os passos são *ao longo do segmento do ray*, não no mundo.
   Clamp de integração: raios que dão miss integram até `fogFar` (empurrado no WorldPush),
   porque `(1-exp(-σd))/σ` satura sozinho — só evita `exp` de `inf`.
3. **`shaders/world/world.rgen.slang`** — três sítios, todos espelhando o bloco de nuvem já
   existente:
   - bloco do cloudSegment na linha ~206: inserir `fogVolume` logo antes, mesma split de canal
     (`L` sempre; `Ldiff` exceto `channel == 2` → `Lspec`), mesma dependência de
     `scene-visibility-primeiro` (o fog do sol só usa `vis` do NEE quando `vis > 0`, como o
     `cloudShadow` faz);
   - **recovery do prefixo do Pass A** (linha ~76, o `if (seg.bounce > 0)`): aplicar o fog do
     prefixo câmera→superfície idem às nuvens, com o mesmo argumento do split (F+(1−F)=S);
   - bloco celestial NEE (~585) e bloco ReSTIR (~660): calcular a quadratura do fog reusando
     `vis`/reservoir já em mãos. O caso "segmento termina no céu" (miss) roda um `visibility()`
     único do ponto jittered se `FEATURE_FOG_SHAFTS` ligado (M1 completo; M0 pode nascer sem).
4. **`shaders/world/world_primary.rgen.slang`** — Pass A é 1 spp fixo: deixar o fog do prefixo por
   conta do recovery do item 3 (é o que o código de nuvem já diz que é o certo) e não duplicar.
5. **`WorldPush` (`world_common.slang`) + Java (`RtComposite`)** — 2 float4 novos,
   serializador já é gerado do tipo refletido:
   ```
   public float4 fogParams;   // x sigma_s por bloco (0 = off), y altura-H (falloff exponencial),
                              // z g (HG), w fogFar (blocos)
   public float4 fogTint;     // xyz linear tint do espalhamento, w albedo_v (0.95 default)
   ```
   Config: `CausticaConfig.Rt.Composite.FOG_DENSITY/FOG_HEIGHT/FOG_G` no mesmo padrão
   `clampedFloat` do `WATER_OPACITY`; slider `RtVideoOptions` + submenu `RtSubScreens`;
   `FEATURE_FOG`/`FEATURE_FOG_SHAFTS` em `featureFlags`; lang `pt_br`/`en_us` etc.
6. **Denoise/upscale** — sem mudança em NRD/SVGF: fog entra no bucket `Ldiff` (a convenção REBLUR
   que já vale para emissão — comentário da linha ~560), que é o bucket temporal mais estável. O
   que NÃO fazer: meter fog em `Lspec` no primary (o filtro especular edge-stops por roughness e
   come as shafts finas). Se em 1 spp as shafts de tocha ficarem ruidosas: primeiro aumentar o
   jitter stratified do ponto de quadratura (mesmo hash de `CausticaJitter`), depois — só se doer —
   buffer de fog acumulado em meia resolução; o método em si não precisa dele.

### Variação espacial SEM grade voxel

- **Altura** (névoa que encharca vale/praiara): `sigma(h) = sigma_s * exp(-(h - h0)/H)`. A integral
  do Beer-Lambert de um perfil exponencial em y tem forma fechada simples p/ segmento reto; na
  prática: midpoint quadrature de 8 passos no segmento primário, 2 no bounce. Custo: ALU, zero ray.
- **Bioma/dimensão**: o `waterParams` já empurra tint do bioma da câmera — empurrar
  `fogTint/fogParams` resolvidos do `EnvironmentAttributes`/metadata do bioma (densidade e cor do
  fog vanilla já existem por bioma; usar como sinal, com transição interpolada pela célula como é
  feito pra água). Nether: `lightRadiance` é zero → sun fog some sozinho (gate `celestialLit` já
  cobre); o "haze" carmesim do Nether = termo ambiente-only com tint, que é o mais barato de todos
  (zero rays).
- **Clima**: `worldPush.weather` (rain/thunder) multiplica densidade e dessatura o tint —
  análogo direto do que o `cloudState` faz.
- **Volumes locais** (bruma junto d'água, névoa "dentro" de um build): NÃO marchar campo. Reusar o
  truque do `cloudClassicBoxes`: lista pequena de AABBs e **interseção analítica do segmento com a
  caixa** — o fog integration fica restrito ao sub-segmento `[tIn, tOut]`. Exato, sem aliasing, sem
  upload. (M4, opcional.)
- **Névoa que "respeita salas/cavernas"**: se um dia for essencial, o campo certo é um **campo de
  densidade** (altura ocupada por céu por coluna, um float2 por 4×4 ou 1×1 bloco, derivado do
  `RtSectionSnapshots` na build da seção — CPU amortizada), lido ao longo do segmento na
  quadratura. Reparem: o que muda vs. a tentativa voxelfog é que **a grade nunca guarda luz**, só
  densidade; luz continua fechada/por segmento. Os 500 bugs da injeção por luz não nascem.

### Custo realista (por que roda com 500 tochas)

| Item | Custo |
|---|---|
| Beer–Lambert do segmento | já pago hoje (branch `any(extinction>0)` + 1 `exp`) |
| Sun fog em hit geométrico | +0 rays (compartilha NEE `vis`/`cloudShadow`) |
| Sun fog em segmento pro céu (shafts M1) | +1 shadow ray/frame/pixel (coerente c/ light-march das nuvens: 6 passos p/ *frame*, aqui 1 amostra × SPP frames) |
| Emitter fog (M2) | +0 rays (quadratura no reservoir shading existente) |
| Altura/bioma/clima (M3) | ALU de quadratura, 0–8 passos curtos no segmento |
| CPU/memória por frame | zero uploads; ~8 bytes de WorldPush + 2 float4 |

Comparar com voxel 128³ RGBA16F: 16 MB + clear/upload + splat por luz + DDA por segmento × bounce.

## Alternativas consideradas e por que não

- **Froxel/light-volume grid (UE-style)** — ótimo rasterizador, redundante aqui: re-implementa
  light culling que o `RtLightGrid` + ReSTIR já fazem e continua sendo "de fora do path" (mesmo
  problema de consistência dos bounces). Só teria sentido se faltasse seleção de luz, o que não falta.
- **Voxel density + DDA com splat de luz** — a tentativa atual; ver seção acima. A variante
  densidade-only ainda pode voltar como M3+ (campo de sky-occupancy), sem nunca injetar luz na grade.
- **Post screen-space "god rays" (radial blur + depth)** — barato e bonito pra sol único, mas: sem
  per-light, sem correção em reflexão/refração, e em pipeline com TAA + DLSS-RR/FSR o blur
  screen-space vira fonte de ghosting/halo (cf. o halo de água que motivou o `waterOpacity` extra).
  Serve de debug, não de produto.
- **Volume PT completo (delta/ratio tracking, Hillaire)** — qualidade máxima, múltiplo scattering;
  custo ~o da nuvem volumétrica ×(passos de densidade). Guardar como aspiração do modo "cinema"; a
  aproximação powder + dual-lobe das nuvens mostra que o produto aqui aceita aproximação barata.

## Milestones

1. **M0 — esqueleto homogêneo sem shafts:** `Medium.scatter/g`, `fog.slang` com forma fechada,
   wiring do scatter no bloco do cloud (rgen + recovery do prefixo), `fogParams` no WorldPush +
   config. Sun/lua já viram god ray **contra geometria** (o `vis` compartilhado faz o trabalho);
   custo ~1–2%. Sem nenhum ray novo — é o commit que prova o método.
2. **M1 — shafts no céu:** sombra jittered 1-amostra nos segmentos de miss (flag
   `FEATURE_FOG_SHAFTS`), atenuação do disco solar no rmiss por `T(fogFar)`. Testar com SPP 1 vs 8
   pra ver o TAA fechar as shafts.
3. **M2 — emissores por reservatório:** quadratura no shading do ReSTIR + held light. Critério:
   tocha acesa num corredor escuro produz cone; cobrir a tocha com um bloco → cone some na mesma
   frame (sem ghost — não tem estado).
4. **M3 — altura + bioma + clima:** perfil exponencial (8/2 quadrature), tint/densidade por
   EnvironmentAttributes, weather × densidade.
5. **M4 (opcional):** volumes locais AABB (padrão cloudClassicBoxes) / sky-occupancy field
   densidade-only; turbidez in-scatter na água (mesmo `fogSegment` com o medium da água).

## Armadilhas (leiam antes de abrir PR)

1. **Não duplicar a extinção**: fog usa o `Medium.extinction` existente; `fogSegment` devolve só
   scatter. Quem marcar extinção nos dois lugares escurece água atrás de ar duas vezes.
2. **Ordem visibilidade**: `visibility()` da cena primeiro, sun-vis do fog depois — é a regra que o
   `cloudShadow` já documenta (linha ~588 do rgen). Fog em path com throughput zero é bug, não nuance.
3. **Pass A**: não somar fog no primary pass E no recovery do `tracePath` — o recovery é o dono
   (a lição do bug "mar sob nuvem em brilho total").
4. **Jitter compartilhado**: mesma `sampleSquare(lightDir, ...)` pro NEE e pro fog; seed do ponto de
   quadratura vem do `seg.seed`/`CausticaJitter` pra ser stratified no tempo, não azul puro
   (azul puro por-pixel com ponto aleatório descorrelaciona o shaft da sombra da superfície).
5. **`payload.hitT = -1`** é sentinel de miss — clampar o segmento do fog por `fogFar` no caso miss,
   senão `I(d)` come um `inf` e o horizonte vira branco.
6. **Entidades/partículas** já estão no BVH e oclusam `visibility()` — não adicionar "fog das
   partículas" separado; a partícula é oclusor, não emissor (exceto se `RtCuboidEmitter`/emissiva,
   que entra pelo grid normalmente).
7. **Regression test** no estilo `RtCloudShaderRegressionTest`: assertar textualmente que (a) o fog
   scatter é acumulado ANTES do `throughput *=` do segmento, (b) o sun-fog está dentro do guard
   `vis > 0` e depois do `cloudShadow`, (c) `fogSegment` não é chamado no `world_primary.rgen`
   (o recovery é o dono).
8. **Nether/End**: gate natural via `celestialLit`/`lightRadiance==0`; se o bioma do Nether pedir
   haze ambiente, ele não depende do sol — testar dimension switch sem flicker do `fogTint`.
9. **DLSS-RR/FSR**: se ativar shafts de tocha em 1 spp e nascer halo no upscaler, a alavanca é o
   bucket (manter tudo em `Ldiff`) e o jitter temporal; NÃO embregar blur extra.
