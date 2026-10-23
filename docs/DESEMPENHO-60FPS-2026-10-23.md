# Rumo aos 60 fps no POCO F7 Ultra (Turnip): medições de 23/10/2026

Cena: 1.8.2, Grande Platô perto do Templo do Tempo (save de teste), Video > Frame rate **60**, resolução interna 1x.
Aparelho: Adreno 830, Mesa Turnip 26.3-devel.

## Como medir sem se enganar

- **Use `frame_rate = 60` no arquivo de teste.**
  - Com `frame_rate = 30` (o padrão do app), o pacer finge contadores de GPU lentos.
  - O jogo então fica na resolução dinâmica mínima, mesmo com `LARECOMP_FPS=60`.
  - Os testes de antes desta noite estavam nesse modo misto.
- **O celular esquenta (60–90 °C) em poucos minutos e, carregando, não esfria abaixo de ~50 °C.**
  - Duas rodadas seguidas diferem mais do que a maioria das mudanças.
  - Use **`LARECOMP_AB=<quadros>:<chave>`**: alterna uma chave a cada N quadros dentro da mesma rodada, e o log
    `[pace]` marca `ab 0/1`. Os dois lados ficam no mesmo calor.
  - Chaves: `split`, `ubo`, `sgsr`, `nobarrier`, `copy`, `chunk`.
- **`LARECOMP_GPUPROF_AT=<quadro>`:** tempo de GPU por passe. Os valores absolutos ficam inflados pelos timestamps,
  mas as proporções valem.
- **`simpleperf`** funciona no app (debuggable). Símbolos: a `libmain.so` de `merged_native_libs` (-O2 -g).

## O que mudou e quanto rendeu

Medições na mesma rodada (A/B):

| Mudança | Antes | Depois |
| --- | --- | --- |
| Leitura no lugar em vez de cópia de feedback (9d0ea24, 1725400) | 31,2 fps, GPU 22,4 ms | **40,5 fps, GPU 16,6 ms** |
| SGSR 1 em vez de FSR 1 (padrão novo no Android, 6a67bd8) | 35,9 fps, GPU 19,7 ms | **39,0 fps, GPU 18,3 ms** |
| Constant buffers lidos no lugar (`LARECOMP_UBO_DIRECT=1`) | render 13,0 ms | render 12,4 ms (GPU igual) |

- **Cópias de feedback:** os decalques do terreno (fs `f984e489`) leem e escrevem dois alvos do G-buffer, com uma barreira
  a cada um ou dois draws. Eram 32 pares de cópias de tela cheia por quadro, ~14 ms de GPU. Agora o passe só termina onde
  havia cópia, e o draw lê o próprio alvo. É um feedback loop que o Vulkan deixa indefinido sem
  `VK_EXT_attachment_feedback_loop_layout`, então fica **só no Turnip**, onde foi conferido quadro a quadro. Os outros
  drivers mantêm as cópias, ou usam `LARECOMP_FEEDBACK_INPLACE=1`.
- **Traços pretos na grama (b9321e7):** eram do FSR 1. A saída intermediária A2B10G10R10 comprimida (UBWC) devolvia
  blocos 16x4 de zeros ao RCAS no Turnip. Agora essa imagem é criada sem compressão. Antes, eram ~1 por quadro.

## Testes sem efeito (os padrões ficam)

- **Resolução 50%:** GPU igual (~18 ms). O custo não é por pixel.
- **Saída final em 1080p em vez de 1440p** (`LARECOMP_OUT_LINES=1080`): ≤ 1 ms.
- **Bilinear em vez de SGSR:** dentro do ruído.
- **`TU_DEBUG=sysmem`:** igual ao autotune. **`TU_DEBUG=gmem`:** muito pior (GPU ~30 ms).
- **Matemática de shader em 32 bits** (`LARECOMP_VK_INT32=1`): igual ao int64 emulado.
- **`IR3_SHADER_DEBUG=nouboopt`:** igual.
- **Sem a barreira no início de cada passe** (`nobarrier`): igual.
- **Sem dividir o passe longo** (`split`): ~0,5 ms a menos de GPU, mas o quadro fica mais longo, porque a GPU começa
  depois.
- **Submissões a cada 0,5 ms** (`chunk`): igual.
- **Janela compartilhada para os constant buffers de 64 KB:** pior. São sempre o mesmo endereço, com 16 bytes
  reescritos antes de cada draw (+0x70). Cada bind precisa da própria cópia, ~14 MB por quadro de memcpy.

## Onde está o limite agora

- **O jogo espera o fence do quadro anterior a cada quadro** (`LARECOMP_LOG_SYNC`, lr `100d81418`).
  - São 8,5–9 ms esperando a GPU; a tradução já passou desse ponto.
  - A GPU (~17 ms de trabalho, mais ~3 ms parada no início de cada quadro) é o que segura o jogo em ~40 fps.
  - A tela é de 120 Hz: entre 16,7 e 25 ms, o quadro cai em 25 ms.
- **Para 60 estáveis aqui, o trabalho de GPU precisa ficar em ~13–14 ms**, ~25% a menos. O custo é por draw (~2 µs)
  e por passe (~20–40 µs; são ~190 passes por quadro), não por pixel.
- **Thread de render:** ~12–13 ms por quadro. Não é o limite hoje, mas está perto. Maiores itens no `simpleperf`:
  - `draw_` 13%;
  - descriptor sets 12%;
  - memcpy dos constant buffers 12%;
  - `begin_pass` 9%.

## Próximos passos possíveis

1. **Passes dos decalques sem quebrar o passe:** barreira dentro do passe com `VK_EXT_attachment_feedback_loop_layout`
   e `VK_KHR_dynamic_rendering_local_read` (o Turnip tem os dois). Corta ~45–90 passes de um draw por quadro.
2. **Medir o clock da GPU** (contadores do `VK_KHR_performance_query` do Turnip), para separar trabalho de
   governador de frequência.
3. **Geração de quadros (30 → 60)** para GPUs que não chegam a 60: ver `FRAMEGEN-AVALIACAO.md`.
