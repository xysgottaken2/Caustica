# Estudo arquitetural do Caustica 26.2

Referência fixada: [xysgottaken2/Caustica](https://github.com/xysgottaken2/Caustica/tree/3c54fc201f93598246db62ebb1947dfb1e274e92),
commit `3c54fc201f93598246db62ebb1947dfb1e274e92` (2026-09-02).
Estudo por leitura de código, **não** execução ou validação de performance.

## Build e organização

- `gradle.properties`: MC 26.2, Loader 0.19.3, Loom 1.17-SNAPSHOT,
  Fabric API 0.153.0+26.2. Não transportar essas versões para 26.3.
- `settings.gradle`: Fabric Maven, Maven Central e Plugin Portal.
- `build.gradle`: Java 25, runs cliente/servidor; `CompileShaders` compila GLSL
  via glslang e Slang via slangc, valida SPIR-V e publica em diretório gerado.
  Verifica colisões de nomes; usa saída temporária antes de publicar. Mantém
  variante sem SER e variante opcional SER. Compilar no build é reutilizável
  como princípio, mas o projeto novo priorizará ShaderC para GLSL.
- `buildSrc/.../GenerateShaderRecords.groovy`: gera estruturas Java a partir
  da reflexão de layouts dos shaders Slang, reduzindo divergência de ABI.
  Não é necessário para um bootstrap sem shader/payload.
- `.github/workflows/ci.yml`: compila shims Windows/Linux, instala ferramentas,
  usa SDK Vulkan fixado/checksum e reúne artefatos no JAR. DLSS/FSR/NRD/XeSS
  ampliam muito a superfície de dependências; não são requisitos de RT básico.
- **`build.sh` não existe neste commit.** Foram localizados `runClient.ps1` e
  `profileMinecraft.ps1`; o script cliente chama `runClient` com Vulkan e
  debug labels. Não presumir scripts citados no pedido como arquivos existentes.

## Device, recursos e frame

| Área | Implementação estudada | Lição / limite para 26.3 |
|---|---|---|
| Negociação | `mixin/VulkanBackendMixin`, `rt/RtDeviceBringup` | Extensões e pNext antes de `vkCreateDevice`; hooks 26.2 inválidos em 26.3 |
| Instância | `mixin/VulkanInstanceMixin` | Augmenta instância vanilla para HDR quando disponível; não cria outro backend |
| Contexto | `rt/RtContext` | Usa VkDevice/queues existentes; cria allocator BDA separado porque vanilla não o habilita |
| Ownership / GPU executor | `rt/RtGpuExecutor`, `RtFramePresenter`, `RtComposite` | Separar preparação assíncrona, consumo graphics e aposentadoria de recursos |
| Buffers / images | `rt/accel/RtBuffer`, `RtImage` | Usage correto, staging, flush de memória host, alinhamento e destruição |
| AS | `rt/accel/RtAccel` | Usa `vkCmdBuildAccelerationStructuresKHR`, scratch, compactação, instance data; não é RT simulado |
| Pipeline / SBT | `rt/pipeline/RtPipeline` | Cria `vkCreateRayTracingPipelinesKHR`, obtém group handles e chama `vkCmdTraceRaysKHR` |
| Interceptação | `mixin/LevelRendererMixin`, `GameRendererMixin`; `client/VanillaRenderController` | Cancela mundo segundo estado de readiness e compõe antes de mão/UI; assinaturas mudaram |
| Terrain | `rt/terrain/RtTerrainMesher`, `RtSectionBuilder`, `RtTerrain`, `RtSectionTable` | Produz geometria própria, dados por seção, staging e BLAS. Não copiar remeshing por hábito |
| Entities | `rt/entity/RtEntityCapture`, `RtEntities`, `RtParticleCapture` | Captura e lifetime distintos do terreno; etapa posterior |
| Materiais | `rt/material/RtMaterialAbi`, registry, LabPBR, texture pages | ABI explícita, índices de material e ownership de texturas precisam testes |
| Denoising | `rt/pipeline/RtSvgfDenoiser`, `display/svgf_reproject.comp`, `svgf_atrous.comp` | Reprojeção e filtro separados; NRD não precisa ser parte da base |

`RtDeviceBringup` exige também position fetch e ray query na base desse
commit, além de reservar compute queue para seu executor. Isso não será
transformado em requisito universal para NVIDIA/AMD/Intel. Uma imagem RT
básica pode usar a graphics+compute queue vanilla, vértices explícitos e trace
rays sem ray query. Comentários do Caustica sobre alvo RTX/Vulkan 1.4 não
substituem a inspeção vanilla: a instância e o piso observados em 26.3 são 1.2.

## Shaders

O mundo é implementado principalmente em **Slang**, não como um conjunto GLSL
idêntico ao exemplo do pedido. `world_primary.rgen.slang` e `world.rgen.slang`
separam trabalho primário/continuações. `world.rchit.slang` recupera tabelas por
instance ID e materiais com barycentrics/UV; iluminação é conduzida pelo
raygen. `world.rahit.slang` trata cutout e transmissão de shadow rays;
`world.rmiss.slang` e `shadow.rmiss.slang` têm finalidades distintas.
`world_common`, módulos de lighting, fog, water e materiais compartilham ABI.

Elementos a aproveitar conceitualmente: separar shader stages, validar SBT,
manter payload compatível entre todos os estágios alcançáveis, evitar exigir
position fetch para recuperar posições, compilar e validar offline. Não
copiar shaders complexos antes de um triângulo comprovado.

## Native / NGX

`native/ngx_shim/ngx_shim.cpp` expõe C ABI plana sobre SDK C++/macros NGX;
`NgxLibrary` usa **Java FFM** (`Linker`, `SymbolLookup`, downcall handles),
não um JNI genérico para todo o renderer. O CMake depende de `DLSS_SDK` e
`VULKAN_SDK`, liga biblioteca estática NGX e gera shim por plataforma.
Há também `fsr_shim`, `nrd_shim` e `xess_shim`.

Isso resolve interoperabilidade de SDK específico, não uma limitação que
obrigue RT Vulkan a ser C++. LWJGL já expõe as operações KHR necessárias.
A base nova não copiará DLLs presentes na referência, não exigirá SDK NVIDIA
e não criará `native/` sem uma necessidade executável e licença revisada.

## Portabilidade e licença

Caustica é LGPL-3.0-or-later; componentes NVIDIA têm condições próprias.
Nenhum fonte ou shader foi copiado. A arquitetura proposta é original,
baseada na análise das APIs reais e em operações Vulkan padronizadas.
