# Инструкция по настройке Auth0 для data-forge-middleware

## 📋 Содержание

1. [Создание Auth0 Tenant](#1-создание-auth0-tenant)
2. [Настройка приложений](#2-настройка-приложений)
   - 2.1 API Application (Resource Server)
   - 2.2 Machine-to-Machine Application (Backend Management API)
   - 2.3 Single Page Application (Frontend UI) ⭐ **ВАЖНО**
   - 2.4 Проверка CORS и Callback URLs
3. [Создание ролей](#3-создание-ролей)
4. [Настройка Database Connection](#4-настройка-database-connection)
5. [Создание Auth0 Action для добавления ролей](#5-создание-auth0-action-для-добавления-ролей)
6. [Получение credentials](#6-получение-credentials)
7. [Настройка локальной разработки](#7-настройка-локальной-разработки)
8. [Тестирование интеграции](#8-тестирование-интеграции)

## 🏗️ Архитектура Auth0 интеграции

```
┌─────────────────────┐
│   Frontend (React)  │
│   localhost:3000    │
└──────────┬──────────┘
           │ Auth Code + PKCE
           ▼
┌─────────────────────────────┐
│  Auth0 SPA Application      │  ← Callback URLs, CORS
│  (Browser Authentication)   │
└─────────────┬───────────────┘
              │ JWT Token
              ▼
┌─────────────────────────────┐
│  Backend (Spring Boot)      │
│  localhost:8080             │
└──────────┬──────────────────┘
           │ Management API Calls
           │ (Client Credentials)
           ▼
┌─────────────────────────────┐
│  Auth0 M2M Application      │
│  (Server-to-Server)         │
└─────────────┬───────────────┘
              │
              ▼
┌─────────────────────────────┐
│  Auth0 Management API       │
│  (Create/Block Users)       │
└─────────────────────────────┘
```

**Компоненты:**
- **Auth0 API** - определяет ваш Backend API (audience: `https://api.dataforge.com`)
- **Auth0 M2M App** - для вызовов Management API с бэкенда
- **Auth0 SPA App** - для логина пользователей через браузер (требует CORS настройки)

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

Auth0 требует создать **3 приложения**:
1. **API** - для валидации JWT токенов (определяет ваш Backend API как Resource Server)
2. **Machine-to-Machine (M2M)** - для вызовов Management API с бэкенда (создание/блокировка пользователей)
3. **Single Page Application (SPA)** - для аутентификации пользователей во фронтенде через браузер

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
   - **Settings → Allow Offline Access**: ✅ **Включить** (фронтенд использует refresh tokens для silent authentication)
   - **Settings → Allow Skipping User Consent**: ✅ Включить (для internal API)

   **⚠️ Важно:** Allow Offline Access должен быть включен, потому что:
   - Фронтенд использует `useRefreshTokens={true}` в Auth0Provider
   - Refresh tokens позволяют пользователю оставаться в системе без повторного логина
   - Access tokens обновляются автоматически в фоне (silent authentication)
   - Улучшает UX - пользователь не видит редиректов на Auth0 при истечении токена

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

### Шаг 2.3: Создание Single Page Application для Frontend

**Зачем нужно SPA приложение?**

Auth0 требует создать **отдельное приложение** для фронтенда (Single Page Application), потому что:

1. **Разные типы клиентов**:
   - **M2M приложение (Шаг 2.2)** - для бэкенда (серверная аутентификация через Client Credentials flow)
   - **SPA приложение** - для фронтенда (браузерная аутентификация через Authorization Code + PKCE flow)

2. **Разная модель безопасности**:
   - M2M использует **Client Secret** (серверное приложение может безопасно хранить секрет)
   - SPA использует **PKCE** без Client Secret (браузер не может безопасно хранить секреты)

3. **Разные сценарии использования**:
   - M2M для административных операций (создание пользователей, блокировка аккаунтов)
   - SPA для входа пользователей через браузер (Universal Login с редиректом)

4. **CORS и Callback URLs**:
   - SPA требует настройки CORS и Callback URLs для работы в браузере
   - M2M работает server-to-server, CORS не требуется

**Итог:** Вам нужно **3 приложения в Auth0**:
- ✅ **API (Шаг 2.1)** - определяет ваш API как Resource Server
- ✅ **Machine-to-Machine (Шаг 2.2)** - для вызовов Management API с бэкенда
- ✅ **Single Page Application (Шаг 2.3)** - для аутентификации пользователей во фронтенде

---

1. **Applications → Applications → Create Application**

   ```
   Name: Data Forge Admin UI
   Type: Single Page Web Applications
   ```

2. **Configure Application Settings**

   В настройках приложения (**Applications → Data Forge Admin UI → Settings**) заполнить:

   **Application URIs**:

   ```
   Application Login URI: (оставить пустым)
   ```

   **⚠️ Что такое Application Login URI?**

   Это URL **вашего собственного** login экрана, если вы НЕ используете Auth0 Universal Login.

   - **Оставьте пустым** если используете Auth0 Universal Login (наш случай)
   - Auth0 Universal Login - это hosted login page от Auth0 (рекомендуется)
   - Заполняйте только если создаете custom login UI на своем домене (не рекомендуется для новых проектов)

   **Для data-forge-middleware**: Оставьте это поле **пустым**, потому что мы используем Auth0 Universal Login (встроенный login экран Auth0).

   ```

   Allowed Callback URLs:
   http://localhost:3000,
   http://localhost:3000/callback,
   https://dataforge-dev.example.com,
   https://dataforge-dev.example.com/callback,
   https://dataforge.example.com,
   https://dataforge.example.com/callback

   Allowed Logout URLs:
   http://localhost:3000,
   https://dataforge-dev.example.com,
   https://dataforge.example.com

   Allowed Web Origins:
   http://localhost:3000,
   https://dataforge-dev.example.com,
   https://dataforge.example.com

   Allowed Origins (CORS):
   http://localhost:3000,
   https://dataforge-dev.example.com,
   https://dataforge.example.com
   ```

   **⚠️ Важно:**
   - `localhost:3000` - для локальной разработки (React dev server)
   - `dataforge-dev.example.com` - для staging окружения
   - `dataforge.example.com` - для production окружения
   - Замените `example.com` на ваш реальный домен

3. **Advanced Settings**

   **Grant Types** (оставить по умолчанию для SPA):
   ```
   ✅ Authorization Code
   ✅ Refresh Token
   ✅ Implicit (deprecated, отключить для безопасности)
   ```

   **Application Type**:
   ```
   ✅ Single Page Application
   ```

   **Token Endpoint Authentication Method**:
   ```
   None (Public Client - SPA не использует client secret)
   ```

4. **API Authorization**

   **Applications → Data Forge Admin UI → APIs**

   Авторизовать приложение для доступа к вашему API:
   - Выбрать: **Data Forge API** (созданный в Шаге 2.1)
   - Не требуется выбирать permissions (они определяются ролями пользователя)

5. **Refresh Token Rotation**

   **Applications → Data Forge Admin UI → Settings → Advanced Settings → Refresh Token Rotation**

   ```
   ✅ Rotation: Enabled
   ✅ Reuse Interval: 10 seconds
   ✅ Absolute Expiration: 30 days
   ✅ Inactivity Expiration: 3 days
   ```

   **Зачем нужна Refresh Token Rotation?**

   Refresh tokens используются для автоматического обновления access токенов без повторного логина пользователя:

   - **Access Token** живет 24 часа → истекает → пользователь НЕ выкидывается из системы
   - **Refresh Token** используется для получения нового Access Token в фоне (silent authentication)
   - **Rotation** повышает безопасность - каждый refresh token используется только 1 раз, затем заменяется новым
   - **Absolute Expiration (30 дней)** - максимальное время, после которого пользователь должен перелогиниться
   - **Inactivity Expiration (3 дня)** - если пользователь неактивен 3 дня, refresh token инвалидируется

   **Как это работает во фронтенде?**

   В `Auth0Provider.tsx` установлено `useRefreshTokens={true}`:
   ```typescript
   <Auth0ProviderSDK
     useRefreshTokens={true}  // ← Включает использование refresh tokens
     cacheLocation="memory"   // ← Refresh токены хранятся в памяти (безопаснее localStorage)
   />
   ```

   **Итог**: Пользователь может работать в системе 30 дней без повторного логина (если активен каждые 3 дня).

6. **Получить Credentials**

   В настройках приложения скопировать:
   - **Domain**: `dataforge-dev.us.auth0.com`
   - **Client ID**: `XyZ123AbC456DeF789` (отличается от M2M Client ID!)

   **⚠️ Примечание:** SPA приложения **НЕ используют** Client Secret (публичный клиент).

7. **Connections**

   **Applications → Data Forge Admin UI → Connections**

   Включить Database connection:
   ```
   ✅ Username-Password-Authentication
   ```

   Отключить социальные логины (если не нужны):
   ```
   ❌ google-oauth2
   ❌ github
   ```

### Шаг 2.4: Проверка настроек CORS и Callbacks

**⚠️ КРИТИЧНО:** Неправильная настройка приведет к ошибкам:
- `Callback URL mismatch` - если redirect_uri не в списке Allowed Callback URLs
- `CORS error` - если origin не в списке Allowed Origins (CORS)
- `Invalid logout URL` - если returnTo не в списке Allowed Logout URLs

**Тестирование локально:**

1. Запустить frontend dev server:
   ```bash
   cd frontend
   npm run dev
   # React dev server запустится на http://localhost:3000
   ```

2. Создать `.env.local` в `frontend/`:
   ```bash
   VITE_AUTH0_DOMAIN=dataforge-dev.us.auth0.com
   VITE_AUTH0_CLIENT_ID=XyZ123AbC456DeF789  # Client ID из Шага 2.3
   VITE_AUTH0_AUDIENCE=https://api.dataforge.com
   ```

3. Открыть браузер: `http://localhost:3000`

4. Нажать "Login" → должен произойти редирект на Auth0 Universal Login

5. После логина → редирект обратно на `http://localhost:3000`

6. Проверить DevTools Console на наличие CORS ошибок

**Если возникают ошибки:**

- **`Callback URL mismatch`**:
  ```
  Решение: Убедитесь, что http://localhost:3000 добавлен в Allowed Callback URLs
  ```

- **`Origin has been blocked by CORS policy`**:
  ```
  Решение: Добавьте http://localhost:3000 в Allowed Origins (CORS) и Allowed Web Origins
  ```

- **`The redirectUri must be in the list of allowed Callback URLs`**:
  ```
  Решение: Проверьте, что в Auth0Provider.tsx используется:
  redirect_uri: window.location.origin

  И что этот origin присутствует в Allowed Callback URLs
  ```

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

     // Добавляем accountId из app_metadata (НЕ user_metadata!)
     // accountId хранится в app_metadata, т.к. это системные данные (не редактируемые пользователем)
     if (event.user.app_metadata && event.user.app_metadata.accountId) {
       api.accessToken.setCustomClaim(`${namespace}/accountId`, event.user.app_metadata.accountId);

       console.log(`Added accountId to token: ${event.user.app_metadata.accountId}`);
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
- [ ] Создан Machine-to-Machine приложение (для Backend Management API)
- [ ] Authorized M2M на Management API
- [ ] Создан Single Page Application (для Frontend UI)
- [ ] Настроены Allowed Callback URLs для SPA
- [ ] Настроены Allowed Logout URLs для SPA
- [ ] Настроены Allowed Web Origins для SPA
- [ ] Настроены Allowed Origins (CORS) для SPA
- [ ] Созданы роли (ROLE_USER, ROLE_ADMIN)
- [ ] Настроен Database Connection
- [ ] Создан Auth0 Action "Add Roles to Access Token"
- [ ] Action добавлен в Login Flow
- [ ] Настроены Email Templates

### ✅ Локальная разработка

**Backend:**
- [ ] Скопированы M2M credentials (client_id, client_secret, domain)
- [ ] Обновлен application-dev.yml
- [ ] Создан .env файл (AUTH0_MGMT_CLIENT_ID, AUTH0_MGMT_CLIENT_SECRET)
- [ ] Запущено приложение с profile=dev
- [ ] Создан тестовый админ
- [ ] Получен access token
- [ ] Проверены JWT claims (роли присутствуют)
- [ ] Вызван /admin/accounts endpoint

**Frontend:**
- [ ] Скопированы SPA credentials (domain, client_id)
- [ ] Создан frontend/.env.local (VITE_AUTH0_DOMAIN, VITE_AUTH0_CLIENT_ID, VITE_AUTH0_AUDIENCE)
- [ ] Запущен frontend dev server (npm run dev)
- [ ] Проверен редирект на Auth0 Universal Login
- [ ] Проверен успешный логин и callback на localhost:3000
- [ ] Проверено отсутствие CORS ошибок в DevTools Console

### ✅ Тестирование

- [ ] Unit тесты для Auth0AdminClient
- [ ] Integration тесты создания пользователя
- [ ] Contract тесты Admin API
- [ ] E2E тест блокировки/разблокировки
- [ ] E2E тест сброса пароля

---

## 12. Полезные ссылки

### Auth0 Документация

- **Auth0 Java SDK Documentation:** https://github.com/auth0/auth0-java
- **Management API Reference:** https://auth0.com/docs/api/management/v2
- **Authentication API Reference:** https://auth0.com/docs/api/authentication
- **Actions Documentation:** https://auth0.com/docs/customize/actions
- **Universal Login vs Embedded Login:** https://auth0.com/docs/authenticate/login/auth0-universal-login
- **Refresh Token Rotation:** https://auth0.com/docs/secure/tokens/refresh-tokens/refresh-token-rotation

### Spring Security

- **Spring Security OAuth2 Resource Server:** https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html

### Сообщество

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
