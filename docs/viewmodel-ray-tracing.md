# Viewmodel em path tracing (mão + item segurado)

**Estado:** implementado, opt-in, **desligado por padrão** (`caustica.rt.viewmodelRayTracing`).

Este documento cobre o caminho completo: por que a captura acontece onde acontece, como a geometria
é colocada no mundo, quais três regras fazem dela um *viewmodel* e não um segundo jogador, e o que
fica deliberadamente diferente do vanilla.

## O problema

`GameRenderer.renderItemInHand` é um pass raster *depois* do mundo: profundidade limpa, projeção
"3d hud" própria (FOV fixo de 70°), e no Caustica ele é redirecionado para o `RtUiOverlay`. Sob o
path tracer isso significa que a mão é um decal Flat: sem POM no bloco segurado, sem brilho do sol
numa lanterna, sem reflexo da tocha no chão molhado, sem iluminação global no braço.

Para a mão fazer parte da imagem traçada ela precisa estar no TLAS **antes** de o frame ser
gravado — e o composite roda *antes* do pass da mão (se rodasse depois, cobriria os efeitos de tela
e o HUD 3D). Essa é toda a complexidade do recurso: capturar uma moldura antes de onde o vanilla
submete a dele.

## A captura (o seam)

`GameRendererMixin.caustica$endWorldScaleBeforeHand` (HEAD do `render3dHud`, imediatamente antes de
`WorldRenderScaler.end`) chama `caustica$captureViewmodelForRt`, que:

1. reproduz os **gates** do vanilla exatamente como `renderItemInHand` os faz (`isPanoramicMode`,
   `hasPlayer`, `cameraType.isFirstPerson()`, `entityRenderState.isSleeping`, `guiRenderState.isHudHidden`,
   `GameType.SPECTATOR`, `firstPersonHandsAndItems != null`);
2. reconstrói o **pose stack** do vanilla (`pushPose`, `mulPose(viewRotationMatrix.invert())`, `bobHurt`,
   `bobView` quando `optionsRenderState.bobView`) — `bobHurt`/`bobView` são privados, então o mixin os
   expõe por `@Invoker` em vez de copiar a matemática, que driftaria na próxima atualização do jogo;
3. chama `firstPersonHandsAndItemsRenderer.submitHandsWithItems(partialTicks, pose, collector,
   playerState, playerState.firstPersonHandsAndItems)` com `collector` sendo o
   `RtEntityCollector` do Caustica.

Nada da lógica de viewmodel é reimplementado: seleção de mão principal/offhand via `HandRenderSelection`,
swing, troca de item, tremor de comer, recarga de besta, escopo, tintas e glint são os cálculos do
próprio jogo. É por isso que o resultado é visualmente idêntico ao vanilla por construção, e não por
aproximação.

O `modelViewStack` (`RenderSystem.getModelViewStack().mul(viewRotationMatrix)`) **não** é tocado: ele só
importa ao raster, que é justamente o que deixa de acontecer. Nenhum dos renderizadores envolvidos
(`FirstPersonHandsAndItemsRenderer`, `AvatarRenderer.renderHand`) lê o model-view durante a submissão.

A supressão do pass vanilla fica em `FirstPersonHandsAndItemsRendererMixin`: um `@Inject(HEAD,
cancellable)` em `submitHandsWithItems` que cancela **uma** submissão por frame, quando o latch
`RtViewmodel.shouldSuppressVanillaSubmission()` autoriza. Autorização exige que o frame tenha de fato
instanciado o mesh (`markInstanced`), então em qualquer frame em que o composite desistiu (terrain não
residente, `materialEpochTraceGate`, rebind de reload, janela RT fechada) o mão continua sendo do
vanilla. Não existe janela em que nenhum dos dois desenha.

## Espaço: soldada na câmera de graça

`renderItemInHand` compõe o pose como `V⁻¹ · bob · m` e o pass raster multiplica por `V` de novo; os
dois se cancelam (é por isso que a mão não sai da tela quando você gira). Os vértices que chegam ao
collector são, portanto, **deslocamentos de mundo relativos à câmera** em torno do olho — exatamente o
espaço em que o tracer trabalha. A instância TLAS é, portanto, **só uma translação**:

```
[1 0 0 | cameraPosition.x − rebase.x]
[0 1 0 | cameraPosition.y − rebase.y]
[0 0 1 | cameraPosition.z − rebase.z]
```

Parte linear identidade importa por dois motivos: o `entityO2w` do hit shader (que gira normais de
entidades reutilizadas via transformada da instância) continua sendo o no-op documentado para qualquer
geometria construída no próprio frame, e a mão rasterizada pelo vanilla e a mão traçada diferem por
nada além dessa translação quando a compensação de FOV está desligada.

### `k`: por que o FOV da mão importa

O mundo traçado usa a projeção do nível; a mão do vanilla usa a projeção do 3d-HUD, construída a
partir de `CameraRenderState.hudFov` (fixado em 70°, modulado por `hand.fov-follows-camera` e pelo
fator de morte/líquido). Duas projeções que diferem só na escala vertical produzem a mesma imagem de um
ponto se — e somente se — a profundidade de view for escalada pela razão das tangentes:

```
k = m11_world / m11_hud        (m11 = 1/tan(fovY/2))
```

`m11_world` é lido de `cameraState.projectionMatrix` — a matriz com que os raios realmente são
projetados — e `m11_hud` de `cameraState.hudFov`, reaplicando o mesmo escalonamento que o mixin aplica
ao `Projection.setupPerspective` para que os dois lados concordem por construção. Um `k` positivo nunca
cruza um vértice com o plano do olho (só escala `|z|`), então isso não é proteção de clipping: é
recato. Fora de `[0.35, 2.5]` a compensação é abandonada e a mão mantém a escala de mundo.

É a mesma troca "tamanho aparente ↔ distância" de um slider de FOV de viewmodel. A diferença é *onde*
ela acontece: `RtViewmodel.applyHudDolly` aplica `V⁻¹·Z(k)·V` aos vértices do capture e — com o
**inverso-transposto**, ou seja `V⁻¹·Z(1/k)·V` re-normalizado — às normais dos prims. Colocar a escala
na malha em vez da instância não é gosto: sob um escalonamento anisotrópico a transformada correta de
normais não é a de posições, e o hit shader usa a de posições. Assim o TBN derivado de posições+UV, a
normal do prim e o POM permanecem no mesmo frame consistente. UVs, cores, tintas e buckets não são
tocados, e com `k == 1` a função retorna imediatamente — o capture fica bit-idêntico ao do vanilla.

## As três regras

### 1. A mão não projeta sombra

Em vez de um novo tipo de instância, o viewmodel recebe **seu próprio bit de máscara TLAS** (`0x08`).
Todos os produtores de instância do mundo passaram de `0xFF` para `0xF7` (`RtAccel.Instance` default,
`RtEntities.MASK_ALL`, máscara literal do path de block entities), então:

| raio | máscara de cull | vê o viewmodel? |
|---|---|---|
| primary do mundo | `CULL_PRIMARY = 0x02` | **não** |
| sombra / GI / reflexão | `CULL_SECONDARY = 0x01` | **não** |
| luz do item segurado | `CULL_HAND_LIGHT = 0xF4` (era `0xFC`) | **não** |
| overlay acima dos blocos | `CULL_VIEWMODEL = 0x08` | **só ele** |

Sombras e reflexos de "você" continuam vindo da instância do corpo em primeira pessoa (máscara `0x01`),
como hoje.

### 2. Nada escurece a mão (critério 4)

O hit shader marca o payload (`PAYLOAD_VIEWMODEL`) em cada um dos três retornos do ramo de entidade de
`world.rchit.slang`, e `visibility()` em `trace.slang` ficou payload-aware:

```hlsl
return visibilityCulled(origin, dir, tmax,
        (payload.flags & PAYLOAD_VIEWMODEL) != 0u ? CULL_VIEWMODEL_SHADOW : CULL_SECONDARY);
```

`CULL_VIEWMODEL_SHADOW = 0xF0` mantém terreno/entidades como oclusores mas descarta corpo em primeira
pessoa (`0x01`), partículas (`0x02`), DH LOD (`0x03`) e a própria mão (`0x08`). Uma edição cobre o sol,
os emissores RIS, a luz analítica do item, o nevoeiro e a sombra de verso, porque todos passam por
`visibility()`. Isso só é correto porque `visibilityCulled` traça com um payload local
(`makeShadowPayload`) e portanto não destrói o payload da superfície sendo sombreada — o teste
`shadowRaysFromAViewmodelSurfaceSwapTheirCullMask` fixa as duas coisas.

### 3. A mão fica acima dos blocos (critério 2)

O raster escapa disso com um depth clear; um closest-hit não tem alavanca equivalente, e um bloco através
do qual o braço passa ganharia o hit e abriria um buraco na mão. `traceViewmodelOverlay(ro, rd)` em
`trace.slang` roda **depois** do trace primário dos dois raygens (no `world.rgen` com `bounce == 0`) e:

- não faz nada se `worldPush.viewmodel.x == 0` (nenhum viewmodel neste frame) ou se o hit do mundo está
  além do alcance da mão;
- senão re-traça o mesmo raio com máscara `0x08` e `tmax = reach` contra a geometria da mão, e nada mais;
- se errar, **restaura** o payload salvo antes (inclusive a radiância do céu), para que um pixel de céu
  sem mão não pague nem perca nada.

`reach` é a maior distância ao olho de um vértice **posicionado** (após a transformada, então o dolly
conta) mais uma folga de 0.02, publicada no WorldPush pelo `RtComposite` depois de
`RtEntities.beginFrame` — ou seja, é o alcance do mesh que está de fato no TLAS deste frame.

## Identidade por geometria, não por tipo de instância

O viewmodel **não** ganha um bit de tipo em `InstanceID()`: `world.rchit.slang` decodifica o índice da
tabela como `InstanceID() & ~ENTITY_BIT`, e `ENTITY_BIT|PARTICLE_BIT` já significa Distant Horizons.
Em vez disso a flag vive na tabela de geometria — o lane `reserved` de `EntityGeom` virou `flags`
(`ENTITY_GEOM_VIEWMODEL = 1u`, escrito no byte `+56` por `RtEntities.writeTableEntry`). Consequência
deliberada: por ser "entidade" para os hit shaders, a mão herda sem trabalho algum o alpha test de
cutout, o glint, os portais em itens, emissão, tintas e a tabela de materiais LabPBR.

`RtEntities` hospeda o resto: o stage `captureViewmodel` roda por último em `beginFrame` (depois de
entidades, block entities e partículas), usa o mesmo `appendCapture` transiente (mesh + BLAS por frame,
sem cache de BLAS — a mão muda de qualquer forma), e recebe o capture como parâmetro em vez de usar o
acumulador compartilhado `capture`.

## Movimento, denoiser e upscaler

`RtViewmodel` guarda as posições posicionadas do frame anterior e produz o vetor de movimento por
vértice no mesmo formato do uploader de partículas (`disp = (cur − prev) + shift`, com o shift do
rebase incluído — sem ele, qualquer rebase de terreno pintaria um vetor de um mundo inteiro). Quando o
número de vértices muda (troca de item), a função retorna `null` e o `Motion` fica zero, exatamente
como uma entidade nova: o denoiser re-estima em vez de arrastar cor do item anterior pelo swap.

## Configuração, UI e falha

| chave TOML | opção de vídeo | padrão |
|---|---|---|
| `hand.ray-traced` | Ray-Traced Viewmodel | **off** |
| `hand.ray-traced-matches-hud-fov` | Match Hand FOV | on |

Ambas entram em `CausticaConfig.Rt.Hand`, aparecem na sub-tela de céu/viewmodel e são cobertas pelo
"Reset to Defaults" (via `RtSubScreens.skySettings()`); `RtConfigDefaultsTest` as valida
automaticamente por iterar o registro.

A captura roda fora do try/catch protetor do composite, então ela falha mole por conta própria: um
throw descarta aquele frame, três seguidos desligam o recurso pela sessão com uma linha de log, e
qualquer fallback é a mão do vanilla. `RtEntityCollector` ganhou um contador de submissões que ele
não sabe reproduzir (`submitCustom`, grupos de partículas, gizmos); se ele sair non-zero o capture é
descartado — é isso que faz um **mapa segurado** continuar sendo desenhado pelo vanilla em vez de
aparecer como uma moldura em branco, já que o conteúdo do mapa é emitido por `MapRenderer` e não pelos
buffers de capture.

## O que fica diferente do vanilla (de propósito)

- **Resolução.** A mão passa a viver na imagem do mundo. Com upscaling (FSR/XeSS/DLSS-RR) abaixo do
  nativo ela é desenhada na resolução interna e fica um pouco mais suave que o decal em resolução
  nativa. `hand.ray-traced-matches-hud-fov` não muda isso; muda tamanho, não nitidez.
- **Frame Generation** pode criar artefatos num objeto colado à tela enquanto o mundo atrás varre.
- **Efeitos de tela** (fogo, submersão, bloqueio de visão) compõem **sobre** a mão traçada — o inverso
  do vanilla, onde a mão é desenhada antes deles.
- **`integrate3DHudDepth`** (profundidade 3D do crosshair) não vê mais a mão; a mira pode atravessá-la.
- **Sem piso de ambiente.** A mão é sombreada como qualquer superfície; numa caverna completamente
  escura ela fica preta. Um item luminoso continua iluminando a cena pela `handLight` analítica, mas a
  geometria do viewmodel **não** entra no buffer de emissores RIS — segurar uma tocha não ilumina a
  caverna mais do que antes.
- **Tintes/foil/mapa** herdam as decisões do capture de entidade, incluindo as simplificações dele.
- **Custo por frame**: pose + captura + upload de mesh + build de BLAS. É a razão do padrão desligado,
  junto com o ganho ser cosmético.

## Arquivos tocados

`CausticaConfig` (duas chaves) · `rt/entity/RtViewmodel` (novo) · `rt/entity/RtEntities` (stage,
máscaras, `geomFlags`) · `rt/entity/RtEntityCollector` (contador de descartes) · `rt/accel/RtAccel`
(máscara padrão) · `rt/RtComposite` (lane do WorldPush) · `mixin/GameRendererMixin` (seam +
`@Invoker`) · `mixin/FirstPersonHandsAndItemsRendererMixin` (novo) · `client/WorldRenderScaler`
(consulta da janela) · `shaders/world/{world_common,trace,world.rchit,world.rgen,world_primary.rgen}.slang`
· UI (`RtVideoOptions`, `RtSubScreens`, 11 arquivos de lang) · `docs/minecraft-26.3-abi.tsv` ·
`RtViewmodelRayTracingTest` + `RtShaderConstantMirrorTest`.

## O que ainda não foi verificado

Nada disso foi executado: o ambiente de desenvolvimento do repositório não tem JDK/Gradle nem o
cliente do Minecraft resolvido, então `compileJava`, `verifyMinecraftAbi`, `verifyModJar`, a
compilação dos shaders e o comportamento em jogo estão **em aberto**. As suposições de ABI mais
frágeis (`bobHurt`/`bobView` privados, `hudFov`, os campos de render state) foram pinadas no TSV para
que a CI as confirme no bytecode real, e o `verifyModJar` confere o registro do mixin novo.
