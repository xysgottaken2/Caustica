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

Parte linear identidade importa: o `entityO2w` do hit shader (que gira as normais de entidades
reutilizadas via transformada da instância) continua sendo o no-op documentado para qualquer geometria
construída no próprio frame, e a mão traçada é a malha que o vanilla rasterizaria, transladada e mais nada.

### O tamanho da mão e o FOV do nível

A mão traçada é um objeto de mundo: quem a projeta é a projeção do nível, então o tamanho aparente dela
segue o FOV como o de qualquer coisa segurada. A mão rasterizada do vanilla passa por uma projeção
própria, a do 3d-HUD, montada a partir de `CameraRenderState.hudFov` (70° fixo, modulado pelo fator de
morte/líquido). **`hand.fov-follows-camera`** é a alavanca que reconcilia as duas — ela já existia, já é
onde as pessoas ajustam o tamanho do viewmodel, e continua sendo a única.

Uma revisão anterior tinha um segundo controle: dolava a profundidade de view do capture por
`k = m11_nível / m11_hud` (`m11 = 1/tan(fovY/2)`), que é a condição exata para duas projeções que diferem
só na escala vertical produzirem a imagem uma da outra. Ele saiu de propósito, por três motivos:

- com o FOV em 70° (o padrão) `k == 1`, ou seja, o controle só fazia algo fora do padrão — onde o
  `fov-follows-camera` já está mexendo no mesmo efeito, e dois controles brigando pelo mesmo parâmetro é o
  pior caso;
- escalar um único eixo é uma transformação anisotrópica. As posições iriam por `V⁻¹·Z(k)·V`, mas as
  normais capturadas precisariam do **inverso-transposto** — e o hit shader gira as normais pela parte linear
  da transformada da instância, então o relevo do POM e o especular saíam inclinados. A escala teria de ser
  assada na malha, e não na instância, só para a sombra ser a certa;
- o dolly também move a superfície que a névoa, o plano de foco e o motion vector medem: a mão ganhava o
  tamanho certo com a distância errada, mudando a cada sprint.

O que resta é a diferença honesta: longe de 70° sem `fov-follows-camera`, a mão traçada tem escala de mundo
e a do vanilla tem escala de HUD. Isso vale para qualquer objeto segurado, e é o comportamento que o
recurso escolheu ter.

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

- não faz nada se `worldPush.viewmodel.x == 0` (nenhum viewmodel neste frame), nem se o hit já é do
  viewmodel (guarda de recursão), nem se a direção do raio está fora do cone do capture (abaixo);
- senão re-traça o mesmo raio com máscara `0x08` e `tmax = reach` contra a geometria da mão, e nada mais;
- um hit **substitui** o resultado do mundo sem nenhuma comparação de distância;
- se errar, **restaura** o payload salvo antes (inclusive a radiância do céu), para que um pixel sem mão
  não pague nada a mais nem perca nada.

### Por que a distância do hit do mundo não entra na decisão

Parece óbvio economizar: "se o mundo parou longe da mão, a mão não pode estar aqui". É o inverso da regra.
O hit do mundo estar **além** do alcance da mão é justamente o caso comum em que a mão cobre a tela — céu,
chão, parede a dois blocos. Um portão assim faria a mão aparecer só contra o céu e sumrir diante de tudo
que está perto, que é o que o raster **nunca** fez: com o depth clear a mão ganha sempre que cobre um
pixel, e um bloco a 0,3 do olho não tem prioridade sobre ela. Então a ordenação por distância está fora;
`RtViewmodelRayTracingTest` fixa isso como asserção negativa (o código correto tem a forma "não existe esse
teste").

O que paga a conta é **direção**, não distância: `reach` continua sendo a maior distância ao olho de um
vértice da malha exatamente como ela é instanciada, mais uma folga de 0.02, e o cone que
contém a direção de todos os vértices do capture vai em `worldPush.viewmodelCull` (`xyz` = eixo unitário,
`w` = cosseno do semi-ângulo). Um raio fora do cone não pode tocar a mão — o cone é construído como o
fechamento das direções dos vértices em torno da média delas, triângulos são combinações positivas dessas
direções e um cone convexo é fechado sob combinação positiva, então a conservatividade é garantida e só
falha para mais (há ~2° de folga para não comer a silhueta). Se o capture não consegue se limitar — sem
vértices utilizáveis, uma pose que wraps ao redor do olho, um resultado não-finito — ele publica `w = -1`,
que o shader lê como "sem limite, traça sempre". O `RtComposite` publica os dois depois de
`RtEntities.beginFrame`, do mesh que está de fato no TLAS deste frame.

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

A chave entra em `CausticaConfig.Rt.Hand`, aparece na sub-tela de céu/viewmodel (junto de
`hand.fov-follows-camera`, que é a alavanca de tamanho) e é coberta pelo "Reset to Defaults" via
`RtSubScreens.skySettings()`; `RtConfigDefaultsTest` a valida automaticamente por iterar o registro.
Não há segunda chave para o FOV da mão de propósito: ver *O tamanho da mão e o FOV do nível*.

A captura roda fora do try/catch protetor do composite, então ela falha mole por conta própria: um
throw descarta aquele frame, três seguidos desligam o recurso pela sessão com uma linha de log, e
qualquer fallback é a mão do vanilla. `RtEntityCollector` ganhou um contador de submissões que ele
não sabe reproduzir (`submitCustom`, grupos de partículas, gizmos); se ele sair non-zero o capture é
descartado — é isso que faz um **mapa segurado** continuar sendo desenhado pelo vanilla em vez de
aparecer como uma moldura em branco, já que o conteúdo do mapa é emitido por `MapRenderer` e não pelos
buffers de capture.

## O que fica diferente do vanilla (de propósito)

- **Resolução.** A mão passa a viver na imagem do mundo. Com upscaling (FSR/XeSS/DLSS-RR) abaixo do
  nativo ela é desenhada na resolução interna e fica um pouco mais suave que o decal em resolução nativa.
- **Escala.** A mão tem escala de mundo, não de HUD: veja *O tamanho da mão e o FOV do nível* para por que
  isso é preferido a um segundo controle de FOV e o que `hand.fov-follows-camera` cobre.
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

`CausticaConfig` (uma chave) · `rt/entity/RtViewmodel` (novo) · `rt/entity/RtEntities` (stage,
máscaras, `geomFlags`) · `rt/entity/RtEntityCollector` (contador de descartes) · `rt/accel/RtAccel`
(máscara padrão) · `rt/RtComposite` (lane do WorldPush) · `mixin/GameRendererMixin` (seam +
`@Invoker`) · `mixin/FirstPersonHandsAndItemsRendererMixin` (novo) · `client/WorldRenderScaler`
(consulta da janela) · `shaders/world/{world_common,trace,world.rchit,world.rgen,world_primary.rgen}.slang`
· UI (`RtVideoOptions`, `RtSubScreens`, 11 arquivos de lang) · `docs/minecraft-26.3-abi.tsv` ·
`RtViewmodelRayTracingTest` + `RtShaderConstantMirrorTest`.

## O que ainda não foi verificado

O que a CI cobre está coberto: `compileJava`, `test` (incluindo os testes de contrato deste
recurso), `verifyMinecraftAbi` contra o bytecode real do 26.3 — que é o que confere as suposições mais
frágeis, `bobHurt`/`bobView` privados, `hudFov` e os campos de render state —, `compileShaders` com
`-warnings-as-errors all` + `spirv-val`, `generateShaderRecords` (o registro `WorldPushData` gerado a
partir do novo lane) e `verifyModJar`.

O que não está: **o comportamento em jogo**, que precisa de GPU Vulkan com ray tracing. Especificamente — a
mão bit a bit igual à do vanilla em pose/bobbing/swing; o recorte de silhueta no limite do cone de
direção; estabilidade com denoiser/Frame Generation e com upscalers ligados; e os dois detalhes de mixin
que só resolvem quando os mixins são aplicados (`@Invoker` para `bobHurt`/`bobView` e o nome do campo
`firstPersonHandsAndItemsRenderer`), porque o verificador de ABI confere assinaturas do Minecraft, não o
endereço de um `@Shadow`.
