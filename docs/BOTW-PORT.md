# Plano: BOTW Recomp

Recompilação estática de *The Legend of Zelda: Breath of the Wild* (Nintendo Switch, **v1.0.0**) para PC
(Windows, Linux, macOS) e Android, a partir do motor do [LAS Recomp](https://github.com/peiikai/LASRecomp) e do seu
porte para Android ([LASRecompAndroid](https://github.com/mamcjr/LASRecompAndroid)). Como no LAS, não é emulação:
o código ARM64 do jogo vira C, compilado para a máquina, e o NVN (a API gráfica do Switch) é traduzido para Vulkan.

O plano do porte do LAS para Android, com as medições e decisões dele, está em
[docs/las/ANDROID-PORT.md](las/ANDROID-PORT.md). O README original do LAS está em [docs/las/](las/).

## Ponto de partida

O que vem pronto do LAS e não depende do jogo:

- **O gerador** (`tools/gen.py`, `lifter.py`, `nso.py`, `analyze.py`, `simd_c.py`): NSO → C, função a função, com
  FP/SIMD em asm nativo no ARM64 (`--simd asm`) ou em C portável no x86-64 (`--simd c`), e `--dyn-base` para a
  janela de memória móvel do Android.
- **O runtime** (`runtime/`): kernel do Horizon emulado (threads, sincronização, memória, `svc`), IPC e serviços
  (`fs`, `hid`, `audren`, `vi`, ...), o carregador de NSO com relocações, a tradução **NVN → Vulkan** e o tradutor de
  shaders **Maxwell → GLSL → SPIR-V** (com as otimizações para Adreno), áudio, FFmpeg para vídeos, ImGui.
- **O Android** (`android/`): app Gradle com SDL3, janela de memória dinâmica, libadrenotools (Turnip), arena de GPU
  para drivers sem memória "placed" (Qualcomm, Mali), texturas BC decodificadas na CPU (Mali), controles de toque,
  thread de render, ritmo de quadros, geração do código do jogo no aparelho.

## O dump (medido em 2026-10-18 com `tools/botw_dump.py`)

Título `01007EF00011E000`, **v1.0.0** (build `2094F152836683A3632C6153FD84AB80` do `main`).

| Módulo | Imagem | Símbolos | Relocações | O que é | Tratamento |
|---|---|---|---|---|---|
| `main` | 35,5 MiB | 510 | 223.269 | o jogo (KingSystem/U-King, sead, Havok, vfx) | recompilado |
| `sdk` | 7,6 MiB | 14.849 | 15.275 | nnSdk | recompilado + serviços HLE |
| `subsdk0` | 2,0 MiB | 1.743 | 11.376 | `nvn.nss`, o driver NVN | HLE (`runtime/nvn`) |
| `subsdk1` | 0,2 MiB | 449 | 453 | `nnDisplay.nss` | HLE |
| `subsdk2` | 0,3 MiB | 565 | 673 | `libcurl.nss` | stub |
| `subsdk3` | 6,5 MiB | 10.231 | 18.427 | `multimedia.nss` (vídeos) | HLE com FFmpeg |
| `rtld` | — | — | — | carregador dinâmico | não usado |

`romfs`: 39.678 arquivos, **13,4 GiB**.

Para comparar, o `main` do LAS tem 29 MiB de imagem e ~105 mil relocações: o executável do BOTW é do mesmo porte
(cabe no mesmo mapa: `main` em `0x7000000000`, `sdk` em `0x7004000000`). O `nso.py` do LAS já lê todos os módulos
do BOTW sem mudanças.

## Diferenças em relação ao LAS

| # | Diferença | Onde | Gravidade |
|---|---|---|---|
| 1 | **SDK de 2017.** O LAS foi compilado com o SDK 8.2.0 (2019); o BOTW 1.0.0, com um SDK do lançamento do Switch. Comandos de IPC, versões de serviços e funções internas do nnSdk mudam. | `runtime/services/`, `ipc.c`, `hle/hle_symbols.txt` | **Alta** |
| 2 | **NVN em módulo separado.** No LAS, o NVN vem pelo `nnSdk`; no BOTW, o `main` importa direto do `nvn.nss` (`subsdk0`), numa versão antiga da API. Layouts de structs e procs podem não bater com o que `runtime/nvn` espera. | `runtime/nvn/`, `hle/nvn_boot.c`, `loader.c` | **Alta** |
| 3 | **Código do LAS ligado a endereços do LAS.** `tools/patches.py` (~60 endereços, com `assert` na instrução: o gerador para no primeiro), os hooks de jogo em `runtime/hle/` (chamber, digs, keysanity, warp, widescreen, ...), o randomizer (`tools/rando`, `third_party/LAS-Randomizer`, `runtime/rando.c`), as abas de warp e editor de save, os scripts de teste. Tudo isso sai ou fica vazio. | vários | Alta (bloqueia a Fase 2) |
| 4 | **Escala.** Mundo aberto com streaming, Havok (física, IA, tecido) em várias threads, muito mais shaders e texturas, e o jogo pode usar até ~3,2 GB de memória (o limite de um app no Switch). | CPU, GPU, cache de pipelines | Alta (desempenho) |
| 5 | **romfs de 13,4 GiB.** O app Android do LAS copia os arquivos para a pasta do app e monta uma imagem de dados: com o BOTW isso passaria de 27 GB. Ler os arquivos no lugar. | `romfs_build.c`, app Android | Média |
| 6 | **Versão.** O dump é v1.0.0. A decompilação [zeldaret/botw](https://github.com/zeldaret/botw) (nomes de funções, structs) é da v1.5.0: os endereços não batem, mas os nomes podem ser achados por assinatura. A v1.6.0 é a última; vale avaliar mudar de alvo antes de escrever hooks. | `tools/sym.py`, hooks | Média |
| 7 | **Vídeos e áudio.** `romfs/Movie` passa pelo `multimedia.nss`; o `movie.c` do LAS é ligado ao player do LAS (endereços no `main`). O caminho do áudio do jogo ainda não foi visto. | `hle/movie.c`, `services/audio.c` | Média |

## Desktop primeiro ou Android direto?

O runtime é um só: o mesmo código compila para Windows, Linux, macOS e Android. Não é preciso escolher um caminho,
só a ordem.

**Recomendação: Windows primeiro até o jogo chegar ao menu, Android logo em seguida.**

- No PC o ciclo é muito mais curto: gerar, compilar e rodar sem copiar nada para o aparelho, com depurador,
  RenderDoc, validação do Vulkan e logs direto no terminal.
- Uma GPU NVIDIA tem `VK_EXT_external_memory_host`: os shaders leem a memória do jogo direto, sem a arena do
  Android. Problemas do jogo e problemas do Android ficam separados.
- O Android já tem toda a infraestrutura pronta do LAS. Depois do menu no PC, o porte para Android vira
  "gerar com `--simd asm --dyn-base`, compilar com o NDK e rodar".
- Diferença a lembrar: no x86-64 o FP/SIMD do jogo passa pelo C portável (`simd.h`); no Android, por asm nativo.
  Um bug que só aparece num dos dois aponta para essa camada. Linux aarch64 seria o desktop mais parecido com o
  Android.

Esta máquina já tem o Android SDK/NDK (usados pelo LASRecompAndroid), mas **não tem o ambiente do desktop**
(MSYS2 UCRT64 com clang, SDL3, FFmpeg, shaderc): ver `docs/BUILD-windows.md` e `tools/setup_windows.ps1`.

## Fases

### Fase 0: repositório e dump — **feita (2026-10-18)**

- Fork do LASRecompAndroid com o histórico (129 commits; remotos `las` e `upstream` para trazer melhorias do motor).
- `Dump/` fora do git; `tools/botw_dump.py` confere os módulos, a versão e o romfs.

### Fase 1: tirar o LAS e compilar sem o jogo — **feita (2026-10-18), menos o build desktop**

- Saíram: o randomizer (`third_party/LAS-Randomizer`, `tools/rando`, `runtime/rando.c`), os 17 hooks de jogo do LAS
  em `runtime/hle/` (chamber, digs, doorjump, escape, flags_ext, framepace, hookshot, keysanity, miniboss, mirror,
  pets, pot_glow, qol, rescue, shopsanity, souls, warp), as abas Save Editor / Randomizer / Warp / Cheats / Gameplay /
  Cosmetics e os overlays do LAS, as opções do LAS em `settings.h`, os scripts de teste do LAS e o smoketest Linux
  (que andava pelo jogo do LAS).
- `tools/patches.py` ficou vazio, com o mecanismo e um exemplo; o gerador perdeu as variantes de patch das seeds.
- O que o runtime pergunta ao jogo ficou em `runtime/hle/game.c` (`game_in_play`, `game_level_name`: "fora de
  jogo" por enquanto). `runtime/hle/widescreen.c` virou a interface neutra que os renderers chamam (imagem 16:9 com
  barras até existir um hook de câmera do BOTW). `runtime/nvn/texpatch.c` ficou como stub (os botões no estilo do
  controle eram do LAS).
- Novo: `LARECOMP_MODS=<pasta>` com um `romfs/` que substitui / acrescenta arquivos do jogo (o mecanismo de
  sobreposição das seeds, agora para mods).
- O launcher confere o build id do BOTW v1.0.0 e acha o romfs pela pasta `Pack/` (no PC e no Android).
- Renome: `botwrecomp` (executável, pacote Android `com.botwrecomp`, `BOTWRecompActivity`, pasta
  `android/botwrecomp`), `botwgame` (a biblioteca do código do jogo), `botwgen` (o helper do gerador),
  `botwrecomp.ini`, bundle id `com.mamcjr.botwrecomp`. **As variáveis de ambiente continuam `LARECOMP_*`** e os
  identificadores internos também: o motor é o do LAS, e assim fica mais fácil trazer melhorias de lá.
- Verificação: o app Android (`android/botwrecomp`, `gradlew assembleDebug`) compila e linka com `--no-undefined`.
  Os arquivos só do macOS (`renderer.m`, `metal.m`) foram editados do mesmo jeito, mas não compilados aqui.
- **Falta:** o ambiente MSYS2 do Windows (`tools/setup_windows.ps1` instala MSYS2, pacotes e Python; não rodei sem
  você) e o build desktop.

### Fase 2: gerar o código do jogo — **gerado (2026-10-18)**

O gerador do LAS funcionou no BOTW sem mudanças (para Android: `--simd asm --dyn-base`, em `generated-android-dyn/`):

| Módulo | Funções | Arquivos C | Instruções não suportadas | Tempo |
|---|---|---|---|---|
| `sdk` | 17.689 | 98 | 0 | 6 s |
| `main` | 104.671 (1.056 tabelas de salto) | 581 | 0 | 53 s |

(Os poucos `rt_udf` no C gerado são `udf` / `brk` de verdade do código e o cabeçalho do módulo.) Os `subsdk` não são
recompilados, como no LAS: o que o `main` usa deles é HLE (abaixo).

- `android/build_gamelib.sh <exefs>` compila o `botwgame.so` com o NDK: **compilou e linkou (2026-10-18)**, 683
  arquivos em ~1 h nesta máquina (16 threads), 152 MB sem debug info. O ID (`7e833884382c3dd9`) bate com o do app, e
  os 6 símbolos que ele pede ao runtime (`rt_dispatch`, `rt_svc`, `rt_udf`, `rt_ticks`, `fnprof_hit`, `g_fnprof`)
  existem no `libmain.so`. Pronto para `android/push_game.sh`.

### Fase 3: boot até o primeiro quadro — **em andamento**

Feito sem rodar o jogo (análise estática; `tools/imports.py <exefs>` refaz):

- **Importações do `main` (483):** 431 vêm do `sdk`, o resto de HLE. Faltavam 5, agora implementadas:
  `nn::vi::SetLayerScalingMode`, `movie::Buffer::Size/Offset` (sem `const` no SDK de 2017),
  `movie::Decoder` com 2 argumentos e `movie::MediaData::FindRect`. Sobram as mesmas 12 do LAS (glslc e marcadores
  do linker, que nunca são chamados).
- **NVN:** das ~225 funções que o BOTW pede por nome, só `nvnDeviceWaitForError` faltava (implementada: espera
  100 ms e diz "sem erro"). 7 outras faltam também no LAS, que rodava sem elas.
- **Boot do SDK antigo:** o SDK de 2017 não tem `nn::init::Start`. Lá o próprio `rtld` fazia a sequência:
  `__nnDetailInitLibc0` → `nnosInitialize(handle, args)` → `__nnDetailInitLibc1` → `nndiagStartup` →
  `nninitInitializeSdkModule` → `nninitStartup` → `__nnDetailInitLibc2` → `DT_INIT` de cada módulo (main, depois sdk)
  → `nnMain` → `nninitFinalizeSdkModule` → `nnosQuickExit` (lido do `rtld` do BOTW). `runtime/main.c`
  (`rtld_start_legacy`) faz isso quando não acha `nn::init::Start`.
- **Serviços:** o SDK do BOTW abre quase os mesmos serviços que o do LAS; a mais: `nifm:u`, `prepo:a`, `csrng` (sem
  implementação: viram stubs que registram no log).
- **Símbolos que o runtime procura por nome no SDK:** todos existem no BOTW, menos `nn::init::Start` (acima).

**Primeiro teste no celular (2026-10-18, POCO F7 Ultra, driver Qualcomm):** o jogo **inicializa e apresenta
quadros** (~80–130 fps nas telas iniciais, ~300–500 draws por quadro), com áudio, entrada, saves e ~200 MB lidos do
romfs. Corrigido no caminho (todos em `runtime/`):

| Problema | Correção |
|---|---|
| `adb push` deixa os arquivos do jogo ilegíveis para o app | `android/push_game.sh` faz `chmod o+rX` |
| `fsp-srv` 203 (dados de atualização) devolvia "ok" sem objeto | responde "não encontrado" |
| Espaço de endereçamento de **36 bits** (`main.npdm` tipo 1): o SDK sorteia endereços de alias abaixo de 2 GB | `hle/address_space.c` leva os `AddressSpaceAllocator` para a região de alias do runtime |
| O jogo exige NVN **52.x (≥ 32)**; o runtime dizia 53.207 | responde 52.32 |
| Buffers de comando de 512 bytes com callback vazio | memória do host quando o jogo não dá mais |
| Áudio **REV1**: a saída tinha a seção de render info (só existe a partir da REV5) | só da REV5 em diante |
| `nfp:user` (amiibo) sem implementação | serviço mínimo: nenhum leitor |
| A thread principal gira esperando outra thread do núcleo 0 | **fatia de tempo** de 8 ms na emulação do núcleo 0 (`LARECOMP_C0_SLICE_MS`) |
| Listas de comando gravadas na memória de *comandos* (o BOTW reaproveita essa e mantém a de *controle*) | as listas vão para uma sombra (8×) da memória de **controle**, por página do endereço do jogo |
| `CopyCommands` tratado como `CallCommands` | copia de verdade |
| `nvnTextureFinalize` duas vezes no mesmo objeto (`free` inválido) | só libera texturas vivas |

Ferramentas novas de diagnóstico: `LARECOMP_WATCHDOG=<s>` (threads paradas em syscalls), `LARECOMP_CHECK_HANDLES=1`
(valida listas de comando), `android/run_boot.sh` (abre o jogo direto e grava o logcat).

Próximos problemas vistos:

- Instruções Maxwell que o tradutor de shaders não conhece: `0xc838…`/`0xc83a…` e `0xdf00…` (20 shaders nas telas
  iniciais).
- Crash em `renderer_mark_gpu_owned` (acesso a `0xa3`) depois de ~9 s.
- Ainda não vi a imagem: os testes foram com o celular em uso.

**Segunda rodada de testes (2026-10-18):**

- Corrigido: texturas finalizadas são liberadas 4 quadros depois (a thread de render ainda as usava); listas enviadas
  são copiadas no `SubmitCommands` (o jogo regrava a memória de controle logo depois, como o NVN permite);
  `CallCommands` também copia as sublistas na gravação.
- **Onde parei:** a fatia de tempo do núcleo 0 (8 ms) destrava a thread principal, mas quebra a ordem entre threads
  que o jogo assume: uma thread de trabalho grava listas no anel de memória de controle e a principal reinicia o anel
  por cima (visto com `LARECOMP_CHECK_HANDLES=1`). Quem libera o laço da principal (`main+0x8551a8`, espera a global
  `main+0x22df9e8`) é a thread t110, de prioridade 46 (menor que a da principal, 44): no Switch ela não poderia
  preemptá-la no mesmo núcleo, então deve rodar **em outro núcleo** no console. Próximo passo: ver em que núcleo cada
  thread do BOTW roda de verdade (`svcCreateThread` com núcleo -2 / `svcSetThreadCoreMask`, que o runtime ignora)
  em vez da fatia de tempo, que deve ser desligada (`LARECOMP_C0_SLICE_MS=0`) quando isso estiver certo.
- Diagnóstico novo: `LARECOMP_WATCH=<offset no main>[,<bytes>]` (quem acessa a memória, com a thread).

**Terceira rodada (2026-10-18, depois das mudanças do Astra, commit `164a1a6`, validadas):**

- O Astra implementou `svcSetThreadCoreMask` / `GetThreadCoreMask` (migração entre núcleos virtuais num ponto seguro),
  trocou o lock de tickets do núcleo 0 por uma fila e **desligou a fatia de tempo por padrão**; `MapVirtual` para o
  heap virtual de texturas de 120 MiB; contagem de referências das texturas (os descritores as mantêm vivas). Os
  testes `tools/test_android_runtime.ps1` (afinidade, ciclo de vida do NVN) passam no celular.
- **Confirmado:** o BOTW chama `SetThreadCoreMask` e põe threads nos núcleos 1 e 2 (era o que travava a thread
  principal). Com isso o jogo passa a **renderizar cenas de verdade**: até ~20.000 draws e ~7.700 passes por intervalo
  de 2 s, ~5.000 texturas, centenas de MB enviados.
- **Turnip funciona:** `files/drivers/Turnip_Gen8_V37.zip` (copiado do LAS) carrega; a memória do jogo vira memória
  da GPU (placed). Sem ele, o modo de cópia da Qualcomm é muito mais lento.
- **Onde parei — memória da GPU:** ela cresce ~0,5–0,8 GB/s até ~7 GB e o Android mata o app (lowmemorykiller). O
  número de texturas com imagem (`gpu mem`/`textures` agora no log de fps) só sobe. Não é reinicialização de objetos
  (`LARECOMP_LOG_TEXINIT` não achou nenhuma): o jogo cria mesmo milhares de texturas que, no Switch, são só
  descrições sobre a memória dos pools dele. O runtime cria uma `VkImage` própria (e cópia) para cada uma.
  **Próximo passo:** com Turnip, criar as imagens das texturas sobre a própria memória do pool do jogo (aliasing na
  memória placed) em vez de alocar e copiar; e/ou compartilhar a imagem entre texturas da mesma memória/formato.
  Também ver `[tex] type mismatch: want 6 have 3` (cubemap 64×64×6) e as instruções Maxwell `0xc838`/`0xdf00`.
- Custo a observar: `register_texture` agora espera a thread de render (`vk_async_drain`) a cada registro.

**Quarta rodada (2026-10-18, depois do commit do Gemini `1ab7837`):** o jogo chega ao Santuário da Ressurreição com
a imagem certa, a tela de título com o menu e os textos, ~35 fps, ~950 texturas e 1 GB de memória da GPU estáveis.

| Problema | Causa | Correção |
|---|---|---|
| A cena ocupava 3/4 da tela, lens flares viravam bolas azuis, Link transparente | a regra do LAS para a resolução interna (render targets 16:9 = tela) escalava a cena de 1600x900 do BOTW para 2133x1200 ao lado de alvos do mesmo tamanho sem escala | escala de render targets desligada (`LARECOMP_SCALE_TARGETS=1` volta à do LAS, para testes); a saída continua passando pelo FSR |
| Nenhum texto (menus, títulos) | os glifos de cada linha de texto ficam num buffer ~2,7 GB dentro do heap de 3 GB; o descritor do heap alcança só `maxStorageBufferRange` (2 GB no Adreno): o vertex shader lia zeros | com o heap na GPU (Turnip) e maior que esse alcance, as draws usam a tabela GMap numa janela do heap que começa nos storage buffers delas (`gwin_prepare`, como o compute já fazia); `LARECOMP_VK_NO_GWIN=1` desliga |
| Ruído colorido atrás da lista de saves | o jogo desenha as camadas da UI sobre o que o alvo tinha, sem limpar; uma VkImage nova tinha a memória que o driver reciclou | imagens de cor que podem ser render target nascem zeradas (como as de profundidade) |
| TLD4S (gather compacto) com AOFFI ou DC lia a coordenada Y do registrador errado; TMML com .x/.y trocados | decodificação do commit do Gemini | layout do yuzu (`texture_gather_swizzled.cpp`, `texture_mipmap_level.cpp`) |
| Texturas liberadas na hora no `Finalize` (sem backend e não registradas) enquanto a thread de render ainda podia usá-las; a deduplicação do Gemini dividia um `NvnTexture` entre objetos sem referência por dono (uso depois de liberar) | ciclo de vida do commit do Gemini | tabela de donos (endereço do objeto do jogo -> textura): cada `Initialize` é um dono com sua referência, só o `Finalize` dele (ou o próximo `Initialize`) a solta; cópias e segundos `Finalize` não fazem nada; texturas com a mesma descrição (memória, formato, tamanho, layout) são uma só, achadas por um hash do endereço; toda referência solta espera 4 quadros. Sem a marca `VIEW_WRAPPER_MAGIC` gravada nos objetos do jogo |

Controles (Android): o overlay de toque ganhou o analógico direito (câmera: flutuante, em qualquer ponto livre da
metade direita), o d-pad e L3 / R3, todos no editor de layout (`TOUCH_NCTL` 12 -> 19; layouts salvos continuam
valendo). Todos os controles conectados são abertos e lidos juntos (antes só o primeiro da lista do SDL). Testado no
aparelho com toques injetados pelo adb (com a opção "Depuração USB (configurações de segurança)" do MIUI ligada): os
dois analógicos (movimento e câmera) e o botão de menu funcionam. **Menu do jogo e toque:** toques rápidos não
trocavam de aba - as abas do ImGui só aceitam o clique se já estavam sob o ponteiro no quadro anterior, e um dedo
aparece e clica no mesmo quadro; os eventos de mouse que o SDL faz de um dedo agora vão ao ImGui como
`ImGuiMouseSource_TouchScreen` (como o backend SDL3 da tela inicial já fazia). Arrastar o dedo na vertical rola a
página do menu. `LARECOMP_LOG_POINTER=1` mostra os eventos de ponteiro que o menu recebe. O controle físico ainda
não foi testado (nenhum conectado).

Ferramentas de diagnóstico novas: `LARECOMP_LOG_FILE=<caminho absoluto>` (o log também num arquivo: o logcat perde
linhas num debug frame), `LARECOMP_DEBUG_TEXSTATE=1` (no debug frame: de onde vêm os texels de cada textura, view,
swizzle), `LARECOMP_DEBUG_NO_PASSES=1` (debug frame só com o log: os PNGs esperam a GPU e mudam o timing),
`LARECOMP_DEBUG_DATA` mostra os storage buffers do vertex shader; os PNGs dos passes agora são do alvo do próprio pass
e com o alfa; screenshots no Android vão para o diretório de dados (o SDL tratava caminhos relativos como assets).
Para rodar sem tocar no aparelho: `android/run_boot.sh <s> "LARECOMP_INPUT=700:A:6,..."` (A em 700 abre a lista de
saves; ~3 As levam ao santuário).

Desempenho medido no santuário (`LARECOMP_LOG_PACE=1`, `LARECOMP_GPUPROF_AT`): quadro de ~28 ms (~35 fps; o BOTW roda
a 30 fps no Switch), a thread de render ocupada ~14 ms, a GPU ~19 ms de trabalho + ~6 ms parada no início do quadro.
Na GPU: 16 ms de passes (o G-buffer de 6 alvos 1600x900: 3,7 ms) e 2,4 ms em 15 cópias de "feedback" por quadro -
o jogo liga a mesma textura em dois slots de alvo e lê alvos depois de texture barriers (passes de decal / luz), e o
runtime tira um snapshot a cada vez, quebrando o render pass. Medido sem elas (`LARECOMP_NO_FEEDBACK=1`, ler o
alvo ligado): a GPU fica mais lenta (~22,5 ms de trabalho por quadro contra ~19 ms) - ler um alvo do pass aberto tira
o Turnip da renderização em tiles (GMEM), como faria `VK_EXT_attachment_feedback_loop_layout`; as cópias ficam. E o
jogo se prende ao próprio modo de 30 fps (intervalo de apresentação 2) nos dois casos. O `upload` de ~500 MB/s do log de fps são as cópias por
submissão dos constant buffers (`guest_copy`), CPU, fora do caminho crítico da GPU.

A miniatura na lista de saves sai preta também na build atual (save novo, 2026-10-18): o jogo desenha uma textura
854x480 RGB10A2 (objeto temporário na pilha, memória em `0x3dc60a000`) num alvo 256x144 e lê esse alvo de volta
(`CopyTextureToBuffer`, que funciona: 256x144 RGBA8 para o heap). Mas nada no runtime escreve aquela memória antes:
nenhum render target, cópia ou dispatch a cobre, e ela está zerada (`LARECOMP_WATCH_TEXADDR=<hex>` registra criações,
alvos, cópias e dispatches sobre um endereço; `LARECOMP_LOG_READBACK=1` as leituras de volta). Falta achar quem a
preenche no Switch (a função do jogo que cria o objeto: `main+0xa3e5bc`).

Paredes do santuário esticadas em "cortinas" verticais e picos (os "vértices estourando"): as alturas do terreno vêm
de tiles 256x256 R16 com a flag `0x40`, preenchidos pela CPU em linhas lineares do tamanho da largura (o campo de
stride, 4096, não cabe no espaço delas); o runtime os lia como block-linear e o mapa de alturas saía em listras.
Achado por bisseção dos shaders do quadro (`LARECOMP_SKIP_FSHASH`, agora até 128) e `LARECOMP_WATCH_TEXADDR`.
Corrigido: flag `0x40` lida como linear, linhas na largura.

Luz da porta do santuário (cutscene "You are the light"): os feixes saem com uma borda horizontal dura e colunas
verticais. Reproduzir: o save com o Link no terminal da porta, A a cada ~90 quadros a partir do 2950; a luz aparece
por volta do quadro 4300 (debug frame lá). O buffer de luz já sai errado: pass 800x450 RGBA16F (partículas e malhas
dos feixes, fs `8d989710b28bd28a` e outros), que leem uma profundidade linear R16F 1600x900 (feita pelo fs
`71ac217f9871658e` a partir do depth, com snapshot de feedback) para o fade contra a geometria. A suspeita é essa
profundidade (ou a comparação dela); não confirmada. Nenhum formato sem filtro linear é amostrado (o runtime avisa
uma vez por formato se houver).
Bisseção: o fs `0363359e11684e85` (feixes) faz `TMML.B` (LOD via handle bindless, lido de `cb2`). O tradutor tinha os
opcodes trocados (0xdf58 é TMML, 0xdf60 é TMML.B) e consultava uma textura inexistente. Corrigido: o TMML.B usa a
textura do próximo TEX / TEXS com o mesmo registrador de coordenada. A imagem mudou pouco: a borda horizontal coincide
com a verga da porta (geometria na frente da luz), o que parece ser o comportamento do jogo.

TMML.B com handle em constant buffer: três fragment shaders (luz da porta, terreno e outros) só consultam o LOD, sem
amostragem depois, e falhavam na tradução ("bindless tmml"). Todo draw com eles era pulado, uns 700 por segundo
(o "skipped" do log de fps). Agora o tradutor acha o `MOV c[banco][off]` / `LDC` que carrega o registrador do handle
(`handle_cbuf`) e cria um slot bindless (`ShaderTexture.bindless_cbuf/offset`). O runtime lê o handle de 32 bits
nesse cbuf na hora do draw (`shader_texture_handle`, exec.c). Resultado: 0 draws pulados, e o santuário ficou bem menos
enevoado (paredes nítidas).

Link com as pernas "dentro do chão" (sala da porta, save 2, quadro ~2800, `bis3.sh`): NÃO resolvido. Achados:
- Com os shaders do santuário pulados (`LARECOMP_SKIP_FSHASH`), aparece o terreno de fora (chão rochoso do Planalto,
  céu, paredões), com a superfície na altura do joelho do Link. O terreno parece estar alto demais e atravessar o chão
  do santuário, mas pode ser outra coisa (perna dobrada por IK do pé, ou o Link afundado pela física). Falta uma vista
  lateral para decidir.
- Alturas do terreno: array 1024x1024x8 R16_UNORM (`f0x6`, 4 níveis, em `362ab4000` neste save), renderizado pela GPU
  camada a camada e nível a nível (views certas, `LARECOMP_WATCH_TEXADDR`). Cada camada vem de um tile 256x256 R16 com
  flag `0x40` ampliado 4x (fs `384947400`: amostra + offset de `cb3[2]`). O vs do terreno (`3dabe0800`) lê o array
  com `textureLod` (camada de `cb6[7].x`, LOD `cb6[7].y`, morph com `cb7`); a decodificação parece certa.
- Tentativa descartada: marcar os tiles `0x40` como dinâmicos (re-hash a cada uso, textures.c `upload_texture`) não
  mudou as pernas e deixou a imagem toda verde (sobrescreve conteúdo escrito pela GPU). Revertida.
- Próximos passos: comparar as alturas do array com o tile de origem (ler de volta), conferir se o tile tem
  conteúdo velho no momento do passe de alturas (frames ~1086-1222 e 1744), e tirar uma vista lateral do Link.
- Rodada 2: pulando só os 7 shaders do terreno (`013b27bb`, `178b1629`, `31fe3974`, `4ec45c9b`, `5e869bb1`,
  `8e67d14f`, `dfb4b02d`), as pernas continuam cortadas, então o terreno renderizado não é o que as cobre. Pulando
  santuário e terreno juntos, o modelo inteiro do Link aparece: joelho esquerdo levantado, perna direita esticada
  bem para baixo, quadril baixo. É pose de IK de pé (cada pé num chão diferente), não erro de skinning. A causa está
  na CPU (raycast de chão / física / IK), não na GPU.
- Pistas: SIMD e ponto flutuante rodam como asm nativo (`--simd asm`) e o FPCR é repassado (`rt_set_fpcr`), então a
  precisão bate com o hardware. Na cutscene da porta os pés ficam certos (sem IK). Correndo, as pernas parecem
  normais. Na mesma área o terreno renderizado também aparece alto demais (grama verde furando o chão perto da porta,
  e o chão rochoso na altura do joelho com o santuário escondido). Se a física do terreno também estiver alta, o pé
  esquerdo pisa nela: as duas coisas viriam dos dados de altura (lidos e descomprimidos pela CPU), ou de algum
  mecanismo do jogo que esconde o terreno dentro do santuário e que não funciona aqui.

Luz da porta, rodada 2 (debug frame 4300, `android/out/dbg48`): o buffer de luz 800x450 (pass 82) já sai com a borda
dura e as colunas verticais. O fade dos feixes (fs `36ec29700`, hash `8d989710`) compara a profundidade linear da
cena (R16F 1600x900, fs `3cb594e00`: `A/(1 - d*B) - A`) com a do fragmento. A fórmula parece certa, e a borda
superior coincide com a verga da porta (geometria na frente da luz). Os feixes amostram uma textura BC5 128x128
(`f0x4b`, view com swizzle R R R G). As colunas podem ser o desenho do jogo ou essa textura mal decodificada; não
confirmado.

Rodada 3, com referências do Switch (vídeo, porta abrindo e terminal Sheikah):
- TMML.B: o handle vem de `c[2][0x20 + 8*slot]`, a tabela de texturas ligadas do NVN (a mesma que um TEX com índice
  lê). O runtime não guarda handles no banco 2: lia 0 / 1.0, textura nula, LOD 0. Agora um handle do banco 2 vira o
  slot de textura normal. Os bancos de hardware `k` são `st.ubuf[stage][k-3]` (0 do driver, 1 constantes do shader,
  2 tabela de texturas): o caminho bindless corrigido para isso. O sol voltou a ter forma de estrela.
- Brilho Sheikah em blocos ("padrão de deformação"): o bloom renderiza nos níveis de mip 1-4 de uma textura 320x180 e
  lê de volta com um sampler; no nosso, o sampler do slot estava com LOD 0..0 (só o nível 0), então os níveis
  borrados nunca eram lidos. O jogo recicla slots do sampler pool sem esperar fence, e a thread de render (async.c)
  ainda tinha draws na fila lendo o slot. Corrigido: `register_sampler` (objects.c) espera a fila (`vk_async_drain`)
  quando o conteúdo do slot muda, como o `register_texture` já fazia. Brilho liso, igual à referência; ~29 fps,
  sem perda. A névoa da luz da porta também diminuiu bastante e a escada aparece.
- Diagnóstico novo: `LARECOMP_WATCH_TEXID=<hex>` / `LARECOMP_WATCH_SMPID=<dec>` registram os registros de um slot;
  o log do debug frame mostra o sampler (LOD, filtros, handle) de cada textura e o banco/offset dos bindless.
- Descartado: modo de profundidade 1 (`NEAR_IS_ZERO`) deixa a tela preta; o BOTW usa o padrão -1..1.
- Referência (vídeo 4K do Switch, youtu.be/7zX3D98tt-U, 3:19-4:15): o brilho laranja/azul do pedestal é liso (o nosso
  agora também); no flash da ativação (3:35) há um anel, partículas e um traço de lente fino; a luz da porta (4:05)
  tem o sol em estrela e uma faixa horizontal de feixes com borda superior reta, como a nossa (é o desenho do jogo);
  a névoa da porta no nosso ainda é maior e mais clara; a névoa do chão à esquerda é branca no Switch e esverdeada
  aqui.
- Retângulo azul da ativação: é o traço de lente (fs `36e1fc700`, hash `e97e29b6a86cf9ae`; vs `36e1fa100`, 1 quad em
  strip de 4 vértices, cantos pelo `gl_VertexIndex`), textura BC4 256x78 (um quadrante do traço, centro no canto
  inferior direito; swizzle R R R R, alpha = R) com sampler wrap 7 (clamp). A textura decodificada está certa
  (`LARECOMP_DUMP_TEXADDR`, despejo dos blocos). No nosso o `v` da textura fica constante (>= 1) em todo o quad: faixa
  uniforme com bordas duras. Wrap 7 como espelho não muda nada. Falta rastrear o `v2.y` do vs (`-r3 * cb9[48].y /
  cb9[48].w`). Os streams desse draw têm 16 bytes (um vértice) para 4 vértices: agora a leitura além do tamanho dá
  zero, como no Switch (antes lia os buffers vizinhos); não resolveu a faixa.
- Tela preta ao ativar (relato): não reproduzida em ~10 execuções roteirizadas.
- Traço da ativação e névoa da porta: com o wrap 7 lido como *mirrored repeat* (`LARECOMP_WRAP7`, teste) ficam
  iguais ao Switch (3:35, 4:05), mas a barra clara da tela de carregamento, que também usa wrap 7, passa a se
  repetir em listras: ali o 7 tem de ser clamp. Troca revertida (a9537a9). O mesmo valor não pode significar as duas
  coisas: um dos dois lados está errado no nosso (o uv do traço, 0..2 por `cb9[48] = (2, 2, 1, 1)` sobre um
  quadrante da imagem, ou o sampler que o traço realmente usa no Switch). `BindSeparateSampler(s)` nunca é chamado
  (log em cmdbuf.c), então não é um sampler separado sobrescrevendo o slot.
- Tabela de wrap do NVN: a tabela do agl (main + 0x1c21ccc, [1, 6, 7, 5, 3, 0, 3, 4]) está na ordem do próprio agl,
  não na do GX2. Lida como GX2, trocava 3 e 5 (bd75f20) e o shadow map do sol (wrap 5, clamp to border) virava
  mirror-once: sol iluminando as paredes de dentro do santuário depois da porta aberta. Voltou à tabela original
  (fa7085a): 0 / 7 clamp to edge, 1 repeat, 2 / 3 mirror clamp to edge, 4 / 5 clamp to border, 6 mirrored repeat.
- Traço / névoa / luz da porta, RESOLVIDO: o eft cria uma tabela de 16 samplers (4x4 wraps) e registra cada um pelo
  gerenciador de pool do agl, que reaproveita o slot de um sampler igual já registrado, comparando os objetos
  NVNsampler do guest. No nosso HLE o objeto só tinha o ponteiro do host e bytes velhos: o sampler mirror/mirror do
  eft "batia" com o padrão clamp do agl (índice 257) e ficava com o slot dele. Agora o `nvnSamplerInitialize` também
  grava o descritor no objeto do guest (+8). Traço da ativação, sol e feixes da porta, névoa e a luz entrando pela
  fresta da porta ficaram como no Switch. Diagnóstico: `LARECOMP_LOG_SMPREG=1` (registros), `LARECOMP_LOG_WRAP=1`,
  `LARECOMP_WATCH_HANDLE_TEX=<id>`.
- Sol iluminando o santuário por dentro depois da porta aberta: o `nvnCommandBufferSetDepthClamp` era ignorado. As
  cascatas de sombra do sol (896x896x3, `rt 0 d0x32`) recortavam a montanha acima do santuário (mais perto do sol que o
  near plane): visto do sol, o chão do corredor aparecia sem nada em cima. Com o depth clamp (66d40a9) a sala fica
  escura e com névoa, como no Switch em 3:56. Ainda aparece clara em algumas execuções (1 de 3): resta algo dependente
  de tempo. O debug frame agora salva passes só de profundidade (as cascatas, em vermelho).
- Fechamento ao recarregar (morrer, ou carregar um save já em jogo): SIGSEGV em f_1010c1a04 (main + 0x10c1a04), lendo
  o vtable de um objeto nulo em `this + 0xc60` (o caminho com o bit 0 do segundo argumento). Reproduzido duas vezes
  (2026-10-18 e 2026-10-19). Lógica do jogo num estado que no Switch não acontece (objeto ainda não criado:
  suspeita de ordem entre threads).
- Planalto (save 3, 2026-10-19 17:11): o Link aparece com as pernas cortadas no joelho na grama (o "afundando"),
  céu marrom e dessaturado; num quadro mais tarde (~2600) a cena ficou toda preta com o HUD intacto (suspeita de
  NaN na exposição automática), sem erro no log.
- Link afundando, hipótese "a CPU lê memória que a GPU escreveu" (o *reactive flushing* do Eden): descartada para
  o array de alturas do terreno (1024x1024x8 R16, f0x6, em 362ab4000). `LARECOMP_WATCH_READ_TEX=<w>x<h>x<d>` fecha as
  páginas da primeira textura com esse formato (`rt_range_watch` em dispatch.c, rearmado a cada 50 ms). Cada acesso
  é registrado uma vez por função, como leitura ou escrita (ESR WnR). Com o watch armado já no init, o jogo fechava
  (f_1043283e8, nulo + 0x10): o loader também preenche essa memória pelo kernel, e com a página fechada a leitura
  falha. Por isso arma depois de `LARECOMP_WATCH_READ_DELAY` s (padrão 60). Armado em jogo no planalto por ~60 s
  (~49 fps): 0 acessos. Na carga, f_104327900 mexe nessa memória pela CPU. Os counters do jogo são só timestamps
  (tipos 0 e 16); não há occlusion query. O pé deve vir da física / raycast (CPU), não da GPU.
  Rodada 2: o watch aceita formato e vários intervalos (`LARECOMP_WATCH_READ_TEX=0x0x8:25`, 0 = qualquer
  dimensão, só texturas >= 64 KiB). Nenhum dos arrays do terreno (alturas f0x6; 512x512x8 e 64x64x8 f0x25;
  256x256x8 f0x2a) é lido pela CPU em jogo. Intervalos com páginas de 16 KiB invadem objetos vizinhos e já
  derrubaram o jogo (nvnBufferGetAddress nulo); vigie poucos intervalos e grandes. Vista lateral no planalto
  (RSTICK_LEFT): o Link está de pé, pernas retas, afundado por igual até a canela. Não é pose de IK; o modelo
  inteiro está baixo, como na neve ou areia funda. Travar em 30 fps (`LARECOMP_PRESENT_WAIT=1`: o present espera
  o intervalo do jogo em vsyncs; sem isso o BOTW apresenta ~45-49 vezes por segundo com intervalo 2) não muda
  nada. Pista do Cemu: o afundamento no Wii U vinha do jogo usar a GPU para trabalho fora do gráfico (corrigido no
  Cemu 1.7.4). `LARECOMP_LOG_READBACK=1` não registrou nenhuma cópia textura→buffer no planalto. Pausado
  (2026-10-19): o usuário vai migrar para o update (v1.6.0) e espera que isso resolva.
- Céu marrom no planalto (2026-10-19): corrigido. O céu é desenhado a partir de uma LUT de dispersão atmosférica 3D
  (256x32x16 RGBA16F, escrita pela CPU na carga; fs `81f35db4` gera o céu 256x256, e o triângulo `38905430` o
  desenha). Lida com blocos de 4 GOBs de altura, a LUT saía embaralhada: média R:G:B 16:9:2 no lugar de azul.
  `LARECOMP_DUMP_TEX=<w>x<h>x<d>,<prefixo>` salva os bytes do guest e a versão desembaralhada de um upload. Medindo
  a suavidade dos dados em cada combinação, venceu altura 1 GOB com profundidade 16. É a regra do deko3d (que
  segue o NVN): texturas 3D só têm tile em profundidade (`pick(1.5 x depth)`). As 2D usam
  `pick(1.5 x altura em GOBs)`, arredondando para baixo até 16, enquanto o nosso arredondava a contagem para cima.
  Isso muda poucas texturas não empacotadas (`LARECOMP_LOG_BHDIFF=1` lista; `LARECOMP_TEGRA_OLD_BH=1` volta a regra
  antiga). O céu agora fica azul-claro com névoa branca, e a cena perdeu o tom sépia.
- Write-back de alvos pequenos (2026-10-19): o jogo lê na CPU (f_10120fdc4) os limites de profundidade que reduz
  na GPU até 3x3 RGBA16F (do depth 1600x900 -> 800x450 f0xa -> 192 -> ... -> 3x3, provavelmente para ajustar as
  cascatas de sombra). A memória nunca recebia esses texels. Agora alvos de cor minúsculos (1 nível, sem escala,
  até 4 KiB) são copiados no fim do pass e escritos na memória do guest quando a GPU termina
  (`LARECOMP_NO_WRITEBACK=1` desliga, `LARECOMP_LOG_WRITEBACK=1` lista). `LARECOMP_WATCH_READ_ADDR=<hex>:<len>[+...]`
  vigia leituras da CPU em bytes do guest (armado depois de `LARECOMP_WATCH_READ_DELAY`).
- Véu branco / céu amarelo no planalto: corrigido no tradutor de shaders (2026-10-19). O shader do céu (`81f35db4`,
  Bruneton) escolhe as constantes "chão" ou "céu" com `FSET.CC RZ, ...` seguido de `@P0 BRA CC.NEU`: o desvio
  depende do predicado e dos flags do FSET. O tradutor não emitia nada para o FSET em RZ e ignorava o teste de
  CC do BRA (bits 0-4), então desviava só por P0. Olhando para cima, as constantes do céu eram puladas e o shader
  rodava com valores velhos dos registradores: céu ~10x claro e amarelo, cubemap e exposição verdes, véu sobre tudo.
  - Agora FSET/ISET com `.CC` atualizam `ccz`/`ccs` (mesmo em RZ), e BRA com teste de CC (`cc_test`, o mesmo do
    CSETP) só desvia se o teste passar, inclusive no caminho estruturado (`b->cond`) e na análise de fluxo.
  - Resultado: céu azul, Montanha da Morte, castelo e serras ao longe visíveis, como no vídeo (4:41).
  - Como foi achado: `android/out/veil/simsky.py` reimplementa o shader e bate com a GPU; a LUT é o próprio
    `System/KSys/sky.skybin` (Bootup_Graphics.pack, offset 128 KiB, idêntica byte a byte); e
    `LARECOMP_SAVE_MSL=1` anota cada instrução no GLSL de `LARECOMP_DUMP_FS`.
  - Outras ferramentas: `LARECOMP_DEBUG_RAW_PASSES=1` (texels float dos passes), `LARECOMP_DEBUG_DATA` (bancos
    certos do fragment shader), `LARECOMP_LOG_UBO_UPDATE=<endereço>`, `LARECOMP_WRITEBACK_MAX=<bytes>`.
  - Ainda visível: buracos brancos nas serras distantes (o terreno distante com falhas já anotado).
- Brilho Sheikah em blocos (ainda aberto): a textura do bloom (320x180, 5 níveis) é registrada uma vez, sem view
  (`LARECOMP_WATCH_TEXID=7fffffff` registra a do bloom); todas as passadas de redução / blur leem com um sampler de LOD
  0..0 (nível 0) e escrevem no nível k (view do render target); a composição final lê com LOD 1..1 (nível 1). O jogo
  só tem samplers 0..0 e 1..1 para isso. Fazemos o que o jogo pede; a diferença está em algum detalhe de amostragem.
- O lifter contra o Unicorn com as instruções do próprio BOTW, no celular (`tools/test_lift_android.py`): 20000
  codificações, 59952 casos, 0 falhas.
- Ainda falta: a luz entrando por baixo da porta enquanto ela sobe (Switch 3:48, nosso ~3745-3765): no nosso a fresta
  fica escura. Os debug frames com despejo de passes mudam o tempo do jogo e não pegaram esse momento.

Também anotado: o jogo fechou sozinho uma vez ao carregar um save (SIGSEGV no código do jogo, `f_1010c1a04+284`,
leitura do endereço 0, x0 = 0; backtrace no logcat). Não reproduzido ainda.

Ainda a ver:
o controle físico com o BOTW; a miniatura do save na lista não aparece; upload de ~500 MB/s
(`upload` no log de fps).

Falta (precisa rodar o jogo):

- Rodar: no PC (falta o MSYS2, ver Fase 1) ou no celular (`android/push_game.sh`, com o celular no adb).
- Comandos de IPC do SDK de 2017 que o runtime não conhece; a ordem de construtores entre `main` e `sdk`.
- ~~Os slots das vtables de `movie::Extractor` / `Decoder`~~: lidos das vtables do `multimedia.nss` dos dois SDKs, são
  os mesmos onde os dois têm; o BOTW abre o vídeo pelo slot 2 (`SetDataSource(const char*)`, caminhos
  `content://Movie/<nome>.webm`), agora tratado no `movie.c`.
- A revisão do renderizador de áudio (`audren:u`) do SDK de 2017: o `services/audio.c` lê o buffer de atualização no
  formato das revisões que o LAS usava; revisões antigas podem ter outro layout de vozes / memory pools.
- `romfs.bin` de 13,4 GiB + a cópia do `romfs/` (o `movie.c` e o fundo do launcher leem arquivos soltos): ~27 GB.
- Meta: a tela de carregamento / logo da Nintendo.

### Fase 4: gráficos

- Versão da API NVN do BOTW contra `runtime/nvn`; shaders do jogo pelo tradutor Maxwell.
- Meta: tela título, depois o Planalto Inicial. Cache de pipelines no disco.

### Fase 5: áudio, vídeos, saves, entrada

### Fase 6: Android

- `--simd asm --dyn-base`, NDK, `botwgame.so`; romfs lido no lugar; medir memória (aparelhos de 8 GB).
- Turnip e driver do sistema (arena de GPU), como no LAS.

### Fase 7: desempenho

- Threads do Havok e dos jobs do jogo nos núcleos (o LAS precisou emular o núcleo 0 do Switch), thread de render,
  resolução interna, ritmo de quadros (o jogo roda a 30 fps no Switch).

### Fase 8: distribuição

- Release no GitHub (desktop e APK), sem nada do jogo.

## Regras

- Nada da Nintendo no repositório: nem o dump, nem código gerado, nem assets (ver `CONTRIBUTING.md`).
- Mudanças no motor que não dependem do BOTW devem ficar fáceis de levar de volta ao LAS (e vice-versa).
