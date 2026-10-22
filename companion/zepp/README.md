# Recomp Mapa 0.2.1 — Amazfit GTR 4 New

Mapa externo armazenado no relógio, com posição e direção de Link, pinos/carimbos e indicadores da missão selecionada enviados pelo celular pareado. A página principal não captura nem transmite a imagem do minimapa.

## Correção de interrupções em 0.2.1

O usuário relatou travamento e reinício do relógio ao receber uma notificação com 0.2.0. A causa do reinício no firmware ainda não foi confirmada por logs. A revisão corrige riscos encontrados no código: cancela timers e libera controles na pausa, descarta respostas e callbacks da geração anterior, aguarda 300 ms antes de retomar e não intercepta gestos verticais do sistema. A transição preserva a posição e o zoom do mapa.

O desenho reutiliza no máximo 16 componentes de imagem e os componentes dos marcadores, sem destruir/recriar componentes a cada amostra. Não redefine a imagem quando só sua posição muda, nem recarrega os tiles de um mapa parado. Isso limita o pico de imagens durante pan/zoom e reduz alocações nas interrupções.

24 testes passaram, incluindo 150 ciclos simulados de interrupção/retomada, respostas antigas de sucesso/erro, cancelamento de desenho, ausência de acesso à UI durante pausa/transição e limite dos componentes. Esses testes usam mocks da API; a correção ainda precisa ser validada recebendo uma notificação no GTR 4 New. Atualize somente o Mini Program pelo QR 0.2.1; o APK não mudou. Ciclo de vida e gestos seguem a documentação oficial de [delegate](https://docs.zepp.com/docs/1.0/watchface/api/hmUI/createWidget/) e [gestos](https://docs.zepp.com/docs/1.0/reference/device-app-api/hmApp/gestureEvent/registerGestureEvent/).

## Instalação

1. Instale o APK atualizado de `android/botwrecompandroid/app/build/outputs/apk/debug/app-debug.apk`.
2. No Android, mantenha pressionado o ícone do Recomp → **Mapa no relógio**. Ative o compartilhamento, salve e copie o código.
3. No modo desenvolvedor do Zepp, adicione o Mini Program pelo QR da prévia 0.2.0. Use o alvo **Amazfit GTR 4 new** (9437441), API 1.0 e tela 480×480. A versão do sistema exibida no relógio não corresponde ao API_LEVEL do catálogo.
4. Nas configurações do Mini Program no Zepp, cole o código de 32 caracteres. O código anterior continua válido após atualizar o APK.
5. Abra o jogo e o Recomp Mapa no relógio, com Bluetooth conectado.

A coroa muda entre quatro níveis de zoom. Arraste para explorar; **Centrar Link** retoma o acompanhamento. A seta azul indica Link, pontos laranja representam pinos/carimbos e círculos amarelos representam objetivos da missão. Nesta versão, os pinos e carimbos conservam posição e tipo no protocolo, mas usam um símbolo visual comum.

## Preparar mapa e pacote

O mapa vem exclusivamente do dump local do usuário. Os tiles extraídos são ignorados pelo Git; não publicar nem versionar o pacote que os contém. O visualizador usa níveis de 256, 512, 1024 e 2048 pixels, divididos em 340 tiles de 128×128.

```powershell
python tools/export_watch_map.py --romfs Dump/01007EF00011E000/romfs --output companion/zepp/assets/gtr-4-new/map
cd companion/zepp
npm ci
npm test
npm run build
node tools/zeus.cjs login
npm run preview -- --target "Amazfit GTR 4 new"
```

O exportador requer Pillow e NumPy. O wrapper Zeus 1.9.3 ajusta a resolução de módulo privado e salva a matriz do QR em `dist/preview-qr.json`. Os scripts excluem os produtos intermediários do pacote; a compilação local final tem aproximadamente 3,9 MB. Gerar a prévia envia o pacote, incluindo o mapa, ao serviço Zepp e depende de autorização do usuário. A prévia tem prazo de validade indicado pelo Zeus. O `.zab` é o pacote do relógio, não um APK. O appId 1202601 é de desenvolvimento.

## Ponte e limites

O serviço opcional escuta apenas em `127.0.0.1:18880`, exige código e funciona com o Recomp em primeiro plano. `/v1/state.json` devolve JSON com sessão, sequência, mapa, posição, direção, marcadores e objetivos. O Side Service no Zepp busca esse estado e o envia por Bluetooth. Há um pedido em voo, intervalo nominal de um segundo e pausa quando a página fica inativa. A taxa real depende do Zepp e do relógio. Após cinco segundos sem resposta, a posição é identificada como desatualizada.

A leitura nativa suporta especificamente a revisão Switch 1.0.0, build `2094f152836683a3632c6153fd84ab80`. Verifica instruções dessa revisão, limites de ponteiros e gerações de sessão. Usa `pread` somente para leitura de `/proc/self/mem`; não chama funções do jogo na thread de rede. Posição e seleção podem mudar entre leituras, portanto isso não é um snapshot atômico do motor. Seleção/etapa são conferidas novamente antes da resposta. Revisões desconhecidas e carregamentos retornam indisponibilidade.

Objetivos vêm dos indicadores da etapa da missão selecionada, filtrados pelas flags de conclusão, em vez de usar arrays de guias que podem guardar posições antigas. As coordenadas são as posições definidas pelo indicador da missão; seguir um NPC em movimento ainda não foi validado. Interiores/santuários não usam o mapa externo para projetar Link.

Os endpoints de captura `/v1/map.json` e `/v1/map.png` e a página antiga permanecem como diagnóstico legado; não são usados pela página principal. Os campos de recorte no Android só afetam esse diagnóstico.

## Validação

- Posição real confirmada por leitura antes/depois de caminhar no celular (deslocamento aproximado de 60 m).
- Endpoint Android instalado retornou Link e dois marcadores colocados pelo usuário.
- Missão selecionada `FirstTower` corresponde a **Follow the Sheikah Slate**, confirmada pelo usuário; indicador da torre em aproximadamente x=-560, z=1695.
- APK final instalado: após carregar o save, `/v1/state.json` retornou `objectivesAvailable:true` e o objetivo em x=-560.035, z=1694.863. Os dois marcadores de teste anteriores não reapareceram nesse save.
- 23 testes Node passaram, incluindo protocolo, validação, cobertura dos tiles, pan, zoom, recentralização, objetivo e descarte ao fechar a página.
- Prévia 0.2.0 enviada à Zepp com autorização explícita do usuário em 20/10/2026. QR válido até 27/10/2026, em `dist/preview-qr-0.2.0.png`; pacote final com 340 tiles, API 1.0, cerca de 3,9 MB e sem produtos intermediários.
- O espelhamento antigo 0.1.4 foi confirmado no relógio pelo usuário. A exibição do mapa local 0.2.0, os gestos e a latência ainda precisam ser conferidos no GTR 4 New.

Licença e código legado de comunicação em `shared/legacy`. Referência de comunicação: [exemplo oficial API 1.0](https://github.com/zepp-health/zeppos-samples/tree/main/application/1.0/fetch-api).
