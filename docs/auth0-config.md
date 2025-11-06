# Инструкция по настройке Auth0 для data-forge-middleware

## 📋 Содержание

1. [Создание Auth0 Tenant](#1-создание-auth0-tenant)
2. [Настройка приложений](#2-настройка-приложений)
3. [Создание ролей](#3-создание-ролей)
4. [Настройка Database Connection](#4-настройка-database-connection)
5. [Создание Auth0 Action для добавления ролей](#5-создание-auth0-action-для-добавления-ролей)
6. [Получение credentials](#6-получение-credentials)
7. [Настройка локальной разработки](#7-настройка-локальной-разработки)
8. [Тестирование интеграции](#8-тестирование-интеграции)

---

## 1. Создание Auth0 Tenant

### Шаг 1.1: Регистрация в Auth0

1. Перейти на [auth0.com](https://auth0.com)
2. Нажать **Sign Up** → выбрать **Sign up for free**
3. Зарегистрироваться через:
   - Email + пароль
   - ИЛИ GitHub account (рекомендуется для разработки)

### Шаг 1.2: Создание Tenant

После регистрации Auth0 попросит создать tenant:

```
Tenant Name: dataforge-dev
Region: US (или EU для европейских пользователей)
Environment Tag: Development
```

**Важно:** Tenant name будет частью домена:
- `dataforge-dev.us.auth0.com` (для US региона)
- `dataforge-dev.eu.auth0.com` (для EU региона)

**Рекомендация:** Создать 3 tenant-а:
- `dataforge-dev` (для локальной разработки)
- `dataforge-staging` (для staging окружения)
- `dataforge-prod` (для production)

---

## 2. Настройка приложений

Auth0 требует создать **2 приложения**:
1. **API** - для валидации JWT токенов (Admin API)
2. **Machine-to-Machine** - для Management API (создание пользователей)

### Шаг 2.1: Создание API Application

1. **Applications → APIs → Create API**

   ```
   Name: Data Forge API
   Identifier: https://api.dataforge.com
   Signing Algorithm: RS256
   ```

   **⚠️ Важно:** `Identifier` - это `audience` для JWT токенов. Используйте URL вашего production API.

2. **Настройки API**

   - **Settings → Token Expiration**: `86400` секунд (24 часа) - должно совпадать с `jwt.expiration-seconds` в application.yml
   - **Settings → Allow Offline Access**: ❌ Отключить (не используем refresh tokens)
   - **Settings → Allow Skipping User Consent**: ✅ Включить (для internal API)

3. **Permissions (Scopes)**

   Добавить scopes для будущего использования:

   | Scope | Description |
   |-------|-------------|
   | `read:accounts` | Read account information |
   | `write:accounts` | Create and update accounts |
   | `read:batches` | Read upload batches |
   | `write:batches` | Create batches and upload files |
   | `admin:all` | Full administrative access |

   **Примечание:** Сейчас приложение использует только роли (ROLE_ADMIN), но scopes могут понадобиться для fine-grained permissions.

### Шаг 2.2: Создание Machine-to-Machine Application

1. **Applications → Applications → Create Application**

   ```
   Name: Data Forge Management Client
   Type: Machine to Machine Applications
   ```

2. **Authorize Management Client**

   После создания Auth0 спросит: "Which API do you want to authorize?"

   - Выбрать: **Auth0 Management API**
   - Permissions (выбрать следующие):

   ```
   ✅ read:users          - Get users
   ✅ create:users        - Create users
   ✅ update:users        - Update users
   ✅ delete:users        - Delete users
   ✅ read:roles          - Get roles
   ✅ update:users_app_metadata - Update user metadata
   ✅ update:user_metadata      - Update user metadata
   ✅ create:user_tickets       - Create password reset tickets
   ```

3. **Получить Credentials**

   В настройках приложения скопировать:
   - **Domain**: `dataforge-dev.us.auth0.com`
   - **Client ID**: `aBcDeFgHiJkLmNoPqRsTuVwXyZ123456`
   - **Client Secret**: `SuperSecretString-1234567890abcdefghijklmnop`

   **⚠️ ВАЖНО:** `Client Secret` показывается только один раз! Сохраните в безопасном месте.

---

## 3. Создание ролей

### Шаг 3.1: Создание ролей в Auth0 Dashboard

1. **User Management → Roles → Create Role**

   **Роль 1: ROLE_USER**
   ```
   Name: ROLE_USER
   Description: Regular user with upload permissions
   ```

   **Роль 2: ROLE_ADMIN**
   ```
   Name: ROLE_ADMIN
   Description: Administrator with full system access
   ```

2. **Назначение Permissions (опционально)**

   Для каждой роли можно назначить permissions из **Data Forge API**:

   - **ROLE_USER**:
     - `read:batches`
     - `write:batches`
     - `read:accounts` (только свой account)

   - **ROLE_ADMIN**:
     - `admin:all`
     - Все остальные permissions

### Шаг 3.2: Проверка ролей

Перейти в **User Management → Roles** и убедиться, что обе роли созданы.

---

## 4. Настройка Database Connection

Auth0 хранит пользователей в **Database Connections**.

### Шаг 4.1: Использовать стандартную connection

По умолчанию Auth0 создает connection:
- **Name**: `Username-Password-Authentication`
- **Type**: Database

Это подходит для большинства случаев. Проверить настройки:

1. **Authentication → Database → Username-Password-Authentication**

2. **Settings**:
   ```
   ✅ Require Username: Нет (используем email как username)
   ✅ Requires Email Verification: Да
   Password Policy: Good (минимум 8 символов, 1 lowercase, 1 uppercase, 1 number)
   Password History: 5 (не разрешать повторное использование последних 5 паролей)
   ```

3. **Password Strength**:
   ```
   Policy: Good
   ✅ Minimum length: 8
   ✅ At least 1 lowercase character
   ✅ At least 1 uppercase character
   ✅ At least 1 number
   ❌ At least 1 special character (опционально)
   ```

4. **Brute Force Protection**:
   ```
   ✅ Enabled
   Shields: 10 attempts
   Allowlist: (пусто - блокировать все IP после лимита)
   ```

### Шаг 4.2: Настройка Email Templates

**Authentication → Emails → Templates**

Настроить следующие templates:

1. **Verification Email** (Welcome Email)
   - Subject: `Добро пожаловать в Data Forge!`
   - Body: Использовать HTML template с кнопкой подтверждения

2. **Change Password** (Password Reset)
   - Subject: `Сброс пароля Data Forge`
   - Body: Template с кнопкой "Сбросить пароль"

3. **Blocked Account Email**
   - Subject: `Ваш аккаунт Data Forge был заблокирован`

**Примечание:** Templates можно кастомизировать с брендингом компании.

---

## 5. Создание Auth0 Action для добавления ролей

Auth0 **не добавляет роли в JWT токен автоматически**. Нужно создать Action.

### Шаг 5.1: Создать Action

1. **Actions → Flows → Login**

2. **Custom → Build Custom**

   ```
   Name: Add Roles to Access Token
   Trigger: Login / Post Login
   Runtime: Node 18
   ```

3. **Код Action:**

   ```javascript
   /**
    * Handler that will be called during the execution of a PostLogin flow.
    *
    * @param {Event} event - Details about the user and the context in which they are logging in.
    * @param {PostLoginAPI} api - Interface whose methods can be used to change the behavior of the login.
    */
   exports.onExecutePostLogin = async (event, api) => {
     // Namespace для custom claims (должен быть URL)
     const namespace = 'https://api.dataforge.com';

     // Проверяем наличие ролей
     if (event.authorization && event.authorization.roles) {
       // Добавляем роли в access token
       api.accessToken.setCustomClaim(`${namespace}/roles`, event.authorization.roles);

       console.log(`Added roles to token for user ${event.user.user_id}:`, event.authorization.roles);
     }

     // Опционально: добавляем accountId из user_metadata
     if (event.user.user_metadata && event.user.user_metadata.accountId) {
       api.accessToken.setCustomClaim(`${namespace}/accountId`, event.user.user_metadata.accountId);

       console.log(`Added accountId to token: ${event.user.user_metadata.accountId}`);
     }

     // Опционально: добавляем email для удобства
     if (event.user.email) {
       api.accessToken.setCustomClaim(`${namespace}/email`, event.user.email);
     }
   };
   ```

4. **Deploy Action**

   - Нажать **Deploy** (правый верхний угол)
   - Подтвердить деплой

### Шаг 5.2: Добавить Action в Login Flow

1. **Actions → Flows → Login**

2. В визуальном редакторе перетащить **"Add Roles to Access Token"** между **Start** и **Complete**:

   ```
   [Start] → [Add Roles to Access Token] → [Complete]
   ```

3. **Apply Changes**

### Шаг 5.3: Проверка Action

1. Создать тестового пользователя (см. Шаг 7)
2. Назначить роль ROLE_ADMIN
3. Получить токен через Authentication API
4. Декодировать токен на [jwt.io](https://jwt.io)

Токен должен содержать:

```json
{
  "https://api.dataforge.com/roles": ["ROLE_ADMIN"],
  "https://api.dataforge.com/accountId": "123e4567-e89b-12d3-a456-426614174000",
  "https://api.dataforge.com/email": "admin@example.com",
  "iss": "https://dataforge-dev.us.auth0.com/",
  "sub": "auth0|507f1f77bcf86cd799439011",
  "aud": "https://api.dataforge.com",
  "exp": 1234567890
}
```

---

## 6. Получение credentials

### Шаг 6.1: Management API Credentials

**Для Machine-to-Machine приложения:**

1. **Applications → Applications → Data Forge Management Client → Settings**

   Скопировать:
   ```
   Domain: dataforge-dev.us.auth0.com
   Client ID: YOUR_M2M_CLIENT_ID
   Client Secret: YOUR_M2M_CLIENT_SECRET
   ```

2. **Test Connection**

   ```bash
   curl --request POST \
     --url https://dataforge-dev.us.auth0.com/oauth/token \
     --header 'content-type: application/json' \
     --data '{
       "client_id":"YOUR_M2M_CLIENT_ID",
       "client_secret":"YOUR_M2M_CLIENT_SECRET",
       "audience":"https://dataforge-dev.us.auth0.com/api/v2/",
       "grant_type":"client_credentials"
     }'
   ```

   Ответ:
   ```json
   {
     "access_token": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
     "token_type": "Bearer",
     "expires_in": 86400
   }
   ```

### Шаг 6.2: API Identifier (Audience)

**Для Data Forge API:**

1. **Applications → APIs → Data Forge API → Settings**

   Скопировать:
   ```
   Identifier (Audience): https://api.dataforge.com
   ```

### Шаг 6.3: Issuer URI и JWK Set URI

Auth0 автоматически генерирует:

```
Issuer URI: https://dataforge-dev.us.auth0.com/
JWK Set URI: https://dataforge-dev.us.auth0.com/.well-known/jwks.json
```

Spring Security OAuth2 Resource Server автоматически определит JWK Set URI из Issuer URI.

---

## 7. Настройка локальной разработки

### Шаг 7.1: Обновить application-dev.yml

Создать/обновить `src/main/resources/application-dev.yml`:

```yaml
auth0:
  domain: dataforge-dev.us.auth0.com
  database-connection: Username-Password-Authentication
  management:
    client-id: YOUR_M2M_CLIENT_ID
    client-secret: YOUR_M2M_CLIENT_SECRET
    audience: https://dataforge-dev.us.auth0.com/api/v2/

spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://dataforge-dev.us.auth0.com/
          # Auth0 использует audiences claim для валидации
          audiences:
            - https://api.dataforge.com

# Отключить Keycloak
keycloak:
  enabled: false
```

### Шаг 7.2: Environment Variables

Создать `.env` файл в корне проекта:

```bash
# Auth0 Configuration
AUTH0_DOMAIN=dataforge-dev.us.auth0.com
AUTH0_MGMT_CLIENT_ID=YOUR_M2M_CLIENT_ID
AUTH0_MGMT_CLIENT_SECRET=YOUR_M2M_CLIENT_SECRET
AUTH0_API_AUDIENCE=https://api.dataforge.com

# Database
DB_URL=jdbc:postgresql://localhost:5432/dfm
DB_USERNAME=postgres
DB_PASSWORD=postgres

# AWS S3 (LocalStack для dev)
AWS_S3_ENDPOINT=http://localhost:4566
AWS_S3_REGION=us-east-1
AWS_S3_BUCKET_NAME=dataforge-uploads
AWS_ACCESS_KEY_ID=test
AWS_SECRET_ACCESS_KEY=test

# JWT (custom JWT для client API - оставляем без изменений)
JWT_SECRET=change-this-secret-in-production
```

### Шаг 7.3: Запуск приложения

```bash
# Запустить PostgreSQL и LocalStack
docker-compose up postgres localstack

# Запустить приложение
./gradlew bootRun --args='--spring.profiles.active=dev'
```

---

## 8. Тестирование интеграции

### Шаг 8.1: Создать тестового админа через Management API

**Метод 1: Через curl**

```bash
# 1. Получить Management API токен
MGMT_TOKEN=$(curl -s --request POST \
  --url https://dataforge-dev.us.auth0.com/oauth/token \
  --header 'content-type: application/json' \
  --data '{
    "client_id":"YOUR_M2M_CLIENT_ID",
    "client_secret":"YOUR_M2M_CLIENT_SECRET",
    "audience":"https://dataforge-dev.us.auth0.com/api/v2/",
    "grant_type":"client_credentials"
  }' | jq -r '.access_token')

# 2. Создать пользователя
USER_ID=$(curl -s --request POST \
  --url https://dataforge-dev.us.auth0.com/api/v2/users \
  --header "authorization: Bearer $MGMT_TOKEN" \
  --header 'content-type: application/json' \
  --data '{
    "email": "admin@test.com",
    "email_verified": true,
    "blocked": false,
    "connection": "Username-Password-Authentication",
    "password": "Test1234!",
    "user_metadata": {
      "accountId": "123e4567-e89b-12d3-a456-426614174000"
    }
  }' | jq -r '.user_id')

echo "Created user: $USER_ID"

# 3. Получить ID роли ROLE_ADMIN
ROLE_ID=$(curl -s --request GET \
  --url "https://dataforge-dev.us.auth0.com/api/v2/roles?name_filter=ROLE_ADMIN" \
  --header "authorization: Bearer $MGMT_TOKEN" | jq -r '.[0].id')

echo "Role ID: $ROLE_ID"

# 4. Назначить роль пользователю
curl --request POST \
  --url "https://dataforge-dev.us.auth0.com/api/v2/users/$USER_ID/roles" \
  --header "authorization: Bearer $MGMT_TOKEN" \
  --header 'content-type: application/json' \
  --data "{
    \"roles\": [\"$ROLE_ID\"]
  }"

echo "Role ROLE_ADMIN assigned to user $USER_ID"
```

**Метод 2: Через Auth0 Dashboard**

1. **User Management → Users → Create User**
   ```
   Email: admin@test.com
   Password: Test1234!
   Connection: Username-Password-Authentication
   ```

2. **Назначить роль:**
   - Открыть созданного пользователя
   - **Roles → Assign Roles → ROLE_ADMIN**

3. **Добавить metadata:**
   - **Metadata → user_metadata → Edit**
   ```json
   {
     "accountId": "123e4567-e89b-12d3-a456-426614174000"
   }
   ```

### Шаг 8.2: Получить токен для админа

```bash
# Получить access token через Resource Owner Password Flow
ACCESS_TOKEN=$(curl -s --request POST \
  --url https://dataforge-dev.us.auth0.com/oauth/token \
  --header 'content-type: application/json' \
  --data '{
    "grant_type": "password",
    "username": "admin@test.com",
    "password": "Test1234!",
    "audience": "https://api.dataforge.com",
    "client_id": "YOUR_M2M_CLIENT_ID",
    "client_secret": "YOUR_M2M_CLIENT_SECRET",
    "scope": "openid profile email"
  }' | jq -r '.access_token')

echo "Access Token: $ACCESS_TOKEN"

# Декодировать токен
echo $ACCESS_TOKEN | cut -d. -f2 | base64 -d | jq .
```

**⚠️ Важно:** Resource Owner Password Flow должен быть включен в Application Settings:
- **Applications → Data Forge Management Client → Settings → Advanced Settings → Grant Types**
- ✅ **Password**

### Шаг 8.3: Вызвать Admin API

```bash
# Проверить /admin/accounts endpoint
curl --request GET \
  --url http://localhost:8080/admin/accounts \
  --header "Authorization: Bearer $ACCESS_TOKEN"
```

Ожидаемый ответ:
```json
{
  "content": [...],
  "page": 0,
  "size": 20,
  "totalElements": 10,
  "totalPages": 1
}
```

### Шаг 8.4: Создать account через API

```bash
curl --request POST \
  --url http://localhost:8080/admin/accounts/with-auth0 \
  --header "Authorization: Bearer $ACCESS_TOKEN" \
  --header 'content-type: application/json' \
  --data '{
    "email": "user@example.com",
    "name": "Test User",
    "phone": "+1234567890",
    "company": "Test Company",
    "role": "USER"
  }'
```

Ожидаемый ответ:
```json
{
  "account": {
    "id": "uuid-here",
    "email": "user@example.com",
    "name": "Test User",
    "isActive": true,
    "createdAt": "2025-11-06T10:30:00Z"
  },
  "passwordResetLink": "https://dataforge-dev.us.auth0.com/lo/reset?ticket=AbCdEf123..."
}
```

### Шаг 8.5: Проверка JWT claims

Декодировать токен и убедиться, что присутствуют:

```json
{
  "iss": "https://dataforge-dev.us.auth0.com/",
  "sub": "auth0|507f1f77bcf86cd799439011",
  "aud": "https://api.dataforge.com",
  "exp": 1234567890,
  "https://api.dataforge.com/roles": ["ROLE_ADMIN"],
  "https://api.dataforge.com/accountId": "123e4567-e89b-12d3-a456-426614174000",
  "https://api.dataforge.com/email": "admin@test.com"
}
```

---

## 9. Troubleshooting

### Проблема 1: "Invalid issuer" при валидации токена

**Причина:** Mismatch между `issuer-uri` в application.yml и `iss` claim в токене

**Решение:**
```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          # Auth0 добавляет trailing slash!
          issuer-uri: https://dataforge-dev.us.auth0.com/
```

### Проблема 2: "Invalid audience" ошибка

**Причина:** Access token не содержит правильный `aud` claim

**Решение:**

При получении токена указать `audience`:
```bash
curl --request POST \
  --url https://dataforge-dev.us.auth0.com/oauth/token \
  --data '{
    "grant_type": "password",
    "audience": "https://api.dataforge.com",  # <-- ОБЯЗАТЕЛЬНО
    ...
  }'
```

### Проблема 3: Роли отсутствуют в токене

**Причина:** Auth0 Action не добавлен в Login Flow или не deployed

**Решение:**
1. **Actions → Flows → Login** → убедиться что Action в flow
2. **Actions → Library → Add Roles to Access Token** → Status: **Deployed**

### Проблема 4: "Forbidden" при вызове /admin/** endpoints

**Причина:** Роли не маппятся в Spring Security authorities

**Решение:**

Проверить `SecurityConfiguration.java`:
```java
@Bean
public JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtGrantedAuthoritiesConverter converter = new JwtGrantedAuthoritiesConverter();
    converter.setAuthorityPrefix("ROLE_");
    // Namespace должен совпадать с Action!
    converter.setAuthoritiesClaimName("https://api.dataforge.com/roles");

    JwtAuthenticationConverter jwtConverter = new JwtAuthenticationConverter();
    jwtConverter.setJwtGrantedAuthoritiesConverter(converter);
    return jwtConverter;
}
```

### Проблема 5: "Client is not authorized" при вызове Management API

**Причина:** Machine-to-Machine приложение не авторизовано на Management API

**Решение:**
1. **Applications → Applications → Data Forge Management Client → APIs**
2. **Authorize** для Auth0 Management API
3. Выбрать permissions: `read:users`, `create:users`, etc.

---

## 10. Дополнительные настройки

### 10.1 Настройка MFA (Multi-Factor Authentication)

**Security → Multi-factor Auth**

Рекомендуется включить для админов:
```
✅ Push Notification via Auth0 Guardian
✅ SMS
✅ Time-based One-Time Password (TOTP)
```

Enforce MFA для роли ROLE_ADMIN через Action:
```javascript
exports.onExecutePostLogin = async (event, api) => {
  const roles = event.authorization?.roles || [];

  if (roles.includes('ROLE_ADMIN') && !event.authentication.methods.find(m => m.name === 'mfa')) {
    api.multifactor.enable('any', { allowRememberBrowser: false });
  }
};
```

### 10.2 Настройка rate limiting

**Security → Attack Protection → Suspicious IP Throttling**

```
✅ Enabled
Maximum requests per second per IP: 10
Block duration: 24 hours
```

**Brute Force Protection:**
```
✅ Enabled
Maximum failed attempts: 10
Block duration: 24 hours
```

### 10.3 Логирование и мониторинг

**Monitoring → Logs**

Настроить log streaming в:
- Datadog
- CloudWatch
- Splunk
- Custom Webhook

Для dev окружения достаточно встроенного **Logs → Search**

### 10.4 Custom Domain (для production)

**Branding → Custom Domains**

Вместо `dataforge-prod.us.auth0.com` использовать `auth.dataforge.com`

Требуется:
- SSL certificate
- DNS CNAME запись

---

## 11. Checklist настройки

### ✅ Auth0 Dashboard

- [ ] Создан tenant (dev/staging/prod)
- [ ] Создан API (Data Forge API)
- [ ] Создан Machine-to-Machine приложение
- [ ] Authorized M2M на Management API
- [ ] Созданы роли (ROLE_USER, ROLE_ADMIN)
- [ ] Настроен Database Connection
- [ ] Создан Auth0 Action "Add Roles to Access Token"
- [ ] Action добавлен в Login Flow
- [ ] Настроены Email Templates

### ✅ Локальная разработка

- [ ] Скопированы credentials (client_id, client_secret, domain)
- [ ] Обновлен application-dev.yml
- [ ] Создан .env файл
- [ ] Запущено приложение с profile=dev
- [ ] Создан тестовый админ
- [ ] Получен access token
- [ ] Проверены JWT claims (роли присутствуют)
- [ ] Вызван /admin/accounts endpoint

### ✅ Тестирование

- [ ] Unit тесты для Auth0AdminClient
- [ ] Integration тесты создания пользователя
- [ ] Contract тесты Admin API
- [ ] E2E тест блокировки/разблокировки
- [ ] E2E тест сброса пароля

---

## 12. Полезные ссылки

- **Auth0 Java SDK Documentation:** https://github.com/auth0/auth0-java
- **Management API Reference:** https://auth0.com/docs/api/management/v2
- **Authentication API Reference:** https://auth0.com/docs/api/authentication
- **Actions Documentation:** https://auth0.com/docs/customize/actions
- **Spring Security OAuth2 Resource Server:** https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html
- **Auth0 Community:** https://community.auth0.com/

---

## 13. Поддержка

При возникновении проблем:

1. **Проверить логи Auth0:** Monitoring → Logs → Search
2. **Проверить логи приложения:** `logging.level.com.bitbi.dfm=DEBUG`
3. **Декодировать JWT токен:** https://jwt.io
4. **Auth0 Community Forum:** https://community.auth0.com/
5. **Auth0 Support:** support@auth0.com (для paid plans)

---

**Успешной настройки! 🚀**

Если следовали всем шагам, Auth0 интеграция должна работать корректно.
