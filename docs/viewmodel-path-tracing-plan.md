# Viewmodel (mão e item em primeira pessoa) no path tracer

Status: **plano + fundação**. O viewmodel ainda é desenhado pelo raster vanilla. Este documento
registra o que foi verificado no código, a arquitetura escolhida e a ordem de implementação.

## 1. Estado atual (verificado no código)

- A mão é desenhada pelo vanilla: `GameRendererMixin` redireciona o `createRenderPass` de
  `renderItemInHand` para o overlay de UI (`RtUiOverlay`). Ela é composta por cima do mundo no fim do
  frame e nunca passa pelo path tracer.
- O mundo, as entidades e o corpo do jogador em primeira pessoa (máscara `0x01`, só raios secundários)
  vão para a TLAS. Ver `RtEntities.captureEntities`.
- O FOV da mão já é travado: `caustica$applyHandFov` usa `hudFov` (70 com modulação de morte/fluido)
  quando a opção `Hand FOV` está desligada. Agora essa regra vive em `client/HandFov.java`, com teste
  em `HandFovTest`.

## 2. Restrição de ordem que define a arquitetura

O seam do RT composite (`GameRendererMixin.caustica$endWorldScaleBeforeHand` →
`WorldRenderScaler.end` → `RtComposite.composite`) roda **antes** de `renderItemInHand` dentro de
`render3dHud`. Ou seja, quando o mundo é traçado, a mão desse frame ainda não foi submetida.

Consequências:

- Não dá para incluir a mão no TLAS do mundo, que é montado em `RtComposite.recordFrame`, sem
  capturá-la antes do seam. Os estados de extração da mão (`FirstPersonHandsAndItemsRenderState`)
  só existem dentro de `renderItemInHand`.
- Usar a captura do frame anterior causa um atraso de um frame, e isso é exatamente o sintoma de
  "viewmodel desconectado da câmera" (requisito 2).

**Decisão:** o viewmodel é capturado e traçado **no próprio `renderItemInHand`**, em um dispatch tardio
com command buffer próprio. O padrão já existe em `rt/overlay/RtWorldOverlay.compositeIntoUiOverlay`,
que faz trabalho RT tardio no mesmo frame. Esse dispatch compõe o resultado na overlay de UI
(`RtUiOverlay.beginCompositeLayer`) com `PREMULTIPLIED_ALPHA`, antes do pass de efeitos de tela.

## 3. Como cada requisito é atendido

| Requisito | Mecanismo |
|---|---|
| 1. Viewmodel não atravessa blocos | A mão tem **TLAS/BLAS próprios**, montados só com a geometria da mão. Os raios dela não enxergam o mundo, então nenhum bloco a oclui nem a "empurra". As sombras do mundo sobre a mão usam máscara que exclui a própria mão. |
| 2. Não desconectar da câmera | A geometria vem do mesmo frame (submissão de `renderItemInHand`) e a transformação da instância é `câmera − rebase`, sem histórico. A mão **não passa** por denoiser (NRD/SVGF) nem por DLSS-RR/FSR: ela é composta depois do upscale. |
| 3. FOV travado em 70 | A projeção dos raios da mão é `HandFov.effective(hudFov, slider, handFovOption)`. Desligado, é 70 fixo; ligado, acompanha o slider. |

## 4. Pontos de integração (para a implementação)

1. **Captura.** Trocar a chamada de `FirstPersonHandsAndItemsRenderer.submitHandsWithItems` dentro de
   `GameRenderer.renderItemInHand` (assinatura no ABI: `docs/minecraft-26.3-abi.tsv`, linha 144) por um
   `@Redirect` que submete para um `RtEntityCollector` de captura. O receptor da chamada vem como
   primeiro argumento. Se a RT estiver ativa, o submit vanilla **não** é chamado, então o raster da mão
   some e não há desenho duplicado.
2. **Geometria.** Reutilizar `RtEntityCollector`/`RtEntityCapture` para produzir vértices e índices. Os
   vértices já saem orientados pela matriz da câmera (`viewRotationMatrix`), então a transformação da
   instância é só translação (`câmera − rebase`).
3. **Estruturas de aceleração.** Construir um BLAS da mão e um TLAS dedicado com um `RtAccel.TlasRing`
   próprio. Não usar `RtEntities.beginFrame`, porque ele monta o frame inteiro antes do seam.
4. **Entrada de tabela.** O hit shader de entidade lê a tabela de geometria e a textura bindless pelo
   `customIndex` da instância. A mão precisa de uma entrada nessas tabelas. Isso é o ponto mais
   delicado: a construção de tabelas está em `RtEntities.FrameBuild`/`appendCapture`/`writeTableEntry`
   (`RtEntities.java`, por volta das linhas 1580–1790).
5. **Dispatch.** `RtPipeline.setTlas` avança o ring de descriptor sets, então rebindar a TLAS da mão
   não corrompe o trace do mundo já gravado. Dispatch com `RtPipeline.trace(cmd, w, h, pc, raygenIndex)`
   e um raygen novo, `shaders/world/world_viewmodel.rgen.slang`, registrado no pipeline do mundo.
6. **Saída.** Uma imagem de armazenamento em resolução de display (premultiplicada), ligada a um binding
   extra com `setExtraStorageImage`. Depois, composição na overlay com
   `RtOverlayPipelines` (`overlay_passthrough_composite.frag`).
7. **Push constants.** O raygen da mão precisa da projeção inversa da mão (`inverse(handProj * viewRot)`).
   Isso exige um campo novo no `WorldPush` (Java `WorldPushData` + struct Slang, com teste de espelho em
   `RtShaderConstantMirrorTest`) ou um `WorldPush` separado para o dispatch da mão.

## 5. Riscos e o que precisa de GPU

- Não é possível compilar o mod nem rodar o jogo no ambiente de desenvolvimento usado para este PR (sem
  JDK completo, sem Gradle e sem as bibliotecas do Minecraft). A verificação de sintaxe Java foi feita
  com um parser; compilação e comportamento visual **não** foram verificados.
- A ordem de ligação dos pass (overlay limpa uma vez por frame) precisa ser conferida: a mão RT deve
  compor **depois** da overlay do mundo e **antes** da GUI e das screen effects.
- Itens com sprite animado, item em bloco, escudo, mapa e poção usam caminhos especiais de
  `ItemStackRenderState`. Cada um precisa ser checado no jogo.
- Atraso de frame: confirmar com DLSS-RR ligado que a mão não arrasta ao virar a câmera.

## 6. Checklist de validação no jogo (GPU RTX + Vulkan)

- [ ] Encostar a mão numa parede: a mão não atravessa nem some.
- [ ] Virar a câmera rápido: a mão não fica para trás.
- [ ] Mudar o slider de FOV com Hand FOV desligado: a mão não se move.
- [ ] Ligar Hand FOV: a mão acompanha o slider (FOV alto afasta, baixo aproxima).
- [ ] Sombra do sol sobre a mão quando há bloco entre a mão e o sol.
- [ ] Item luminoso na mão continua iluminando o mundo sem sombra fantasma da mão.
- [ ] Morte e nado (modulação de FOV) continuam funcionando.
- [ ] Com RT desligada, o caminho vanilla da mão continua idêntico.
