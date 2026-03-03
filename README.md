# Motorista Inteligente

Aplicativo Android para apoio a motoristas de app (Uber e 99), com análise de corridas em tempo real, monitoramento de demanda e recomendações operacionais.

## Visão geral

O projeto combina:
- **Acessibilidade + OCR** para detectar ofertas de corrida na tela.
- **Serviço flutuante** para analisar e exibir recomendações sem sair do app de corrida.
- **Firebase (Auth + Firestore)** para histórico, agregações de demanda e dados analíticos.
- **UI em Jetpack Compose** para configuração, dashboards e telas de operação.

## Principais funções disponíveis no projeto

### 1) Captura e análise de corridas
- Detecção de ofertas Uber/99 via `AccessibilityService`.
- Extração de preço, distância, tempo, pickup e sinais de aceitação/recusa.
- Fallback com OCR quando a árvore de acessibilidade não traz texto útil.
- Cálculo de score/recomendação de corrida (compensa, não compensa, neutro).

### 2) Overlay operacional (serviço em primeiro plano)
- Botão flutuante com card de análise em tempo real.
- Card de status operacional com:
  - nível de demanda,
  - sessões e produtividade,
  - distribuição Uber/99,
  - dicas de pico por cidade.
- Notificação persistente de serviço + alertas de pico chegando/diminuindo.

### 3) Demanda por região (cidade/bairro)
- Agregação de demanda em janela de **10 minutos**.
- Quebra por plataforma (Uber/99).
- Exibição de motoristas ativos por cidade/bairro (com base em `driver_locations`).
- Fallback de localização para evitar perda de contabilização quando geocoder falha.

### 4) Histórico e analytics
- Histórico de corridas e sessões do motorista.
- Métricas diárias e logs analíticos em coleções dedicadas:
  - `analytics_offer_logs`
  - `analytics_online_logs`
  - `analytics_driver_daily`

### 5) Configuração do motorista e veículo
- Regras de análise (mínimo R$/km, ganho/h, distâncias máximas etc.).
- Configuração de veículo/combustível com apoio para decisão de custo operacional.

### 6) Autenticação
- Suporte a autenticação anônima e Google Sign-In.
- Parte dos dashboards e métricas exige login Google.

## Telas disponíveis

No menu lateral:
- **Início**
- **Configurar Corrida**
- **Demanda por Região**
- **Resumo Semanal**
- **Permissões**
- **Dicas de Uso**
- **Login**

## Arquitetura (resumo)

- `MainActivity`: navegação e telas Compose.
- `RideInfoAccessibilityService`: captura eventos, parsing e OCR.
- `FloatingAnalyticsService`: overlay, notificações e análise online.
- `FirestoreManager`: persistência, agregações regionais e analytics.
- `PauseAdvisor`: lógica de pico/pausa por horário/cidade.
- `MarketDataService`: contexto de mercado e heurísticas de demanda.
- `LocationHelper`: localização e atualização de posição do motorista.
- `DemandTracker`: métricas de sessão e tendência de demanda.

## Requisitos

- Android Studio + SDK Android configurado.
- JDK 11+.
- Dispositivo Android compatível com:
  - `minSdk 34`
  - `targetSdk 36`
- Projeto Firebase configurado (arquivo `app/google-services.json`).

## Como executar

1. Clone o repositório.
2. Abra no Android Studio.
3. Sincronize o Gradle.
4. Conecte um dispositivo físico.
5. Execute:

```bash
./gradlew.bat :app:installDebug
```

## Permissões necessárias em runtime

- Sobreposição (`SYSTEM_ALERT_WINDOW`)
- Acessibilidade (serviço `RideInfoAccessibilityService`)
- Localização (`ACCESS_FINE_LOCATION`/`ACCESS_COARSE_LOCATION`)
- Notificações (`POST_NOTIFICATIONS`)

Sem essas permissões, recursos críticos (captura, overlay e demanda regional) não funcionam corretamente.

## Estrutura de dados (alto nível)

Principais coleções no Firestore:
- `users/{uid}/ride_offers`
- `users/{uid}/rides`
- `users/{uid}/sessions`
- `regional_demand_15m`
- `driver_locations`
- `analytics_offer_logs`
- `analytics_online_logs`
- `analytics_driver_daily`

## Observações importantes

- O projeto depende de leitura de conteúdo de tela de apps terceiros via acessibilidade; mudanças de UI desses apps podem impactar detecção.
- Existem mecanismos de fallback e heurísticas para reduzir falsos negativos/positivos.
- O serviço principal foi configurado para não reiniciar automaticamente quando desativado pelo usuário.

---

Se você é dev novo no projeto, comece por:
1. `MainActivity`
2. `RideInfoAccessibilityService`
3. `FloatingAnalyticsService`
4. `FirestoreManager`
