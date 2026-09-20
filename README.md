# Native Vulkan RT — Minecraft Java 26.3

## 0.8.0-experimental — TRANSLUCENT integrado; validação visual pendente

O usuário **validou a 0.7.0 em jogo**: SOLID, texturas/UV/tint, overlay do Grass
Block e CUTOUT com alpha test (folhas, grama, flores e furos). Essa é a base aceita.
A 0.8.0 acrescenta TRANSLUCENT real do vanilla — incluindo vidro/água conforme a
classificação da malha — ao **mesmo TLAS**, com cache independente, cópia GPU dos
índices originais, composição alpha ordenada e continuação até o fundo.

Usa o device Vulkan nativo do Minecraft 26.3 e o atlas original, sem segundo
backend, renderer paralelo, readback ou retesselação CPU. TEXEL permanece padrão.
CUTOUT continua sendo descarte em 0,5; TRANSLUCENT usa descarte residual vanilla
em 0,1 **mais composição**, não um substituto opaco. Os três registros SBT permanecem.

**TRANSLUCENT ainda não está validado em jogo.** CI/ABI/SPIR-V não demonstram vidro
ou água transparentes. O ambiente local continua sem Java/GPU. Publicação do JAR
real e relatórios independentes Ubuntu/Windows: [PR #1](https://github.com/xysgottaken2/test-3/pull/1).

- [Investigação 26.3, implementação, limites e diagnóstico da 0.8.0](docs/translucent-0.8.0.md)
- JAR: `native-vulkan-rt-0.8.0-experimental.jar` no artifact `native-vulkan-rt-experimental`.
- Uso normal: substituir o JAR e abrir o mundo, mantendo `enabled=true`,
  `scene=chunks` e `textureSampling=texel` nas propriedades `nativevulkanrt`.
- Teste novo: vidro (também colorido) com blocos atrás e água com substrato visível.
  Conferir fundo, alpha/UV/tint, camadas coexistentes e cache estável; apenas ver
  vidro não basta. Diagnósticos detalhados são opcionais.
- Fora deste marco: entidades/partículas/falling blocks, iluminação RT, sombras,
  reflexos/GI, refração avançada, skybox e fog volumétrico de água.

Comandos de verificação: `./gradlew --no-daemon check assemble verifyMinecraftAbi
verifyModJar`. Documentos antigos abaixo descrevem etapas anteriores; o estado
atual e os limites estão no documento da 0.8.0 acima.

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

# Cena de triângulo com validation layers
./gradlew runClient -Prt=true -PrtScene=triangle -PrtValidation=true

# Malhas SOLID reais dos chunks
./gradlew runClient -Prt=true -PrtScene=chunks -PrtValidation=true
```

No Windows, use `gradlew.bat`. As validation layers precisam estar disponíveis no
ambiente de execução. `runClient` solicita `--graphicsBackend VULKAN` ao Minecraft.
O JAR esperado após build bem-sucedido é
`build/libs/native-vulkan-rt-0.2.0-experimental.jar`.

Os três shaders GLSL em `shaders/` são compilados offline por
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
