# Integração Vulkan nativa — contrato proposto

Estado: **nenhum hook Vulkan foi instalado**. Consulte o mapa de classes em
[minecraft-26.3.md](minecraft-26.3.md). As regras abaixo orientam as fases 3–9.

## Negociação em duas etapas

1. Consultar o **physical device escolhido pelo Minecraft**, extensões,
   `vkGetPhysicalDeviceFeatures2` e propriedades/limites correspondentes.
2. Somente se os requisitos do milestone forem atendidos, acrescentar features
   antes de `vkCreateDevice`, preservando integralmente as features vanilla.
3. Registrar exatamente o que foi habilitado. Após criar o device, verificar
   entrypoints RT, limites e estado habilitado; nunca inferir enablement apenas
   da lista de extensões suportadas.

O candidato 26.3 é um `FeatureSet` opcional ou composição controlada do conjunto
passado ao helper `createDevice(FeatureSet,VulkanPhysicalDevice)`. Não elevar
RT a requisito de `checkDeviceSuitability`, nem forçar seleção de GPU NVIDIA.
A criação RT ampliada precisa permitir tentativa vanilla caso falhe, sem
vazar resources parciais; nenhum hook deste documento implementa isso ainda.

### Requisitos por estágio

| Recurso | Política |
|---|---|
| `VK_KHR_acceleration_structure` + `accelerationStructure` | Obrigatório para AS |
| `VK_KHR_ray_tracing_pipeline` + `rayTracingPipeline` | Obrigatório para pipeline/trace rays |
| `VK_KHR_deferred_host_operations` | Dependência de AS/pipeline |
| `bufferDeviceAddress` | Obrigatório; extensão promovida ao core 1.2 |
| SPIR-V 1.4 / shader float controls | Considerar promoção ao core 1.2; não exigir nomes KHR redundantes |
| `descriptorIndexing` | Consultar; exigir apenas subfeatures realmente usadas no futuro bindless |
| `shaderInt64` | Consultar; exigir se ABI/shader de BDA usar inteiros de 64 bits; não presumir suporte |
| `rayQuery` | Consultar quando extensão/core correspondente estiver disponível; opcional para trace-rays básico |
| RT position fetch / opacity micromaps / SER | Opcionais, fora do primeiro milestone |

Vanilla solicita Vulkan **1.2**; não depender silenciosamente de funções core
1.3/1.4. Synchronization2 já é usada via KHR; respeitar as funções realmente
carregadas. Na query de pNext, somente encadear structs apropriadas às
extensões/versão. Evitar duplicar structs Vulkan12 de BDA/descriptor indexing.

`VkPhysicalDeviceRayTracingPipelinePropertiesKHR` informa tamanho e
alinhamentos SBT, max stride e profundidade. Consultar também
`minAccelerationStructureScratchOffsetAlignment`, limites de instances,
primitives e geometries. Alinhar **endereços**, não só offsets/tamanhos.

## Allocator e buffers

`VulkanBackend.createVma` consultado não usa
`VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT`. Reutilizar o allocator sem
essa flag não resolve BDA. Preferência: habilitá-la na criação vanilla **somente
quando bufferDeviceAddress foi habilitada**. Se não for possível, documentar
por que um allocator RT separado no mesmo device é necessário; isso não é
segundo backend, mas é ownership adicional.

Buffers de input AS precisam de
`ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR` e device address;
backing AS usa `ACCELERATION_STRUCTURE_STORAGE_BIT_KHR`; scratch usa storage
buffer/device address; SBT usa `SHADER_BINDING_TABLE_BIT_KHR`/device address.
Vértices vanilla sem esses usages não podem ser usados diretamente só porque
há um handle. Uma cópia GPU também requer TRANSFER_SRC no buffer de origem.
Validar `VulkanConst.bufferUsageToVk` e a vida dos offsets antes de decidir.

Staging persistente deve respeitar memória coerente ou flush alinhado a
`nonCoherentAtomSize`. Uploads e recursos em voo não podem ser sobrescritos.
Nunca fazer readback de toda a geometria para obter um buffer RT.

## Command recording / barriers

Primeiro milestone usa a graphics queue existente, que vanilla seleciona com
capacidade graphics+compute+present. Não exigir queue dedicada. Depois de
validado, async compute exigirá ownership transfer se as famílias diferirem.

`VulkanCommandEncoder.allocateAndBeginTransientCommandBuffer()` usa pools
vanilla. Gravar fora de render pass, finalizar o buffer e usar `execute()`;
isso preserva ordem com os buffers vanilla anteriores. O encoder faz submit;
o mod não deve submeter concorrentemente à mesma queue por fora do contrato.
Não liberar ou resetar esses command buffers/pools.

Dependências mínimas explícitas:

- upload TRANSFER_WRITE → AS_BUILD / SHADER_READ dos inputs;
- BLAS AS_WRITE → TLAS AS_READ;
- scratch reuse AS_BUILD read/write → próximo AS_BUILD read/write;
- TLAS AS_WRITE → RAY_TRACING_SHADER / AS_READ;
- trace storage-image write → composição transfer/sample read;
- composição write → consumidor vanilla, com layout esperado restaurado.

Os bits de acesso de leitura de vértices não são automaticamente AS_READ:
AS_READ descreve a estrutura de aceleração. A mesma queue não dispensa memory
barriers. Preferir barriers específicas; não usar `vkDeviceWaitIdle` por frame.

## Images, depth e present

As imagens Vulkan vanilla observadas transitam inicialmente para GENERAL.
Não inferir que esse layout pode ser alterado sem conhecimento do encoder.
Não assumir usage STORAGE na imagem do main target. Criar saída RT com uso
correto, compor/copiar para target compatível e manter surface/swapchain vanilla.
Verificar storage format e, se usado blit, suporte do formato/filter.

`Minecraft.renderFrame` faz acquire → render → blit → submit → present.
Resize/minimize/fullscreen são tratados por SDL/Window/GpuSurface. Adiar criação
RT em tamanho zero; aposentar imagem anterior após completion, não no callback
SDL. Nenhum hook deve trocar a swapchain do Minecraft.

Preservar depth compatível com pós-processamento e 3D HUD antes de substituir
mundo. A release consultada limpa depth com 0.0; validar reverse-Z/projeção ao
escrever depth RT, sem reutilizar fórmulas OpenGL cegamente.

## Falhas e lifetime

Para falhas recuperáveis de capability/criação: registrar motivo, destruir
somente recursos RT já criados em ordem reversa segura e manter vanilla.
Destruição em voo deve usar completion e fila de retirement. O hook de shutdown
precisa executar antes de `VulkanDevice.close()` destruir VMA/device/instance.

**Limite técnico:** `VK_ERROR_DEVICE_LOST` afeta o device compartilhado.
Não é possível prometer continuação vanilla no mesmo device perdido apenas
capturando uma exceção Java. Nunca mascarar isso como fallback bem-sucedido;
registrar diagnóstico e seguir o mecanismo de falha/recriação suportado pelo
Minecraft. Falta de RT, por outro lado, não deve causar crash.
