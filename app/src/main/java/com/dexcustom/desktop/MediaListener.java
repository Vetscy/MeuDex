package com.dexcustom.desktop;

import android.service.notification.NotificationListenerService;

/**
 * Serviço vazio, só existe para o Android liberar a leitura das sessões de mídia (Spotify, YouTube Music...).
 * O Meu Dex NÃO lê o conteúdo das suas notificações: ele só usa essa "chave" para o MediaSessionManager
 * mostrar o que está tocando e permitir pausar/avançar. A liberação é feita em
 * Configurações > Notificações > Acesso a notificações > Meu Dex.
 */
public class MediaListener extends NotificationListenerService {
}
