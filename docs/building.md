# Build e desenvolvimento

## Requisitos fixados

| Componente | Versão / origem |
|---|---|
| Minecraft Java | Release 26.3, manifesto Mojang |
| JDK | 25, exige `java` e `javac` no PATH ou `JAVA_HOME` |
| Gradle | 9.7.0, wrapper versionado com checksum da distribuição |
| Fabric Loom | 1.18.2, plugin `net.fabricmc.fabric-loom` (sem remapeamento) |
| Fabric Loader | 0.19.5 |
| LWJGL / Vulkan / SDL3 / ShaderC / VMA | Bibliotecas 3.4.3 resolvidas da metadata Minecraft, não empacotadas pelo mod |
| ASM | 9.10.1, somente `buildSrc`, leitura de classfiles Java 25 |
| JUnit | BOM 5.14.4, somente testes |

Não é necessário Gradle instalado globalmente, Python, CMake, Vulkan SDK ou
SDK DLSS para o bootstrap. Nenhum caminho absoluto do ambiente de preparação
é usado pelo build. Internet é necessária no primeiro build: serviços Gradle,
Mojang metadata/data/assets/libraries, Fabric Maven e Maven Central.
Use suas configurações padrão de proxy Gradle quando necessário; não desabilite
TLS nem coloque credenciais no repositório.

## Comandos

```sh
java -version
javac -version
./gradlew --version
./gradlew -p buildSrc test
./gradlew clean build
./gradlew runClient
```

Windows: substituir `./gradlew` por `gradlew.bat`. O diretório de desenvolvimento
é `run/` (ignorado), não uma instalação pessoal. Não usar mundos importantes.
O cliente de desenvolvimento não é um launcher autenticado para servidores;
use sua conta/licença e launcher normais para testar o JAR em instalação real.

O Loom recebe `clientOnlyMinecraftJar()`: este é um mod client-only e o JAR
completo contém RenderPearl. Não copiamos o workaround Baritone que extrai um
JAR de um caminho de cache específico. A escolha deve ser confirmada pelo
verificador de ABI e build real antes de alterar hooks.

`runClient` fornece `--graphicsBackend VULKAN` e native access ao cliente. O
backend nativo/driver Vulkan precisa estar disponível na máquina do usuário.
Isso não força disponibilidade de hardware RT.

Saídas esperadas:

- `build/libs/native-vulkan-rt-0.1.0-bootstrap.jar`;
- sources JAR separado (somente código do mod);
- `build/reports/minecraft-abi.txt`;
- relatórios JUnit em `build/reports/tests/test/`;
- relatórios dos testes do verificador em `buildSrc/build/reports/tests/test/`.

## Verificações

`check` depende dos testes JVM, `verifyMinecraftAbi` e `verifyModJar`.
`build` já inclui `check`. `runClient` também depende do contrato ABI.

O verificador lê classes da compileClasspath sem inicializá-las, conferindo
owners, nomes e descritores JVM. Compara overloads, detecta classes ausentes e
conflitos de definições. O relatório só informa sucesso depois da verificação.
O arquivo TSV é dado de pesquisa; **não** um Mixin config. Uma classe presente
não prova um injection point correto, nem uma GPU suportada.

Os testes de `buildSrc` são executados explicitamente no workflow `test.yml`.
Eles não são confundidos com `test` do mod nem com teste gráfico.
`verifyModJar` confere metadata expandida, licença, entrypoint e ausência de
classes vanilla/LWJGL ou bibliotecas nativas indevidamente incorporadas.

Arquivos ZIP/JAR gerados têm ordem reproduzível e timestamps removidos.
Versões diretas e distribuição estão fixadas; equivalência byte-a-byte em
máquinas limpas **ainda não foi demonstrada**. Verificação/locking das
resoluções transitivas será feita quando houver acesso às dependências reais;
não gerar um lockfile de hashes inventados.

## CI

- `build.yml`: checkout, Java 25, setup/cache Gradle + wrapper validation,
  build/check, inspeção do JAR, upload do mod e relatórios.
- `test.yml`: Linux e Windows; testes do verificador, testes JVM, ABI e JAR.
- Actions estão fixadas por commit; permissões somente leitura e timeout.
- Não existem etapas fictícias `compileNative`/`compileShaders`: não há esses
  fontes neste milestone. Na Fase 8, integrar compiler/validator fixados como
  dependências reais do processamento de resources, sem pré-instalação manual.
- Runners hospedados comuns não são prova de hardware RT; testes gráficos
  precisam de runners/máquinas GPU dedicados e artefatos de diagnóstico.

## Diagnóstico desta sessão

`java -version` falhou: `java: command not found`. `curl`/Python falharam ao
baixar Mojang, Maven e Gradle por TLS/EOF. A tentativa de obter Temurin 25 por
GitHub Releases também falhou por EOF. Ferramenta de consulta web conseguiu
ler metadata, o que **não** disponibiliza os binários ao Gradle.

Não se executou decompilação local, compilação Java, JUnit, cliente ou Vulkan.
É necessário repetir os comandos acima em ambiente com JDK 25 e rede antes
de considerar o bootstrap aprovado. Não desligar a verificação ABI para fazer
um build artificialmente verde.
