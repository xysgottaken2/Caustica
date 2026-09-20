# Progressão RT e critérios de aceitação

A infraestrutura RT existente já possui o trace primário de SOLID/CUTOUT/
TRANSLUCENT/entidades no TLAS compartilhado. Nesta etapa foram adicionados
sombras diretas hard-shadow por shadow ray serial no mesmo TLAS e uma captura
isolada do caminho vanilla de primeira pessoa. A entrega continua experimental:
compilação/ABI/SPIR-V não substituem validação visual dentro do jogo.

| Fase | Entrega e gate |
|---|---|
| 0 | Inventário + fontes fixadas + comparação 26.2/26.3: pesquisa estática registrada; bytecode ainda pendente |
| 1 | Build/workflows/metadata/docs: preparados; obter `build` verde em máquina com JDK/rede |
| 2 | Entrypoint candidato escrito; iniciar cliente **real**, selecionar Vulkan e confirmar vanilla intacto |
| 3 | Consultar RT no physical device vanilla; testar GPU sem RT sem crash e log de motivo |
| 4 | Negociar features antes de criar device, emprestar handles; capability suportada ≠ feature habilitada |
| 5 | Buffer real com upload e BDA, flush/alinhamentos; validação Vulkan sem erro |
| 6 | BLAS de triângulo fixo, consultar sizes/scratch; build Vulkan real |
| 7 | TLAS de uma instância com matriz 3×4, custom index, mask e lifetime válidos |
| 8 | ShaderC → SPIR-V validado; criar pipeline KHR, descriptors, layout e SBT |
| 9 | `vkCmdTraceRaysKHR`, hit/miss distintos, saída RT integrada ao main target; captura/validação GPU |
| 10 | Uma mesh Minecraft real, preservando transformação e UV |
| 11 | Streaming e revisões de chunks; edição/load/unload sem reconstrução global |
| 12 | Materiais vanilla, atlas, cutout; ABI testada; resource reload seguro |
| 13 | Sombras por rays: Sol direcional + hard shadow no TLAS compartilhado; block lights/emissivas continuam fora |
| 14 | Reflexão de um bounce; configuração 1–4 limitada pelo shader e device |
| 15 | GI simples com pesos/pdf corretos; nenhum ReSTIR de fachada |
| 16 | Histórico, câmera anterior, jitter, motion vectors e validação de disocclusion |
| 17 | Denoiser espacial + temporal independente de DLSS |
| 18 | Players/mobs/items/block entities; particles depois de lifetime/transparência corretos |
| 19 | Validar Overworld/Nether/End e ambientes configuráveis por dimensão |
| 20 | Resolução interna e native upscale primeiro; FSR/XeSS/DLSS opcionais posteriores |
| 21 | Otimização medida com timestamps GPU, memória e validação de regressão |

## Sombras e primeira pessoa desta etapa

`chunks.rgen` lança um shadow ray depois de cada hit de superfície iluminada,
com direção fixa equivalente ao Sol, offset de normal e `rayTMin` positivos,
contra o binding 0 `scene` já usado pelo trace primário. O any-hit mantém o
alpha test/discard de CUTOUT e termina no primeiro SOLID/CUTOUT/entidade aceito.
TRANSLUCENT é ignorado como bloqueador e essa limitação é exposta na
instrumentação; não há shadow map, CPU tracing, readback ou segundo TLAS.

`GameRenderer.renderItemInHand` foi confirmado na fonte 26.3 como uma passagem
separada antes da composição de GUI: ele chama
`FirstPersonHandsAndItemsRenderer.submitHandsWithItems`, que emite braços,
main hand, offhand, itens e modelos especiais via `ItemStackRenderState` e
`SubmitNodeCollector`, preservando transform/FOV/material vanilla. O hook de
captura só observa o estado e os receipts do buffer/draw nessa passagem; não
reclassifica o viewmodel como entidade, não o inclui em shadow rays e deixa o
pass vanilla compor as mãos. Os receipts são diagnóstico CPU/enqueue, nunca
`LIGHT`/`SHADOW`; esses labels vêm exclusivamente do probe GPU do raygen.

O HUD opcional do center-ray mostra `LIGHT` e `SHADOW` a partir de
`shadowStats` escrito pelo shader depois do shadow trace. `shadowStats` também
separa rays lançados, bloqueados, livres e superfícies sombreadas; razões
CUTOUT descartado e TRANSLUCENT ignorado ficam em `shadowReasons`.

## Primeiro trace: não confundir shader com feature

Apenas três stages são necessários ao primeiro triângulo: raygen, miss e
closest-hit. Any-hit só quando houver alpha; intersection só se houver
procedurais/AABBs. Não criar dois raygens redundantes nem shader `.rint` vazio.
Só adicionar fontes ao repositório junto do consumidor executável.

O build futuro deve usar versão fixada do ShaderC, includes resolvidos de forma
determinística, `--target-env` coerente com device e `spirv-val`. Shader errors
falham o build; pipeline não deve compilar shaders durante gameplay. O wrapper
vanilla de `ShaderType` raster não será tratado como API RT sem inspeção.

Critérios da Fase 9: extensões/features efetivamente habilitadas, BLAS e TLAS
construídas, SBT alinhada, trace gravado e submetido, barrier/composição corretas,
imagem hit/miss observável. Só depois é lícito exibir “Hardware Ray Tracing: ON”.

## Estratégia AS a validar

- BLAS por seção/mesh com geração. Rebuild quando topologia/material buckets
  mudam; compactação assíncrona para meshes estáveis, sem stall por seção.
- Atualização dinâmica somente se `ALLOW_UPDATE` e restrições de formato,
  contagem e geometria forem atendidas. Nunca refit indiscriminado após edição.
- TLAS com IDs estáveis e matriz por instância. Não reconstruir se nada mudou.
  Transform-only pode usar UPDATE/refit quando permitido e economicamente útil.
- Vulkan não oferece uma promessa genérica de “patch parcial da TLAS” por
  intervalo de instâncias. Atualizar buffers sujos não elimina automaticamente
  o trabalho de build/update da estrutura. Mudança de contagem/topologia pode
  exigir rebuild. Medir custo em vez de anunciar atualização parcial fictícia.
- Retirar referências TLAS antes de aposentar BLAS; recursos compartilhados
  permanecem vivos até última submissão que os consome.

## Temporal, dimensões, configuração e debug

Quando implementados, históricos invalidam em world/dimension change, camera
cut/teleport, resize/escala, resource reload, material/lighting revision e
disocclusion. Usar depth/normal/material ID para rejeição, motion vectors com
objetos dinâmicos e camera-relative coordinates, exposição consistente.

A arquitetura deve aceitar ambiente por dimensão desde o início; a Fase 19
valida os três mundos completos, não legitima hardcode Overworld nas fases
anteriores. Sol não é luz universal de Nether/End.

Configuração futura: enable, escalas 0.25/0.33/0.50/0.67/0.75/1.0, bounces 1–4,
reflexos, sombras, GI, temporal, denoiser, distância e debug. Negociação de
features que faltam no logical device requer reinício, não toggle mágico.
UI só expõe features executáveis. O bootstrap não gera config inútil.

Debugs futuros: albedo, normal, depth, roughness, metallic, emissive, world
position, instance ID, BLAS/TLAS e ruído. Contadores reais de AS/triângulos,
memória e timestamps RT/denoiser/upscaler. Ray count exato exige instrumentação;
não apresentar `width × height` como número de rays secundários realmente
traçados. Debugs/instrumentação devem ser desligáveis para não distorcer medições.
