# Tasks: Basic UI with Keycloak Authentication and Subscriber Management

**Input**: Design documents from `/specs/005-basic-ui-with/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: TDD is mandatory per Constitution Principle XI (Frontend). All test tasks must be completed and FAIL before implementation begins.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`
- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3, US4, US5, US6)
- Include exact file paths in descriptions

## Path Conventions
- **Frontend**: `frontend/src/` (NEW - to be created)
- **Backend**: `src/main/java/com/bitbi/dfm/` (EXISTING - no changes)
- **Tests**: `frontend/tests/` (unit, integration, e2e)
- **Specs**: `/specs/005-basic-ui-with/` (documentation)

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Initialize React 19 frontend project with all required dependencies

**Estimated Time**: 4-6 hours

- [ ] **T001** [P] [SETUP] Create `frontend/` directory structure per plan.md FSD architecture (app, pages, widgets, features, entities, shared)
- [ ] **T002** [SETUP] Initialize Vite project with React 19 + TypeScript template in `frontend/`
- [ ] **T003** [P] [SETUP] Install core dependencies: TanStack (Query, Router, Table, Store), react-oidc-context, axios, Recharts
- [ ] **T004** [P] [SETUP] Install UI dependencies: shadcn/ui, Tailwind CSS, Sonner, Lucide React
- [ ] **T005** [P] [SETUP] Install form dependencies: React Hook Form, Zod, @hookform/resolvers
- [ ] **T006** [P] [SETUP] Install dev dependencies: Vitest, Testing Library, Playwright, ESLint, Prettier
- [ ] **T007** [P] [SETUP] Configure Tailwind CSS (tailwind.config.js, postcss.config.js, src/index.css)
- [ ] **T008** [P] [SETUP] Configure TypeScript strict mode and path aliases in `tsconfig.json` (@/app, @/pages, @/widgets, @/features, @/entities, @/shared)
- [ ] **T009** [P] [SETUP] Configure Vite with path aliases and proxy to backend (vite.config.ts)
- [ ] **T010** [P] [SETUP] Configure Vitest for unit tests (vitest.config.ts with path aliases)
- [ ] **T011** [P] [SETUP] Configure Playwright for E2E tests (playwright.config.ts)
- [ ] **T012** [P] [SETUP] Create environment files: .env.development, .env.production with Keycloak config placeholders
- [ ] **T013** [P] [SETUP] Initialize shadcn/ui and install base components (Button, Dialog, Label, Input, Toast)
- [ ] **T014** [P] [SETUP] Add React 19 compatibility override for Recharts in package.json (react-is dependency)

**Checkpoint**: Frontend project initialized, all dependencies installed, configuration complete

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure that MUST be complete before ANY user story can be implemented

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

**Estimated Time**: 8-10 hours

- [ ] **T015** [P] [FOUNDATION] Create `frontend/src/shared/api/client.ts` - axios instance with base URL configuration
- [ ] **T016** [P] [FOUNDATION] Create `frontend/src/shared/config/env.ts` - typed environment variable exports (VITE_KEYCLOAK_URL, VITE_API_BASE_URL)
- [ ] **T017** [P] [FOUNDATION] Create `frontend/src/shared/lib/utils.ts` - utility functions (cn from class-variance-authority)
- [ ] **T018** [FOUNDATION] Create `frontend/src/app/providers/AuthProvider.tsx` - react-oidc-context configuration wrapper
- [ ] **T019** [FOUNDATION] Create `frontend/src/app/providers/QueryProvider.tsx` - TanStack Query provider with axios client integration
- [ ] **T020** [FOUNDATION] Create `frontend/src/app/providers/RouterProvider.tsx` - TanStack Router provider
- [ ] **T021** [FOUNDATION] Create `frontend/src/app/providers/index.ts` - compose all providers
- [ ] **T022** [FOUNDATION] Create `frontend/src/app/router.tsx` - TanStack Router configuration with routes: /, /login, /dashboard, /subscribers
- [ ] **T023** [FOUNDATION] Configure axios interceptor in `frontend/src/shared/api/interceptors.ts` - attach JWT from useAuth hook to all requests
- [ ] **T024** [FOUNDATION] Create global error handler in `frontend/src/shared/api/error-handler.ts` - handle 401, 403, 409 with toast notifications
- [ ] **T025** [P] [FOUNDATION] Create `frontend/src/entities/user-session/model/types.ts` - UserSession, UserInfo, AuthError interfaces per data-model.md
- [ ] **T026** [P] [FOUNDATION] Create `frontend/src/shared/ui/` - copy shadcn/ui components: Button, Dialog, Label, Input, Select, Toast, Table components
- [ ] **T027** [FOUNDATION] Create `frontend/src/app/App.tsx` - root component with providers chain (Auth → Query → Router)
- [ ] **T028** [FOUNDATION] Create `frontend/src/app/main.tsx` - React entry point with strict mode
- [ ] **T029** [FOUNDATION] Update `frontend/index.html` - add app title, meta tags, root div

**Checkpoint**: Foundation ready - authentication framework, API client, routing, and providers configured. User story implementation can now begin in parallel.

---

## Phase 3: User Story 1 - User Authentication (Priority: P1) 🎯 MVP

**Goal**: Allow users to securely log in via Keycloak OAuth 2.0, access the application, and log out. Session persists across browser restarts.

**Independent Test**: Access application URL → Click login → Authenticate in Keycloak → Redirected to dashboard. Close browser → Reopen → Still authenticated. Click logout → Session terminated.

**Estimated Time**: 12-14 hours

### Unit Tests for User Story 1 (TDD - Write FIRST, ensure FAIL)

- [ ] **T030** [P] [US1] Unit test for `LoginButton` component in `frontend/tests/unit/features/auth/login/LoginButton.test.tsx` - renders button, triggers login on click
- [ ] **T031** [P] [US1] Unit test for `LogoutButton` component in `frontend/tests/unit/features/auth/logout/LogoutButton.test.tsx` - renders button, triggers logout on click
- [ ] **T032** [P] [US1] Unit test for `useAuth` hook wrapper in `frontend/tests/unit/entities/user-session/api/useAuth.test.ts` - returns auth state from react-oidc-context

### Integration Tests for User Story 1 (TDD - Write FIRST, ensure FAIL)

- [ ] **T033** [US1] Integration test for login flow in `frontend/tests/integration/auth/LoginFlow.test.tsx` - mock Keycloak redirect, verify token storage, verify dashboard navigation
- [ ] **T034** [US1] Integration test for logout flow in `frontend/tests/integration/auth/LogoutFlow.test.tsx` - verify token cleared, verify redirect to login

### E2E Tests for User Story 1 (TDD - Write FIRST, ensure FAIL)

- [ ] **T035** [US1] E2E test for full authentication journey in `frontend/tests/e2e/auth.spec.ts` - real Keycloak login, verify dashboard access, verify logout

### Implementation for User Story 1

- [ ] **T036** [P] [US1] Create `frontend/src/features/auth/login/LoginButton.tsx` - button component that calls useAuth().signinRedirect()
- [ ] **T037** [P] [US1] Create `frontend/src/features/auth/logout/LogoutButton.tsx` - button component that calls useAuth().signoutRedirect()
- [ ] **T038** [P] [US1] Create `frontend/src/entities/user-session/api/useAuth.ts` - thin wrapper around useAuth from react-oidc-context for type safety
- [ ] **T039** [US1] Create `frontend/src/pages/login/LoginPage.tsx` - login page with LoginButton, Keycloak branding, error display
- [ ] **T040** [US1] Create `frontend/src/pages/login/CallbackPage.tsx` - OAuth callback page that handles redirect from Keycloak
- [ ] **T041** [US1] Add login and callback routes to `frontend/src/app/router.tsx` - "/" → LoginPage, "/callback" → CallbackPage
- [ ] **T042** [US1] Create `frontend/src/features/auth/token-refresh/TokenRefreshHandler.tsx` - silent refresh component (react-oidc-context handles automatically)
- [ ] **T043** [US1] Add error handling for Keycloak unavailability in `frontend/src/pages/login/LoginPage.tsx` - display "Service unavailable, please try again later" per clarification
- [ ] **T044** [US1] Add loading state to `frontend/src/pages/login/LoginPage.tsx` - show spinner while redirecting to Keycloak

**Checkpoint**: User Story 1 complete - users can authenticate via Keycloak, access application, and log out. Test independently by running E2E test T035.

---

## Phase 4: User Story 2 - Dashboard Overview (Priority: P2)

**Goal**: Display dashboard with demo charts and navigation menu after successful authentication. Responsive across desktop, tablet, mobile.

**Independent Test**: Log in → Automatically redirected to dashboard → See charts with demo data → Navigation menu visible → Resize browser → Layout adapts.

**Estimated Time**: 10-12 hours

### Unit Tests for User Story 2 (TDD - Write FIRST, ensure FAIL)

- [ ] **T045** [P] [US2] Unit test for `DashboardPage` component in `frontend/tests/unit/pages/dashboard/DashboardPage.test.tsx` - renders charts, navigation menu
- [ ] **T046** [P] [US2] Unit test for `Header` widget in `frontend/tests/unit/widgets/header/Header.test.tsx` - renders navigation links, logout button
- [ ] **T047** [P] [US2] Unit test for `DashboardCharts` widget in `frontend/tests/unit/widgets/dashboard-charts/DashboardCharts.test.tsx` - renders all chart types with demo data
- [ ] **T048** [P] [US2] Unit test for demo data generator in `frontend/tests/unit/entities/dashboard-metrics/model/demo-data.test.ts` - generates consistent fake data

### Integration Tests for User Story 2 (TDD - Write FIRST, ensure FAIL)

- [ ] **T049** [US2] Integration test for dashboard navigation in `frontend/tests/integration/dashboard/DashboardNavigation.test.tsx` - click navigation links, verify routing

### Implementation for User Story 2

- [ ] **T050** [P] [US2] Create `frontend/src/entities/dashboard-metrics/model/types.ts` - DashboardMetrics, TrendData, PieData, BarData interfaces per data-model.md
- [ ] **T051** [P] [US2] Create `frontend/src/entities/dashboard-metrics/model/demo-data.ts` - generate fake dashboard data (450 subscribers, 12-month trend, status distribution, top companies)
- [ ] **T052** [P] [US2] Create `frontend/src/widgets/header/Header.tsx` - app header with navigation menu (Dashboard, Subscribers) and LogoutButton
- [ ] **T053** [P] [US2] Create `frontend/src/widgets/dashboard-charts/AreaChartWidget.tsx` - subscriber trend over time (Recharts AreaChart)
- [ ] **T054** [P] [US2] Create `frontend/src/widgets/dashboard-charts/PieChartWidget.tsx` - status distribution (Recharts PieChart)
- [ ] **T055** [P] [US2] Create `frontend/src/widgets/dashboard-charts/BarChartWidget.tsx` - monthly growth (Recharts BarChart)
- [ ] **T056** [P] [US2] Create `frontend/src/widgets/dashboard-charts/TopCompaniesWidget.tsx` - top 5 companies (Recharts BarChart horizontal)
- [ ] **T057** [US2] Create `frontend/src/widgets/dashboard-charts/index.tsx` - compose all chart widgets into dashboard layout (CSS Grid, responsive)
- [ ] **T058** [US2] Create `frontend/src/pages/dashboard/DashboardPage.tsx` - page component with Header widget and DashboardCharts widget
- [ ] **T059** [US2] Add dashboard route to `frontend/src/app/router.tsx` - "/dashboard" → DashboardPage (protected by useAuth check)
- [ ] **T060** [US2] Add responsive breakpoints to chart widgets - stack on mobile (<640px), 2-column on tablet (640-1024px), 2x2 grid on desktop (≥1024px)
- [ ] **T061** [US2] Redirect authenticated users from login to dashboard in `frontend/src/app/router.tsx` - if already authenticated, skip login page
- [ ] **T061A** [CHECKPOINT] [US2] Verify bundle size is within budget - Run `npm run build:analyze` and confirm gzipped size <250KB (50% of 500KB total budget). If exceeded: optimize Recharts imports (use only needed chart types via shadcn/ui), review TanStack bundle sizes, enable tree-shaking

**Checkpoint**: User Story 2 complete - dashboard displays with demo charts, navigation works, responsive layout. **CRITICAL**: Bundle size checkpoint passed (<250KB). Test independently by logging in and verifying charts render.

---

## Phase 5: User Story 3 - View Subscriber List (Priority: P3)

**Goal**: Display paginated table of all subscribers with search, filter, and sort capabilities. Supports up to 10,000 subscribers.

**Independent Test**: Navigate to Subscribers page → See table with subscriber data → Use pagination controls → Search for subscriber → Filter by status → Verify results update.

**Estimated Time**: 14-16 hours

### Unit Tests for User Story 3 (TDD - Write FIRST, ensure FAIL)

- [ ] **T062** [P] [US3] Unit test for `SubscriberTable` widget in `frontend/tests/unit/widgets/subscriber-table/SubscriberTable.test.tsx` - renders table with data, pagination controls
- [ ] **T063** [P] [US3] Unit test for `SubscriberSearch` feature in `frontend/tests/unit/features/subscriber-search/SubscriberSearch.test.tsx` - input debounced, triggers search
- [ ] **T064** [P] [US3] Unit test for `useSubscribers` hook in `frontend/tests/unit/entities/subscriber/api/useSubscribers.test.ts` - fetches data with React Query, handles pagination
- [ ] **T065** [P] [US3] Unit test for subscriber Zod schema in `frontend/tests/unit/entities/subscriber/model/schema.test.ts` - validates subscriber data structure

### Integration Tests for User Story 3 (TDD - Write FIRST, ensure FAIL)

- [ ] **T066** [US3] Integration test for subscriber list with mock API in `frontend/tests/integration/subscribers/SubscriberList.test.tsx` - fetch subscribers, render table, pagination works

### Implementation for User Story 3

- [ ] **T067** [P] [US3] Create `frontend/src/entities/subscriber/model/types.ts` - Subscriber, SubscriberStatus, SubscriberFilters, SubscriberListResponse per data-model.md
- [ ] **T068** [P] [US3] Create `frontend/src/entities/subscriber/model/schema.ts` - Zod schemas (subscriberSchema, subscriberFiltersSchema, subscriberListResponseSchema)
- [ ] **T069** [P] [US3] Create `frontend/src/entities/subscriber/api/keys.ts` - React Query key factory (subscriberKeys.list, subscriberKeys.detail)
- [ ] **T070** [US3] Create `frontend/src/entities/subscriber/api/client.ts` - API functions (fetchSubscribers using axios with query params)
- [ ] **T071** [US3] Create `frontend/src/entities/subscriber/api/useSubscribers.ts` - React Query hook (useQuery with subscriberKeys, pagination, caching)
- [ ] **T072** [P] [US3] Create `frontend/src/features/subscriber-search/SearchInput.tsx` - debounced search input (400ms delay)
- [ ] **T073** [P] [US3] Create `frontend/src/features/subscriber-search/StatusFilter.tsx` - dropdown for active/inactive/all filter
- [ ] **T074** [US3] Create `frontend/src/widgets/subscriber-table/SubscriberTable.tsx` - TanStack Table with virtualization, columns (ID, Name, Email, Phone, Company, Status, Created, Actions)
- [ ] **T075** [US3] Create `frontend/src/widgets/subscriber-table/Pagination.tsx` - pagination controls (previous, next, page numbers, page size selector)
- [ ] **T076** [US3] Create `frontend/src/pages/subscribers/list/SubscriberListPage.tsx` - page layout with SearchInput, StatusFilter, SubscriberTable, Pagination
- [ ] **T077** [US3] Add subscribers list route to `frontend/src/app/router.tsx` - "/subscribers" → SubscriberListPage (protected)
- [ ] **T078** [US3] Add "Subscribers" link to Header navigation in `frontend/src/widgets/header/Header.tsx`
- [ ] **T079** [US3] Add empty state to SubscriberTable - display "No subscribers found" when content array is empty
- [ ] **T080** [US3] Add loading state to SubscriberTable - display skeleton rows while useSubscribers.isLoading

**Checkpoint**: User Story 3 complete - subscriber list displays with pagination, search, and filter. Supports 10,000 records via virtualization. Test independently by navigating to /subscribers.

---

## Phase 6: User Story 4 - Create New Subscriber (Priority: P4)

**Goal**: Add new subscribers via validated form (name, email, phone, company). Form validates client-side, shows success/error messages, updates list.

**Independent Test**: Click "Create Subscriber" button → Form opens → Fill valid data → Submit → See success toast → New subscriber appears in list. Try duplicate email → See error message.

**Estimated Time**: 12-14 hours

### Unit Tests for User Story 4 (TDD - Write FIRST, ensure FAIL)

- [ ] **T081** [P] [US4] Unit test for `CreateSubscriberForm` in `frontend/tests/unit/features/subscriber-create/CreateSubscriberForm.test.tsx` - renders fields, validates input, submits data
- [ ] **T082** [P] [US4] Unit test for `useCreateSubscriber` hook in `frontend/tests/unit/entities/subscriber/api/useCreateSubscriber.test.ts` - calls API, invalidates queries, shows toast
- [ ] **T083** [P] [US4] Unit test for create subscriber Zod schema in `frontend/tests/unit/entities/subscriber/model/schema.test.ts` - validates required fields, email format, transforms empty strings to null

### Integration Tests for User Story 4 (TDD - Write FIRST, ensure FAIL)

- [ ] **T084** [US4] Integration test for subscriber creation flow in `frontend/tests/integration/subscribers/CreateSubscriber.test.tsx` - open form, fill fields, submit, verify API call, verify list update

### Implementation for User Story 4

- [ ] **T085** [P] [US4] Add `createSubscriberSchema` to `frontend/src/entities/subscriber/model/schema.ts` - Zod schema with validation rules per data-model.md
- [ ] **T086** [US4] Create `frontend/src/entities/subscriber/api/client.ts` - add `createSubscriber(data: CreateSubscriberFormData)` function (POST /api/admin/subscribers)
- [ ] **T087** [US4] Create `frontend/src/entities/subscriber/api/useCreateSubscriber.ts` - useMutation hook with optimistic update, invalidate subscriberKeys.lists() on success
- [ ] **T088** [P] [US4] Create `frontend/src/features/subscriber-create/CreateSubscriberForm.tsx` - React Hook Form with Zod resolver, fields (name, email, phone, company), client-side validation
- [ ] **T089** [US4] Create `frontend/src/features/subscriber-create/CreateSubscriberDialog.tsx` - Dialog wrapper with "Create Subscriber" title, form inside, cancel/submit buttons
- [ ] **T090** [US4] Add "Create Subscriber" button to `frontend/src/pages/subscribers/list/SubscriberListPage.tsx` - opens CreateSubscriberDialog
- [ ] **T091** [US4] Add success toast to `useCreateSubscriber` hook - "Subscriber created successfully" on success
- [ ] **T092** [US4] Add error handling to `useCreateSubscriber` hook - display field errors for 400 Bad Request, show "Email already exists" for 409 Conflict
- [ ] **T093** [US4] Close dialog and reset form after successful creation in CreateSubscriberDialog

**Checkpoint**: User Story 4 complete - users can create new subscribers with validated form. Test independently by creating a subscriber and verifying it appears in the list.

---

## Phase 7: User Story 5 - Edit Existing Subscriber (Priority: P5)

**Goal**: Update subscriber information via pre-populated form. Validates changes, shows success/error messages, updates list immediately.

**Independent Test**: Click "Edit" button on subscriber → Form opens with current data → Modify fields → Save → See success toast → Updated data visible in list. Try invalid data → See validation errors.

**Estimated Time**: 10-12 hours

### Unit Tests for User Story 5 (TDD - Write FIRST, ensure FAIL)

- [ ] **T094** [P] [US5] Unit test for `EditSubscriberForm` in `frontend/tests/unit/features/subscriber-edit/EditSubscriberForm.test.tsx` - renders pre-filled fields, validates changes, submits updates
- [ ] **T095** [P] [US5] Unit test for `useUpdateSubscriber` hook in `frontend/tests/unit/entities/subscriber/api/useUpdateSubscriber.test.ts` - calls API, updates cache optimistically, shows toast

### Integration Tests for User Story 5 (TDD - Write FIRST, ensure FAIL)

- [ ] **T096** [US5] Integration test for subscriber edit flow in `frontend/tests/integration/subscribers/EditSubscriber.test.tsx` - click edit, modify field, submit, verify API call, verify list update

### Implementation for User Story 5

- [ ] **T097** [P] [US5] Add `updateSubscriberSchema` to `frontend/src/entities/subscriber/model/schema.ts` - partial version of createSubscriberSchema with id field
- [ ] **T098** [US5] Create `frontend/src/entities/subscriber/api/useSubscriberById.ts` - useQuery hook to fetch single subscriber (GET /api/admin/subscribers/{id})
- [ ] **T099** [US5] Add `updateSubscriber(id: string, data: UpdateSubscriberFormData)` to `frontend/src/entities/subscriber/api/client.ts` (PUT /api/admin/subscribers/{id})
- [ ] **T100** [US5] Create `frontend/src/entities/subscriber/api/useUpdateSubscriber.ts` - useMutation hook with optimistic update, invalidate specific subscriber and list queries
- [ ] **T101** [P] [US5] Create `frontend/src/features/subscriber-edit/EditSubscriberForm.tsx` - React Hook Form with Zod resolver, pre-fill with current data, client-side validation
- [ ] **T102** [US5] Create `frontend/src/features/subscriber-edit/EditSubscriberDialog.tsx` - Dialog with "Edit Subscriber" title, EditSubscriberForm inside, cancel/save buttons
- [ ] **T103** [US5] Add "Edit" button to each row in `frontend/src/widgets/subscriber-table/SubscriberTable.tsx` - opens EditSubscriberDialog with subscriber ID
- [ ] **T104** [US5] Add success toast to `useUpdateSubscriber` hook - "Subscriber updated successfully"
- [ ] **T105** [US5] Add error handling to `useUpdateSubscriber` hook - display field errors for 400, show "Email already exists" for 409, show "Subscriber not found" for 404
- [ ] **T106** [US5] Close dialog and reset form after successful update in EditSubscriberDialog

**Checkpoint**: User Story 5 complete - users can edit existing subscribers with pre-populated form. Test independently by editing a subscriber and verifying changes appear in the list.

---

## Phase 8: User Story 6 - Delete Subscriber (Priority: P6)

**Goal**: Remove subscribers via confirmation dialog. Shows subscriber info, requires confirmation, displays success message, removes from list.

**Independent Test**: Click "Delete" button on subscriber → Confirmation dialog appears with subscriber info → Confirm deletion → See success toast → Subscriber removed from list. Verify soft delete (status set to inactive).

**Estimated Time**: 8-10 hours

### Unit Tests for User Story 6 (TDD - Write FIRST, ensure FAIL)

- [ ] **T107** [P] [US6] Unit test for `DeleteSubscriberDialog` in `frontend/tests/unit/features/subscriber-delete/DeleteSubscriberDialog.test.tsx` - renders subscriber info, confirms deletion, cancels deletion
- [ ] **T108** [P] [US6] Unit test for `useDeleteSubscriber` hook in `frontend/tests/unit/entities/subscriber/api/useDeleteSubscriber.test.ts` - calls API, removes from cache optimistically, shows toast

### Integration Tests for User Story 6 (TDD - Write FIRST, ensure FAIL)

- [ ] **T109** [US6] Integration test for subscriber delete flow in `frontend/tests/integration/subscribers/DeleteSubscriber.test.tsx` - click delete, confirm in dialog, verify API call, verify removal from list

### Implementation for User Story 6

- [ ] **T110** [US6] Add `deleteSubscriber(id: string)` to `frontend/src/entities/subscriber/api/client.ts` (DELETE /api/admin/subscribers/{id})
- [ ] **T111** [US6] Create `frontend/src/entities/subscriber/api/useDeleteSubscriber.ts` - useMutation hook with optimistic update (remove from cache), invalidate subscriberKeys.lists()
- [ ] **T112** [P] [US6] Create `frontend/src/features/subscriber-delete/DeleteSubscriberDialog.tsx` - confirmation dialog with subscriber name/email, "Are you sure?" message, cancel/delete buttons (delete button styled as destructive)
- [ ] **T113** [US6] Add "Delete" button to each row in `frontend/src/widgets/subscriber-table/SubscriberTable.tsx` - opens DeleteSubscriberDialog with subscriber data
- [ ] **T114** [US6] Add success toast to `useDeleteSubscriber` hook - "Subscriber deleted successfully"
- [ ] **T115** [US6] Add error handling to `useDeleteSubscriber` hook - show "Subscriber not found" for 404, show generic error for 500
- [ ] **T116** [US6] Close dialog after successful deletion in DeleteSubscriberDialog

**Checkpoint**: User Story 6 complete - users can delete subscribers with confirmation. Test independently by deleting a subscriber and verifying it's removed from the list.

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: Improvements that affect multiple user stories

**Estimated Time**: 8-10 hours

- [ ] **T117** [P] [POLISH] Add loading spinners to all async operations (login redirect, data fetching, form submissions)
- [ ] **T118** [P] [POLISH] Add accessibility attributes (ARIA labels, keyboard navigation) to all interactive components per Constitution Principle XVI
- [ ] **T119** [P] [POLISH] Add form field focus management (auto-focus first field, focus first error on validation failure)
- [ ] **T120** [P] [POLISH] Add responsive table horizontal scroll for mobile in SubscriberTable
- [ ] **T121** [P] [POLISH] Add hover states and visual feedback to all buttons and interactive elements
- [ ] **T122** [P] [POLISH] Optimize bundle size - verify <500KB gzipped target met using `npm run build:analyze`
- [ ] **T123** [P] [POLISH] Add code splitting for routes using React.lazy in router.tsx (dashboard, subscribers pages)
- [ ] **T124** [P] [POLISH] Add E2E tests for critical paths in `frontend/tests/e2e/critical-paths.spec.ts` - full CRUD flow, authentication flow
- [ ] **T125** [POLISH] Update `frontend/README.md` with quickstart instructions from `specs/005-basic-ui-with/quickstart.md`
- [ ] **T126** [POLISH] Verify test coverage meets ≥80% overall, ≥95% critical paths using `npm run test:coverage`
- [ ] **T127** [P] [POLISH] Add CSP headers configuration for security (Vite server config or nginx config)
- [ ] **T128** [P] [POLISH] Add Lighthouse performance audit - verify score ≥90
- [ ] **T129** [POLISH] Run full quickstart validation - follow `specs/005-basic-ui-with/quickstart.md` step-by-step

**Checkpoint**: All polish and cross-cutting concerns complete. Application ready for production deployment.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS all user stories
- **User Stories (Phase 3-8)**: All depend on Foundational phase completion
  - User stories can then proceed in parallel (if staffed)
  - Or sequentially in priority order (P1 → P2 → P3 → P4 → P5 → P6)
- **Polish (Phase 9)**: Depends on all desired user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational (Phase 2) - No dependencies on other stories
- **User Story 2 (P2)**: Can start after Foundational (Phase 2) - Requires US1 for logout button in Header, but otherwise independent
- **User Story 3 (P3)**: Can start after Foundational (Phase 2) - Independent of US1/US2
- **User Story 4 (P4)**: Depends on User Story 3 (needs SubscriberTable to display created subscriber)
- **User Story 5 (P5)**: Depends on User Story 3 (needs SubscriberTable edit button)
- **User Story 6 (P6)**: Depends on User Story 3 (needs SubscriberTable delete button)

### Within Each User Story

- Tests (unit, integration, E2E) MUST be written and FAIL before implementation
- Models/types before API hooks
- API hooks before UI components
- Feature components before page compositions
- Core implementation before integration with other stories

### Parallel Opportunities

**Within Setup (Phase 1)**:
- T001-T014 can all run in parallel (different configuration files)

**Within Foundational (Phase 2)**:
- T015-T017 can run in parallel (shared utilities)
- T018-T021 can run in parallel after T015-T017 (providers)
- T023-T026 can run in parallel (API interceptors, types, UI components)

**Within User Story 1**:
- T030-T032 (unit tests) can run in parallel
- T036-T038 (components) can run in parallel after tests written

**Within User Story 2**:
- T045-T048 (unit tests) can run in parallel
- T050-T051 (types and demo data) can run in parallel
- T052-T056 (chart widgets) can run in parallel after T050-T051

**Within User Story 3**:
- T062-T065 (unit tests) can run in parallel
- T067-T069 (types, schemas, keys) can run in parallel
- T072-T073 (search and filter) can run in parallel

**Within User Story 4**:
- T081-T083 (unit tests) can run in parallel
- T085 and T088 can run in parallel (schema and form)

**Within User Story 5**:
- T094-T095 (unit tests) can run in parallel
- T097 and T101 can run in parallel (schema and form)

**Within User Story 6**:
- T107-T108 (unit tests) can run in parallel
- T110 and T112 can run in parallel (API function and dialog)

**Within Polish (Phase 9)**:
- T117-T124, T127-T128 can run in parallel (different concerns)

**Across User Stories** (after Foundational complete):
- US1, US2, US3 can be developed in parallel by different team members
- US4, US5, US6 must wait for US3 (table) but can proceed in parallel after US3 completes

---

## Parallel Example: User Story 3 (Subscriber List)

```bash
# Launch all unit tests together (write FIRST):
Task T062: "Unit test for SubscriberTable widget"
Task T063: "Unit test for SubscriberSearch feature"
Task T064: "Unit test for useSubscribers hook"
Task T065: "Unit test for subscriber Zod schema"

# Launch types/schema/keys together (after tests written):
Task T067: "Create Subscriber types"
Task T068: "Create Subscriber Zod schemas"
Task T069: "Create React Query key factory"

# Launch search and filter components together:
Task T072: "Create SearchInput component"
Task T073: "Create StatusFilter component"
```

---

## Implementation Strategy

### MVP First (User Story 1 + User Story 2 Only)

1. Complete Phase 1: Setup (T001-T014)
2. Complete Phase 2: Foundational (T015-T029) - CRITICAL, blocks all stories
3. Complete Phase 3: User Story 1 (T030-T044) - Authentication
4. Complete Phase 4: User Story 2 (T045-T061) - Dashboard
5. **STOP and VALIDATE**: Test US1 + US2 independently (E2E test T035, manual dashboard check)
6. Deploy/demo MVP: Users can log in, see dashboard with charts, log out

### Incremental Delivery

1. **Foundation** (Phases 1-2) → Project initialized, authentication framework ready
2. **MVP** (Phase 3-4) → Authentication + Dashboard → Test independently → Deploy/Demo
3. **Read Subscribers** (Phase 5) → Add subscriber list → Test independently → Deploy/Demo
4. **Full CRUD** (Phases 6-8) → Add create, edit, delete → Test independently → Deploy/Demo
5. **Production Ready** (Phase 9) → Polish, performance, accessibility → Final deploy

### Parallel Team Strategy

With multiple developers:

1. **All Together**: Complete Setup (Phase 1) + Foundational (Phase 2)
2. **After Foundational Complete**:
   - **Developer A**: User Story 1 (Authentication) - T030-T044
   - **Developer B**: User Story 2 (Dashboard) - T045-T061
   - **Developer C**: User Story 3 (Subscriber List) - T062-T080
3. **After US3 Complete**:
   - **Developer A**: User Story 4 (Create) - T081-T093
   - **Developer B**: User Story 5 (Edit) - T094-T106
   - **Developer C**: User Story 6 (Delete) - T107-T116
4. **All Together**: Polish & Cross-Cutting (Phase 9) - T117-T129

---

## Estimated Timeline

| Phase | Estimated Time | Cumulative |
|-------|---------------|------------|
| Phase 1: Setup | 4-6 hours | 4-6 hours |
| Phase 2: Foundational | 8-10 hours | 12-16 hours |
| Phase 3: US1 Authentication | 12-14 hours | 24-30 hours |
| Phase 4: US2 Dashboard | 11-13 hours | 35-43 hours |
| Phase 5: US3 Subscriber List | 14-16 hours | 49-59 hours |
| Phase 6: US4 Create Subscriber | 12-14 hours | 61-73 hours |
| Phase 7: US5 Edit Subscriber | 10-12 hours | 71-85 hours |
| Phase 8: US6 Delete Subscriber | 8-10 hours | 79-95 hours |
| Phase 9: Polish & Cross-Cutting | 8-10 hours | 87-105 hours |

**Total Estimated Time**: 87-105 hours (~11-13 working days for one developer, ~3-4 days for team of 3)

**MVP Only (US1 + US2)**: 25-31 hours (~3-4 working days)

---

## Notes

- **[P]** tasks = different files, no dependencies - can run in parallel
- **[Story]** label maps task to specific user story for traceability (US1-US6)
- Each user story should be independently completable and testable
- **TDD is mandatory**: Write tests FIRST, verify they FAIL, then implement to make them PASS
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- Avoid: vague tasks, same file conflicts, cross-story dependencies that break independence
- **Test coverage targets**: ≥80% overall, ≥95% critical paths (auth, CRUD), 100% utilities
- **Bundle size target**: <500KB gzipped - monitor during Phase 9 (T122)
- **Performance target**: Lighthouse score ≥90 - verify in Phase 9 (T128)

---

## Task Count Summary

- **Setup Tasks**: 14 (T001-T014)
- **Foundational Tasks**: 15 (T015-T029)
- **User Story 1 Tasks**: 15 (T030-T044) - 6 tests, 9 implementation
- **User Story 2 Tasks**: 18 (T045-T061A) - 5 tests, 12 implementation, 1 checkpoint
- **User Story 3 Tasks**: 19 (T062-T080) - 5 tests, 14 implementation
- **User Story 4 Tasks**: 13 (T081-T093) - 4 tests, 9 implementation
- **User Story 5 Tasks**: 13 (T094-T106) - 3 tests, 10 implementation
- **User Story 6 Tasks**: 10 (T107-T116) - 3 tests, 7 implementation
- **Polish Tasks**: 13 (T117-T129)

**Total Tasks**: 130
**Total Test Tasks**: 26 (unit + integration + E2E)
**Total Implementation Tasks**: 90
**Total Checkpoint Tasks**: 1 (bundle size verification)
**Total Polish/Setup Tasks**: 13

**Parallelizable Tasks**: 58 (marked with [P])
**Sequential Tasks**: 72 (no [P] marker)
