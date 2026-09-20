# 0.9.0 — entidades na cena RT existente

**Implementação experimental, não validação visual.** Os 14 cenários abaixo
continuam pendentes de GPU. A compilação/testes automatizados não demonstram
presença, textura correta, animação, queda ou ausência de regressão em jogo.

## Investigação primeiro: Minecraft 26.3 real

Referência de leitura: `mc-dataminning/build-changes`, branch `26.3`, commit
`213a1038d61f60468efdfd73c34138cd5b2679ce` (fontes decompiladas de terceiro).
Não redistribuímos essas fontes. O CI resolve Minecraft **26.3** e verifica
membros e call sites no bytecode correspondente; não extrapola APIs 26.2.
`docs/minecraft-26.3-abi.tsv` e `EntityHookContract` registram esses contratos.
Isso não substitui iniciar Minecraft com os mixins aplicados.

### Fluxo seguido

1. `EntityRenderer.extractRenderState` → `EntityRenderState`: ID do Entity,
   tipo, posição interpolada e estado; o renderer de jogador é `AvatarRenderer`.
2. `LevelRenderer.submitFeatures` começa com pose identidade.
   `EntityRenderDispatcher.submit` aplica posição relativa à câmera e render
   offset, e o renderer submete nós Model/Item/MovingBlock. A posse acompanha os
   nós **deferred**; não se presume que submit já emitiu vértices.
3. `ModelFeatureRenderer.prepareModel` chama `setupAnim`, depois
   `Model.renderToBuffer` → `ModelPart.compile` → `Cube.compile`.
   A pose contém transformações hierárquicas, rotação, escala e animação.
   Vértices já saem **posed, relativos à câmera**, não em espaço de seção nem
   pré-rotacionados pela matriz de visão.
4. `ItemFeatureRenderer.prepareMainSubmit` chama `VertexConsumer.putBakedQuad`.
   `QuadInstance` contém as cores por canto, overlay e light escolhidos pelo
   vanilla. O redirect chama a emissão original exatamente uma vez.
5. `RenderTypeFeatureRenderer.getVertexBuilder` / `StagedVertexBuffer` / real
   `BufferBuilder` agrupam vários submits no mesmo draw/buffer. Não existe um
   VBO independente por entidade. Registramos builder base, início/count da
   parte, formato, draw e `PreparedRenderType`.
6. `StagedVertexBuffer.uploadDrawsToBuffers` grava dados em staging e chama
   `CommandEncoder.copyToBuffer`. O staging tem usage **22**, incluindo COPY_SRC;
   o pool final tem usage **40**, sem COPY_SRC. A captura GPU copia o **staging
   real**, com offset `draw.vertexOffset + (builderBase + pieceStart) * stride`,
   validando bytes/limites. Não tenta ler/copiar ilegalmente o pool final.
7. `PreparedRenderType.draw` → `RenderPass.drawIndexed` confirma o draw vanilla
   que utilizou o range/material. Só esses receipts entram na composição da cena.
   Isso prova o caminho de comandos, **não um hit RT ou amostragem GPU**.

A captura é restrita a `RenderSystem.isRenderingLevel`. Preview de inventário,
GUI e mãos de primeira pessoa não viram instâncias falsas no mundo.

### Formatos e índices

| atributo | ENTITY (36 bytes) | BLOCK (28 bytes, moving block) |
|---|---:|---:|
| Position RGB32_FLOAT | 0 | 0 |
| Color RGBA8_UNORM | 12 | 12 |
| UV0 RG32_FLOAT | 16 | 16 |
| UV1 RG16_SINT, overlay | 24 | — |
| UV2 RG16_SINT, light | 28 | 24 |
| Normal RGBA8_SNORM | 32 | — |

Só esses formatos e QUADS são aceitos neste marco. O índice canônico vanilla
`0,1,2,2,3,0` referencia cada quad **já emitido**, sem gerar novas faces. Ordenação
raster de quads transparentes não altera a geometria; o RT ordena interseções por
raio. ENTITY_GLINT_SPECIAL (44 bytes) e topologias desconhecidas são rejeitados.
UV/Color/light/normal não são deduzidos de posição. UV1/Color atuais vão também em
metadados; normal e UV2 do snapshot original são preservados, mas não consumidos
para iluminação neste renderer de albedo. Não há skinning/retesselação CPU própria.

### FallingBlockEntity é parte desta implementação

`FallingBlockRenderer` → `MovingBlockRenderState` → submit MovingBlock, após
translação `(-.5,0,-.5)` → `MovingBlockFeatureRenderer.buildGroup` →
`ModelBlockRenderer` → quads originais via `putBakedQuad`. A emissão/range GPU é
capturada nessa rota. O bloco deixa de depender da malha estática quando vira
entidade: areia, cascalho e concreto em pó são os alvos obrigatórios.
Os pipelines são `SOLID_BLOCK`, `CUTOUT_BLOCK`, `TRANSLUCENT_BLOCK`, não os
pipelines de entidade presumidos a partir do nome Java. Não criamos chunk fake.

## Investigação específica da correção 0.9.0.1 — FallingBlockEntity

A rota normal de Model/Item não é modificada. O diagnóstico adicional é exclusivo
para owners cujo `EntityRenderState` é `FallingBlockRenderState` e acompanha a
mesma sequência real: detecção pelo dispatcher, construção de `MovingBlock` submit,
entrada em `MovingBlockFeatureRenderer.buildGroup`, chamada de `putBakedQuad`,
range do `StagedVertexBuffer`/upload, receipt de draw, BLAS, instância TLAS e
material. A linha `[RT][falling-trace]` informa cada estágio, vertices/triângulos,
BLAS novo/reutilizado, receipt de draw, textura/pipeline, posição interpolada,
matriz 3x4 efetiva e o motivo de descarte.

`FallingBlockRenderer` aplica `(-.5,0,-.5)` antes de criar o submit. O bloco é
emitido em `DefaultVertexFormat.BLOCK` (28 bytes), não em `ENTITY` (36 bytes),
com origem de modelo `(0,0,0)`. O snapshot captura essa pose já aplicada, o
compute remove exatamente essa pose uma única vez no XYZ, e o TLAS reaplica
`translation(camera-anchor) * pose`. Assim a posição mundial não é adicionada
uma segunda vez e a origem do cubo permanece a origem do modelo vanilla.

Para o caso obrigatório `SOLID_BLOCK`, a correção mantém o material ENTITY para
textura/HUD, mas marca o BLAS de FallingBlockEntity como `OPAQUE` no nível Vulkan;
antes o caminho genérico de entidades passava toda geometria capturada pelo BLAS
não-opaco. Falling `CUTOUT_BLOCK`/`TRANSLUCENT_BLOCK` continuam não-opacos, com
alpha/composição existentes. Mobs/player/itens não passam por essa decisão.

O campo `discard` só significa descarte/ausência em um estágio CPU; quando aparece
`NONE_CPU_PIPELINE_COMPLETE_GPU_HUD_REQUIRED`, captura, upload, draw, BLAS, TLAS
e material chegaram ao fim. Nesse caso a interseção não é inferida no CPU: use a
linha `FALLING HIT` do HUD, que é gravada pelo closest-hit do shader no probe
GPU. A implementação não lê vértices nem hit records de volta para a CPU.

## Implementação e recursos

- `EntityCapture` e mixins registram ownership, geometria, pose, cores, material,
  upload e draw; `EntityGeometryManager` mantém cache separado do terreno.
- Chave geométrica: lista dos Cubes vanilla imutáveis de uma parte rígida ou
  BakedQuad original + layout. Entidades diferentes podem compartilhar BLAS.
  Uma entidade pode ter várias partes/instâncias: **BLAS != entidade**.
- Primeiro uso: cópia GPU interleaved do range e `entity-local.comp` desfaz a
  pose nas posições **na GPU**; apenas XYZ é escrito. BLAS local é construído.
  É um compute de conversão na mesma fila/device, não outro renderer/pipeline RT.
- Instância: `translation(camera - anchor) * capturedPose`, empacotada como
  `VkTransformMatrixKHR` **3×4 row-major**. Movimento/rotação/escala/animação rígida
  alteram instâncias; não copiam vértices nem recriam BLAS em cache hits.
  O caminho de cache hit nem cria/submete command batch de captura.
- Geometria nova invalida a chave; sair da lista renderizada remove instâncias e
  agenda destruição de BLAS sem referências. Entrada posterior recaptura conforme
  necessário. Resource/world reset também aposenta o cache independentemente.
- `WorldGeometryManager.compose` concatena SOLID, CUTOUT, TRANSLUCENT e entidades
  em **um TLAS**. Mesma contagem + transform diferente: UPDATE; contagem diferente:
  BUILD; lista igual: REUSE. Materiais são comparados separadamente.
- Mantidos SectionMesh/UberGpuBuffer, lock do dispatcher, identidade owner/mesh,
  offsets, sanity checks e cópia antecipada do terreno antes de reuso de heap.
  A composição final é posterior aos receipts de draw de entidades.
- Barreiras cobrem staging-read → reuso/write, transfer → compute, compute → AS/
  shader e build/update → trace. Destruição usa aposentadoria/fences do vanilla;
  nada de liberar AS ou textura emprestada ainda em voo. Device loss compartilhado
  propaga; falha opcional RT desativa o recurso e preserva fallback vanilla.

## Materiais e compatibilidade do terreno

Mesmos raygen/miss/closest-hit/any-hit e três registros SBT. A tabela de material
passa de 96 para **144 bytes** mantendo o prefixo de terreno; flags ENTITY e
metadados adicionais indicam textura, cutoff, overlay, cores, mirror e ID.
Transformações espelhadas são consideradas no culling.

- `Sampler0`, view, mip range e sampler **reais de PreparedRenderType** são
  emprestados. `Sampler1` fornece overlay quando existe. Nenhuma imagem nova,
  atlas paralelo, downsample, textura genérica ou GPU→CPU readback.
- Banco fixo de **12 pares view/sampler**, incluindo overlays/atlas usados por
  entidades. GLSL seleciona índices literais, sem exigir descriptor indexing ou
  shaderInt64. Slots excedentes são rejeitados e diagnosticados, não substituídos.
- Slots não utilizados recebem descritores válidos, não materiais substitutos.
  Em cena **somente de entidades**, sem draw SOLID, um binding de terreno não usado
  pode apontar para uma textura real da entidade. A presença de qualquer material
  de terreno proíbe esse fallback. Cada hit de entidade continua usando seu slot
  próprio. Isso evita depender de terreno visível ao olhar areia contra o céu.
- TEXEL continua referência em view LOD 0, resolução nativa. LINEAR permanece
  comparação opcional, não correção de textura. Mip chain original é mantida;
  não se promete reproduzir seleção por derivadas/RGSS do raster vanilla.
- Opaque fica opaque. Cutout usa cutoff real do pipeline: entidade/item .1,
  moving-block cutout .5; terreno CUTOUT .5 **inalterado**. Há descarte, não blend
  gradual como substituto de CUTOUT.
- Pipeline genuinamente translucent usa a continuação/composição ordenada já
  existente (até 64 superfícies), não blend unordered em any-hit ou segundo
  renderer. Preserva alpha/tint reais e os limites documentados na 0.8.0.
- Grass overlay/SOLID/CUTOUT do terreno não são reinterpretados como entidade.
  Alterações de material/cor não entram na chave de geometria de entidade.

## Diagnósticos: CPU não é amostragem GPU

Uso normal: mesmo launcher, substituir JAR, mundo normal, `enabled=true`,
`scene=chunks`, `textureSampling=texel`. Sem montagem complexa obrigatória.

Opcional `-Dnativevulkanrt.entityDiagnostics=true` (ou HUD compartilhado com
`materialDiagnostics=true`):

- `[RT][entities]`: descobertas **submetidas pelo vanilla** (não todas do mundo),
  owners capturados/ignorados, motivos específicos, cache geométrico, cópias GPU,
  BLAS construídos/reusados por receipt, aposentados e destruídos realmente pelo
  callback de cleanup, instâncias TLAS, triângulos, transform updates, FallingBlock.
- `reusedEntities`: owners cujas peças aceitas não precisaram BLAS novo neste frame.
  `BLASreusedReceipts` não é contagem de entidades únicas. Triângulos contam instâncias.
- `[RT][entity-geometry]`: amostras limitadas de offset, bytes, stride, vértices,
  triângulos, pipeline/cutoff/culling de novas cópias; CPU receipt explicitamente.
- `[RT][entity-texture]`: label, handle da view, dimensões, mips e sampler reais.
- `[RT][falling]`: ID, **block state** (areia/cascalho/concreto em pó), posição e
  captura/enqueue. Não afirma `sampled=YES`.
- Contadores por camada SOLID/CUTOUT/TRANSLUCENT, section samples e total de
  BLAS/TLAS/triângulos permanecem; total inclui entidades. Reuso do terreno compara
  o snapshot no início do frame, não o cache já comitado após os builds.
- HUD direito: cabeçalho **CPU COUNTS**; abaixo, ID/tipo/posição/textura, `GPU SAMPLED`
  e `FALLING HIT` a partir de um índice gravado por closest-hit no probe GPU. A
  seleção é o raio central, podendo encontrar entidade atrás de transparência.
  Sem hit não preenche uma entidade como se fosse vista. Labels são truncados.

ABI GPU: material 144, probe 288, HUD header 64 / record 160, compute push 80 bytes.
O HUD é GPU-only: nenhum hit é lido no CPU. Sem diagnóstico, usa SSBO vazio
persistente, sem alocar/copiar o buffer de labels a cada frame.

## Limites explícitos

Cobertura inicial: jogador em terceira pessoa, zumbi, vaca/porco/galinha, itens
comuns não-foil, falling sand/gravel/concrete powder e outros modelos que realmente
compartilhem os caminhos/pipelines aceitos. Não é suporte irrestrito a entidades.

Glint/foil (inclusive item inteiro quando a única rota é glint), armadura/efeitos
especiais, consumers com UV mapping/decal, renderizadores especiais, ropes/lines,
labels, olhos emissivos e formatos customizados podem ficar ausentes/parciais.
Há motivos por owner/pipeline/foil/range/formato; não se falsifica uma textura
ou tessela uma substituição. O banco de 12 texturas também pode limitar cenas
com muitas skins/materiais simultâneos. Partes sem geometria compatível são omitidas.

A chave assume a geometria imutável produzida pelo vanilla; mod que muta Cube ou
arrays de BakedQuad **in-place**, sem trocar identidade, não está validado/suportado.
Deformações não rígidas customizadas não são automaticamente cobertas pelo unpose.
Moving-blocks com overlays coplanares arbitrários não têm a mesma garantia do
caminho de grass overlay do terreno; os blocos gravitacionais simples são o alvo.

Não inclui mãos de primeira pessoa, partículas, iluminação RT, sombras,
reflexos/GI, refração avançada, skybox ou View Bobbing. TRANSLUCENT 0.8.0 ainda
precisa de confirmação visual, mesmo após CI verde.

## Verificação disponível e checklist obrigatório

Local: `./gradlew --no-daemon check assemble verifyMinecraftAbi verifyModJar`
não inicia: `JAVA_HOME` ausente / `java` indisponível. GPU não disponível.
No GitHub Actions: testes independentes Ubuntu/Windows; testes buildSrc de
contratos positivos/negativos; ABI 26.3 e contagens dos call sites; testes de
layout real ENTITY/BLOCK e políticas dos pipelines, recuperação de pose e packing
3×4, cache/material, shaders compilados/refletidos e `spirv-val`; inspeção do JAR
real em `build/libs/` e upload `native-vulkan-rt-experimental`.

Comandos: `./gradlew --no-daemon -p buildSrc test` e
`./gradlew --no-daemon check assemble verifyMinecraftAbi verifyModJar`.
Os links/resultados do commit entregue constam no PR #1. Downloads de artifact
neste sandbox falharam no storage TLS: publicação é conferida por registros de
upload e SHA-256 do CI, não por uma alegação de execução do JAR localmente.

| # | Cenário | Aceitação a conferir em jogo | Estado |
|---:|---|---|---|
| 1 | Jogador | F5: corpo/skin/pose emitidos pelo vanilla | **Pendente GPU** |
| 2 | Zumbi | Corpo e textura original, UV/tint/alpha corretos | **Pendente GPU** |
| 3 | Vaca/porco/galinha | Modelos/texturas corretos, sem textura de fallback | **Pendente GPU** |
| 4 | Item dropado | Item comum, textura/tint/recortes e bob/rotação | **Pendente GPU** |
| 5 | Múltiplas entidades | Materiais não se confundem, total TLAS coerente | **Pendente GPU** |
| 6 | Movimento | Posição/rotação/escala seguem; BLAS estável em geometria igual | **Pendente GPU** |
| 7 | Animação vanilla | Pernas/asas/cabeça seguem poses; sem remontagem BLAS por pose | **Pendente GPU** |
| 8 | Entrada/saída de alcance | Sem fantasmas; remoção/retorno e retirement seguros | **Pendente GPU** |
| 9 | Areia caindo | Presente no ar após sair de SectionMesh; FALLING HIT real | **Pendente GPU** |
| 10 | Cascalho/concreto em pó | Queda contínua e material/block state correto | **Pendente GPU** |
| 11 | Coexistência SOLID | Oclusão/profundidade e contadores independentes | **Pendente GPU** |
| 12 | Coexistência CUTOUT | Furos e foliage continuam alpha test; entidade por trás | **Pendente GPU** |
| 13 | Coexistência TRANSLUCENT | Entidade/bloco vistos através de vidro/água, alpha correto | **Pendente GPU** |
| 14 | Regressões de terreno | TEXEL, UV, tint/grass overlay, foliage e cache aceitos | **Pendente GPU** |

A base 444 SOLID BLAS / 444 instâncias / 356364 triângulos já foi demonstrada pelo
usuário em marco anterior. Não se exige provar isso novamente para justificar
captura vanilla. Com entidades, o total pode crescer por partes rígidas/quads;
compare **por camada e por cache**, não force o total novamente a 444.
