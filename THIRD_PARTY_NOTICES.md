# Componentes e referências de terceiros

## Gradle Wrapper

`gradlew`, `gradlew.bat` e `gradle/wrapper/gradle-wrapper.jar` são o wrapper
Gradle, Apache License 2.0. Os scripts mantêm seus avisos originais.
Obtidos do repositório FabricMC/fabric-loom, tag `v1.18.2`, commit
`6056fe796865f1d88149e93c62ddf2158aa30782`.

SHA-256 do JAR wrapper:
`7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d`.

Origem do Gradle: https://github.com/gradle/gradle
Licença: [gradle/LICENSE](https://github.com/gradle/gradle/blob/v9.7.0/LICENSE).
Uma cópia da licença Apache-2.0 está em `gradle/wrapper/LICENSE`.

## Dependências resolvidas, não incorporadas ao mod

Fabric Loom (build), Fabric Loader, ASM (verificação de build), JUnit (testes),
as bibliotecas e o cliente oficial Minecraft são obtidos por Gradle/Loom.
O JAR do mod não deve incorporar classes Minecraft, RenderPearl ou LWJGL.
Consulte as licenças dos respectivos projetos antes de redistribuir dependências.

## Referência arquitetural

Caustica: https://github.com/xysgottaken2/Caustica, commit
`3c54fc201f93598246db62ebb1947dfb1e274e92`, LGPL-3.0-or-later.
Nenhum shader, renderer, DLL NGX/DLSS ou código nativo do Caustica foi copiado.
A licença MIT do código novo não relicencia essas referências.
