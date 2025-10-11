# Safety separation Refactoring

Мы хотим разделить и больше не смешивать подходы к безопасности.
- Контроллеры, которые отвечают за коммуникацию с аппликативным клиентом (Загрузкой файлов) будут пользоватся только генерируемым JWT token'ом.
- API для комуникаци с UI будет пользоваться `keycloak`.
- Разделение будет по потьям /api/dfc/** это пути для загрузчика файлов (клиетна)  
- все остальные /api/** будут упавлятся keycloak

### Список контролеров управляемых нами генерируемым ключом:
- batch-controller
- error-log-controller
- file-upload-controller

## Все остальные управляются keycloak
- account-admin-controller
- batch-admin-controller
- error-admin-controller
- site-admin-controller

## Должны быть разделенны `security filters`
**ОЧЕНЬ ВАЖНО:** Все тесты должны быть исправленны и быплнятся на **100%**
