<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# Política de privacidade do Selfband

*Vigente desde: 08/11/2026* · [English](PRIVACY.md)

O Selfband é um app livre e de código aberto para gravar vídeos multipista no Android. Ele é
desenvolvido por Higor (github.com/higor0227) e o código-fonte é público em
https://github.com/higor0227/selfband.

## Resumo

**O Selfband não coleta, não envia e não compartilha nenhum dado.** O app não tem a permissão
de internet do Android, então não consegue se conectar a nenhum servidor. Não há anúncios,
análise de uso, relatórios de falha, contas nem SDKs de terceiros que coletem dados.

## O que o app usa, e para quê

| Permissão | Usada para |
|---|---|
| Microfone (`RECORD_AUDIO`) | Gravar as trilhas de áudio e medir a latência do áudio (calibração). |
| Câmera (`CAMERA`) | Gravar o vídeo de cada trilha. |
| Notificações (`POST_NOTIFICATIONS`) | Mostrar o progresso de uma exportação em segundo plano. |
| Serviço em primeiro plano | Terminar uma exportação quando você sai do app. |

O microfone e a câmera só são usados enquanto você está numa tela de gravação ou de calibração,
e o Android mostra os indicadores de sempre enquanto estão ligados.

## Dados guardados no seu aparelho

Tudo o que o app cria fica no seu celular:

- seus projetos (gravações de áudio e vídeo, mixagem e layout), no armazenamento privado do app;
- os resultados da calibração dos seus dispositivos de áudio (medidas de latência), suas
  configurações e os templates de layout que você salvou;
- os vídeos e áudios exportados, salvos em `Movies` e `Music`, numa pasta com o nome que você
  escolher (por padrão `Selfband`), onde
  outros apps conseguem vê-los, como qualquer outro vídeo do celular.

O app não participa do backup em nuvem do Android, então nada disso é copiado para servidores do
Google pelo app. Desinstalar o app apaga os dados privados dele. Os arquivos exportados ficam até
você apagá-los.

## Compartilhamento

Quando você toca em "Compartilhar" depois de exportar, abre o menu de compartilhamento do Android
e **você** escolhe o app que recebe o arquivo (por exemplo Instagram ou TikTok). A partir daí, vale
a política de privacidade desse app para aquele arquivo. O Selfband nunca envia nada por conta
própria.

## Crianças

O app não coleta dados pessoais de ninguém, inclusive de crianças.

## Mudanças

Se esta política mudar, a nova versão será publicada neste endereço com uma nova data de vigência,
e a mudança fica registrada no histórico público do projeto.

## Contato

Dúvidas sobre esta política: abra uma issue em https://github.com/higor0227/selfband/issues.
