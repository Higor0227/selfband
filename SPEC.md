# Selfband — Especificação do Produto e Técnica

> App Android de código aberto para gravar **vídeos multitrilha sincronizados** sozinho (a "banda de uma pessoa só"): grave a base, depois grave as outras partes ouvindo as anteriores, e monte tudo numa colagem de vídeo com mixagem e edição em tempo real.

Versão do documento: 1.0 — 2026-11-07
Idioma do documento: português. **Código, comentários, commits e README em inglês** (projeto open source). Interface em **pt-BR e inglês**.

---

## 1. Visão

Existem apps de "colagem musical", mas nenhum resolve bem o essencial: **sincronia precisa entre trilhas**, suporte sério a **interfaces de áudio USB** e **liberdade de layout**. O Selfband é:

- **Preciso:** compensação de latência por dispositivo, com calibração. Meta: as trilhas alinhadas com erro de ±1 ms no áudio.
- **Sem limites artificiais:** número de trilhas e duração limitados só pelo hardware e pelo armazenamento.
- **Para qualquer pessoa:** funciona com o microfone do celular, fone com fio, Bluetooth (com avisos) ou qualquer interface USB class-compliant.
- **Modular:** arquitetura em módulos, efeitos e layouts plugáveis.
- **Focado:** só tem dois efeitos de áudio, **EQ e reverb**. Ele não é uma DAW completa.

### Princípio: nada específico de um aparelho ou equipamento
O Selfband **não pode ter nenhum comportamento feito sob medida para uma marca ou modelo** de celular, interface ou fone. Todo comportamento vem do que o app **detecta** (tipo de dispositivo, canais, sample rates suportados), do que ele **mede** (calibração, teste de vazamento) ou do que o usuário **escolhe** (perfil). Nada de listas de modelos no código, nada de "if modelo == X". Se um equipamento se comporta de um jeito diferente, isso tem que ser resolvido por um mecanismo genérico (detecção, medição ou configuração), que vale para qualquer outro equipamento parecido.

### Cenários de uso que precisam funcionar bem
1. **Só o celular:** microfone embutido, ouvindo as trilhas por fone com fio ou USB-C.
2. **Fone Bluetooth:** funciona, com aviso de latência e calibração própria.
3. **Interface de áudio USB class-compliant** (placa de som, pedaleira com saída USB, microfone USB):
   - com **monitoramento direto pelo hardware** (o músico se ouve pela própria interface, às vezes com efeitos dela)
   - ou sem monitoramento direto (o app oferece monitoramento por software)
   - mono ou estéreo, com qualquer sample rate suportado (algumas trabalham só em 48 kHz)
   - algumas interfaces com porta para celular devolvem na entrada o que o celular toca (vazamento de playback). O app tem que detectar isso.
4. **Destinos:** vertical para Instagram/TikTok/Shorts (9:16), horizontal para YouTube (16:9) e quadrado (1:1).
5. **Duração:** de clipes curtos a músicas inteiras (tipicamente 3 a 5 minutos), **sem limite imposto**.

---

## 2. Funcionalidades

### 2.1 Projetos
- Lista de projetos com miniatura, nome, duração, nº de trilhas e data.
- Criar, renomear, duplicar e excluir projetos.
- Configurações por projeto: formato (9:16, 16:9, 1:1), BPM/compasso (opcional) e sample rate (fixado na criação; padrão = nativo do dispositivo, normalmente 48 kHz).
- Autosave contínuo. Um crash nunca pode perder trilhas já gravadas.

### 2.2 Gravação
- Fluxo: escolher/criar trilha → prévia da câmera → contagem regressiva opcional → gravar → as trilhas anteriores tocam no fone em sincronia → parar → a trilha entra no projeto já alinhada.
- **Sem limite de trilhas e sem limite de duração.** A gravação é feita em streaming direto para o disco. Mostrar o espaço livre e uma estimativa do tempo restante de gravação.
- A primeira trilha define o início do projeto. A duração do projeto é a da trilha mais longa.
- **Takes:** regravar uma trilha cria um novo take. O usuário escolhe qual take fica ativo, e os anteriores ficam guardados até serem excluídos.
- **Trilha só de áudio** (sem câmera), opcional.
- **Câmera:**
  - frontal ou traseira por trilha
  - resolução e fps configuráveis (padrão 1080p30)
  - estabilização liga/desliga
  - grade de enquadramento
  - **travas de exposição, balanço de branco e foco** (AE/AWB/AF lock), para manter a aparência consistente entre trilhas
  - **"fantasma" (onion skin):** uma trilha anterior sobreposta à prévia com opacidade ajustável, para alinhar o enquadramento
- Tela acesa durante a gravação. Bloquear a rotação durante a gravação.

### 2.3 Metrônomo
- BPM de 30 a 300, tap tempo, compasso (2/4, 3/4, 4/4, 6/8, personalizado) e acento no tempo 1.
- Contagem antes de gravar (0 a 4 compassos).
- Som do clique (2 ou 3 opções), volume do clique e flash visual opcional.
- O clique **só toca no fone**. Ele nunca é gravado e nunca é exportado.
- O metrônomo é opcional. Também dá para gravar livre, sem BPM.

### 2.4 Áudio, dispositivos e calibração
- **Detecção automática** de entradas e saídas (embutido, fone com fio, Bluetooth, USB) via `AudioManager`/`AudioDeviceCallback`.
- **Perfis por dispositivo:** o app guarda a latência calibrada, o canal de entrada (L, R ou estéreo), o ganho de entrada digital, o modo de monitoramento e o sample rate. Ao reconectar um dispositivo, o perfil dele carrega sozinho.
- **Monitoramento por software** (o som da entrada devolvido ao fone pelo app):
  - Padrão **DESLIGADO** para interfaces USB, porque elas em geral fazem monitoramento direto.
  - Opcional para o microfone embutido com fone com fio. Mostrar um aviso da latência.
  - Nunca permitir em alto-falante (risco de microfonia).
- **Bluetooth:** permitido para ouvir, com um aviso claro sobre a latência alta (150 a 300 ms). Exige calibração própria.
- **Calibração da latência total** (saída + entrada), com 3 métodos:
  1. **Acústico:** o alto-falante toca impulsos e o microfone capta. Serve para o microfone embutido.
  2. **Cabo de loopback:** a saída da interface é ligada na entrada com um cabo. É o mais preciso para interfaces.
  3. **Tocar junto (tap):** o usuário toca ou bate junto com cliques. O app detecta os ataques e tira a média, descartando os valores discrepantes. Funciona com qualquer equipamento, mas é menos preciso.
  - Ponto de partida: a estimativa de latência informada pelo Oboe.
  - Algoritmo: tocar uma sequência de impulsos (ou um chirp/MLS), gravar e achar o atraso por correlação cruzada. Repetir N vezes e reportar a mediana e a dispersão.
- **Detecção de vazamento de playback (loopback do hardware):** algumas interfaces com porta para celular misturam de volta, na entrada USB, o que o celular toca. Isso faria as trilhas antigas vazarem para dentro da nova.
  - Teste dedicado: tocar impulsos com o instrumento em silêncio e verificar se eles aparecem na entrada.
  - Se aparecerem: avisar o usuário e salvar essa característica no perfil do dispositivo.
  - (Investigação, fase futura: cancelamento por subtração do sinal conhecido, com atraso e ganho estimados.)
- **Tela de diagnóstico:** dispositivo ativo, sample rate, tamanho de buffer, latência estimada e calibrada, contagem de xruns/underruns, API em uso (AAudio/OpenSL) e modo exclusivo. É essencial para usuários e para depurar.

### 2.5 Mixer (tempo real)
Todos os controles alteram o som **durante a reprodução**, sem renderizar.
- Por trilha:
  - volume (fader em dB)
  - pan
  - mute e solo
  - inverter fase (opcional, avançado)
- **Ajuste de offset** por trilha, com precisão de amostra: arrastar a forma de onda ou digitar em ms. Existe um offset de áudio e outro de vídeo (o de vídeo é independente, para corrigir lip-sync).
- **EQ por trilha:**
  - passa-altas (corte de graves, 20 a 300 Hz)
  - low shelf
  - 1 banda peaking com frequência, ganho e Q ajustáveis
  - high shelf
  - curva visual simples
  - bypass
- **Reverb:** um único reverb no barramento master, alimentado por um **envio (send) por trilha**. Ajustes:
  - tamanho da sala, decaimento, pré-delay, amortecimento
  - nível de retorno
  - presets (Sala pequena, Sala, Hall, Plate)
  - Esse modelo é mais barato e soa mais coeso que um reverb por trilha.
- **Master:** volume e **proteção contra clipping** (limitador transparente, ligado por padrão). Ele é proteção técnica, não um efeito criativo.
- Medidores de nível por trilha e no master.
- **Não existem outros efeitos.** A arquitetura permite adicionar mais efeitos no futuro, mas o escopo do produto é só EQ + reverb.

### 2.6 Edição de vídeo (canvas)
- **Canvas** no formato do projeto (9:16, 16:9 ou 1:1), com prévia ao vivo, sincronizada com o áudio.
- Cada trilha com vídeo é uma **camada** com:
  - **recorte (crop)** com alças
  - **posição** (arrastar)
  - **escala** (pinça)
  - ordem de profundidade (frente/trás)
  - opacidade
  - cantos arredondados, borda (espessura e cor)
  - visível ou oculta
  - (rotação: opcional, baixa prioridade)
- **Templates de layout:** 1, 2, 3, 4, 6, 9 e "auto-grade para N trilhas". Um template só posiciona as camadas, e depois tudo continua editável.
  - Salvar layouts próprios como templates.
  - Templates são dados (JSON), não código.
- Snap a guias e à grade, e botões de alinhamento rápido.
- **Fundo:** cor sólida, desfoque de uma das trilhas ou imagem.
- **Trocar de formato** (ex.: 9:16 → 16:9) reposiciona as camadas pelo template atual ou por coordenadas normalizadas, sem perder recortes.
- **Corte global** (início e fim) do projeto.
- **Prévia = exportação:** o mesmo renderizador gera a prévia e o arquivo final (WYSIWYG).

### 2.7 Exportação
- **Vídeo:**
  - H.264 (padrão, máxima compatibilidade com Instagram/TikTok) ou HEVC
  - 720p, 1080p (padrão) ou 4K, quando suportado
  - 30 ou 60 fps
  - bitrate automático ou manual
- **Áudio:** AAC 48 kHz, 256 kbps estéreo.
- Exportações extras:
  - **mix em WAV**
  - **stems em WAV**: cada trilha separada, já alinhada, em 24-bit ou 32-bit float, para usar numa DAW
- Exportação em **foreground service**, com notificação de progresso. Ela continua se o app for para segundo plano e pode ser cancelada.
- Salva em `Movies/Selfband` (MediaStore) e oferece o botão de compartilhar (Instagram, TikTok etc.).

### 2.8 Primeira execução e personalização
- **Assistente inicial:**
  - permissões
  - "Como você grava?" (microfone do celular / fone com fio / interface USB / Bluetooth)
  - calibração guiada
  - formato padrão
- Tema claro, escuro ou do sistema. Idioma pt-BR ou en.
- Padrões configuráveis: metrônomo, qualidade de gravação, qualidade de exportação, contagem, câmera e pasta de exportação.

---

## 3. Arquitetura técnica

### 3.1 Stack
- **Kotlin** e **Jetpack Compose** (Material 3). Coroutines e Flow.
- **minSdk 29** (Android 10). **targetSdk/compileSdk** na última versão estável.
- **Motor de áudio em C++** com **Oboe** (AAudio, com fallback para OpenSL ES), via NDK, CMake e JNI.
- **Câmera:** CameraX (`VideoCapture` **sem áudio**), com `Camera2Interop` para as travas AE/AWB/AF. Se o CameraX não der controle suficiente de timestamps, migrar o módulo para Camera2 + MediaCodec, ficando escondido atrás da mesma interface.
- **Vídeo:** decodificação com `MediaCodec` em `SurfaceTexture`. Compositor próprio em **OpenGL ES 3**, com codificação por `MediaCodec` e `MediaMuxer`. (Media3 Transformer/CompositionPlayer pode ser avaliado, mas o requisito de prévia ao vivo dirigida pelo relógio de áudio favorece um compositor próprio, compartilhado entre prévia e export.)
- **DI:** Hilt. **Persistência:** arquivos JSON versionados (kotlinx.serialization). Room só se for necessário para indexar projetos.
- **Build:** Gradle com version catalog, e GitHub Actions para gerar o APK de debug e release a cada push/tag.

### 3.2 Módulos Gradle
```
:app                     — entry point, navegação, DI root
:core:model              — modelos puros (Project, Track, Take, Layer, MixerState, DeviceProfile...)
:core:project            — persistência, schema versionado, migrações, gerenciamento de arquivos
:core:audio              — engine C++ (Oboe) + API Kotlin (record, playback, mixer, efeitos, metrônomo, calibração)
:core:camera             — captura de vídeo, travas, timestamps, fantasma
:core:video              — decoders, proxies, compositor GL (usado por preview e export)
:core:export             — pipeline offline de áudio+vídeo, stems, foreground service
:core:ui                 — design system, componentes Compose (faders, knobs, waveform, meters)
:feature:projects        — lista de projetos
:feature:studio          — tela principal (canvas + trilhas)
:feature:recorder        — fluxo de gravação
:feature:mixer           — detalhe da trilha (EQ, send de reverb, offset, takes)
:feature:layout          — editor do canvas
:feature:export          — tela de exportação
:feature:settings        — ajustes, dispositivos, calibração, diagnóstico, onboarding
```
Regra: os módulos `feature` dependem dos `core`, e nunca de outro `feature`. Os módulos `core` expõem interfaces, para que dê para trocar implementações (ex.: CameraX ↔ Camera2).

### 3.3 Motor de áudio (C++)
- **Stream full-duplex** (entrada + saída) no Oboe, preferencialmente `PerformanceMode::LowLatency` e `SharingMode::Exclusive`, com fallback.
- O sample rate segue o nativo do dispositivo. Se ele diferir do sample rate do projeto, usar o resampler do Oboe.
- **Thread de áudio sem alocação, sem locks e sem I/O.** A comunicação com o resto do app usa:
  - ring buffers lock-free (input → thread de escrita em disco)
  - parâmetros atômicos com suavização (ramp) para evitar cliques
- **Playback:** as trilhas são lidas do disco por uma thread de streaming com pré-buffer, mixadas no callback (gain, pan, EQ e send de cada trilha → reverb bus → master → limitador) e somadas ao metrônomo, que vai só para o fone.
- **Gravação:** WAV 32-bit float (ou 24-bit), mono ou estéreo conforme o perfil, escrito por streaming. Uma trilha pode ter qualquer duração.
- **Relógio mestre = posição de frames de áudio.** O vídeo segue o áudio.
- **Efeitos:** interface comum `Effect { prepare(sampleRate, maxFrames); process(buffer, frames); setParam(id, value); }`. EQ com biquads (fórmulas RBJ Audio EQ Cookbook). Reverb algorítmico (ex.: Freeverb ou FDN), implementado no projeto ou com uma lib de licença compatível.
- **Renderização offline:** o mesmo grafo de mixagem roda mais rápido que o tempo real, para exportar. O resultado tem que ser idêntico, bit a bit, ao que se ouve (exceto pelo metrônomo).

### 3.4 Sincronização (o coração do app)
Definições:
- `L_out` = latência de saída (do buffer até o ouvido), `L_in` = latência de entrada (do som até o buffer), `L_rt = L_out + L_in` (medida pela calibração).
- Quando o usuário ouve a amostra `p` do playback e toca junto, esse som chega ao buffer de entrada aproximadamente `L_rt` depois do momento em que `p` foi escrito na saída.

**Alinhamento de áudio:**
- Registrar o frame de saída e o frame de entrada no mesmo instante de início, via `getTimestamp()` do Oboe e contadores de frames.
- Deslocar a trilha gravada `L_rt` amostras para trás, mais o offset manual do usuário.
- Guardar no projeto o offset aplicado e o perfil usado, sem reescrever o WAV. O alinhamento é não destrutivo.

**Alinhamento de vídeo:**
- Converter o timestamp de cada frame (sensor timestamp, conferindo `SENSOR_INFO_TIMESTAMP_SOURCE`) e o timestamp do áudio para o mesmo relógio (`CLOCK_MONOTONIC`/`BOOTTIME`).
- Mapear o frame de vídeo para a posição de áudio correspondente.
- Tolerância: ±1 frame. O offset de vídeo fica ajustável separadamente.

**Testes de aceitação de sync:**
- (a) Com cabo de loopback: gravar 5 trilhas "tocando" o clique de volta. Depois de alinhadas, os transientes devem coincidir em ±1 ms.
- (b) Bater palmas filmando com o microfone embutido. O pico do áudio deve coincidir com o frame da palma em ±1 frame.

### 3.5 Vídeo: prévia, proxies e escala
- O compositor GL desenha um quad por camada, com transformação (posição, escala, z, opacidade) e recorte via retângulo de UV. Cantos e borda são feitos no shader. O fundo também.
- As coordenadas das camadas são **normalizadas** em relação ao canvas, para sobreviver à troca de formato.
- **Proxies:** depois de gravar, gerar em segundo plano uma cópia leve (ex.: 540p, GOP curto) para a prévia.
  - Usar proxies quando o número de trilhas passar da capacidade de decodificadores simultâneos do aparelho, consultada via `MediaCodecInfo.CodecCapabilities.getMaxSupportedInstances()` e medição de desempenho.
  - Por padrão, ligar a partir de ~8 trilhas. Deve ser configurável.
- **Export com muitas trilhas:** decodificar os originais até o limite de instâncias. Acima disso, compor em várias passadas (grupos de camadas → intermediário de alta qualidade → composição final).
- A prévia mostra o frame mais próximo da posição atual do relógio de áudio, descartando frames atrasados em vez de travar o áudio.

### 3.6 Armazenamento e projeto
```
files/projects/<uuid>/
  project.json            — schema versionado (schemaVersion), trilhas, takes, mixer, layers, layout, formato
  tracks/<trackId>/<takeId>.wav
  tracks/<trackId>/<takeId>.mp4      — vídeo original (sem áudio)
  tracks/<trackId>/<takeId>.proxy.mp4
  thumbs/
```
- Escrita atômica do `project.json` (arquivo temporário + rename). Migrações a cada mudança de schema.
- Estimativa de espaço: um vídeo 1080p30 ocupa ~15 a 20 Mbps, ou seja, 5 min ≈ 600 MB por trilha. Avisar quando o espaço estiver baixo e permitir escolher a qualidade da gravação.

### 3.7 Permissões e serviços
- `CAMERA`, `RECORD_AUDIO`, `POST_NOTIFICATIONS` e foreground service do tipo `mediaProcessing` (Android 15+) ou `dataSync` (anteriores) para exportar.
- Sem internet e sem coleta de dados. A política de privacidade é trivial, o que facilita a Play Store e o F-Droid.

### 3.8 Qualidade
- Testes unitários:
  - DSP: resposta do EQ, matemática de alinhamento, detecção de atraso por correlação, pan law
  - serialização e migração do projeto
  - matemática do layout
- Testes instrumentados para o fluxo de projeto.
- Teste manual guiado por um checklist por fase (seção 5).
- Logs com a tag `Selfband`, e um nível de log verboso ativável nas configurações.

---

## 4. Fases de desenvolvimento

Ordem pensada para **atacar o maior risco primeiro (sincronia de áudio)**. O MVP são as fases 0 a 5. Ao final de cada fase, o APK tem que estar instalável e testável no aparelho.

**Fase 0 — Fundação**
- Estrutura de módulos, Compose, Hilt, NDK/CMake com Oboe e version catalog.
- GitHub Actions gerando o APK de debug.
- Tela de diagnóstico de áudio (lista de dispositivos, sample rate, buffer, latência estimada).
- README (en), LICENSE e CONTRIBUTING.

**Fase 1 — Motor de áudio e sincronia (só áudio)**
- Stream full-duplex, gravação WAV por streaming e playback de N trilhas.
- Metrônomo com contagem.
- Perfis por dispositivo, os 3 métodos de calibração e a detecção de vazamento de playback.
- Alinhamento automático com offset manual.
- ✅ Critério: gravar 4+ trilhas de áudio em pelo menos dois cenários da seção 1 (ex.: microfone embutido e interface USB) e ouvi-las perfeitamente alinhadas. O teste de loopback deve passar em ±1 ms.

**Fase 2 — Câmera**
- Gravação de vídeo sem áudio, sincronizada com o motor de áudio.
- Câmera frontal/traseira, travas AE/AWB/AF, fantasma, grade e takes.
- ✅ Critério: o teste da palma deve passar em ±1 frame.

**Fase 3 — Canvas e prévia**
- Compositor GL e prévia ao vivo dirigida pelo relógio de áudio.
- Templates de layout, edição de camadas (crop, posição, escala, z, opacidade, borda, cantos), fundo e formatos 9:16, 16:9 e 1:1.

**Fase 4 — Mixer**
- Volume, pan, mute, solo, medidores.
- EQ por trilha, reverb com send, limitador master.
- Offset por forma de onda e corte global.

**Fase 5 — Exportação** *(fim do MVP)*
- Export H.264/HEVC + AAC, mix WAV e stems WAV.
- Foreground service, MediaStore e compartilhar.

**Fase 6 — Escala e experiência**
- Proxies, composição em várias passadas e testes com 12 a 16 trilhas.
- Assistente inicial, temas, i18n completo e configurações padrão.
- Templates salvos pelo usuário.

**Fase 7 — Publicação**
- Ícone e identidade visual.
- Política de privacidade, ficha da Play Store, assinatura de release via CI.
- Avaliar o F-Droid.

**Futuro (fora do escopo atual):**
- Punch-in (regravar só um trecho)
- Cancelamento de vazamento de playback
- Destaque automático da camada que está "solando"
- Importar áudio externo
- Rotação de camadas

---

## 5. Checklist de teste no aparelho (por fase)
Testar em todos os cenários da seção 1 que estiverem disponíveis, e sempre que possível em mais de um aparelho (inclusive um intermediário/mais fraco, não só topo de linha).
- Conectar e desconectar a interface USB durante o uso: o app não pode travar, e precisa trocar de perfil e avisar.
- Ligação recebida e app em segundo plano durante a gravação: a trilha precisa ser salva até onde foi gravada.
- Gravação de 10 minutos (sem limite): sem underruns, sem drift entre áudio e vídeo.
- Gravar com o celular quente e com a bateria baixa.
- Contar os xruns na tela de diagnóstico durante a reprodução de 8+ trilhas.
- Exportar e postar no Instagram e no TikTok: conferir a sincronia depois da recompressão das plataformas.

---

## 6. Decisões
**Tomadas:**
- **Licença: GPL-3.0.** O arquivo LICENSE vai na raiz, com o cabeçalho SPDX `GPL-3.0-or-later` nos arquivos de código. Todas as dependências precisam ter licença compatível com a GPL-3.0 (Apache-2.0, MIT, BSD e LGPL são compatíveis). Conferir antes de adicionar qualquer biblioteca.

**Pendentes (perguntar ao dono do projeto):**
- Nome do pacote: sugestão `io.github.<usuario>.selfband`.
- Identidade visual (cores, ícone).
