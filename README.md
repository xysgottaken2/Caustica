# Native Vulkan RT — Minecraft Java 26.3

## 0.9.0-experimental — entidades e FallingBlockEntity no TLAS compartilhado

A implementação captura a emissão/upload vanilla de **jogador (AvatarRenderer),
zumbi, vaca, porco, galinha, itens comuns dropados e FallingBlockEntity**. Areia,
cascalho e concreto em pó usam `MovingBlockFeatureRenderer`, não `SectionMesh`.
Geometria local compartilhável, transformação afim da instância e materiais são
separados: movimento/animação rígida atualiza o TLAS; geometria inalterada reutiliza
BLAS sem nova cópia/normalização GPU.

É o **mesmo renderer, device, fila, pipeline RT, SBT e TLAS** de
SOLID + CUTOUT + TRANSLUCENT. Não há readback, retesselação própria, atlas
reduzido/copiado ou textura substituta. TEXEL continua padrão/referência. Texturas,
UV, Color/tint/alpha e overlay vêm dos dados e bindings reais do vanilla.

**Experimental: os 14 cenários visuais da 0.9.0 ainda estão pendentes.**
SOLID/TEXEL/grass overlay/CUTOUT são a base anteriormente validada pelo usuário;
TRANSLUCENT 0.8.0 passou no CI, mas continua sem validação visual confirmada.
CI/ABI/SPIR-V não equivalem a execução em GPU. Este ambiente local não tem Java/GPU.

- [Investigação 26.3, implementação, limites e checklist dos 14 cenários](docs/entities-0.9.0.md).
- JAR: **`native-vulkan-rt-0.9.0-experimental.jar`**, artifact
  **`native-vulkan-rt-experimental`**; uploads e relatórios independentes
  `test-ubuntu` / `test-windows` no [PR #1](https://github.com/xysgottaken2/test-3/pull/1).
- Uso: substituir o JAR e abrir o mundo, mantendo `nativevulkanrt.enabled=true`,
  `nativevulkanrt.scene=chunks` e `nativevulkanrt.textureSampling=texel`.
  Não trocar para a cena de triângulo. F5 permite verificar o jogador emitido
  pelo vanilla; não se inventa um corpo de primeira pessoa.
- Opcional: `-Dnativevulkanrt.entityDiagnostics=true` ativa HUD de hit GPU e
  detalhes de geometria/texturas. Contadores CPU são identificados como CPU;
  `GPU SAMPLED` e `FALLING HIT` dependem do shader, não do enqueue.
- Limites: banco de 12 pares texture-view/sampler; glint/foil, armaduras e efeitos
  especiais, consumers envelopados e formatos customizados não são cobertura
  completa. Rejeições são explícitas, sem substituir texturas. Veja o documento.
- Continuam fora: partículas, iluminação RT, sombras, reflexos/GI, refração
  avançada e View Bobbing. O caminho TRANSLUCENT mantém seus limites da 0.8.0.

Verificação: `./gradlew --no-daemon check assemble verifyMinecraftAbi verifyModJar`
e `./gradlew --no-daemon -p buildSrc test`. O CI também executa `spirv-val`.
Documentos históricos abaixo descrevem marcos anteriores, não o escopo atual.

## Build e teste

Requer **JDK 25** e acesso à rede. Gradle **9.7.0**, Fabric Loom **1.18.2** e
Fabric Loader **0.19.5** estão fixados. O jogo é a release **26.3**, não snapshot.
Para executar RT, é necessário driver/GPU Vulkan com os recursos exigidos.

```sh
./gradlew --no-daemon build
./gradlew --no-daemon -p buildSrc test
./gradlew --no-daemon check

# Vanilla Vulkan, RT desativado
./gradlew runClient

# Malhas SOLID + CUTOUT + TRANSLUCENT reais dos chunks
./gradlew runClient -Prt=true -PrtScene=chunks -PrtValidation=true
```

No Windows, use `gradlew.bat`. As validation layers precisam estar disponíveis no
ambiente de execução. `runClient` solicita `--graphicsBackend VULKAN` ao Minecraft.
O JAR esperado após build bem-sucedido é
`build/libs/native-vulkan-rt-0.8.0-experimental.jar`.

Os oito shaders GLSL em `shaders/` são compilados offline por
`src/shaderCompiler/java/dev/xys/vulkanrt/build/CompileRtShaders.java` e seus SPIR-V
são incluídos como recursos do JAR. Não há compilação de shaders RT por frame.
O workflow de build também prepara validação com `spirv-val --target-env vulkan1.2`.

`build` e `runClient` verificam o contrato `docs/minecraft-26.3-abi.tsv` contra as
classes resolvidas pelo Loom. Isso não prova que os Mixins ou o caminho GPU funcionam.
O verificador e seus testes estão em `buildSrc/`.

## Fontes e documentação

O renderer está em `src/main/java/dev/xys/vulkanrt/render/`, a captura de geometria
em `geometry/`, os hooks em `mixin/` e a negociação em `integration/`.
Configurações Fabric/Mixin estão em `src/main/resources/`.

Os estudos em `docs/` registram a pesquisa de Minecraft/Caustica e a arquitetura
anterior de bootstrap. Afirmações de status nesses estudos podem estar desatualizadas;
este README e a descrição do PR distinguem a implementação atual da validação pendente.
Fontes do Minecraft, SDKs e dados de pesquisa locais não são redistribuídos.

## Licenças

Código original deste projeto: MIT. Caustica foi estudado como referência,
**não copiado**; sua licença é LGPL-3.0-or-later. Minecraft e suas fontes não são
redistribuídos. O wrapper Gradle é software de terceiros Apache-2.0; veja
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
