# Отчет: Миграция с Keycloak на Auth0

## 📊 Резюме

**Сложность миграции:** 🟡 **СРЕДНЯЯ** (6/10)

**Время на реализацию:** 16-24 часа

**Риски:** СРЕДНИЕ (требуется изменение конфигурации и тестирование аутентификации)

---

## 1. Анализ текущей интеграции Keycloak

### 1.1 Компоненты, использующие Keycloak

| Компонент | Файл | Функциональность |
|-----------|------|------------------|
| **KeycloakAdminClient** | `account/infrastructure/KeycloakAdminClient.java` | Управление пользователями (создание, блокировка, сброс пароля) |
| **KeycloakAccountSyncService** | `account/application/KeycloakAccountSyncService.java` | Синхронизация учетных записей с Keycloak |
| **KeycloakAdminConfig** | `shared/config/KeycloakAdminConfig.java` | Конфигурация Keycloak Admin Client |
| **KeycloakSecurityConfig** | `auth/infrastructure/KeycloakSecurityConfig.java` | Настройка OAuth2 Resource Server (deprecated) |
| **SecurityConfiguration** | `shared/config/SecurityConfiguration.java` | Валидация JWT токенов от Keycloak |

### 1.2 Зависимости Keycloak

```kotlin
// build.gradle.kts
implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server") // OAuth2 JWT валидация
implementation("org.keycloak:keycloak-admin-client:26.0.7") // Management API
```

### 1.3 Операции с Keycloak

**Management API (через KeycloakAdminClient):**
- ✅ Создание пользователя с временным паролем
- ✅ Назначение ролей (ROLE_USER, ROLE_ADMIN)
- ✅ Блокировка/разблокировка пользователя (enable/disable)
- ✅ Сброс пароля
- ✅ Удаление пользователя (rollback)
- ✅ Получение информации о пользователе
- ✅ Обновление атрибутов пользователя (bidirectional mapping: accountId)
- ✅ Получение времени последнего входа (из сессий)

**OAuth2 Resource Server (валидация токенов):**
- ✅ Валидация JWT токенов от Keycloak для Admin API (`/admin/**`)
- ✅ Извлечение ролей из `realm_access.roles` claim
- ✅ Маппинг ролей в Spring Security authorities (ROLE_*)

---

## 2. План миграции на Auth0

### 2.1 Что нужно изменить

#### ✅ Полностью эквивалентные операции (простая замена)

| Операция Keycloak | Auth0 Management API | Сложность |
|-------------------|---------------------|-----------|
| Создание пользователя | POST `/api/v2/users` | 🟢 Простая |
| Назначение ролей | POST `/api/v2/users/{id}/roles` | 🟢 Простая |
| Блокировка пользователя | PATCH `/api/v2/users/{id}` (`blocked: true`) | 🟢 Простая |
| Разблокировка | PATCH `/api/v2/users/{id}` (`blocked: false`) | 🟢 Простая |
| Сброс пароля | POST `/api/v2/tickets/password-change` | 🟡 Средняя |
| Удаление пользователя | DELETE `/api/v2/users/{id}` | 🟢 Простая |
| Получение пользователя | GET `/api/v2/users/{id}` | 🟢 Простая |
| Обновление метаданных | PATCH `/api/v2/users/{id}` (`user_metadata`) | 🟢 Простая |

#### 🟡 Операции с изменением логики

| Операция | Keycloak | Auth0 | Действие |
|----------|----------|-------|----------|
| **Временный пароль** | `temporary: true` в credentials | Отправка email с reset link через `/api/v2/tickets/password-change` | Изменить логику: вместо возврата пароля генерировать ссылку |
| **Время последнего входа** | Из `getUserSessions()` | Из `last_login` поля в user metadata | Простой маппинг |
| **Атрибуты пользователя** | `attributes` Map | `user_metadata` JSON | Переименовать поле |

#### 🔴 Архитектурные изменения

1. **Аутентификация Admin Client**
   - Keycloak: PASSWORD или CLIENT_CREDENTIALS grant
   - Auth0: CLIENT_CREDENTIALS с Machine-to-Machine приложением
   - **Действие:** Создать M2M приложение в Auth0, получить client_id/secret

2. **Realm vs Database Connections**
   - Keycloak: realm = `dfm`
   - Auth0: database connection (например, `Username-Password-Authentication`)
   - **Действие:** Указать connection при создании пользователей

3. **JWT Claims для ролей**
   - Keycloak: `realm_access.roles` (массив строк)
   - Auth0: Custom claim через Action/Rule (например, `https://yourdomain.com/roles`)
   - **Действие:** Создать Auth0 Action для добавления ролей в токен

---

### 2.2 Файлы для изменения

#### 🔧 Конфигурация (3 файла)

1. **`src/main/resources/application.yml`** (замена keycloak блока на auth0)
2. **`build.gradle.kts`** (замена keycloak-admin-client на auth0-java)
3. **`docker-compose.yml`** (удаление Keycloak сервиса)

#### 🔧 Java классы (5 файлов)

4. **`KeycloakAdminClient.java`** → **`Auth0AdminClient.java`**
   - Замена `org.keycloak.admin.client.Keycloak` на `com.auth0.client.mgmt.ManagementAPI`
   - Переписать все методы на Auth0 Management API

5. **`KeycloakAccountSyncService.java`** → **`Auth0AccountSyncService.java`**
   - Изменить логику временного пароля (генерация ссылки вместо пароля)
   - Обновить DTOs (вместо пароля возвращать reset link)

6. **`KeycloakAdminConfig.java`** → **`Auth0AdminConfig.java`**
   - Заменить `KeycloakBuilder` на `ManagementAPI.newBuilder()`
   - Конфигурация client_credentials для M2M

7. **`KeycloakSecurityConfig.java`** → УДАЛИТЬ (уже deprecated)

8. **`SecurityConfiguration.java`** (обновить OAuth2 конфигурацию)
   - Изменить `issuer-uri` на Auth0 tenant
   - Изменить маппинг ролей на custom claim

#### 🔧 DTOs (2 файла)

9. **`CreateAccountResponse.java`** - изменить `String temporaryPassword` на `String passwordResetLink`
10. **`ResetPasswordResponse.java`** - изменить `String temporaryPassword` на `String passwordResetLink`

#### 🧪 Тесты (все файлы с mock Keycloak)

11. **`TestKeycloakConfig.java`** → **`TestAuth0Config.java`**
12. Все integration тесты с `@ConditionalOnProperty(name = "keycloak.enabled")`

---

### 2.3 Подробный план замены кода

#### Шаг 1: Обновить зависимости

```kotlin
// build.gradle.kts
// УДАЛИТЬ
// implementation("org.keycloak:keycloak-admin-client:26.0.7")

// ДОБАВИТЬ
implementation("com.auth0:auth0:2.21.0")
implementation("com.auth0:java-jwt:4.4.0") // для JWT валидации (если нужно)
```

#### Шаг 2: Создать Auth0AdminClient

```java
@Component
@ConditionalOnProperty(name = "auth0.enabled", havingValue = "true", matchIfMissing = true)
public class Auth0AdminClient {

    private final ManagementAPI managementAPI;
    private final String databaseConnection;

    public Auth0AdminClient(Auth0AdminConfig config) {
        this.managementAPI = ManagementAPI.newBuilder(
            config.getDomain(),
            config.getManagement().getAccessToken()
        ).build();
        this.databaseConnection = config.getDatabaseConnection();
    }

    public String createUser(String email, String username, boolean enabled) {
        User user = new User(databaseConnection);
        user.setEmail(email);
        user.setEmailVerified(true);
        user.setBlocked(!enabled);

        try {
            User createdUser = managementAPI.users().create(user).execute().getBody();
            return createdUser.getId(); // auth0|user_id
        } catch (APIException e) {
            throw new Auth0OperationException("Failed to create user", e);
        }
    }

    public String sendPasswordResetLink(String userId) {
        EmailVerificationIdentity identity = new EmailVerificationIdentity(userId);

        try {
            Ticket ticket = managementAPI.tickets()
                .requestPasswordChange(identity, null)
                .execute()
                .getBody();
            return ticket.getTicket(); // URL для сброса пароля
        } catch (APIException e) {
            throw new Auth0OperationException("Failed to send password reset", e);
        }
    }

    public void blockUser(String userId) {
        User user = new User();
        user.setBlocked(true);

        try {
            managementAPI.users().update(userId, user).execute();
        } catch (APIException e) {
            throw new Auth0OperationException("Failed to block user", e);
        }
    }

    public void unblockUser(String userId) {
        User user = new User();
        user.setBlocked(false);

        try {
            managementAPI.users().update(userId, user).execute();
        } catch (APIException e) {
            throw new Auth0OperationException("Failed to unblock user", e);
        }
    }

    public void assignRole(String userId, String roleName) {
        // Предварительно создать роли в Auth0 Dashboard
        List<Role> roles = managementAPI.roles()
            .list(new RolesFilter().withName(roleName))
            .execute()
            .getBody()
            .getItems();

        if (roles.isEmpty()) {
            throw new Auth0OperationException("Role not found: " + roleName);
        }

        managementAPI.users().addRoles(userId, Arrays.asList(roles.get(0).getId()))
            .execute();
    }

    public void updateUserMetadata(String userId, String accountId) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("accountId", accountId);

        User user = new User();
        user.setUserMetadata(metadata);

        try {
            managementAPI.users().update(userId, user).execute();
        } catch (APIException e) {
            throw new Auth0OperationException("Failed to update metadata", e);
        }
    }
}
```

#### Шаг 3: Обновить application.yml

```yaml
# УДАЛИТЬ keycloak блок
# keycloak:
#   realm: ...
#   auth-server-url: ...

# ДОБАВИТЬ auth0 блок
auth0:
  domain: ${AUTH0_DOMAIN:your-tenant.us.auth0.com}
  database-connection: Username-Password-Authentication
  management:
    client-id: ${AUTH0_MGMT_CLIENT_ID:}
    client-secret: ${AUTH0_MGMT_CLIENT_SECRET:}
    audience: https://${auth0.domain}/api/v2/

spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://${auth0.domain}/
          # Auth0 использует RS256 по умолчанию, jwk-set-uri автоматически определяется
```

#### Шаг 4: Создать Auth0 Action для добавления ролей в токен

В Auth0 Dashboard → Actions → Flows → Login:

```javascript
// auth0-add-roles-to-token.js
exports.onExecutePostLogin = async (event, api) => {
  const namespace = 'https://yourdomain.com';

  if (event.authorization) {
    // Добавляем роли в access_token
    api.accessToken.setCustomClaim(`${namespace}/roles`, event.authorization.roles);

    // Добавляем accountId из app_metadata (НЕ user_metadata!)
    if (event.user.app_metadata && event.user.app_metadata.accountId) {
      api.accessToken.setCustomClaim(`${namespace}/accountId`, event.user.app_metadata.accountId);
    }
  }
};
```

#### Шаг 5: Обновить SecurityConfiguration для Auth0 claims

```java
@Bean
public JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtGrantedAuthoritiesConverter grantedAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
    grantedAuthoritiesConverter.setAuthorityPrefix("ROLE_");
    // Auth0 custom claim для ролей
    grantedAuthoritiesConverter.setAuthoritiesClaimName("https://yourdomain.com/roles");

    JwtAuthenticationConverter jwtAuthenticationConverter = new JwtAuthenticationConverter();
    jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter);

    return jwtAuthenticationConverter;
}
```

---

### 2.4 Изменения в бизнес-логике

#### Временный пароль → Ссылка на сброс

**БЫЛО (Keycloak):**
```java
public CreateAccountResponse createAccount(...) {
    String temporaryPassword = passwordGenerator.generate();
    keycloakClient.createUser(email, username, temporaryPassword, true);
    return new CreateAccountResponse(account, temporaryPassword);
}
```

**СТАНЕТ (Auth0):**
```java
public CreateAccountResponse createAccount(...) {
    String userId = auth0Client.createUser(email, username, true);
    String passwordResetLink = auth0Client.sendPasswordResetLink(userId);
    return new CreateAccountResponse(account, passwordResetLink);
}
```

**DTO изменения:**
```java
// CreateAccountResponse.java
public record CreateAccountResponse(
    AccountResponseDto account,
    String passwordResetLink // было: temporaryPassword
) {}

// ResetPasswordResponse.java
public record ResetPasswordResponse(
    UUID accountId,
    String passwordResetLink, // было: temporaryPassword
    Instant linkExpiresAt // было: expiresAt
) {}
```

#### Последний вход

**БЫЛО:**
```java
public Long getLastLogin(String keycloakUserId) {
    var sessions = userResource.getUserSessions();
    return sessions.stream()
        .map(session -> session.getStart())
        .max(Long::compareTo)
        .orElse(null);
}
```

**СТАНЕТ:**
```java
public Instant getLastLogin(String auth0UserId) {
    User user = managementAPI.users().get(auth0UserId, null).execute().getBody();
    return user.getLastLogin(); // Auth0 автоматически отслеживает last_login
}
```

---

## 3. Оценка сложности

### 3.1 По компонентам

| Компонент | Сложность | Время | Комментарий |
|-----------|-----------|-------|-------------|
| Auth0AdminClient | 🟡 Средняя | 4-6 ч | Переписать все методы на Management API |
| Auth0AccountSyncService | 🟢 Простая | 2-3 ч | Изменить логику временного пароля |
| Auth0AdminConfig | 🟢 Простая | 1 ч | Конфигурация M2M приложения |
| SecurityConfiguration | 🟢 Простая | 1-2 ч | Изменить claims маппинг |
| DTOs | 🟢 Простая | 1 ч | Переименовать поля |
| Тесты | 🟡 Средняя | 4-6 ч | Mock Auth0 API, обновить контракты |
| Auth0 Dashboard настройка | 🟡 Средняя | 2-3 ч | Создание приложений, ролей, Actions |

**ИТОГО:** 16-24 часа

### 3.2 Риски

| Риск | Вероятность | Влияние | Митигация |
|------|-------------|---------|-----------|
| Несовместимость API | Низкая | Высокое | Использовать официальные SDK Auth0 |
| Проблемы с JWT claims | Средняя | Среднее | Протестировать Action на dev окружении |
| Потеря данных при миграции | Низкая | Критическое | Экспорт пользователей из Keycloak перед миграцией |
| Изменение поведения паролей | Высокая | Среднее | Документировать изменения для пользователей |

---

## 4. Рекомендации

### ✅ Плюсы миграции на Auth0

1. **Managed Service** - не нужно деплоить и поддерживать Keycloak
2. **Лучшая документация** - Auth0 имеет более структурированную документацию
3. **Встроенная аналитика** - Dashboard с метриками аутентификации
4. **Compliance** - SOC2, GDPR, HIPAA сертификации из коробки
5. **Масштабируемость** - Auth0 автоматически масштабируется

### ⚠️ Минусы миграции

1. **Стоимость** - Auth0 платный сервис (бесплатно до 7000 активных пользователей/месяц)
2. **Vendor Lock-in** - зависимость от сервиса Auth0
3. **Изменение UX** - временный пароль → email ссылка (может быть неудобно для админов)
4. **Миграция данных** - нужно перенести существующих пользователей

### 🎯 Альтернативы

Если основная цель - упростить инфраструктуру:

1. **AWS Cognito** - дешевле Auth0, интеграция с AWS
2. **Okta** - enterprise решение (владеет Auth0)
3. **Firebase Auth** - бесплатно до 50K MAU, Google экосистема
4. **Оставить Keycloak** - если нет проблем с поддержкой

---

## 5. Контрольный список миграции

### Подготовка

- [ ] Создать Auth0 tenant (dev/staging/production)
- [ ] Создать Machine-to-Machine приложение для Management API
- [ ] Создать роли (ROLE_USER, ROLE_ADMIN) в Auth0 Dashboard
- [ ] Создать Auth0 Action для добавления ролей в токен
- [ ] Настроить database connection (Username-Password-Authentication)

### Разработка

- [ ] Обновить `build.gradle.kts` (заменить зависимости)
- [ ] Создать `Auth0AdminClient.java`
- [ ] Обновить `Auth0AccountSyncService.java`
- [ ] Создать `Auth0AdminConfig.java`
- [ ] Обновить `SecurityConfiguration.java`
- [ ] Обновить DTOs (CreateAccountResponse, ResetPasswordResponse)
- [ ] Обновить `application.yml` конфигурацию
- [ ] Удалить deprecated `KeycloakSecurityConfig.java`

### Тестирование

- [ ] Unit тесты для Auth0AdminClient
- [ ] Integration тесты с mock Management API
- [ ] Contract тесты для всех эндпоинтов
- [ ] E2E тесты создания пользователя
- [ ] E2E тесты блокировки/разблокировки
- [ ] E2E тесты сброса пароля
- [ ] Проверить JWT токен с ролями

### Миграция данных

- [ ] Экспортировать пользователей из Keycloak
- [ ] Создать скрипт миграции в Auth0
- [ ] Провести миграцию на staging окружении
- [ ] Проверить все пользователи перенесены корректно

### Деплой

- [ ] Обновить environment variables (AUTH0_DOMAIN, AUTH0_MGMT_CLIENT_ID, etc.)
- [ ] Деплой на staging
- [ ] Smoke tests на staging
- [ ] Деплой на production
- [ ] Мониторинг логов аутентификации

### Post-Migration

- [ ] Удалить Keycloak из docker-compose.yml
- [ ] Удалить все Keycloak классы
- [ ] Обновить документацию (README, CLAUDE.md)
- [ ] Обновить API документацию (Swagger)

---

## 6. Заключение

Миграция с Keycloak на Auth0 - **реалистичная задача средней сложности**.

**Основные изменения:**
- Замена 5 Java классов
- Изменение конфигурации
- Изменение логики временных паролей (пароль → email ссылка)
- Создание Auth0 Action для ролей

**Время:** 16-24 часа разработки + тестирование + миграция данных

**Рекомендация:** Миграция оправдана, если:
1. Keycloak создает проблемы с инфраструктурой/поддержкой
2. Нужны enterprise фичи Auth0 (аналитика, compliance)
3. Есть бюджет на managed service

Если Keycloak работает стабильно и команда знает как его поддерживать - миграция может быть избыточной.
