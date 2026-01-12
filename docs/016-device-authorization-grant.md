# ТЗ: Device Authorization Grant (RFC 8628) для Data Forge Middleware

## Резюме

Добавление Device Code Flow для клиентского API, позволяющего headless-устройствам (IoT, CLI) получать credentials через браузерную авторизацию пользователя. Работает параллельно с существующей Basic Auth + JWT аутентификацией.

## Требования (из обсуждения)

| Аспект | Решение |
|--------|---------|
| Auth Server | Auth0 (Device Flow из коробки) |
| Результат авторизации | Привязка к существующему Site (новый clientSecret) |
| Кто подтверждает | ROLE_USER (владелец аккаунта) |
| Выбор Site | Пользователь выбирает при подтверждении |
| Стратегия токенов | Ротация secret (старый инвалидируется) |
| Multi-device | Один Site = одно устройство |
| UI для подтверждения | Новая страница /device-verify в React |
| TTL device code | 15 минут |

## Архитектура Flow

```
Device                              Backend                           User (Browser)
  │                                    │                                    │
  ├── POST /api/v1/device/authorize ──>│                                    │
  │<── device_code, user_code ─────────│                                    │
  │                                    │                                    │
  │ [Показать: "Откройте /device-verify, введите код: ABCD-1234"]          │
  │                                    │                                    │
  ├── POST /api/v1/device/token ──────>│                                    │
  │<── {"error":"authorization_pending"} │                                  │
  │                                    │                                    │
  │                                    │<── Auth0 Login ───────────────────│
  │                                    │<── GET /device-verify?user_code=xxx│
  │                                    │<── POST /api/v1/device/confirm ───│
  │                                    │                                    │
  ├── POST /api/v1/device/token ──────>│                                    │
  │<── {domain, clientSecret} ─────────│                                    │
```

---

## Backend: Новые компоненты

### Структура пакета `deviceauth`

```
src/main/java/com/bitbi/dfm/deviceauth/
├── domain/
│   ├── DeviceCode.java              # Entity
│   ├── DeviceCodeRepository.java    # Repository interface
│   └── DeviceCodeStatus.java        # Enum: PENDING, APPROVED, EXPIRED, DENIED
├── application/
│   ├── DeviceAuthorizationService.java
│   ├── DeviceCodeProperties.java    # @ConfigurationProperties
│   └── DeviceAuthorizationException.java
├── infrastructure/
│   └── JpaDeviceCodeRepository.java
└── presentation/
    ├── DeviceAuthorizationController.java  # Публичные endpoints
    ├── DeviceConfirmController.java        # Auth0-protected endpoints
    └── dto/
        └── *.java
```

### Database Migration

**Файл:** `V17__create_device_authorization_codes_table.sql`

```sql
CREATE TABLE device_authorization_codes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_code VARCHAR(64) NOT NULL UNIQUE,
    user_code VARCHAR(10) NOT NULL UNIQUE,
    verification_uri VARCHAR(255) NOT NULL,
    account_id UUID,
    site_id UUID,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    client_metadata JSONB,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    approved_at TIMESTAMPTZ,

    CONSTRAINT fk_device_auth_account FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE,
    CONSTRAINT fk_device_auth_site FOREIGN KEY (site_id) REFERENCES sites(id) ON DELETE CASCADE
);

CREATE INDEX idx_device_auth_device_code ON device_authorization_codes(device_code);
CREATE INDEX idx_device_auth_user_code ON device_authorization_codes(user_code);
CREATE INDEX idx_device_auth_expires_at ON device_authorization_codes(expires_at);
```

### API Endpoints

| Endpoint | Method | Auth | Описание |
|----------|--------|------|----------|
| `/api/v1/device/authorize` | POST | Public | Инициировать flow, получить device_code + user_code |
| `/api/v1/device/token` | POST | Public | Polling для получения credentials |
| `/api/v1/device/confirm` | GET | Auth0 | Получить info о device code |
| `/api/v1/device/confirm` | POST | Auth0 | Подтвердить авторизацию + выбрать Site |
| `/api/v1/device/confirm` | DELETE | Auth0 | Отклонить авторизацию |

### Security Configuration

Добавить новый filter chain в `SecurityConfiguration.java`:

```java
@Bean
@Order(0)  // Наивысший приоритет
public SecurityFilterChain deviceAuthorizationPublicFilterChain(HttpSecurity http) throws Exception {
    http
        .securityMatcher("/api/v1/device/authorize", "/api/v1/device/token")
        .csrf(csrf -> csrf.disable())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
    return http.build();
}
```

### Модификация Site.java

Добавить метод ротации secret:

```java
public void updateClientSecretHash(String newClientSecretHash) {
    Objects.requireNonNull(newClientSecretHash);
    if (newClientSecretHash.isBlank()) {
        throw new IllegalArgumentException("ClientSecretHash cannot be blank");
    }
    this.clientSecretHash = newClientSecretHash;
    this.updatedAt = LocalDateTime.now();
}
```

### Конфигурация

```yaml
device-authorization:
  verification-uri: ${DEVICE_VERIFICATION_URI:https://app.dataforge.com/device-verify}
  expiration-minutes: 15
  polling-interval-seconds: 5
```

---

## Frontend: Новая страница

### Страница `/device-verify`

**Путь:** `frontend/src/pages/device-verify/DeviceVerifyPage.tsx`

**Состояния:**
1. **Input** — ввод user_code (или auto-fill из URL ?user_code=xxx)
2. **Confirm** — показ info устройства + выбор Site из списка
3. **Success** — "Устройство авторизовано"
4. **Denied** — "Авторизация отклонена"

**Компоненты:**
- Input для user_code (формат: XXXX-1234)
- Select для выбора Site (только активные sites пользователя)
- Warning: "При авторизации устройства будут сгенерированы новые credentials. Ранее подключённое устройство будет отключено."
- Кнопки: Authorize / Deny

### Feature Module

```
frontend/src/features/device-auth/
├── api/
│   └── deviceAuthApi.ts
└── model/
    └── queries.ts
```

### Routing

Добавить в `router.tsx`:
```typescript
path: '/device-verify' → <UserOnlyGuard component={DeviceVerifyPage} />
```

---

## RFC 8628 Error Codes

| Error | HTTP | Описание |
|-------|------|----------|
| `authorization_pending` | 400 | Ожидание авторизации пользователя |
| `slow_down` | 400 | Слишком частый polling (future) |
| `access_denied` | 403 | Пользователь отклонил |
| `expired_token` | 400 | Device code истёк |
| `invalid_grant` | 400 | Device code не найден |

---

## Критические файлы для модификации

| Файл | Изменение |
|------|-----------|
| `SecurityConfiguration.java` | Добавить filter chain Order(0) для публичных device endpoints |
| `Site.java` | Добавить `updateClientSecretHash()` |
| `ApiRoutes.java` | Добавить константы DEVICE_AUTHORIZE, DEVICE_TOKEN, DEVICE_CONFIRM |
| `router.tsx` (frontend) | Добавить /device-verify route |
| `apiRoutes.ts` (frontend) | Добавить DEVICE_CONFIRM константу |

---

## Порядок реализации

### Phase 1: Backend Foundation
1. Flyway migration V17
2. Domain entity: `DeviceCode`, `DeviceCodeStatus`
3. Repository interface + JPA implementation
4. Configuration properties

### Phase 2: Backend Services
5. `DeviceAuthorizationService`
6. `Site.updateClientSecretHash()`
7. `ApiRoutes.java` constants

### Phase 3: Backend Controllers
8. `DeviceAuthorizationController` (public)
9. `DeviceConfirmController` (Auth0)
10. `SecurityConfiguration.java` update

### Phase 4: Frontend
11. API client + queries
12. `DeviceVerifyPage` component
13. Router update

### Phase 5: Testing
14. Backend unit tests
15. Backend integration tests
16. Frontend component tests

---

## Безопасность

- **Device code**: 64 символа (UUID-based, криптографически стойкий)
- **User code**: 8 символов XXXX-1234 (исключены I, O, 0, 1)
- **TTL**: 15 минут (cleanup каждые 5 минут)
- **Ротация**: Старый secret инвалидируется при привязке нового устройства
- **Ownership**: Проверка принадлежности Site к Account при подтверждении
