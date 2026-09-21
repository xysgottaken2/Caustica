# Testes: evidências e gates

## Resultado desta sessão

- Inventário Git e leitura das fontes fixadas: realizados.
- Verificações estáticas aprovadas: JSON/template de resources, 33 linhas TSV
  únicas com owners e nomes presentes nas fontes consultadas, integridade ZIP
  e SHA-256 do wrapper, `bash -n gradlew`, `git diff --check`, links locais e
  parse YAML dos dois workflows com actions fixadas por commit.
- `./gradlew build`, `./gradlew -p buildSrc test` e `./gradlew runClient`
  foram tentados: os três pararam no wrapper com `JAVA_HOME is not set and no
  'java' command could be found in your PATH`.
- Compilação Java / JUnit / ABI contra JAR oficial: **não executados** (JDK/rede).
- `runClient` e carregamento Fabric: **não executados**.
- Testes Vulkan, imagem RT, GPUs NVIDIA/AMD/Intel/sem RT: **não executados**.
- Workflows criados, mas não publicados/executados nesta sessão.

Um teste unitário do entrypoint somente confirma ausência de exceção em JVM;
não demonstra que o loader o chama no momento correto. Um contrato ASM confirma
assinaturas, não injeções. Um shader validado não confirma que foi despachado.

## Gate imediatamente seguinte

1. `./gradlew -p buildSrc test` e `./gradlew clean build` com Java 25/rede.
2. Inspecionar `build/reports/minecraft-abi.txt` e fontes locais `genSources`.
3. `./gradlew runClient`: confirmar no log backend **Vulkan** e bootstrap RT OFF.
4. Entrar/sair de mundo, mudar janela/resolução e fechar sem regressão vanilla.
5. Repetir sem GPU RT. Só após isso aprovar Fase 2 e escrever capability hooks.

## Matriz de aceitação do renderer futuro

Todos os itens abaixo estão **pendentes**. Registrar jogo exato, mod commit,
OS, modelo GPU, driver, API Vulkan, extensions/features habilitadas, resolução,
passos, logs, validation messages e captura de frame. FPS sozinho não é prova RT.

| Caso | Resultado exigido |
|---|---|
| 1. Iniciar Minecraft | Loader/mixins corretos; nenhuma inicialização RT antecipada |
| 2. Selecionar Vulkan | Device/surface vanilla; OpenGL não ativa RT |
| 3. Carregar mundo | Renderer vanilla preservado até RT pronto |
| 4. Habilitar RT | Estado ON somente com dispatch real utilizável |
| 5. Gerar TLAS | Instâncias/endereços/alinhamentos válidos |
| 6. Renderizar chunks | Mesh/UV/material/origem corretos, sem remeshing redundante |
| 7. Mover câmera | Projeção/depth/histórico corretos, sem ghosting excessivo |
| 8. Carregar novos chunks | Upload/build incremental e sem stalls globais |
| 9. Remover chunks | Sem referências TLAS a BLAS destruídas |
| 10. Trocar dimensão | Histórico/AS anteriores aposentados; ambiente correto |
| 11. Chover | Iluminação/ambiente/partículas consistentes |
| 12. Dormir | Camera/time discontinuity invalida histórico |
| 13. Inventário | GUI não desaparece nem recebe jitter RT |
| 14. Menus | Não despachar RT sem cena; UI nativa |
| 15. Entrar/sair de mundos | Nenhum leak/callback usando mundo anterior |
| 16. Fechar | Destruir RT antes do device vanilla, após completion |
| 17. Desabilitar RT | Retirement seguro, nenhum comando usando recursos fechados |
| 18. Voltar a vanilla | Renderer/resto do jogo continuam válidos |

Testes adicionais: resize, minimizar/restaurar, fullscreen SDL, resource reload,
OOM simulado durante cada etapa de criação, extension/feature ausente, formato
storage incompatível, driver sem RT e falha de compilação/pipeline. Falha
recuperável não pode cancelar vanilla. Device lost tem limite distinto descrito
em [vulkan.md](vulkan.md).

Hardware alvo para ensaio: NVIDIA RTX, AMD RDNA2+ e Intel Arc com drivers que
realmente exponham os requisitos; marca/modelo não substitui capability query.
Ensaiar também uma GPU Vulkan sem RT. Software Vulkan pode servir a testes
parciais de API, mas não comprova hardware traversal.
