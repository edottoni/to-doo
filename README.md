# Casa em dia — aplicativo Android (único)

Um só aplicativo para os três níveis de perfil. O **nível do perfil** (definido no site) escolhe a tela:
- **Usuário / Administrador**: Início (hoje, amanhã, atrasadas), Calendário, Tarefas, Trocas, Saldos, Histórico, Ajustes. Conclui tarefas (inclusive “fiz no lugar de fulano”, que ajusta o saldo), registra uso de tarefas por uso, marca checklist, pede trocas.
- **Subadmin**: só as tarefas em que foi escolhido como confirmador — **Confirmar** (aceitar/rejeitar registros e limpezas), **Dias** (marcar um dia como feito sem o usuário registrar), Histórico e Ajustes.

**Primeira abertura:** “Adicionar site” → cole o endereço mostrado no site (Ajustes) → verificação → login com o **nome do perfil** e a senha → tela de carregamento → app liberado. Depois funciona offline (dados salvos no aparelho; ações ficam numa fila que sincroniza ao voltar a internet). O widget mostra hoje/amanhã e abre o app ao tocar.

**Codemagic (plano gratuito, `mac_mini_m2`):** workflow `app-debug` não precisa de configuração e gera o APK; `app-release` exige keystore `casa_em_dia_keystore`. O Gradle Wrapper é gerado no build.
**Local:** JDK 17 + Android SDK 34: `gradle wrapper --gradle-version 8.9 && ./gradlew :app-main:assembleDebug`.

**Proteção anti-robô:** hospedagens gratuitas (InfinityFree e similares) bloqueiam clientes sem navegador com uma página de teste de JavaScript. O app resolve esse teste sozinho (cookie `__test`) e repete o pedido.

**Status:** o código foi reescrito para o novo modelo de dados e **ainda não foi compilado** (sem Android SDK no ambiente de criação). O primeiro build pode acusar erros de compilação; envie o log. Release só fala com https; debug aceita http.
