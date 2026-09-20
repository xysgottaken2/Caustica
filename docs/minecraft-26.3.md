# Minecraft Java 26.3 — análise pré-implementação

Data da consulta: **2026-09-20 (UTC)**. Estado: análise estática das fontes
consultadas; confirmação de ABI no binário oficial e testes de execução pendentes.
Não confundir um nome encontrado em decompilação com um Mixin validado em runtime.

## 1. Inventário antes de qualquer alteração

Branch de trabalho: `arena/01a0bf16-test-3`, base
`8b0f663f88292193ca27b65f04033feec5430f3a`.
O repositório continha somente `README.md`, com `# test-3`, além de `.git/`.
Não havia Java, build, loader, workflows, código nativo ou shaders a preservar.
O working tree estava limpo. A pesquisa foi feita fora do repositório; somente
após inspecionar as referências foram criados build/workflows e o bootstrap.

## 2. Evidências e proveniência

### Fontes oficiais

- [Manifesto Mojang](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json):
  `26.3`, `release`, publicada em `2026-09-15T11:23:02+00:00`.
- [Metadata exata 26.3](https://piston-meta.mojang.com/v1/packages/96c00d95a31328714d3811cfade2804bb050e455/26.3.json):
  Java **25**, componente `java-runtime-epsilon`.
- Cliente: SHA-1 `e877b6a07acd633fb3bb475002175cec036e7b87`, 41.483.720 bytes,
  [download oficial](https://piston-data.mojang.com/v1/objects/e877b6a07acd633fb3bb475002175cec036e7b87/client.jar).
- A metadata não publica `client_mappings`; o código 26.x é distribuído com nomes
  legíveis. Não aplicar Yarn nem mappings antigos por hábito.
- LWJGL **3.4.3**, incluindo `lwjgl-sdl`, `lwjgl-vulkan`, `lwjgl-shaderc`, `lwjgl-vma`.
  A metadata 26.2 usa **3.4.1** e GLFW.
- [Fabric metadata 26.3](https://meta.fabricmc.net/v2/versions/loader/26.3):
  Loader **0.19.5** estável. `intermediary:0.0.0` não deve ser tratado como mappings
  Yarn a resolver. O projeto usa o plugin Loom sem remapeamento.
- [Loom 1.18.2](https://github.com/FabricMC/fabric-loom/tree/6056fe796865f1d88149e93c62ddf2158aa30782):
  `LoomNoRemapGradlePlugin.NAME = net.fabricmc.fabric-loom`; wrapper upstream 9.7.0.

### Fontes decompiladas inspecionadas

**26.3:** [mc-dataminning/build-changes, tag 26.3](https://github.com/mc-dataminning/build-changes/tree/213a1038d61f60468efdfd73c34138cd5b2679ce/src),
commit `213a1038d61f60468efdfd73c34138cd5b2679ce`.
Seu `version.json` identifica a release 26.3 e o mesmo hash de cliente do
manifesto oficial. Foram inspecionados backend, frontend, shaders, janela,
frame loop, renderização do mundo, câmera, extração e upload de seções.

**26.2:** [VihFinal/source-MC-26.2](https://github.com/VihFinal/source-MC-26.2/tree/1cb3ee6216cd755a0106cc46089e2821dd73b97a),
commit `1cb3ee6216cd755a0106cc46089e2821dd73b97a`, diretório
`minecraft-clientOnly-043a8b3edf-26.2-sources`.
A tag 26.2 de `build-changes` contém metadata, mas não `src/`; ela **não** foi
usada como se contivesse decompilação. As assinaturas 26.2 foram cruzadas com
os mixins do Caustica.

**Limite de confiança:** são decompilações de terceiros, não uma decompilação
local autenticada pelo hash do JAR. O download binário oficial falhou neste
ambiente. Antes de instalar hooks, executar `verifyMinecraftAbi`, gerar fontes
locais com Loom e validar os injection points no cliente real. Fontes
Minecraft não foram copiadas para este projeto.

### Referências auxiliares solicitadas

- [Caustica](caustica-analysis.md): commit fixado e análise específica.
- [MinecraftSourcesGenerator](https://github.com/Bram1903/MinecraftSourcesGenerator):
  é ferramenta, não repositório de fontes; gera versões 26.x sem remapear e usa
  Vineflower. Não foi executada nem se registrou aceite de EULA pelo usuário.
- [Baritone 26.3](https://github.com/dysnasia/baritone-26.3/tree/60ebece82e74bdb9a4e3c3011c6f7bd7897f24ac):
  Java 25, Loader 0.19.5, imports RenderPearl. Seu workaround Unimined extrai
  RenderPearl de um caminho de cache; **não copiamos esse caminho local**.
- [NeoForge](https://github.com/neoforged/NeoForge): `gradle.properties` consultado
  declara Minecraft 26.3/Java 25. Não usado como implementação Vulkan nem como
  dependência deste projeto; Fabric reduz o escopo do bootstrap.

## 3. Mapa de responsabilidades 26.3

Os nomes abaixo foram encontrados na árvore 26.3 acima. Pacotes abreviados:

- **VK** = `com.mojang.renderpearl.backend.vulkan`
- **RP** = `com.mojang.renderpearl`
- **R** = `net.minecraft.client.renderer`

| Responsabilidade | Classe / método ou campo observado | Consequência para RT |
|---|---|---|
| Inicialização | `net.minecraft.client.Minecraft` construtor; `com.mojang.blaze3d.systems.RenderSystem.initRenderer(GpuDevice)` | A inicialização Fabric não implica device pronto |
| Loader Vulkan / SDL | `VK.VulkanBackend.loadLibrary()`, `unloadLibrary()`, `createWindow(String,int,int,long)` | Usar loader e janela vanilla; não chamar GLFW |
| Instância | `VK.VulkanInstance.<init>(int,boolean,boolean)`, `vkInstance()` | Usa `SDL_Vulkan_GetInstanceExtensions` e `vkCreateInstance`; solicita Vulkan 1.2 |
| Physical device | `VK.VulkanBackend.findPhysicalDevice(VulkanInstance,Set,Set)`, `checkDeviceSuitability(...)` | Manter seleção vanilla; RT não pode tornar GPU sem RT inelegível |
| Logical device | `VK.VulkanBackend.createDevice(GpuDebugOptions)` e helper privado `createDevice(FeatureSet,VulkanPhysicalDevice)` | Features devem entrar antes de `vkCreateDevice` |
| Features | `VK.VulkanFeatureSets.optionalFeatureSets()`; `VK.init.FeatureSet.isSupported(...)`, `composite(...)` | Candidato a negociação opcional, não hook instalado |
| Acesso ao backend | `RP.frontend.FrontendGpuDevice.backend` (privado); `VK.VulkanDevice.vkDevice()` | Accessor teria como alvo o frontend concreto, não a interface `GpuDevice` |
| Allocator | `VK.VulkanBackend.createVma(VkDevice)`; `VK.VulkanDevice.vma()` | A criação consultada não habilita a flag VMA de BDA |
| Queues | `VK.VulkanPhysicalDevice.queueFamilyCreateInfoMap()`, `graphicsQueueFamilyAndIndex()`, `computeQueueFamilyAndIndex()`, `transferQueueFamilyAndIndex()` | Família/índice reais; não assumir índices 0/1 |
| Queues em uso | `VK.VulkanDevice.graphicsQueue()/computeQueue()/transferQueue()`; `VK.VulkanQueue.beginSubmit()` | Reusar sincronização/ownership vanilla |
| Command pools | `VK.VulkanCommandPool.allocateBuffer()`, `reset()`, `destroy()` | Encoder recicla dois pools conforme submissões concluídas |
| Command buffers | `VK.VulkanCommandEncoder.allocateAndBeginTransientCommandBuffer()`, `execute(VkCommandBuffer)` | Gravar fora de render pass, finalizar antes de `execute` |
| Sincronização | `VK.VulkanCommandEncoder.waitSemaphore/signalSemaphore/submit`, `queueForDestroy(Destroyable)`; `VK.VulkanQueue.Submission` | Timeline semaphores, reciclagem e destruição diferida |
| Surface / swapchain | `VK.VulkanGpuSurface.<init>(VulkanDevice,long)`, `configure`, `acquireNextTexture`, `blitFromTexture`, `present` | Surface SDL e swapchain continuam vanilla |
| Images / views | `VK.VulkanGpuTexture.vkImage()`; `VK.VulkanGpuTextureView.vkImageView()` | Não assumir que todo target tem usage STORAGE |
| Render targets / depth | `com.mojang.blaze3d.pipeline.RenderTarget.createBuffers/resize/destroyBuffers`; `R.LevelTargetBundle`; `R.LevelRenderer.render` | Targets OIT/depth próprios; depth clear observado é 0.0 |
| Descriptors | `VK.VulkanRenderPipeline.compile`; `VK.VulkanRenderPass` usa `vkCmdPushDescriptorSetKHR` | Descriptors vanilla não são um pool genérico para AS/storage images RT |
| Pipeline layouts | `VK.VulkanRenderPipeline.compile/pipelineLayout()` | RT precisa de layout/SBT próprios, no mesmo device |
| Shader compilation | `RP.frontend.shaders.GlslCompiler.compileToSpv`; `PipelineBuilder`; `SPIRVModule`; `VK.VulkanRenderPipeline.compile` | Compilação ShaderC no frontend, criação de shader modules no backend |
| Chunk generation | `R.chunk.SectionCompiler.compile`; `SectionRenderDispatcher.RenderSection.RebuildTask.doTask` | Aproveitar mesh produzida, não re-tesselar o mundo |
| Chunk upload / lifetime | `R.chunk.SectionRenderDispatcher.uploadTerrainBuffersToGpu`, `getRenderSectionSlice`; `RenderSection.addSectionBuffersToUberBuffer`, `reset`, `releaseSectionMesh` | Buffers compartilhados, offsets mutáveis e callbacks de upload |
| Terrain draw | `R.LevelRenderer.extractSectionDrawGroups`, `prepareChunkRenders`, `prepareChunkRendersIndirect`, `isChunkRenderingUsingMultiDrawIndirect` | Draw groups têm baseVertex/firstIndex/sectionInfo; não confundir draw index com section ID |
| Entidades / block entities | `R.extract.LevelExtractor.extractVisibleEntities/extractVisibleBlockEntities`; `R.LevelRenderer.submitEntities/submitBlockEntities`; `R.feature.FeatureRenderDispatcher.prepareFrame` | Consumir estados extraídos, sem ler mundo mutável da thread de render |
| Partículas | `R.LevelRenderer.submitFeatures` → `levelRenderState.particlesRenderState.submit`; `R.feature.QuadParticleFeatureRenderer` | Geometria dinâmica e alpha exigem tratamento separado |
| Primeira pessoa | `R.GameRenderer.renderItemInHand(CameraRenderState,PlayerRenderState,GpuTextureView)` → `R.FirstPersonHandsAndItemsRenderer.submitHandsWithItems(float,PoseStack,SubmitNodeCollector,PlayerRenderState,FirstPersonHandsAndItemsRenderState)` → `ItemStackRenderState.submit` → `FeatureRenderDispatcher.prepareFrame/renderAllFeatures` | Passo separado `Item in hand`; não é `EntityRenderDispatcher` nem geometria mundial |

### Contrato de captura instalado nesta etapa

A fronteira confirmada pelo cliente/RenderPearl usado pelo projeto é a mesma
fronteira de upload já usada pelos recursos de entidades: `StagedVertexBuffer`
expõe `Draw.format`, `Draw.vertexOffset`, `Draw.vertexCount` e
`Draw.primitiveTopology`; `uploadDrawsToBuffers` copia a alocação vanilla; e
`PreparedRenderType.draw` é a emissão final do draw. Para partículas, o formato
real observado na API oficial é `DefaultVertexFormat.PARTICLE`, com a ordem
`Position`, `UV0`, `Color`, `UV2/lightmap`, stride de 28 bytes, e topologia
`QUADS` (quatro vértices, seis triângulos no BLAS). O caminho instalado é:

`ParticleEngine.extract(ParticlesRenderState, Frustum, Camera, partialTick)` →
`ParticlesRenderState.submit` → `QuadParticleFeatureRenderer` →
`StagedVertexBuffer.getVertexBuilder`/upload → `PreparedRenderType.draw`.

`ParticleCapture` apenas registra o range efetivamente copiado e o material
`PreparedRenderType`; a geometria é copiada GPU→GPU para
`ParticleGeometryManager`. Os quads já saem do vanilla orientados para a
câmera, portanto o TLAS aplica somente `camera - anchor`, sem multiplicar a
matriz de câmera. O material usa o atlas `Sampler0` original, o sampler
capturado e o bit `PARTICLE` no row de 144 bytes; `CUTOUT`/`TRANSLUCENT` são
selecionados pelos estados reais do `RenderPipeline`, e a textura de partículas
fica em um binding separado do atlas de terreno.

O cliente oficial 26.3 não foi redistribuído nem pôde ser baixado/autenticado
neste ambiente por falha TLS; os nomes de extração acima continuam sujeitos ao
`verifyMinecraftAbi`/CI e a execução no jogo. A captura de `StagedVertexBuffer`
é intencionalmente independente desses nomes de estado e falha fechada quando
o formato, topologia, textura ou draw não coincide. Os contadores
`[RT][particles]` são observações CPU de upload/enqueue; o center-ray/probe do
shader continua sendo a única fonte de hit/interseção RT, inclusive quando o
row selecionado é uma instância de partícula no TLAS compartilhado.

### Caminho de viewmodel confirmado em 26.3

A fonte 26.3 fixada em `mc-dataminning/build-changes` confirma que o método
`GameRenderer.renderItemInHand` cria o `PoseStack` da câmera, aplica
`CameraRenderState.viewRotationMatrix`, bob de dano/visão e FOV HUD, então chama
`FirstPersonHandsAndItemsRenderer.submitHandsWithItems`. Este último escolhe
main hand/offhand conforme `HandRenderSelection`, preserva os
`ItemStackRenderState` resolvidos pelo `ItemModelResolver` e submete braço,
modelos de item e modelos especiais ao mesmo `SubmitNodeCollector`. Em seguida
`FeatureRenderDispatcher.prepareFrame` faz o upload do `StagedVertexBuffer` e
`renderAllFeatures` executa o render pass vanilla chamado `Item in hand`.

`FirstPersonHandsAndItemsRendererMixin` registra exatamente a fronteira de
emissão, e `ViewmodelCapture` observa builders, cópias e `PreparedRenderType.draw`
dentro da janela de `renderItemInHand`. O snapshot preserva item principal,
offhand, seleção de mãos, alturas de troca, rotações/bobbing e estado de
scoping; nenhum atlas, material ou tesselação paralelo é criado. A textura e
o material continuam sendo os objetos vanilla e o caminho de textura RT,
quando aplicável, permanece `textureSampling=texel`.

A captura permanece deliberadamente isolada: o RT do mundo é gravado antes do
passo vanilla `Item in hand`, o viewmodel não é adicionado ao `EntityGeometryManager`,
não vira entidade/BLAS/TLAS mundial e não é presumido como bloqueador de
sombras. O resultado mostrado pelos receipts de `ViewmodelCapture` é CPU/
enqueue/draw vanilla, não um hit GPU; a composição visual final das mãos e
itens continua sendo feita pelo pass vanilla. Isso evita gerar sombra mundial
sem uma confirmação de integração do comportamento vanilla, preservando FOV,
transformações, animações, escala, UV, Color, alpha e texturas especiais.

### Sombras por ray tracing

Depois de cada hit primário válido, `chunks.rgen` calcula uma fonte direcional
fixa equivalente ao Sol e lança um segundo `traceRayEXT` contra o **mesmo**
`scene`/TLAS, com offset normal de `0.002` e `rayTMin=0.002`. O payload
secundário usa `terminateRayEXT` no primeiro bloqueador aceito: SOLID bloqueia
no closest-hit mesmo quando o BLAS é opaco; CUTOUT passa pelo any-hit existente,
usa a mesma amostra de atlas/cor e mantém `discard`/alpha test antes de bloquear;
entidades podem bloquear pelo mesmo caminho. O resultado aplica apenas
iluminação direta e hard shadow, sem GI, bounce, caustics ou soft shadow.

TRANSLUCENT não é convertido silenciosamente em opaco: o any-hit do shadow ray
ignora a camada translúcida e conta esse motivo. A composição translúcida já
existente continua no raygen; composição completa de transparência para sombras
fica explicitamente fora deste marco. No probe GPU opcional, `shadowStats`
registra rays lançados/bloqueados/livres/superfícies sombreadas e
`shadowReasons` registra candidatos CUTOUT descartados e camadas translúcidas
ignoradas. O HUD `LIGHT`/`SHADOW` só é escrito após essa execução GPU do
center-ray; diagnósticos CPU não são promovidos a hit RT.

| Transparência | `R.LevelRenderer.prepareTranslucents/executeOit/executeClassicTransparency/executeOitWaterMask` | Não substituir OIT por simples alpha no closest-hit |
| Sky / fog | `R.SkyRenderer.extractRenderState/render`; `R.fog.FogRenderer.updateBuffer/getBuffer`; `CameraRenderState.fogData` | Consumir ambiente por dimensão, não fixar sol Overworld |
| Pós-processamento | `R.GameRenderer.preparePostEffects/applyPostEffects`; `R.PostChain.process/addToFrame` | Efeitos podem exigir depth consistente |
| Câmera | `R.GameRenderer.extractCamera/renderLevel`; `R.state.level.CameraRenderState.projectionMatrix/viewRotationMatrix/pos` | Capturar projeção final após bobbing/náusea; não usar matriz pré-efeito |
| Resolução interna | `R.GameRenderer.resize/mainRenderTarget`; `R.state.WindowRenderState`; `RenderTarget.resize` | Imagem RT interna separada; GUI permanece resolução nativa |
| Apresentação | `Minecraft.renderFrame(boolean)` → blit do main target → encoder `submit()` → surface `present()` | Compor no target antes da apresentação vanilla |
| Janela / resize / fullscreen | `com.mojang.blaze3d.platform.Window.handleEvent(SDL_Event)`, `queryFramebufferSize`, `updateFullscreenIfChanged` | Não criar outra janela nem superfície |

O contrato `minecraft-26.3-abi.tsv` seleciona **35 assinaturas críticas**, não
todo esse mapa. Verifica existência e descritor; não garante semântica,
visibilidade adequada, locals de Mixin ou ordem de execução.

## 4. Diferenças comprovadas na comparação 26.2 → 26.3

| 26.2 | 26.3 | Ação |
|---|---|---|
| `com.mojang.blaze3d.vulkan.*` | `com.mojang.renderpearl.backend.vulkan.*` | Revalidar todos os owners de mixins |
| `blaze3d.systems.GpuDevice`, classe com backend | `renderpearl.api.device.GpuDevice`, interface; `FrontendGpuDevice`, implementação | Accessor do Caustica não é transferível literalmente |
| `createDevice(long,ShaderSource,GpuDebugOptions,Runnable)` | `createDevice(GpuDebugOptions)` | Descritor antigo deixa de existir |
| Helper `createDevice(Collection,VulkanPhysicalDevice,Set)` | Helper `createDevice(FeatureSet,VulkanPhysicalDevice)` | Negociação passa por conjunto nomeado de extensões/features |
| `VulkanPNextStruct(int,int)` | `VulkanPNextStruct(Class<?>)` ou construtor canônico `(Class,int,int)` | Não copiar offsets/sType da integração 26.2 |
| `VulkanFeature(struct,name,offset)` | Construtor conveniente `(struct,name)` resolve offset | Evitar construir manualmente cadeia incompatível |
| Shader cache/compiler no `VulkanDevice` | `FrontendGpuDevice` possui `PipelineBuilder`; compilador em `frontend.shaders` | Shaders RT terão caminho build-time próprio |
| GLFW window hints, `glfwVulkanSupported` | `loadLibrary/createWindow`, SDLVulkan/SDLVideo | Nenhuma API GLFW no mod |
| `GameRenderer.render(DeltaTracker,boolean)` | Separação `extract(DeltaTracker,boolean)` / `render()` | Não portar hook `render(DeltaTracker,boolean)` |
| `renderLevel(DeltaTracker)` | `renderLevel()` | Usar render state extraído |
| `LevelRenderer.render(...DeltaTracker,...Matrix4fc,...)` | Sem esses dois argumentos; inclui `consistentDepthRequired` | Depth pós-efeitos/3D HUD passa a fazer parte do contrato |
| Compilação/upload antigos | Terrain MDI e grupos por buffers compartilhados observados em 26.3 | Rastrear mesh generation e offsets, não copiar interceptação de draw |

### Mudanças de shaders e armadilha nas notas de snapshot

As notas oficiais de [snapshot 4](https://www.minecraft.net/en-us/article/minecraft-26-3-snapshot-4)
confirmam SDL3; [snapshot 5](https://www.minecraft.net/en-us/article/minecraft-26-3-snapshot-5)
confirma ShaderC também em OpenGL, `#include`, locations explícitas e mudanças
de defines. [Snapshot 6](https://www.minecraft.net/en-us/article/minecraft-26-3-snapshot-6)
descreve terrain MDI e reorganização de uniforms.

**A release deve prevalecer:** a fonte 26.3 de `GlslCompiler` usa
`RENDERPEARL_DEPTH_IS_ZERO_TO_ONE`, enquanto notas anteriores falam em
`RENDERPEARL_IS_ZERO_TO_ONE`. Também foram observados
`RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE` e
`RENDERPEARL_INSTANCE_INDEX_INCLUDES_BASE_INSTANCE`. Não converter macros só
pelo texto de um snapshot. Validar layout dos uniforms e atributos reais;
locations do raster e locations dos ray payloads não são a mesma interface.

## 5. Decisão de avanço

A infraestrutura de partículas agora está isolada em `ParticleCapture` e
`ParticleGeometryManager`; não há alteração em `ParticleCapture`, `ParticleGeometryManager`
ou `FallingBlockEntity` nesta etapa. Sombras compartilham o TLAS existente e o
viewmodel permanece no pass vanilla separado, sem ser entidade ou geometria
mundial. O gate é `verifyMinecraftAbi`, compilação Java/ShaderC/SPIR-V e
execução no cliente 26.3. CI valida apenas build, ABI, testes CPU e SPIR-V;
**CI não equivale a validação visual** de sombras, alpha/OIT, FOV, troca de
item, animação, offhand, especial, transparência ou hit RT. A validação final
deve executar no jogo esses cenários, incluindo SOLID/CUTOUT/entidades
bloqueando e TRANSLUCENT não-opaco.
