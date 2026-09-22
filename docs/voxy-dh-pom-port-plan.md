# Plano de port: POM, Distant Horizons e Voxy

**Estado:** proposta inicial de integração — ainda não ativa compatibilidade em runtime.

## Objetivo e premissas

Esta série adicionará geometria LOD de **Distant Horizons (DH)** e **Voxy** à TLAS de ray tracing, além de **Parallax Occlusion Mapping (POM)** para mapas LabPBR. A instalação sem qualquer um desses mods continuará no caminho atual de terreno vanilla; nenhuma dependência de compilação para DH ou Voxy será introduzida.

O fork autorizado foi auditado em **2026-07-30**:

| Fonte | Referência usada na auditoria | Uso planejado |
| --- | --- | --- |
| `X2XMTUCI/Caustica` | `voxy-compat` em `21e9559987649bf250a97cda0c05fbbc43b797f3` | estado final de Voxy/DH e correções posteriores |
| mesmo fork | `4dd5a5560f8a35a53b735db7a101de11f270d6a4` — *Add relief path tracing…* | desenho de POM e testes de regressão |
| mesmo fork | `bc58f4b32583cb3a87a31ed862b0b3344f797225` — *Add Distant Horizons…* | captura inicial de VBOs DH |
| mesmo fork | `23000edc669fce5c0062a895baf5f60c1a04fef6` e follow-ups até `156949c` | bridge Voxy, hand-off, água e UI |

Esses commits pertencem a uma história Git diferente da deste repositório e o commit de POM altera também compositor, entidades, iluminação e materiais. Portanto **não** devemos fazer merge nem `cherry-pick` em bloco. O port será por transplantes semânticos, em commits pequenos, revisáveis e com testes. Isso é necessário para não reverter as correções já presentes de `RtEntityCollector`, baús, entidades, iluminação do barco, ReSTIR, tabelas de materiais e streaming do terreno.

Não serão versionados os JARs em `dist/` que aparecem no fork. Eles são artefatos de distribuição do autor, não fonte de uma dependência reproduzível.

---

## Resultado da auditoria

### 1. O que o fork realmente faz para Voxy

O fork **não** intercepta buffers Vulkan de Voxy nem compartilha um BLAS de Voxy. Ele usa uma bridge de CPU opcional, especificamente a API da variante de Voxy preparada para Caustica:

```text
FabricLoader.isModLoaded("voxy")
  -> Class.forName("me.cortex.voxy.client.compat.CausticaBridge")
  -> poll(camera, vanillaDistance, minY, maxY)
  -> lista imutável de meshes LOD de quads de 64 bytes
  -> Caustica decodifica, cria buffers/BLAS próprios e os anexa à TLAS
```

A bridge é chamada apenas via reflexão e publica `key`, `version`, origem, largura, `dataPointWidth`, e arrays `opaque`/`transparent`. O Caustica mantém a propriedade de todos os `RtBuffer`, BLAS, tabela de geometria, instâncias TLAS e destruição posterior. Isso torna a ausência de Voxy segura, mas significa que a versão upstream comum de Voxy, sem `CausticaBridge`, não é magicamente compatível.

**Contrato de compatibilidade proposto:**

* Se `voxy` não estiver carregado, ou se a classe/método bridge não existir, a integração estará indisponível e o renderizador seguirá normalmente.
* Se a bridge falhar depois de ter produzido um snapshot, o snapshot será descartado, o proxy LOD deixará de ser usado e um aviso será emitido uma única vez. Não haverá crash no render thread.
* A primeira versão suportará explicitamente a bridge acima. Uma adaptação posterior para uma API pública de Voxy deve virar outro provider, não contaminar o provider da bridge.
* O polling e a leitura da câmera ocorrem no render thread; workers só recebem `List.copyOf(...)`/arrays imutáveis.

A correção importante que deve acompanhar o port é que Voxy e DH **não podem** apresentar duas simplificações da mesma região. O fork usa Voxy como fonte preferida enquanto ele está ativo e tem snapshot; DH é o fallback. Manteremos essa regra no adaptador de fontes LOD em vez de acrescentar ambos cegamente à TLAS.

### 2. Como DH é capturado e levado à aceleração

O fork instala um mixin `@Pseudo` no alvo opcional
`com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer`.
O hook em `tryMakeAndUploadBuffersAsync` copia os `ByteBuffer`s de opaque/transparent antes que DH os reutilize. Não há import estático de classe DH:

* uma camada `DistantHorizonsCompat` armazena cada captura por posição e por identidade de `ClientLevel`;
* cada mesh é normalizado em `LodMesh` e recebe uma versão monotônica;
* reflexão busca a origem/extensão do `DhSectionPos`, qualidade e conjunto ativo quando a versão do DH expõe essas APIs;
* falhas de reflexão mantêm o último snapshot seguro ou retornam vazio, nunca impedem o renderizador;
* buffers têm quads de 64 bytes. O decoder valida coordenadas locais e ignora dados inválidos/incompletos antes de produzir geometria RT.

No lado Vulkan, o proxy:

1. filtra LODs fora do raio e remove LOD grosseiro totalmente coberto por um mais fino;
2. particiona o upload em lotes limitados de quads para limitar staging/scratch;
3. converte quads em `PackedSection` compatível com o construtor de BLAS existente;
4. publica uma tabela de `Section` separada, com prim/UV e os offsets de buckets;
5. cria instâncias TLAS próprias e retira os recursos somente depois do timeline graphics que os usou;
6. reaproveita BLAS de source/version inalterados, publica checkpoints progressivos e nunca remove o proxy anterior enquanto o substituto é preparado.

O formato de mini-material do LOD só permite uma classificação grosseira. Ainda assim, a conversão preserva cor/tint já assados, e distingue pedra, madeira, metal, folhas, neve, areia, água, lava, iluminado e vidro. Água LOD será uma superfície fina; não deve ser tratada como volume fechado de células grandes. Emissivos LOD serão incluídos no orçamento de luzes distante já existente, sem deixar que terreno comum iluminado pelo sol vire emissor.

### 3. Separação de chunks vanilla, DH e Voxy

A simples adição de instâncias de LOD à TLAS causa dupla geometria onde o chunk vanilla já está residente. O port deve manter os três mecanismos abaixo em conjunto:

1. **Classe de instância separada.** Instâncias LOD usarão o tipo reservado equivalente a `0xC00000` (`ENTITY_BIT | PARTICLE_BIT`) e uma tabela LOD separada. Closest-hit/any-hit detectará essa classe antes dos caminhos de entidades e terreno vanilla.
2. **Máscara exata de seções vanilla publicadas.** Antes de aceitar um hit LOD, o any-hit consultará a máscara de seções realmente publicadas pelo `RtTerrain`; se a seção vanilla está pronta, faz `IgnoreHit()`. Não usar apenas uma distância circular da câmera: chunks podem estar em streaming, vazios, recém-evictados ou ter um raio diferente.
3. **Uma fonte LOD por área.** O agregador de snapshots prefere Voxy ativo/com dados; só usa DH quando Voxy não possui snapshot. Além disso, o planejador remove LOD grosseiro coberto por um LOD mais refinado da mesma fonte.

A máscara e o hand-off são parte do contrato de shader, não uma otimização opcional. Um teste deve cobrir a seção vanilla publicada, a seção em streaming e a troca DH ↔ Voxy.

### 4. POM e o caminho de materiais já existente

A base atual já transporta a informação fundamental para POM:

* `RtBlockMaterials` carrega `*_n.png` LabPBR;
* `normalAo.rg` armazena a normal, `.b` AO e **`normalAo.a` o alpha/heightmap**;
* `RtMaterialTextureData.reduce` reduz o height channel por mip;
* páginas canônicas são enviadas como `surface0`, `normalAo` e `surface1` por `RtPipeline.setMaterialPage`.

Logo, o port de POM **não** deve criar um segundo cache Java de heightmaps, uma textura por material ou reconstruir BLAS. Ele deve consumir `normalAo.a` da página canônica bindless já carregada. Material sem `_n`, alpha sem relevo, PBR desativado ou POM desativado seguirá exatamente pelo caminho atual.

No fork, POM é shader-only e inclui mais que deslocamento visual de UV:

* `world.rchit.slang` faz o ray march/refinamento no height field, somente para sólido opaco; alpha-test continua na UV não deslocada;
* o hit exporta página, LOD, base tangente/normal, UV local, deslocamento planar e altura para o payload;
* `world.rgen.slang` testa a visibilidade no mesmo height field para NEE, sol/lua e raio de continuação;
* o LOD da visibilidade deve ser idêntico ao LOD da interseção. Isso evita acne/sombras pretas por iniciar o raio dentro de outro mip;
* `WorldPush.parallaxParams` recebe profundidade, passos e distância; configuração proposta: enabled, strength, smoothing e distance.

O fork também contém uma antiga implementação de `HeightField` para deslocamento de malha. Ela não será portada: a própria versão final do fork retorna `null` nesse gancho porque POM passou a ser inteiramente GPU/shader. O nosso objetivo é POM, não alteração de topologia ou aumento de BLAS.

**Atualização (implementação atual):** o height field **não** é mais amostrado em camadas de profundidade fixas como no fork. `world.rchit.slang` (`parallaxTrace`) intersecta o campo como **geometria real**: cada texel de altura é uma coluna em caixa e o raio percorre essa grade 2D com o mesmo caminhamento Amanatides & Woo que `cloudClassicBoxes` usa para o deck clássico de nuvens. Um empilhamento de camadas não tem laterais — de lado ou muito perto dá para ver (e atravessar) as fatias — enquanto o caminhamento entrega as quatro paredes de cada coluna de graça. O hit é o **topo** da coluna (mantém o normal map LabPBR) ou uma **parede lateral**, devolvida com a normal da própria parede. O plano acima permanece válido no resto: nada de cache Java de heightmap, nada de rebuild de BLAS, e a página canônica bindless continua sendo a única fonte de altura.

---

## Arquitetura alvo

```text
DH mixin (opcional) -----------+                         +--> RtSectionBuilder/RtAccel
                               |                         |      buffers + BLAS próprios
Voxy reflective bridge -------+--> RtLodSourceRegistry -+--> RtLodTerrain
                                      (snapshot único)   |      tabela LOD + lifecycle
                                                         +--> instâncias TLAS
RtTerrain (vanilla) ------------------------------------------> TLAS
                  máscara publicada --------------------------> any-hit hand-off

material _n alpha --> página normalAo bindless --> rchit POM --> payload --> rgen visibility
```

Os nomes finais podem variar, mas a divisão de responsabilidades não:

| Módulo | Responsabilidade | Não pode fazer |
| --- | --- | --- |
| `compat` | presença opcional, reflexão, cópia/normalização de snapshot | criar recursos Vulkan ou tocar em classes opcionais diretamente |
| `terrain` LOD | planejamento, decoding, buffers/BLAS, tabela e retirement | consultar APIs dos mods em worker ou substituir o terreno vanilla |
| `RtComposite` | chamar `frame`, anexar instâncias, preencher BDA/push constants | conhecer o formato de quad de DH/Voxy |
| shaders | classificar instância, hand-off, shading LOD, POM | depender da presença de mod Java |
| `material` | page canônica e bits/ABI de material | duplicar heightmaps para POM |

A tabela LOD terá a mesma disposição estrutural de `Section` que o shader já consome, mas endereço separado (`lodTableAddr`/equivalente) no `WorldPushConstants`. Alterar `world_common.slang` exige regenerar e validar os records Java, em vez de editar offsets manualmente.

---

## Sequência de implementação proposta

### PR 1 — contrato, testes de base e POM isolado

1. Adicionar as configurações `parallax.enabled`, `strength`, `smoothing` e `distance`, com defaults conservadores e chaves de idioma.
2. Portar seletivamente `WorldPush.parallaxParams`, o caminho de `rchit` e o estado extra de `Payload`; inicializar esse estado em **todos** os miss/hit paths.
3. Portar a visibilidade de height field em `rgen` e limitar POM a bucket sólido opaco, sem água, vidro, partículas ou cutout.
4. Ajustar apenas os bits de `RtMaterialRegistry`/ABI indispensáveis, preservando os atuais bits de emissão e stochastic alpha.
5. Adicionar testes de layout e shader para: alpha de `_n` preservado nos mips; fallback sem normal map; POM desligado com profundidade zero; mesma seleção de mip para hit/visibility; ausência de mudança de ABI não intencional.

**Critério de aceite:** resource pack LabPBR com `_n` alpha mostra relevo e auto-oclusão; o mesmo pack sem alpha, ou POM desligado, não altera a imagem base nem dispara rebuild de terreno/BLAS.

### PR 2 — fundação LOD e Distant Horizons

1. Introduzir `LodMesh`/provider interno imutável e testes de validação de blocos de 64 bytes, versões e world scope.
2. Portar o mixin DH `@Pseudo`, com `require = 0`, e o adaptador reflexivo. A lista de mixins continuará válida sem DH instalado.
3. Implementar o proxy LOD reutilizando `RtSectionBuilder`, `RtSectionTable` e a fila/timeline atual; trabalhar em lotes e reciclar BLAS inalterado por `(sourceKey, sourceVersion)`.
4. Acrescentar tabela/endereço LOD aos records gerados e anexar instâncias no `RtComposite`.
5. Portar shader de hit LOD, classificação de material/água/vidro e máscara exata de hand-off para seções vanilla.
6. Integrar emissivos LOD no orçamento atual, com limite explícito, sem reverter ReSTIR ou o coletor de luzes existente.

**Critério de aceite:** com DH ausente não há erro de mixin nem alocação LOD; com DH presente, LODs aparecem além dos chunks vanilla, desaparecem atrás de seção RT vanilla pronta e não deixam buraco durante refresh/world change.

### PR 3 — provider Voxy e hand-off DH ↔ Voxy

1. Implementar provider reflexivo para `me.cortex.voxy.client.compat.CausticaBridge`, sem referências Voxy no constant pool.
2. Converter a resposta em `LodMesh` imutável no render thread; aplicar revisão, reset de mundo e aviso único em falha.
3. Conectar o provider ao registro: Voxy ativo/com snapshot vence DH; Voxy vazio ou desativado devolve DH.
4. Portar controles opcionais de Voxy apenas quando a bridge expuser os métodos de configuração; a tela deve continuar funcional sem eles.
5. Cobrir água transparente, refresh/reset e mudança de fonte, incluindo testes de que os dois providers não são anexados simultaneamente.

**Critério de aceite:** Voxy ausente, Voxy upstream sem bridge e Voxy bridge quebrada degradam sem crash; Voxy bridge compatível fornece LOD RT, não duplica chunks vanilla e não duplica DH.

### PR 4 — estabilização e validação visual

1. Profiling de VRAM/scratch e tuning do orçamento de lotes para cenas de longa distância.
2. Cenários de recarga de resource pack, troca de dimensão/servidor, teleport, render distance variável e desligar/ligar providers.
3. Capturas RenderDoc para verificar endereço de tabela, máscara de instância, barreiras BLAS→TLAS→trace e retirement por timeline.
4. Documentação de versões de DH/Voxy efetivamente testadas e limitações da bridge.

---

## Matriz de testes obrigatória

### Automáticos

* `./gradlew test` com nenhum mod opcional no classpath.
* Testes unitários para `LodMesh`: cópia que respeita `position/limit`, tail incompleto, seção inválida, versão monotônica e reset por identidade de mundo.
* Testes do planejador: prioridade do LOD fino, cobertura remove LOD grosseiro, reuse por versão, troca Voxy→DH e DH→Voxy.
* Testes de ABI: `WorldPush`, `WorldPushConstants`, `Payload`, `Section` e `MaterialHeader` devem coincidir com reflection Slang/records gerados.
* Testes de source shader para a ordem segura: classificar instância LOD antes de entidades; `IgnoreHit` se a máscara vanilla diz pronta; inicialização do payload parallax em todos os paths.
* Regressões existentes de `RtEntityCollector`, block entities/baús, glint e geometria/iluminação de entidades devem continuar no `./gradlew test` e não ser removidas.

### Manuais/integração

| Cenário | Esperado |
| --- | --- |
| Sem DH/Voxy | imagem e uso de memória do caminho vanilla, sem warnings repetidos |
| Apenas DH | LOD RT além da distância vanilla; seam sem sobreposição/black holes |
| Apenas Voxy com bridge | LOD RT ativo; sem require de Sodium; polling sem rebuild storm |
| DH + Voxy | uma única fonte LOD por área (Voxy quando snapshot ativo) |
| Caminhar/teleportar/trocar dimensão | proxy anterior fica até o substituto; recursos velhos são aposentados com segurança |
| Água/vidro/lava LOD | água fina, vidro transmissivo, lava/emissor estável; não volume de macro-células |
| PBR com `_n` alpha | POM, sombra própria e GI consistentes |
| PBR sem `_n` ou POM off | nenhum deslocamento e nenhuma regressão de normal/PBR |
| Baús, entidades, barco e glint | permanecem visíveis/iluminados como na baseline |

---

## Riscos conhecidos e mitigação

| Risco | Mitigação |
| --- | --- |
| Internals de DH mudam | `@Pseudo`, reflexão isolada, `require=0`, catch no adapter e fallback vazio/último snapshot seguro |
| Voxy normal não tem a bridge do fork | capability detection explícita; indisponível com aviso único, nunca crash |
| Duplicação/oclusão entre LOD e vanilla | máscara exata de seções publicadas no any-hit e precedência de fonte |
| Pico de VRAM durante rebuild | batches limitados, checkpoints progressivos, reuse por version e destroy após timeline graphics |
| Dados de VBO em thread errada/reutilizados | cópia no hook; somente snapshot imutável vai a workers |
| Divergência Java/Slang | records gerados + testes de layout; nenhuma constante de offset escrita à mão |
| Acne de sombra POM | hit e visibility usam o mesmo page/LOD/escala enviados no payload |
| Port amplo sobrescrever fixes locais | sem cherry-pick/merge do fork; cada arquivo alterado terá diff semântico e regressões existentes preservadas |

---

## Checklist para começar o código

- [x] Auditoria dos commits e do pipeline do fork documentada.
- [x] Confirmado que heightmap LabPBR já chega em `normalAo.a` nas páginas Vulkan da base.
- [x] Confirmado que Voxy no fork usa uma bridge reflexiva, não interop Vulkan direto.
- [x] Identificado o hand-off correto como máscara de seção vanilla publicada, não raio de câmera.
- [ ] Criar os tipos/provider LOD internos e seus testes.
- [ ] Portar POM sem o antigo deslocamento de malha.
- [ ] Portar DH em uma PR separada e testável.
- [ ] Portar provider/controles Voxy depois de DH.
- [ ] Executar a matriz manual em build com as versões suportadas dos mods.

Este PR inicial é deliberadamente somente de plano: evita introduzir um hook opcional de mixin, uma mudança de ABI Slang ou um proxy BLAS parcial sem a bateria de testes e o ciclo de vida completo.
