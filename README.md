# Native Vulkan RT — Minecraft Java 26.3

**Caminho de hardware ray tracing implementado experimentalmente. RUNTIME VERIFIED: NO.**

O código usa o **device Vulkan nativo do Minecraft 26.3**, sem Iris, OptiFine,
shaderpack, OpenGL, janela ou backend paralelo. RT permanece desativado por padrão.
Não há alegação de build aprovado, Minecraft iniciado ou imagem RT renderizada.

## Estado desta entrega

| Componente | Estado no código; execução ainda não validada |
|---|---|
| Contexto Vulkan e capacidades | Device emprestado; negociação de AS, RT pipeline e BDA antes da criação do device lógico |
| BLAS / TLAS | Criação, buffers, scratch, build e atualização de TLAS com contagem fixa |
| Pipeline / SBT / shaders | Descritores, grupos, SBT, raygen/miss/closest-hit e `vkCmdTraceRaysKHR` |
| Integração de imagem | Storage image e blit para o render target vanilla antes do HUD |
| Cena de teste | Triângulo de diagnóstico |
| Chunks | Captura das malhas SOLID reais, conversão de quads, BLAS por seção e TLAS |
| Ciclo de vida | Limites de captura/build, invalidação de mundo e descarte diferido |
| Build / testes | Gradle, ShaderC offline, verificações de ABI/JAR e testes adicionados; não executados com sucesso |

A saída atual é **visibilidade com cores diagnósticas**, não iluminação completa.
UV, tint e light bytes são preservados na captura, mas não usados para shading de
materiais. Cutout/any-hit, transparências, entidades, iluminação, sombras, reflexos,
GI, denoising, upscaling e UI de configuração ainda não estão implementados.
O mundo rasterizado e o depth vanilla não são substituídos integralmente.
Não foi necessário adicionar C/C++: as chamadas Vulkan necessárias existem no LWJGL.

## Validação

**RUNTIME VERIFIED: NO**

A tentativa local de `./gradlew --no-daemon build` parou antes de iniciar o Gradle:
`JAVA_HOME is not set and no 'java' command could be found in your PATH.`
O ambiente também não dispõe de GPU Vulkan acessível em `/dev/dri`.

A análise sintática de 31 arquivos Java passou; isso **não é compilação nem
verificação de tipos**. Compilação Java/GLSL, testes JUnit, verificação de ABI contra
os binários oficiais, aplicação dos Mixins, validation layers e imagem na GPU
continuam pendentes. Os workflows de CI estão configurados; sua execução deve ser
verificada no PR e não substitui o teste no jogo.

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
