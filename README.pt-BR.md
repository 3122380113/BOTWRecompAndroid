# BOTWRecompAndroid

[English](README.md) | **Português**

Uma recompilação estática de *The Legend of Zelda: Breath of the Wild* (Nintendo Switch, **v1.0.0**) para Android. **Não é emulação**: o código ARM64 do jogo vira C compilado para a sua máquina, e
os gráficos (NVN) são traduzidos para Vulkan.

O projeto parte do motor do **[LAS Recomp](https://github.com/peiikai/LASRecomp)**, de peiikai (uma recompilação de
*Link's Awakening*), e do seu porte para Android, o **[LASRecompAndroid](https://github.com/mamcjr/LASRecompAndroid)**.

> [!WARNING]
> **Em estágio inicial: ainda não é jogável.** O plano, o que se sabe dos arquivos do jogo e o estado de cada fase
> estão em [docs/BOTW-PORT.md](docs/BOTW-PORT.md).

## Plataforma suportada

Android é o único alvo do aplicativo. Os ports e pacotes desktop foram descontinuados em 2026-10-22.
Windows, Linux e macOS podem continuar sendo usados como ambientes de desenvolvimento com o Android SDK/NDK.

## Estado

- **Recompilação:** todo o código do jogo (main: 104.671 funções; sdk: 17.689) vira C sem nenhuma instrução não
  suportada e compila para Android (NDK).
- **Android (primeiros testes num POCO F7 Ultra, driver da Qualcomm):** o jogo passa pelo boot do SDK de 2017,
  inicializa os gráficos (NVN 52 para Vulkan), o áudio, a entrada e os saves, carrega seus dados e **apresenta os
  primeiros quadros**. Ainda cai depois de alguns segundos: a ordem entre as threads que dividem o núcleo 0 do Switch é
  o trabalho atual.
- **Ainda não:** uma imagem na tela para ver, shaders com instruções Maxwell que o tradutor não conhece, os vídeos.

> [!IMPORTANT]
> Como o LAS Recomp, este projeto é feito quase todo com IA (Claude, da Anthropic, pelo Claude Code), dirigido,
> testado e revisado por um humano.

> [!NOTE]
> **Nenhum arquivo do jogo vem incluído.** Você precisa do seu próprio dump, obtido legalmente, de *Breath of the
> Wild* v1.0.0 (`exefs` e `romfs`). Não serão dadas instruções de como obtê-lo.

## Testando no Android (desenvolvedores)

Com o Android SDK / NDK, um celular no adb e o seu dump em `Dump/`:

```bash
cd android/botwrecompandroid && ./gradlew assembleDebug    # o app
android/build_gamelib.sh Dump/01007EF00011E000/exefs  # o código do jogo (cerca de uma hora)
android/push_game.sh Dump/01007EF00011E000           # arquivos e código do jogo para o celular
android/run_boot.sh 120                               # abre o jogo, log em android/out/boot.log
```

## Conferindo o dump

Coloque o dump em `Dump/<title id>/` (`Dump/01007EF00011E000/exefs` e `.../romfs`; a pasta é ignorada pelo git) e
rode, com Python e o pacote `lz4`:

```bash
cd tools && python botw_dump.py
```

O script confere cada módulo, identifica a versão do jogo pelo build id do `main` e conta o romfs.

## Créditos

- [peiikai](https://github.com/peiikai), pelo LAS Recomp, o recompilador e o runtime de onde este projeto parte
  (MIT, ver [LICENSE](LICENSE)). O README original fica em [docs/las/](docs/las/).
- A decompilação [zeldaret/botw](https://github.com/zeldaret/botw), pelo que se sabe do código do jogo.
- [libadrenotools](https://github.com/bylaws/libadrenotools), [SDL](https://libsdl.org),
  [Dear ImGui](https://github.com/ocornut/imgui), [FFmpeg](https://ffmpeg.org).

*The Legend of Zelda* e *Breath of the Wild* são marcas da Nintendo. Este projeto não é afiliado nem endossado pela
Nintendo.
