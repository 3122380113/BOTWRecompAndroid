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
