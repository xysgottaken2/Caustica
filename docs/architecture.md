# Arquitetura e decisões

**Este documento é projeto técnico, não lista de features implementadas.**
O código atual contém somente o bootstrap e ferramentas de verificação.

## Fluxo proposto

```text
Minecraft 26.3 (extração de render state)
  → RenderPearl frontend → backend Vulkan vanilla
    → negociação RT opcional no device vanilla
    → renderer RT
       geometry stream → BLAS por seção/mesh → TLAS por cena
       materiais/texturas → descriptors → pipeline + SBT
       raygen → visibilidade → iluminação → histórico → denoiser
    → imagem interna → composição/escala no mainRenderTarget
  → mão / efeitos / GUI preservados conforme contrato de depth
  → blit e present vanilla
```

Não criar `VkInstance`, `VkDevice`, janela SDL, surface ou swapchain paralelos.
Não substituir backend por VulkanMod. Backend OpenGL selecionado pelo usuário
significa RT indisponível; o mod não oferece implementação OpenGL.

## Ownership planejado

| Recurso | Proprietário | Política |
|---|---|---|
| Instance, physical/logical device, queues | Minecraft | Empréstimo; nunca destruir pelo mod |
| Allocator VMA vanilla | Minecraft | Preferir augmentar BDA antes de criar, se habilitada no device |
| Command buffers transitórios / pools vanilla | Minecraft | Obter pelo encoder, não guardar após reciclagem |
| Swapchain / janela / eventos | Minecraft | Só observar dimensões/invalidations |
| BLAS/TLAS, buffers RT, SBT, pipeline/layout/descriptors | Mod | Construção transacional e retirement após último uso GPU |
| Atlas vanilla | Minecraft | Referência por geração de resource reload, não ownership |
| Imagens RT / histórico / denoiser | Mod | Ring por frames em voo, recriar em resize/escala |

Separações futuras (adicionar código somente quando usado): integração do
Minecraft; capabilities e contexto emprestado; geometria; AS; pipeline;
materiais; iluminação; histórico; denoiser; upscaler; diagnóstico.
Não gerar 20 classes vazias com nomes de subsistemas inexistentes.

## Gates e estado verdadeiro

Estados futuros: `DISABLED`, `UNSUPPORTED`, `INITIALIZING`, `READY`, `ACTIVE`,
`FAILED`. `SUPPORTED` não significa habilitado no logical device. `READY`
não significa que um trace foi executado. `ACTIVE` requer dispatch válido,
imagem integrada e lifetime válido no frame. A versão atual não oferece essa
máquina de estados: permanece bootstrap/RT OFF.

Vanilla só pode ser cancelado depois de existir caminho RT utilizável para o
frame. Uma falha antes de publicar recursos descarta a construção parcial e
mantém vanilla. Uma falha após enqueue exige respeitar completion, não
liberar imediatamente nem tentar desfazer comandos já submetidos.

## Geometria e materiais

Capturar o resultado já produzido por `SectionCompiler` ou a etapa de upload
`addSectionBuffersToUberBuffer`. A seleção final entre cópia da mesh existente
e reuso GPU depende dos usages e da validade dos offsets no `UberGpuBuffer`.
Nenhum caminho pode exigir nova tesselação completa só para RT.

Manter ID estável por seção + geração, layer, dimensões/origem, transformação,
triângulos, UV, cor/tint, normal, iluminação e referência de material/atlas.
Indices implícitos de quads devem ser expandidos corretamente. A mesh vanilla
não necessariamente contém block-state/material ID suficiente para emissive
ou PBR; capturar metadados adicionais na emissão original, sem re-meshing.

Não usar apenas a lista raster visível: sombras/reflexos podem atingir chunks
fora do frustum. Residency RT usa raio configurável limitado aos dados
carregados, com orçamento e fila incremental. Invalidar ao trocar mundo,
dimensão, geração da mesh, atlas/resource pack ou representação de material.

Começar com materiais vanilla explicitamente definidos: albedo, normal,
roughness, metallic, emissive e opacity. Emissive não pode ser inferido só de
cor clara. Material/triangle ABI deve ser versionada antes de LabPBR.

## Dependências mínimas

Fabric Loader, sem Fabric API enquanto não necessário. LWJGL fornecido pelo
jogo; não embutir outra versão. Nenhum SDK proprietário ou JNI na base.
Denoiser independente de upscaler; DLSS/FSR/XeSS só como módulos posteriores
com capability/licença/runtime próprios. Native upscale é o caminho inicial.
